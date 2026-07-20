package com.finora.api.statementimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Cross-owner isolation of the whole statement-import surface: another
 * user's batches, items, rules, accounts and categories behave as absent —
 * 404, never 403 — an attack attempt leaves the victim's data untouched,
 * and duplicate detection never crosses user boundaries (neither matched
 * transactions nor FITID strong identities leak between users).
 */
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class StatementImportOwnershipTest extends AbstractIntegrationTest {

    private static final String OFX = """
            OFXHEADER:100
            DATA:OFXSGML

            <OFX>
            <BANKMSGSRSV1><STMTTRNRS><STMTRS>
            <BANKTRANLIST>
            <STMTTRN>
            <TRNTYPE>DEBIT
            <DTPOSTED>20260605
            <TRNAMT>-25.90
            <FITID>FIT-OWN-1
            <NAME>Padaria Sao Joao
            </STMTTRN>
            </BANKTRANLIST>
            </STMTRS></STMTTRNRS></BANKMSGSRSV1>
            </OFX>
            """;

    private TestUser owner;
    private TestUser attacker;
    private long ownerAccountId;
    private long ownerBatchId;
    private long ownerItemId;

    @BeforeEach
    void setUp() throws Exception {
        owner = registerUser("Titular");
        attacker = registerUser("Invasor");
        ownerAccountId = createAccount(owner);
        JsonNode batch = upload(owner, ownerAccountId, status().isCreated());
        ownerBatchId = batch.get("id").asLong();
        ownerItemId = batch.get("items").get(0).get("id").asLong();
    }

    private long createAccount(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta Corrente", "type": "CHECKING",
                                 "openingBalance": 1000}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("id").asLong();
    }

    private JsonNode upload(TestUser user, long accountId,
                            org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/statement-imports")
                        .file(new MockMultipartFile("file", "extrato.ofx",
                                "application/octet-stream", OFX.getBytes(StandardCharsets.UTF_8)))
                        .param("accountId", String.valueOf(accountId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(expected)
                .andReturn();
        return json(result);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void assignCategoryAndConfirm(TestUser user, long batchId, long itemId)
            throws Exception {
        mockMvc.perform(patch("/api/statement-imports/%d/items/%d".formatted(batchId, itemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedCategoryId\": %d}".formatted(
                                categoryId(user, "Alimentação", CategoryType.EXPENSE))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/statement-imports/%d/confirm".formatted(batchId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void historyDetailAndEveryMutationOfForeignBatchesBehaveAsAbsent() throws Exception {
        // The attacker's history is empty even though the owner has a batch.
        mockMvc.perform(get("/api/statement-imports").cookie(attacker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/statement-imports/" + ownerBatchId)
                        .cookie(attacker.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/statement-imports/%d/items/%d"
                        .formatted(ownerBatchId, ownerItemId))
                        .cookie(attacker.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"included\": false}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/statement-imports/%d/confirm".formatted(ownerBatchId))
                        .cookie(attacker.session()).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/statement-imports/%d/undo".formatted(ownerBatchId))
                        .cookie(attacker.session()).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/statement-imports/%d/items/%d/undo"
                        .formatted(ownerBatchId, ownerItemId))
                        .cookie(attacker.session()).with(csrf()))
                .andExpect(status().isNotFound());

        // The attack attempts left the owner's batch fully intact.
        mockMvc.perform(get("/api/statement-imports/" + ownerBatchId).cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREVIEW_READY"))
                .andExpect(jsonPath("$.items[0].included").value(true));
    }

    @Test
    void foreignAccountsAndCategoriesBehaveAsAbsent() throws Exception {
        // Uploading into another user's account is a 404, and no batch appears.
        upload(attacker, ownerAccountId, status().isNotFound());
        mockMvc.perform(get("/api/statement-imports").cookie(attacker.session()))
                .andExpect(jsonPath("$.totalElements").value(0));

        // Selecting another user's category on an own item is a 404.
        long attackerAccount = createAccount(attacker);
        JsonNode batch = upload(attacker, attackerAccount, status().isCreated());
        mockMvc.perform(patch("/api/statement-imports/%d/items/%d"
                        .formatted(batch.get("id").asLong(),
                                batch.get("items").get(0).get("id").asLong()))
                        .cookie(attacker.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedCategoryId\": %d}".formatted(
                                categoryId(owner, "Alimentação", CategoryType.EXPENSE))))
                .andExpect(status().isNotFound());
    }

    @Test
    void categoryRulesAreOwnerScoped() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/category-mapping-rules")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionType": "EXPENSE", "matchField": "DESCRIPTION",
                                 "operation": "CONTAINS", "pattern": "PADARIA",
                                 "categoryId": %d, "priority": 10, "active": true}
                                """.formatted(
                                categoryId(owner, "Alimentação", CategoryType.EXPENSE))))
                .andExpect(status().isCreated())
                .andReturn();
        long ruleId = json(created).get("id").asLong();

        mockMvc.perform(get("/api/category-mapping-rules").cookie(attacker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(put("/api/category-mapping-rules/" + ruleId)
                        .cookie(attacker.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionType": "EXPENSE", "matchField": "DESCRIPTION",
                                 "operation": "CONTAINS", "pattern": "PADARIA",
                                 "categoryId": %d, "priority": 10, "active": false}
                                """.formatted(
                                categoryId(attacker, "Alimentação", CategoryType.EXPENSE))))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/category-mapping-rules/" + ruleId)
                        .cookie(attacker.session()).with(csrf()))
                .andExpect(status().isNotFound());

        // A rule that targets another user's category cannot be created.
        mockMvc.perform(post("/api/category-mapping-rules")
                        .cookie(attacker.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionType": "EXPENSE", "matchField": "DESCRIPTION",
                                 "operation": "CONTAINS", "pattern": "MERCADO",
                                 "categoryId": %d, "priority": 5, "active": true}
                                """.formatted(
                                categoryId(owner, "Alimentação", CategoryType.EXPENSE))))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateDetectionNeverCrossesUserBoundaries() throws Exception {
        // The attacker holds a transaction identical to the owner's statement
        // row: the owner's preview still reports UNIQUE, with no leaked match.
        long attackerAccount = createAccount(attacker);
        mockMvc.perform(post("/api/transactions")
                        .cookie(attacker.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 25.90,
                                 "description": "Padaria São João",
                                 "date": "2026-06-05", "categoryId": %d, "accountId": %d}
                                """.formatted(
                                categoryId(attacker, "Alimentação", CategoryType.EXPENSE),
                                attackerAccount)))
                .andExpect(status().isCreated());

        MvcResult detail = mockMvc.perform(get("/api/statement-imports/" + ownerBatchId)
                        .cookie(owner.session()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode item = json(detail).get("items").get(0);
        assertThat(item.get("duplicateStatus").asString()).isEqualTo("UNIQUE");
        assertThat(item.get("matchedTransaction").isNull()).isTrue();

        // The owner importing FIT-OWN-1 does not poison the attacker's own
        // account: the same FITID stays importable per user.
        assignCategoryAndConfirm(owner, ownerBatchId, ownerItemId);
        JsonNode attackerBatch = upload(attacker, attackerAccount, status().isCreated());
        assertThat(attackerBatch.get("items").get(0).get("duplicateStatus").asString())
                .isNotEqualTo("EXACT_DUPLICATE");
    }
}
