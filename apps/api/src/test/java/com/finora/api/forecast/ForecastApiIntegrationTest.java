package com.finora.api.forecast;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forecast correctness: cash movement (not expense recognition), no double
 * counting between projections and artifacts, card cash at invoice due date,
 * unassigned flows disclosed, negative balance detected. Non-transactional
 * because materialization commits in its own transaction.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ForecastApiIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long expenseCategory;
    private Long incomeCategory;
    private long accountId;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        expenseCategory = categoryId(user, "Assinaturas", CategoryType.EXPENSE);
        incomeCategory = categoryId(user, "Salário", CategoryType.INCOME);
        accountId = createAccount("Conta Previsão", "1000.00");
    }

    private long createAccount(String name, String openingBalance) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "type": "CHECKING", "openingBalance": %s}
                                """.formatted(name, openingBalance)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    private void createTransaction(String type, Long category, String amount, LocalDate date,
                                   Long account) throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "%s", "amount": %s, "description": "Lançamento futuro",
                                 "date": "%s", "categoryId": %d%s}
                                """.formatted(type, amount, date, category,
                                account != null ? ", \"accountId\": " + account : "")))
                .andExpect(status().isCreated());
    }

    @Test
    void openingBalanceExcludesFutureTransactionsAndProjectsThem() throws Exception {
        // A future income and a future expense already recorded.
        createTransaction("INCOME", incomeCategory, "500.00", today.plusDays(10), accountId);
        createTransaction("EXPENSE", expenseCategory, "200.00", today.plusDays(20), accountId);

        mockMvc.perform(get("/api/forecast?days=30").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(1000.00))
                .andExpect(jsonPath("$.projectedIncome").value(500.00))
                .andExpect(jsonPath("$.projectedAccountExpenses").value(200.00))
                .andExpect(jsonPath("$.closingBalance").value(1300.00))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].source").value("ACTUAL_TRANSACTION"))
                .andExpect(jsonPath("$.firstNegativeDate").isEmpty());
    }

    @Test
    void recurringProjectionIsReplacedByItsArtifactAfterMaterialization() throws Exception {
        // Monthly expense due in 5 days, account target.
        LocalDate due = today.plusDays(5);
        MvcResult created = mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Assinatura prevista", "amount": 100.00,
                                 "categoryId": %d, "cadence": "MONTHLY", "dueDay": %d,
                                 "startDate": "%s",
                                 "targetKind": "ACCOUNT_TRANSACTION", "accountId": %d}
                                """.formatted(expenseCategory, due.getDayOfMonth(),
                                today.minusMonths(1), accountId)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = objectMapper.readTree(
                        created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();

        // Before: one recurring projection reduces the closing balance.
        mockMvc.perform(get("/api/forecast?days=20").cookie(user.session()))
                .andExpect(jsonPath("$.events[0].source").value("RECURRING_ACCOUNT_OCCURRENCE"))
                .andExpect(jsonPath("$.events[0].commitmentId").value(id))
                .andExpect(jsonPath("$.closingBalance").value(900.00));

        // Materialize the occurrence: the projection must be replaced by the
        // actual transaction — same closing balance, different source.
        mockMvc.perform(post("/api/commitments/%d/occurrences/%s/materialize".formatted(id, due))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/forecast?days=20").cookie(user.session()))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].source").value("ACTUAL_TRANSACTION"))
                .andExpect(jsonPath("$.closingBalance").value(900.00));
    }

    @Test
    void skippedOccurrenceLeavesTheForecast() throws Exception {
        LocalDate due = today.plusDays(7);
        MvcResult created = mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Despesa pulável", "amount": 300.00,
                                 "categoryId": %d, "cadence": "MONTHLY", "dueDay": %d,
                                 "startDate": "%s",
                                 "targetKind": "ACCOUNT_TRANSACTION", "accountId": %d}
                                """.formatted(expenseCategory, due.getDayOfMonth(),
                                today.minusMonths(1), accountId)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = objectMapper.readTree(
                        created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();

        mockMvc.perform(post("/api/commitments/%d/occurrences/%s/skip".formatted(id, due))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/forecast?days=20").cookie(user.session()))
                .andExpect(jsonPath("$.events.length()").value(0))
                .andExpect(jsonPath("$.closingBalance").value(1000.00));
    }

    @Test
    void cardCashLeavesOnInvoiceDueDateNotPurchaseDate() throws Exception {
        // Card due 15 days from now, closing 8 days from now, default account set.
        LocalDate closing = today.plusDays(8);
        LocalDate dueDate = today.plusDays(15);
        MvcResult card = mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Cartão Previsto", "brand": "VISA", "creditLimit": 2000.00,
                                 "closingDay": %d, "dueDay": %d,
                                 "defaultPaymentAccountId": %d}
                                """.formatted(closing.getDayOfMonth(), dueDate.getDayOfMonth(),
                                accountId)))
                .andExpect(status().isCreated())
                .andReturn();
        long cardId = objectMapper.readTree(
                        card.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();

        // A real purchase today: expense recognition is immediate, but cash
        // only moves on the invoice due date.
        mockMvc.perform(post("/api/credit-cards/%d/purchases".formatted(cardId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Compra no cartão", "categoryId": %d,
                                 "purchaseDate": "%s", "totalAmount": 400.00,
                                 "installmentCount": 1}
                                """.formatted(expenseCategory, today)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/forecast?days=40").cookie(user.session()))
                .andExpect(status().isOk())
                // Bank cash untouched today.
                .andExpect(jsonPath("$.openingBalance").value(1000.00))
                // One invoice outflow on the due date, assigned to the account.
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].source").value("CARD_INVOICE"))
                .andExpect(jsonPath("$.events[0].amount").value(-400.00))
                .andExpect(jsonPath("$.events[0].accountId").value(accountId))
                .andExpect(jsonPath("$.projectedInvoiceOutflows").value(400.00))
                .andExpect(jsonPath("$.closingBalance").value(600.00));

        // Paying the invoice removes the projected outflow (no double count).
        MvcResult invoices = mockMvc.perform(
                        get("/api/credit-cards/%d/invoices".formatted(cardId))
                                .cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        long invoiceId = objectMapper.readTree(
                        invoices.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get(0).get("id").asLong();
        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/payments".formatted(cardId, invoiceId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": %d, "amount": 400.00, "paidOn": "%s"}
                                """.formatted(accountId, today)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/forecast?days=40").cookie(user.session()))
                // Cash already left through the payment; nothing stays projected.
                .andExpect(jsonPath("$.openingBalance").value(600.00))
                .andExpect(jsonPath("$.events.length()").value(0))
                .andExpect(jsonPath("$.closingBalance").value(600.00));
    }

    @Test
    void recurringCardPurchaseProjectsInvoicePressureWithoutTouchingCash() throws Exception {
        LocalDate closing = today.plusDays(8);
        LocalDate dueDate = today.plusDays(15);
        MvcResult card = mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Cartão Recorrência", "brand": "VISA",
                                 "creditLimit": 3000.00,
                                 "closingDay": %d, "dueDay": %d,
                                 "defaultPaymentAccountId": %d}
                                """.formatted(closing.getDayOfMonth(), dueDate.getDayOfMonth(),
                                accountId)))
                .andExpect(status().isCreated())
                .andReturn();
        long cardId = objectMapper.readTree(
                        card.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();

        // Recurring card charge two days from now (before the closing).
        LocalDate charge = today.plusDays(2);
        mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Streaming recorrente", "amount": 60.00,
                                 "categoryId": %d, "cadence": "MONTHLY", "dueDay": %d,
                                 "startDate": "%s",
                                 "targetKind": "CREDIT_CARD_PURCHASE", "creditCardId": %d,
                                 "installmentCount": 1}
                                """.formatted(expenseCategory, charge.getDayOfMonth(), charge,
                                cardId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/forecast?days=30").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].source")
                        .value("PROJECTED_RECURRING_CARD_PURCHASE"))
                // Cash impact lands on the projected invoice due date.
                .andExpect(jsonPath("$.events[0].amount").value(-60.00))
                .andExpect(jsonPath("$.projectedInvoiceOutflows").value(60.00))
                .andExpect(jsonPath("$.closingBalance").value(940.00));
    }

    @Test
    void projectionOnlyAndAccountlessFlowsAreDisclosedAsUnassigned() throws Exception {
        // Projection-only recurring expense.
        LocalDate due = today.plusDays(6);
        mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Estimativa sem conta", "amount": 150.00,
                                 "categoryId": %d, "cadence": "MONTHLY", "dueDay": %d,
                                 "startDate": "%s"}
                                """.formatted(expenseCategory, due.getDayOfMonth(),
                                today.minusMonths(1))))
                .andExpect(status().isCreated());
        // Future transaction without an account.
        createTransaction("INCOME", incomeCategory, "80.00", today.plusDays(4), null);

        mockMvc.perform(get("/api/forecast?days=20").cookie(user.session()))
                .andExpect(jsonPath("$.unassignedInflows").value(80.00))
                .andExpect(jsonPath("$.unassignedOutflows").value(150.00))
                // Unassigned flows never change the balance.
                .andExpect(jsonPath("$.closingBalance").value(1000.00))
                .andExpect(jsonPath("$.events[?(@.unassigned == true)]").isNotEmpty());
    }

    @Test
    void firstNegativeDateAndLowestBalanceAreDetected() throws Exception {
        createTransaction("EXPENSE", expenseCategory, "1500.00", today.plusDays(3), accountId);
        createTransaction("INCOME", incomeCategory, "2000.00", today.plusDays(10), accountId);

        mockMvc.perform(get("/api/forecast?days=30").cookie(user.session()))
                .andExpect(jsonPath("$.firstNegativeDate").value(today.plusDays(3).toString()))
                .andExpect(jsonPath("$.lowestBalance").value(-500.00))
                .andExpect(jsonPath("$.lowestBalanceDate").value(today.plusDays(3).toString()))
                .andExpect(jsonPath("$.closingBalance").value(1500.00));
    }

    @Test
    void accountFilterAndHorizonValidation() throws Exception {
        long other = createAccount("Conta Secundária", "50.00");
        createTransaction("EXPENSE", expenseCategory, "20.00", today.plusDays(2), other);

        mockMvc.perform(get("/api/forecast?days=10&accountId=" + other).cookie(user.session()))
                .andExpect(jsonPath("$.openingBalance").value(50.00))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.closingBalance").value(30.00));

        mockMvc.perform(get("/api/forecast?days=8000").cookie(user.session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FORECAST_HORIZON_INVALID"));

        // Another user's account id behaves as absent.
        TestUser intruder = registerUser("Intruso");
        mockMvc.perform(get("/api/forecast?days=10&accountId=" + accountId)
                        .cookie(intruder.session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherUsersDataNeverEntersTheForecast() throws Exception {
        createTransaction("EXPENSE", expenseCategory, "700.00", today.plusDays(2), accountId);

        TestUser other = registerUser("Outro");
        mockMvc.perform(get("/api/forecast?days=30").cookie(other.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(0.00))
                .andExpect(jsonPath("$.events.length()").value(0));
    }
}
