package com.finora.api.offlinesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.category.CategoryType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/** Every supported domain, end to end through the replay endpoint. */
class OfflineSyncDomainTest extends OfflineSyncTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private TestUser user;
    private Long foodCategory;
    private Long salaryCategory;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        foodCategory = categoryId(user, "Alimentação", CategoryType.EXPENSE);
        salaryCategory = categoryId(user, "Salário", CategoryType.INCOME);
    }

    @Test
    void transactionsSupportTheFullOfflineLifecycle() throws Exception {
        UUID clientResourceId = UUID.randomUUID();
        JsonNode created = syncResults(user, create(UUID.randomUUID(), "TRANSACTION",
                clientResourceId, transactionPayload(foodCategory, "42.00", "Mercado")));
        assertThat(created.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        long id = created.get(0).get("resourceId").asLong();
        assertThat(created.get(0).get("version").asLong()).isZero();

        JsonNode updated = syncResults(user, update(UUID.randomUUID(), "TRANSACTION", id, 0,
                transactionPayload(foodCategory, "45.50", "Mercado corrigido")));
        assertThat(updated.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        assertThat(updated.get(0).get("version").asLong()).isEqualTo(1);

        JsonNode removed = syncResults(user,
                deleteMutation(UUID.randomUUID(), "TRANSACTION", id, 1));
        assertThat(removed.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        assertThat(removed.get(0).get("version").isNull()).isTrue();

        mockMvc.perform(get("/api/transactions").cookie(user.session()).param("month", "2026-07"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void aTransactionCreatedOfflineIsAddressableByItsClientIdentity() throws Exception {
        UUID clientResourceId = UUID.randomUUID();
        syncResults(user, create(UUID.randomUUID(), "TRANSACTION", clientResourceId,
                transactionPayload(foodCategory, "42.00", "Mercado")));

        // A later mutation queued before the server id was known targets the
        // same row through the identity the device generated.
        JsonNode updated = syncResults(user, """
                {"clientMutationId":"%s","resourceType":"TRANSACTION","operation":"UPDATE",
                 "target":{"clientResourceId":"%s"},"baseVersion":0,"payload":%s}
                """.formatted(UUID.randomUUID(), clientResourceId,
                transactionPayload(foodCategory, "50.00", "Ajustada")));
        assertThat(updated.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        assertThat(updated.get(0).get("result").get("amount").decimalValue())
                .isEqualByComparingTo("50.00");
    }

    @Test
    void refusesForeignReferencesAndNewGenericCreditSpending() throws Exception {
        long foreignCategory = categoryId(registerUser("Estranho"), "Alimentação",
                CategoryType.EXPENSE);

        JsonNode foreign = syncResults(user, create(UUID.randomUUID(), "TRANSACTION",
                UUID.randomUUID(), transactionPayload(foreignCategory, "10.00", "Alheia")));
        assertThat(foreign.get(0).get("status").stringValue()).isEqualTo("REJECTED");

        JsonNode mismatch = syncResults(user, create(UUID.randomUUID(), "TRANSACTION",
                UUID.randomUUID(), """
                {"type":"EXPENSE","amount":10.00,"description":"Tipo errado",
                 "date":"2026-07-15","categoryId":%d}
                """.formatted(salaryCategory)));
        assertThat(mismatch.get(0).get("error").get("code").stringValue())
                .isEqualTo("CATEGORY_TYPE_MISMATCH");

        JsonNode credit = syncResults(user, create(UUID.randomUUID(), "TRANSACTION",
                UUID.randomUUID(), """
                {"type":"EXPENSE","amount":10.00,"description":"Crédito novo",
                 "date":"2026-07-15","categoryId":%d,"paymentMethod":"CREDIT"}
                """.formatted(foodCategory)));
        assertThat(credit.get(0).get("error").get("code").stringValue())
                .isEqualTo("USE_CREDIT_CARD_PURCHASE");
    }

    @Test
    void budgetsGoalsAndWishlistItemsSupportTheFullOfflineLifecycle() throws Exception {
        JsonNode budget = syncResults(user, create(UUID.randomUUID(), "BUDGET",
                UUID.randomUUID(), """
                {"month":"2026-07","categoryId":%d,"limitAmount":800.00}
                """.formatted(foodCategory)));
        assertThat(budget.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        long budgetId = budget.get(0).get("resourceId").asLong();
        assertThat(syncResults(user, update(UUID.randomUUID(), "BUDGET", budgetId, 0, """
                {"month":"2026-07","categoryId":%d,"limitAmount":950.00}
                """.formatted(foodCategory))).get(0).get("version").asLong()).isEqualTo(1);
        assertThat(syncResults(user, deleteMutation(UUID.randomUUID(), "BUDGET", budgetId, 1))
                .get(0).get("status").stringValue()).isEqualTo("APPLIED");

        JsonNode goal = syncResults(user, create(UUID.randomUUID(), "GOAL", UUID.randomUUID(),
                """
                {"name":"Reserva","targetAmount":10000.00,"currentAmount":500.00,
                 "targetDate":"2027-06-30"}
                """));
        assertThat(goal.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        long goalId = goal.get(0).get("resourceId").asLong();
        JsonNode goalUpdated = syncResults(user, update(UUID.randomUUID(), "GOAL", goalId, 0, """
                {"name":"Reserva de emergência","targetAmount":12000.00,
                 "currentAmount":900.00,"targetDate":"2027-06-30"}
                """));
        assertThat(goalUpdated.get(0).get("result").get("currentAmount").decimalValue())
                .isEqualByComparingTo("900.00");

        JsonNode item = syncResults(user, create(UUID.randomUUID(), "WISHLIST_ITEM",
                UUID.randomUUID(), """
                {"name":"Notebook","priority":"HIGH","targetPrice":4500.00}
                """));
        assertThat(item.get(0).get("status").stringValue()).isEqualTo("APPLIED");
    }

    @Test
    void aGoalUpdateIsNotTurnedIntoAContributionEvent() throws Exception {
        JsonNode goal = syncResults(user, create(UUID.randomUUID(), "GOAL", UUID.randomUUID(),
                """
                {"name":"Reserva","targetAmount":10000.00,"currentAmount":500.00}
                """));
        long goalId = goal.get(0).get("resourceId").asLong();

        // 900 is the new balance, not 500 + 900.
        syncResults(user, update(UUID.randomUUID(), "GOAL", goalId, 0, """
                {"name":"Reserva","targetAmount":10000.00,"currentAmount":900.00}
                """));

        mockMvc.perform(get("/api/goals/{id}", goalId).cookie(user.session()))
                .andExpect(jsonPath("$.currentAmount").value(900.00));
    }

    @Test
    void anOfflineItemCarriesItsOptionAndItsPriceObservationInOneBatch() throws Exception {
        UUID itemId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();

        JsonNode results = syncResults(user,
                create(UUID.randomUUID(), "WISHLIST_ITEM", itemId, """
                        {"name":"Notebook","priority":"HIGH","targetPrice":4500.00}
                        """),
                create(UUID.randomUUID(), "PURCHASE_OPTION", optionId, """
                        {"item":{"clientResourceId":"%s"},"merchant":"Loja","kind":"CASH",
                         "basePrice":4800.00,"shipping":20.00,"fees":10.00}
                        """.formatted(itemId)),
                create(UUID.randomUUID(), "PRICE_SNAPSHOT", snapshotId, """
                        {"item":{"clientResourceId":"%s"},
                         "purchaseOption":{"clientResourceId":"%s"},
                         "merchant":"Loja","paymentKind":"CASH","basePrice":4700.00,
                         "shipping":20.00,"fees":10.00,"observedOn":"2026-07-20"}
                        """.formatted(itemId, optionId)));

        assertThat(results.valueStream().map(node -> node.get("status").stringValue()).toList())
                .containsExactly("APPLIED", "APPLIED", "APPLIED");

        long serverItemId = results.get(0).get("resourceId").asLong();
        mockMvc.perform(get("/api/wishlist/{id}", serverItemId).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options.length()").value(1))
                .andExpect(jsonPath("$.options[0].merchant").value("Loja"));

        // History only: the current option keeps the price the user set.
        mockMvc.perform(get("/api/wishlist/{id}", serverItemId).cookie(user.session()))
                .andExpect(jsonPath("$.options[0].basePrice").value(4800.00));
        mockMvc.perform(get("/api/wishlist/{id}/price-snapshots", serverItemId)
                        .cookie(user.session()).param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].basePrice").value(4700.00));
    }

    @Test
    void aSnapshotExposesTheVersionAnOfflineEditHasToSendBack() throws Exception {
        UUID itemId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        JsonNode created = syncResults(user,
                create(UUID.randomUUID(), "WISHLIST_ITEM", itemId, """
                        {"name":"Notebook","priority":"HIGH"}
                        """),
                create(UUID.randomUUID(), "PRICE_SNAPSHOT", snapshotId, """
                        {"item":{"clientResourceId":"%s"},"merchant":"Loja",
                         "paymentKind":"CASH","basePrice":100.00,"observedOn":"2026-07-20"}
                        """.formatted(itemId)));
        long serverItemId = created.get(0).get("resourceId").asLong();
        long serverSnapshotId = created.get(1).get("resourceId").asLong();

        // Without this field on the response, an offline edit has nothing to
        // send as baseVersion and would guess at zero.
        mockMvc.perform(get("/api/wishlist/{id}/price-snapshots", serverItemId)
                        .cookie(user.session()).param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].version").value(0));

        JsonNode edited = syncResults(user, update(UUID.randomUUID(), "PRICE_SNAPSHOT",
                serverSnapshotId, 0, """
                {"item":{"serverId":%d},"merchant":"Loja","paymentKind":"CASH",
                 "basePrice":90.00,"observedOn":"2026-07-20"}
                """.formatted(serverItemId)));
        assertThat(edited.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        assertThat(edited.get(0).get("version").asLong()).isEqualTo(1);

        // The version the client last saw is now stale, and the server says so
        // instead of overwriting the newer observation.
        JsonNode stale = syncResults(user, update(UUID.randomUUID(), "PRICE_SNAPSHOT",
                serverSnapshotId, 0, """
                {"item":{"serverId":%d},"merchant":"Loja","paymentKind":"CASH",
                 "basePrice":80.00,"observedOn":"2026-07-20"}
                """.formatted(serverItemId)));
        assertThat(stale.get(0).get("status").stringValue()).isEqualTo("CONFLICT");
        assertThat(stale.get(0).get("conflict").get("conflictType").stringValue())
                .isEqualTo("VERSION_MISMATCH");

        mockMvc.perform(get("/api/wishlist/{id}/price-snapshots", serverItemId)
                        .cookie(user.session()).param("page", "0").param("size", "20"))
                .andExpect(jsonPath("$.content[0].basePrice").value(90.00));
    }

    @Test
    void aSnapshotPayloadCannotSmuggleAnOptionUpdate() throws Exception {
        UUID itemId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        syncResults(user,
                create(UUID.randomUUID(), "WISHLIST_ITEM", itemId, """
                        {"name":"Notebook","priority":"HIGH"}
                        """),
                create(UUID.randomUUID(), "PURCHASE_OPTION", optionId, """
                        {"item":{"clientResourceId":"%s"},"merchant":"Loja","kind":"CASH",
                         "basePrice":4800.00}
                        """.formatted(itemId)));

        // updateLinkedOption has no wire representation, so sending it is inert.
        JsonNode results = syncResults(user, create(UUID.randomUUID(), "PRICE_SNAPSHOT",
                UUID.randomUUID(), """
                {"item":{"clientResourceId":"%s"},
                 "purchaseOption":{"clientResourceId":"%s"},
                 "merchant":"Loja","paymentKind":"CASH","basePrice":100.00,
                 "observedOn":"2026-07-20","updateLinkedOption":true}
                """.formatted(itemId, optionId)));
        assertThat(results.get(0).get("status").stringValue()).isEqualTo("APPLIED");

        long serverItemId = mockMvc.perform(get("/api/wishlist").cookie(user.session()))
                .andReturn().getResponse().getContentAsString()
                .isEmpty() ? 0 : firstWishlistId();
        mockMvc.perform(get("/api/wishlist/{id}", serverItemId).cookie(user.session()))
                .andExpect(jsonPath("$.options[0].basePrice").value(4800.00));
    }

    @Test
    void purchaseOptionsKeepTheirReconciliationAndCardRules() throws Exception {
        long item = createWishlistItemOnline(user, "Notebook");

        JsonNode broken = syncResults(user, create(UUID.randomUUID(), "PURCHASE_OPTION",
                UUID.randomUUID(), """
                {"item":{"serverId":%d},"merchant":"Loja","kind":"INSTALLMENT",
                 "basePrice":1000.00,"installmentCount":10,"installmentAmount":50.00}
                """.formatted(item)));
        assertThat(broken.get(0).get("error").get("code").stringValue())
                .isEqualTo("OPTION_INSTALLMENTS_DONT_RECONCILE");

        JsonNode cashWithInstallments = syncResults(user, create(UUID.randomUUID(),
                "PURCHASE_OPTION", UUID.randomUUID(), """
                {"item":{"serverId":%d},"merchant":"Loja","kind":"CASH",
                 "basePrice":1000.00,"installmentCount":2,"installmentAmount":500.00}
                """.formatted(item)));
        assertThat(cashWithInstallments.get(0).get("error").get("code").stringValue())
                .isEqualTo("OPTION_CASH_WITH_INSTALLMENTS");

        // Cards are never created offline; an unknown card reference is refused.
        JsonNode unknownCard = syncResults(user, create(UUID.randomUUID(), "PURCHASE_OPTION",
                UUID.randomUUID(), """
                {"item":{"serverId":%d},"merchant":"Loja","kind":"INSTALLMENT",
                 "basePrice":1000.00,"installmentCount":10,"installmentAmount":100.00,
                 "creditCardId":999999}
                """.formatted(item)));
        assertThat(unknownCard.get(0).get("status").stringValue()).isEqualTo("REJECTED");
    }

    @Test
    void everyGeneratedOrProtectedTransactionIsRefusedByTheEndpoint() throws Exception {
        // Each of these flags is set by the workflow that owns the record. The
        // fixture writes them directly, with real parent rows, so all five
        // refusals are exercised deterministically without depending on the
        // shape of another domain's endpoints.
        assertProtected("statement_import_item_id = " + seedImportItem(),
                "SYNC_TRANSACTION_IMPORTED");
        assertProtected("commitment_id = " + seedCommitment(),
                "SYNC_TRANSACTION_FROM_RECURRING");
        assertProtected("wishlist_item_id = " + createWishlistItemOnline(user, "Comprado"),
                "SYNC_TRANSACTION_FROM_WISHLIST");
        assertProtected("legacy_credit = TRUE", "SYNC_TRANSACTION_LEGACY_CREDIT");
        // V10 only allows an inactive row to exist if it is also legacy credit,
        // so the converted case is necessarily both — and must report the more
        // specific reason.
        assertProtected("legacy_credit = TRUE, financially_active = FALSE",
                "SYNC_TRANSACTION_CONVERTED");
    }

    private void assertProtected(String flag, String expectedCode) throws Exception {
        long id = createTransactionOnline(user, foodCategory, "30.00", "Protegida");
        jdbc.update("UPDATE transactions SET " + flag + " WHERE id = ? AND user_id = ?",
                id, user.id());

        JsonNode edit = syncResults(user, update(UUID.randomUUID(), "TRANSACTION", id, 0,
                transactionPayload(foodCategory, "1.00", "Adulterada")));
        assertThat(edit.get(0).get("status").stringValue()).as(flag).isEqualTo("REJECTED");
        assertThat(edit.get(0).get("error").get("code").stringValue())
                .as(flag).isEqualTo(expectedCode);

        JsonNode removal = syncResults(user,
                deleteMutation(UUID.randomUUID(), "TRANSACTION", id, 0));
        assertThat(removal.get(0).get("error").get("code").stringValue())
                .as(flag).isEqualTo(expectedCode);

        // The protected row is untouched and still there.
        mockMvc.perform(get("/api/transactions/{id}", id).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(30.00));
    }

    private long seedAccount() {
        jdbc.update("""
                INSERT INTO accounts (name, type, opening_balance, user_id, currency)
                VALUES ('Conta sync', 'CHECKING', 0, ?, 'BRL')
                """, user.id());
        return lastId("accounts");
    }

    private long seedCommitment() {
        jdbc.update("""
                INSERT INTO commitments
                    (user_id, description, amount, category_id, cadence, due_day, start_date,
                     currency)
                VALUES (?, 'Assinatura sintética', 30, ?, 'MONTHLY', 10, '2026-01-01', 'BRL')
                """, user.id(), foodCategory);
        return lastId("commitments");
    }

    private long seedImportItem() {
        long account = seedAccount();
        jdbc.update("""
                INSERT INTO statement_import_batches
                    (user_id, account_id, original_filename, format, file_sha256,
                     file_size_bytes, parser_version, fingerprint_version, status)
                VALUES (?, ?, 'sync.csv', 'CSV', ?, 10, 1, 1, 'COMPLETED')
                """, user.id(), account, "d".repeat(64));
        long batch = lastId("statement_import_batches");
        jdbc.update("""
                INSERT INTO statement_import_items
                    (batch_id, user_id, account_id, source_index, posted_date, description,
                     normalized_description, amount, type, fingerprint, status, imported_at)
                VALUES (?, ?, ?, 1, '2026-07-03', 'Importada', 'importada', 25, 'EXPENSE',
                        ?, 'IMPORTED', now())
                """, batch, user.id(), account, "e".repeat(64));
        return lastId("statement_import_items");
    }

    private long lastId(String table) {
        Long id = jdbc.queryForObject(
                "SELECT max(id) FROM " + table + " WHERE user_id = ?", Long.class, user.id());
        return java.util.Objects.requireNonNull(id);
    }

    private long firstWishlistId() throws Exception {
        String body = mockMvc.perform(get("/api/wishlist").cookie(user.session()))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get(0).get("id").asLong();
    }
}
