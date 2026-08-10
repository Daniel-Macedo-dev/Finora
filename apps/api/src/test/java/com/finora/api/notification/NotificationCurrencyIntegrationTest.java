package com.finora.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * A notification that carries money must carry the denomination of that money.
 *
 * <p>Everything below is about one failure: a figure whose currency has to be
 * guessed. Before this, every notification was stored as reais because the
 * entity said so, which meant a dollar bill and a yen bill both arrived wearing
 * {@code R$}. These tests pin the currency at every place it can be lost — the
 * commitment, the card, each currency's own forecast, the persisted row, the
 * inbox response and the browser claim — and pin the one place it must NOT
 * appear: the payload of a claim whose owner asked for amounts to stay private.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationCurrencyIntegrationTest extends AbstractIntegrationTest {

    private final LocalDate today = LocalDate.now();

    // ── currency at the source ───────────────────────────────────────────────

    @Test
    void recurringNotificationsCarryTheCurrencyOfTheirCommitment() throws Exception {
        TestUser owner = registerUser("Multimoeda");
        long category = categoryId(owner, "Assinaturas", CategoryType.EXPENSE);

        long brl = createAccount(owner, "Caixa BRL", "BRL", "1000.00");
        long usd = createAccount(owner, "Caixa USD", "USD", "1000.00");
        long jpy = createAccount(owner, "Caixa JPY", "JPY", "100000");
        overdueCommitment(owner, category, "Assinatura em reais", "120.00", "BRL", brl);
        overdueCommitment(owner, category, "Assinatura em dólares", "80.00", "USD", usd);
        // Yen has no centavos, so a whole number is the only shape that exists.
        overdueCommitment(owner, category, "Assinatura em ienes", "3000", "JPY", jpy);

        sync(owner);

        Map<String, JsonNode> byTitle = inboxByTitle(owner);
        assertThat(byTitle.get("\"Assinatura em reais\" venceu e não foi executado")
                .get("currency").asString()).isEqualTo("BRL");
        assertThat(byTitle.get("\"Assinatura em dólares\" venceu e não foi executado")
                .get("currency").asString()).isEqualTo("USD");
        assertThat(byTitle.get("\"Assinatura em ienes\" venceu e não foi executado")
                .get("currency").asString()).isEqualTo("JPY");

        // The amount travels beside its own currency, never converted.
        assertThat(byTitle.get("\"Assinatura em dólares\" venceu e não foi executado")
                .get("amount").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(byTitle.get("\"Assinatura em ienes\" venceu e não foi executado")
                .get("amount").decimalValue()).isEqualByComparingTo("3000");
    }

    @Test
    void invoiceNotificationsCarryTheCurrencyOfTheirCard() throws Exception {
        TestUser owner = registerUser("Cartão estrangeiro");
        long category = categoryId(owner, "Assinaturas", CategoryType.EXPENSE);
        long usd = createAccount(owner, "Caixa USD", "USD", "5000.00");

        // Closes two days ago, falls due five days from now: the invoice is
        // inside the notification window without depending on a month boundary.
        long card = createCard(owner, "Cartão USD", "USD", usd,
                today.minusDays(2).getDayOfMonth(), today.plusDays(5).getDayOfMonth());
        purchase(owner, card, category, today.minusDays(4), "400.00");

        // Fourteen days of lead so the invoice is certainly inside the window.
        widenLeadDays(owner, 14);
        sync(owner);

        List<JsonNode> rows = inbox(owner);
        JsonNode invoice = rows.stream()
                .filter(row -> row.get("resourceType").asString().equals("CARD_INVOICE"))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "nenhuma notificação de fatura foi gerada: " + rows));
        assertThat(invoice.get("currency").asString()).isEqualTo("USD");
        assertThat(invoice.get("amount").decimalValue()).isEqualByComparingTo("400.00");
    }

    @Test
    void aForeignCashRiskIsNeverLabelledWithTheBaseCurrency() throws Exception {
        // The owner's base currency is BRL and their only money is in dollars.
        // The risk is a dollar risk; the base currency is not evidence.
        TestUser owner = registerUser("Só dólares");
        long category = categoryId(owner, "Assinaturas", CategoryType.EXPENSE);
        long usd = createAccount(owner, "Caixa USD", "USD", "50.00");
        upcomingCommitment(owner, category, "Aluguel em dólares", "300.00", "USD", usd, 3);

        sync(owner);

        JsonNode risk = cashRisks(owner).get("FORECAST:INSUFFICIENT_CASH:USD");
        assertThat(risk).as("risco de caixa em USD").isNotNull();
        assertThat(risk.get("currency").asString()).isEqualTo("USD");
        assertThat(risk.get("amount").decimalValue()).isNegative();
        assertThat(cashRisks(owner)).containsOnlyKeys("FORECAST:INSUFFICIENT_CASH:USD");
    }

    // ── one cash risk per currency ───────────────────────────────────────────

    @Test
    void eachCurrencyThatRunsOutOfMoneyRaisesItsOwnIndependentCashRisk() throws Exception {
        TestUser owner = registerUser("Caixa misto");
        long category = categoryId(owner, "Assinaturas", CategoryType.EXPENSE);
        long brl = createAccount(owner, "Caixa BRL", "BRL", "100.00");
        long usd = createAccount(owner, "Caixa USD", "USD", "50.00");
        long brlCommitment =
                upcomingCommitment(owner, category, "Aluguel em reais", "500.00", "BRL", brl, 3);
        upcomingCommitment(owner, category, "Aluguel em dólares", "300.00", "USD", usd, 4);

        sync(owner);

        // Two facts, not one blended number. Before this, a mixed forecast
        // nulled its scalar fields and the alert vanished in BOTH currencies.
        Map<String, JsonNode> risks = cashRisks(owner);
        assertThat(risks).containsOnlyKeys(
                "FORECAST:INSUFFICIENT_CASH:BRL", "FORECAST:INSUFFICIENT_CASH:USD");
        assertThat(risks.get("FORECAST:INSUFFICIENT_CASH:BRL").get("currency").asString())
                .isEqualTo("BRL");
        assertThat(risks.get("FORECAST:INSUFFICIENT_CASH:USD").get("currency").asString())
                .isEqualTo("USD");

        // Each risk states its own currency's shortfall. R$ 100 - R$ 500 and
        // US$ 50 - US$ 300 are different arithmetic in different denominations.
        assertThat(risks.get("FORECAST:INSUFFICIENT_CASH:BRL").get("amount").decimalValue())
                .isEqualByComparingTo("-400.00");
        assertThat(risks.get("FORECAST:INSUFFICIENT_CASH:USD").get("amount").decimalValue())
                .isEqualByComparingTo("-250.00");
        // Nothing anywhere equals the two added together.
        assertThat(risks.values()).noneMatch(risk ->
                risk.get("amount").decimalValue().compareTo(new java.math.BigDecimal("-650.00")) == 0);

        // Fixing the reais leaves the dollars exactly as at risk as before.
        mockMvc.perform(post("/api/commitments/{id}/pause", brlCommitment)
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk());
        sync(owner);

        Map<String, JsonNode> after = cashRisksIncludingResolved(owner);
        assertThat(after.get("FORECAST:INSUFFICIENT_CASH:BRL").get("resolvedAt").isNull())
                .as("o risco em reais foi resolvido").isFalse();
        assertThat(after.get("FORECAST:INSUFFICIENT_CASH:USD").get("resolvedAt").isNull())
                .as("o risco em dólares continua ativo").isTrue();
    }

    // ── browser claim privacy ────────────────────────────────────────────────

    @Test
    void aHiddenAmountTakesItsCurrencyWithItWhileTheInboxKeepsBoth() throws Exception {
        TestUser owner = registerUser("Privacidade");
        long category = categoryId(owner, "Assinaturas", CategoryType.EXPENSE);
        long usd = createAccount(owner, "Caixa USD", "USD", "1000.00");
        // Enabling first: the baseline exists so that turning notifications on
        // does not deliver a backlog, so anything claimable must postdate it.
        enableBrowser(owner, false);
        overdueCommitment(owner, category, "Assinatura em dólares", "80.00", "USD", usd);
        sync(owner);

        MvcResult hidden = mockMvc.perform(post("/api/notifications/browser-claims")
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").doesNotExist())
                // The whole point: adding a currency to every notification must
                // not leak the denomination of the sum being withheld.
                .andExpect(jsonPath("$[0].currency").doesNotExist())
                // And no source key either — the cash-risk key names a currency.
                .andExpect(jsonPath("$[0].sourceKey").doesNotExist())
                .andReturn();
        String body = hidden.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("USD").doesNotContain("80");
        // Only what a private delivery needs: who it is, which revision, what
        // it says and where it goes.
        assertThat(objectMapper.readTree(body).get(0).propertyNames())
                .containsExactlyInAnyOrder("id", "revision", "title", "amount", "currency", "route");

        // The durable inbox is authenticated and keeps the whole truth.
        JsonNode row = inbox(owner).get(0);
        assertThat(row.get("amount").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(row.get("currency").asString()).isEqualTo("USD");
        // Claiming is delivery, not reading.
        assertThat(row.get("unread").asBoolean()).isTrue();
    }

    @Test
    void aDisclosedAmountArrivesWithTheCurrencyThatExplainsIt() throws Exception {
        TestUser owner = registerUser("Divulgação");
        long category = categoryId(owner, "Assinaturas", CategoryType.EXPENSE);
        long jpy = createAccount(owner, "Caixa JPY", "JPY", "100000");
        enableBrowser(owner, true);
        overdueCommitment(owner, category, "Assinatura em ienes", "3000", "JPY", jpy);
        sync(owner);

        mockMvc.perform(post("/api/notifications/browser-claims")
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value(3000))
                .andExpect(jsonPath("$[0].currency").value("JPY"));
    }

    @Test
    void enablingDeliveryDoesNotReplayTheBacklogItInheritedInAnyCurrency() throws Exception {
        TestUser owner = registerUser("Baseline");
        long category = categoryId(owner, "Assinaturas", CategoryType.EXPENSE);
        long usd = createAccount(owner, "Caixa USD", "USD", "1000.00");
        // Everything below already existed when delivery was switched on.
        overdueCommitment(owner, category, "Assinatura anterior", "80.00", "USD", usd);
        sync(owner);

        enableBrowser(owner, true);
        mockMvc.perform(post("/api/notifications/browser-claims")
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // A revision that happens after the baseline is delivered normally,
        // with its own currency, so the baseline suppresses history and not
        // the feature.
        long jpy = createAccount(owner, "Caixa JPY", "JPY", "100000");
        overdueCommitment(owner, category, "Assinatura posterior", "3000", "JPY", jpy);
        sync(owner);
        mockMvc.perform(post("/api/notifications/browser-claims")
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].currency").value("JPY"));
    }

    @Test
    void aClaimedRevisionIsNotClaimedTwiceAndSeverityStillFilters() throws Exception {
        TestUser owner = registerUser("Uma vez só");
        long category = categoryId(owner, "Assinaturas", CategoryType.EXPENSE);
        long usd = createAccount(owner, "Caixa USD", "USD", "1000.00");
        enableBrowser(owner, true);
        // Due soon is INFO, below the WARNING minimum the baseline defaults to.
        upcomingCommitment(owner, category, "Assinatura futura", "40.00", "USD", usd, 3);
        sync(owner);

        mockMvc.perform(post("/api/notifications/browser-claims")
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Lowering the bar to INFO lets exactly one claim through, once.
        setPreferences(owner, "\"browserEnabled\":true,\"browserMinimumSeverity\":\"INFO\","
                + "\"browserShowAmounts\":true");
        mockMvc.perform(post("/api/notifications/browser-claims")
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].currency").value("USD"));
        mockMvc.perform(post("/api/notifications/browser-claims")
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aCommitmentRefusesToChangeCurrencySoNoLifecycleIsEverReinterpreted() throws Exception {
        // The first line of defence against a notification whose stored
        // denomination stops matching its source is that the source cannot
        // change denomination at all. NotificationTest pins what the row does
        // if one ever slips through anyway.
        TestUser owner = registerUser("Moeda imutável");
        long category = categoryId(owner, "Assinaturas", CategoryType.EXPENSE);
        long brl = createAccount(owner, "Caixa BRL", "BRL", "1000.00");
        long usd = createAccount(owner, "Caixa USD", "USD", "1000.00");
        long commitment =
                overdueCommitment(owner, category, "Assinatura móvel", "120.00", "BRL", brl);
        sync(owner);
        assertThat(inbox(owner).get(0).get("currency").asString()).isEqualTo("BRL");

        mockMvc.perform(put("/api/commitments/{id}", commitment)
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Assinatura móvel","amount":80.00,
                                 "categoryId":%d,"cadence":"MONTHLY","dueDay":%d,
                                 "startDate":"%s","targetKind":"ACCOUNT_TRANSACTION",
                                 "accountId":%d,"currency":"USD"}
                                """.formatted(category, today.minusDays(2).getDayOfMonth(),
                                today.minusMonths(2), usd)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_IMMUTABLE"));

        sync(owner);
        JsonNode unchanged = inbox(owner).get(0);
        assertThat(unchanged.get("currency").asString()).isEqualTo("BRL");
        assertThat(unchanged.get("amount").decimalValue()).isEqualByComparingTo("120.00");
    }

    // ── owner isolation ──────────────────────────────────────────────────────

    @Test
    void oneOwnerCannotProbeAnotherOwnersCurrencyThroughAnyNotificationSurface() throws Exception {
        TestUser owner = registerUser("Dona da moeda");
        long category = categoryId(owner, "Assinaturas", CategoryType.EXPENSE);
        long jpy = createAccount(owner, "Caixa JPY", "JPY", "100000");
        overdueCommitment(owner, category, "Assinatura em ienes", "3000", "JPY", jpy);
        sync(owner);
        long id = inbox(owner).get(0).get("id").asLong();

        TestUser stranger = registerUser("Estranha");
        sync(stranger);
        // Their own inbox is empty and names no currency of anyone else's.
        mockMvc.perform(get("/api/notifications?filter=ALL").cookie(stranger.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        // Every lifecycle action on a foreign id is a 404, not a currency oracle.
        for (String action : List.of("read", "unread", "dismiss", "restore")) {
            mockMvc.perform(post("/api/notifications/{id}/{action}", id, action)
                            .cookie(stranger.session()).with(csrf()))
                    .andExpect(status().isNotFound());
        }
        // And a claim by the stranger returns nothing at all.
        enableBrowser(stranger, true);
        mockMvc.perform(post("/api/notifications/browser-claims")
                        .cookie(stranger.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private long createAccount(TestUser owner, String name, String currency, String balance)
            throws Exception {
        return id(mockMvc.perform(post("/api/accounts").cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","type":"CHECKING","openingBalance":%s,
                                 "currency":"%s"}
                                """.formatted(name, balance, currency)))
                .andExpect(status().isCreated()).andReturn());
    }

    private long createCard(TestUser owner, String name, String currency, long paymentAccount,
                            int closingDay, int dueDay) throws Exception {
        return id(mockMvc.perform(post("/api/credit-cards").cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","brand":"VISA","creditLimit":5000.00,
                                 "closingDay":%d,"dueDay":%d,"currency":"%s",
                                 "defaultPaymentAccountId":%d}
                                """.formatted(name, closingDay, dueDay, currency, paymentAccount)))
                .andExpect(status().isCreated()).andReturn());
    }

    private void purchase(TestUser owner, long card, long category, LocalDate date, String total)
            throws Exception {
        mockMvc.perform(post("/api/credit-cards/{id}/purchases", card)
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Compra","categoryId":%d,"purchaseDate":"%s",
                                 "totalAmount":%s,"installmentCount":1}
                                """.formatted(category, date, total)))
                .andExpect(status().isCreated());
    }

    /** A commitment whose occurrence is already two days late: CRITICAL. */
    private long overdueCommitment(TestUser owner, long category, String description,
                                   String amount, String currency, long account) throws Exception {
        return commitment(owner, category, description, amount, currency, account,
                today.minusDays(2), today.minusMonths(2));
    }

    /**
     * A commitment falling due inside the upcoming window: INFO.
     *
     * <p>It starts today on purpose. A start two months back would also emit an
     * occurrence inside the 30-day overdue lookback, quietly adding a CRITICAL
     * item to a fixture meant to produce exactly one informative one.
     */
    private long upcomingCommitment(TestUser owner, long category, String description,
                                    String amount, String currency, long account, int inDays)
            throws Exception {
        return commitment(owner, category, description, amount, currency, account,
                today.plusDays(inDays), today);
    }

    private long commitment(TestUser owner, long category, String description, String amount,
                            String currency, long account, LocalDate due, LocalDate start)
            throws Exception {
        return id(mockMvc.perform(post("/api/commitments").cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"%s","amount":%s,"categoryId":%d,
                                 "cadence":"MONTHLY","dueDay":%d,"startDate":"%s",
                                 "targetKind":"ACCOUNT_TRANSACTION","accountId":%d,
                                 "currency":"%s"}
                                """.formatted(description, amount, category, due.getDayOfMonth(),
                                start, account, currency)))
                .andExpect(status().isCreated()).andReturn());
    }

    private void sync(TestUser owner) throws Exception {
        mockMvc.perform(post("/api/notifications/sync").cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk());
    }

    private void widenLeadDays(TestUser owner, int days) throws Exception {
        setPreferences(owner, "\"upcomingLeadDays\":%d".formatted(days));
    }

    private void enableBrowser(TestUser owner, boolean showAmounts) throws Exception {
        setPreferences(owner, "\"browserEnabled\":true,\"browserShowAmounts\":%s"
                .formatted(showAmounts));
    }

    /** Reads the current preferences and writes them back with an override. */
    private void setPreferences(TestUser owner, String overrides) throws Exception {
        JsonNode current = objectMapper.readTree(
                mockMvc.perform(get("/api/notification-preferences").cookie(owner.session()))
                        .andExpect(status().isOk()).andReturn()
                        .getResponse().getContentAsString(StandardCharsets.UTF_8));
        String base = """
                {"enabled":%s,"upcomingLeadDays":%d,"recurringDueEnabled":%s,
                 "invoiceDueEnabled":%s,"executionFailureEnabled":%s,"cashRiskEnabled":%s,
                 "browserEnabled":%s,"browserMinimumSeverity":"%s","browserShowAmounts":%s,
                 %s}"""
                .formatted(current.get("enabled").asBoolean(),
                        current.get("upcomingLeadDays").asInt(),
                        current.get("recurringDueEnabled").asBoolean(),
                        current.get("invoiceDueEnabled").asBoolean(),
                        current.get("executionFailureEnabled").asBoolean(),
                        current.get("cashRiskEnabled").asBoolean(),
                        current.get("browserEnabled").asBoolean(),
                        current.get("browserMinimumSeverity").asString(),
                        current.get("browserShowAmounts").asBoolean(),
                        overrides);
        // The overrides win: Jackson keeps the last occurrence of a duplicate key.
        mockMvc.perform(put("/api/notification-preferences").cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(base))
                .andExpect(status().isOk());
    }

    private List<JsonNode> inbox(TestUser owner) throws Exception {
        JsonNode page = objectMapper.readTree(
                mockMvc.perform(get("/api/notifications?filter=ALL&size=100")
                                .cookie(owner.session()))
                        .andExpect(status().isOk()).andReturn()
                        .getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<JsonNode> rows = new ArrayList<>();
        page.get("content").forEach(rows::add);
        return rows;
    }

    private Map<String, JsonNode> inboxByTitle(TestUser owner) throws Exception {
        Map<String, JsonNode> byTitle = new LinkedHashMap<>();
        for (JsonNode row : inbox(owner)) {
            byTitle.put(row.get("title").asString(), row);
        }
        return byTitle;
    }

    private Map<String, JsonNode> cashRisks(TestUser owner) throws Exception {
        Map<String, JsonNode> risks = new LinkedHashMap<>(cashRisksIncludingResolved(owner));
        risks.values().removeIf(risk -> !risk.get("resolvedAt").isNull());
        return risks;
    }

    private Map<String, JsonNode> cashRisksIncludingResolved(TestUser owner) throws Exception {
        Map<String, JsonNode> risks = new LinkedHashMap<>();
        for (JsonNode row : inbox(owner)) {
            if (row.get("resourceType").asString().equals("FORECAST")) {
                risks.put(row.get("sourceKey").asString(), row);
            }
        }
        return risks;
    }

    private long id(MvcResult result) throws Exception {
        return objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }
}
