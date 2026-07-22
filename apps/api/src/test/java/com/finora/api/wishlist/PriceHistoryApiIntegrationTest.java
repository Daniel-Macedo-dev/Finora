package com.finora.api.wishlist;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.finora.api.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class PriceHistoryApiIntegrationTest extends AbstractIntegrationTest {
    private TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
    }

    @Test
    void manualHistoryIsIdempotentAndSummaryUsesPreviousComparable() throws Exception {
        long item = createItem(user, "Notebook", 1600);
        String requestId = UUID.randomUUID().toString();
        manual(user, item, requestId, "Loja Ágil", 1800, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nominalCost").value(1830.00))
                .andExpect(jsonPath("$.seriesKey").value("MANUAL:loja agil:CASH"));
        manual(user, item, requestId, "Loja Ágil", 1800, false)
                .andExpect(status().isCreated());
        manual(user, item, UUID.randomUUID().toString(), "Loja Ágil", 1500, false)
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/wishlist/{id}/price-history-summary", item)
                        .cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationCount").value(2))
                .andExpect(jsonPath("$.seriesCount").value(1))
                .andExpect(jsonPath("$.latestObservedBestCost").value(1530.00))
                .andExpect(jsonPath("$.previousComparableCost").value(1830.00))
                .andExpect(jsonPath("$.absoluteChange").value(-300.00))
                .andExpect(jsonPath("$.percentageChange").value(-16.39))
                .andExpect(jsonPath("$.historicalMinimum").value(1530.00))
                .andExpect(jsonPath("$.historicalMaximum").value(1830.00))
                .andExpect(jsonPath("$.historicalAverage").value(1680.00))
                .andExpect(jsonPath("$.targetReached").value(true));
    }

    @Test
    void conflictingRetryAndUnsafeUrlAreRejected() throws Exception {
        long item = createItem(user, "Monitor", 900);
        String requestId = UUID.randomUUID().toString();
        manual(user, item, requestId, "Loja", 1000, false).andExpect(status().isCreated());
        manual(user, item, requestId, "Loja", 999, false)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRICE_SNAPSHOT_IDEMPOTENCY_CONFLICT"));

        mockMvc.perform(post("/api/wishlist/{id}/price-snapshots", item)
                        .cookie(user.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"%s","merchant":"Loja","paymentKind":"CASH",
                                 "basePrice":100,"observedOn":"2026-07-01",
                                 "offerUrl":"javascript:alert(1)","updateLinkedOption":false}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRICE_SNAPSHOT_URL_INVALID"));
    }

    @Test
    void captureCopiesOptionAndOptionDeletionPreservesHistory() throws Exception {
        long item = createItem(user, "Cadeira", 1000);
        long option = createCashOption(user, item, "Loja Atual", 1100);
        String body = mockMvc.perform(post(
                        "/api/wishlist/{item}/options/{option}/price-snapshots", item, option)
                        .cookie(user.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"%s","observedOn":"2026-07-01",
                                 "offerUrl":"https://example.test/offer"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merchant").value("Loja Atual"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long snapshot = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(delete("/api/wishlist/{item}/options/{option}", item, option)
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/wishlist/{id}/price-snapshots", item).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(snapshot))
                .andExpect(jsonPath("$.content[0].purchaseOptionId").doesNotExist())
                .andExpect(jsonPath("$.content[0].seriesKey").value("OPTION:" + option));
    }

    @Test
    void linkedHistoryOnlyLeavesOptionAndExplicitUpdateChangesItAtomically() throws Exception {
        long item = createItem(user, "Mesa", 700);
        long option = createCashOption(user, item, "Loja", 900);
        linked(user, item, option, 850, false).andExpect(status().isCreated());
        mockMvc.perform(get("/api/wishlist/{id}", item).cookie(user.session()))
                .andExpect(jsonPath("$.options[0].basePrice").value(900.00));
        linked(user, item, option, 750, true).andExpect(status().isCreated());
        mockMvc.perform(get("/api/wishlist/{id}", item).cookie(user.session()))
                .andExpect(jsonPath("$.options[0].basePrice").value(750.00));
    }

    @Test
    void historyCrudFiltersAndChartRemainOwnerScoped() throws Exception {
        long item = createItem(user, "Telefone", 2000);
        String response = manual(user, item, UUID.randomUUID().toString(), "Loja A", 2100, false)
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        long snapshot = objectMapper.readTree(response).get("id").asLong();
        TestUser other = registerUser("Outro");
        mockMvc.perform(get("/api/wishlist/{id}/price-snapshots", item).cookie(other.session()))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/wishlist/{item}/price-snapshots/{snapshot}", item, snapshot)
                        .cookie(user.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant":"Loja B","paymentKind":"CASH","basePrice":1900,
                                 "shipping":0,"fees":0,"observedOn":"2026-07-02",
                                 "offerUrl":"https://example.test/b"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.merchant").value("Loja B"));
        mockMvc.perform(get("/api/wishlist/{id}/price-history-series", item)
                        .param("from", "2026-01-01").param("to", "2026-07-22")
                        .cookie(user.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.points.length()").value(1));
        mockMvc.perform(delete("/api/wishlist/{item}/price-snapshots/{snapshot}", item, snapshot)
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/wishlist/{id}/price-snapshots", item).cookie(user.session()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void historyOnlySnapshotDoesNotChangePurchaseAnalysis() throws Exception {
        long item = createItem(user, "Análise estável", 1000);
        long option = createCashOption(user, item, "Loja", 1200);
        String before = mockMvc.perform(get("/api/wishlist/{id}/analysis", item)
                        .cookie(user.session())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        linked(user, item, option, 700, false).andExpect(status().isCreated());
        String afterHistory = mockMvc.perform(get("/api/wishlist/{id}/analysis", item)
                        .cookie(user.session())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(afterHistory)).isEqualTo(objectMapper.readTree(before));

        linked(user, item, option, 900, true).andExpect(status().isCreated());
        mockMvc.perform(get("/api/wishlist/{id}/analysis", item).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].nominalCost").value(900.00));
    }

    private long createItem(TestUser owner, String name, int target) throws Exception {
        String body = mockMvc.perform(post("/api/wishlist").cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","priority":"HIGH","targetPrice":%d,
                                 "status":"MONITORING"}
                                """.formatted(name, target)))
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long createCashOption(TestUser owner, long item, String merchant, int price)
            throws Exception {
        String body = mockMvc.perform(post("/api/wishlist/{id}/options", item)
                        .cookie(owner.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant":"%s","kind":"CASH","basePrice":%d}
                                """.formatted(merchant, price)))
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions manual(TestUser owner, long item,
            String requestId, String merchant, int price, boolean update) throws Exception {
        return mockMvc.perform(post("/api/wishlist/{id}/price-snapshots", item)
                .cookie(owner.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"clientRequestId":"%s","merchant":"%s","paymentKind":"CASH",
                         "basePrice":%d,"shipping":20,"fees":10,"observedOn":"2026-07-01",
                         "notes":"Observação sintética","updateLinkedOption":%s}
                        """.formatted(requestId, merchant, price, update)));
    }

    private org.springframework.test.web.servlet.ResultActions linked(TestUser owner, long item,
            long option, int price, boolean update) throws Exception {
        return mockMvc.perform(post("/api/wishlist/{id}/price-snapshots", item)
                .cookie(owner.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"clientRequestId":"%s","purchaseOptionId":%d,"merchant":"Loja",
                         "paymentKind":"CASH","basePrice":%d,"observedOn":"2026-07-01",
                         "updateLinkedOption":%s}
                        """.formatted(UUID.randomUUID(), option, price, update)));
    }
}
