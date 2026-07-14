package com.finora.api.commitment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materialization engine behavior through the public API. Runs without the
 * test transaction because the engine commits each attempt in its own
 * transaction (REQUIRES_NEW), exactly like production; isolation comes from
 * unique users per test.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecurringMaterializationTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long expenseCategory;
    private Long incomeCategory;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        expenseCategory = categoryId(user, "Assinaturas", CategoryType.EXPENSE);
        incomeCategory = categoryId(user, "Salário", CategoryType.INCOME);
        accountId = createAccount("Conta Recorrente", "2500.00");
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

    private long createCommitment(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    private long accountExpense() throws Exception {
        return createCommitment("""
                {"description": "Internet fibra", "amount": 99.90, "categoryId": %d,
                 "cadence": "MONTHLY", "dueDay": 10, "startDate": "2026-01-01",
                 "targetKind": "ACCOUNT_TRANSACTION", "accountId": %d,
                 "paymentMethod": "PIX"}
                """.formatted(expenseCategory, accountId));
    }

    @Test
    void manualMaterializationCreatesOneAccountTransactionExactlyOnce() throws Exception {
        long id = accountExpense();

        MvcResult result = mockMvc.perform(
                        post("/api/commitments/%d/occurrences/2026-03-10/materialize".formatted(id))
                                .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATERIALIZED"))
                .andExpect(jsonPath("$.transactionId").isNumber())
                .andExpect(jsonPath("$.persisted").value(true))
                .andReturn();
        long transactionId = objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("transactionId").asLong();

        // The generated transaction is a real expense on the account.
        mockMvc.perform(get("/api/transactions/" + transactionId).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("EXPENSE"))
                .andExpect(jsonPath("$.amount").value(99.90))
                .andExpect(jsonPath("$.date").value("2026-03-10"))
                .andExpect(jsonPath("$.commitmentId").value(id))
                .andExpect(jsonPath("$.account.id").value(accountId));

        // Repeating the call cannot create a second artifact.
        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-03-10/materialize".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OCCURRENCE_ALREADY_MATERIALIZED"));

        // Balance reflects exactly one expense.
        mockMvc.perform(get("/api/accounts/" + accountId).cookie(user.session()))
                .andExpect(jsonPath("$.currentBalance").value(2400.10));
    }

    @Test
    void incomeDefinitionGeneratesIncomeTransaction() throws Exception {
        long id = createCommitment("""
                {"description": "Salário mensal", "amount": 6000.00, "categoryId": %d,
                 "cadence": "MONTHLY", "dueDay": 5, "startDate": "2026-01-01",
                 "targetKind": "ACCOUNT_TRANSACTION", "accountId": %d}
                """.formatted(incomeCategory, accountId));

        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-02-05/materialize".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATERIALIZED"));

        mockMvc.perform(get("/api/accounts/" + accountId).cookie(user.session()))
                .andExpect(jsonPath("$.currentBalance").value(8500.00));
    }

    @Test
    void projectionOnlyDefinitionCannotMaterialize() throws Exception {
        long id = createCommitment("""
                {"description": "Estimativa de luz", "amount": 180.00, "categoryId": %d,
                 "cadence": "MONTHLY", "dueDay": 15, "startDate": "2026-01-01"}
                """.formatted(expenseCategory));

        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-02-15/materialize".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COMMITMENT_NO_TARGET"));
    }

    @Test
    void dateOutsideTheRecurrenceIsRejected() throws Exception {
        long id = accountExpense();
        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-03-11/materialize".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OCCURRENCE_DATE_INVALID"));
    }

    @Test
    void skipPreventsMaterializationAndUnskipRestoresIt() throws Exception {
        long id = accountExpense();

        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-04-10/skip".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SKIPPED"));

        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-04-10/materialize".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OCCURRENCE_SKIPPED"));

        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-04-10/unskip".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"));

        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-04-10/materialize".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATERIALIZED"));
    }

    @Test
    void rescheduleMovesEffectiveDateKeepingIdentity() throws Exception {
        long id = accountExpense();

        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-05-10/reschedule".formatted(id))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newDate\": \"2026-05-18\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduledDate").value("2026-05-10"))
                .andExpect(jsonPath("$.effectiveDate").value("2026-05-18"));

        MvcResult result = mockMvc.perform(
                        post("/api/commitments/%d/occurrences/2026-05-10/materialize".formatted(id))
                                .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        long transactionId = objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("transactionId").asLong();

        // The generated transaction lands on the effective date.
        mockMvc.perform(get("/api/transactions/" + transactionId).cookie(user.session()))
                .andExpect(jsonPath("$.date").value("2026-05-18"));
    }

    @Test
    void reversalDeletesGeneratedTransactionExactlyOnce() throws Exception {
        long id = accountExpense();
        MvcResult result = mockMvc.perform(
                        post("/api/commitments/%d/occurrences/2026-03-10/materialize".formatted(id))
                                .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        long transactionId = objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("transactionId").asLong();

        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-03-10/reverse".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));

        // The artifact is gone and the balance is restored once.
        mockMvc.perform(get("/api/transactions/" + transactionId).cookie(user.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/accounts/" + accountId).cookie(user.session()))
                .andExpect(jsonPath("$.currentBalance").value(2500.00));

        // Terminal: neither reversal nor materialization can run again.
        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-03-10/reverse".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OCCURRENCE_NOT_MATERIALIZED"));
        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-03-10/materialize".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OCCURRENCE_REVERSED"));
    }

    @Test
    void generatedTransactionCannotBeDeletedDirectly() throws Exception {
        long id = accountExpense();
        MvcResult result = mockMvc.perform(
                        post("/api/commitments/%d/occurrences/2026-03-10/materialize".formatted(id))
                                .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        long transactionId = objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("transactionId").asLong();

        mockMvc.perform(delete("/api/transactions/" + transactionId)
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_FROM_RECURRING"));
    }

    @Test
    void anotherUserCannotTouchOccurrences() throws Exception {
        long id = accountExpense();
        TestUser intruder = registerUser("Intruso");

        mockMvc.perform(get("/api/commitments/%d/occurrences?from=2026-01-01&to=2026-06-30"
                        .formatted(id)).cookie(intruder.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-03-10/materialize".formatted(id))
                        .cookie(intruder.session()).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-03-10/skip".formatted(id))
                        .cookie(intruder.session()).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-03-10/reverse".formatted(id))
                        .cookie(intruder.session()).with(csrf()))
                .andExpect(status().isNotFound());
    }
}
