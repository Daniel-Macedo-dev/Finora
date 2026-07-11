package com.finora.api.wishlist;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class WishlistApiIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
    }

    private long createItem() throws Exception {
        String body = mockMvc.perform(post("/api/wishlist")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Cadeira ergonômica", "priority": "HIGH",
                                 "referencePrice": 1800.00, "status": "PLANNING"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void createsItemWithOptionsAndListsSummary() throws Exception {
        long id = createItem();

        mockMvc.perform(post("/api/wishlist/{id}/options", id)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja A", "kind": "CASH", "basePrice": 1700.00,
                                 "shipping": 50.00, "fees": 0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nominalCost").value(1750.00));

        mockMvc.perform(post("/api/wishlist/{id}/options", id)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja B", "kind": "INSTALLMENT", "basePrice": 1800.00,
                                 "installmentCount": 10, "installmentAmount": 180.00}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/wishlist").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].optionCount").value(2))
                .andExpect(jsonPath("$[0].bestNominalCost").value(1750.00));
    }

    @Test
    void rejectsCashOptionCarryingInstallmentData() throws Exception {
        long id = createItem();
        mockMvc.perform(post("/api/wishlist/{id}/options", id)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja C", "kind": "CASH", "basePrice": 1700.00,
                                 "installmentCount": 10, "installmentAmount": 170.00}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPTION_CASH_WITH_INSTALLMENTS"));
    }

    @Test
    void rejectsInstallmentsThatDontReconcileWithTotal() throws Exception {
        long id = createItem();
        mockMvc.perform(post("/api/wishlist/{id}/options", id)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja D", "kind": "INSTALLMENT", "basePrice": 2000.00,
                                 "installmentCount": 10, "installmentAmount": 180.00}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPTION_INSTALLMENTS_DONT_RECONCILE"));
    }

    @Test
    void rejectsInstallmentOptionWithoutInstallmentData() throws Exception {
        long id = createItem();
        mockMvc.perform(post("/api/wishlist/{id}/options", id)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja E", "kind": "INSTALLMENT", "basePrice": 2000.00}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPTION_INSTALLMENT_DATA_REQUIRED"));
    }

    @Test
    void updatesSettingsUsedByAnalysis() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"minimumCashBuffer": 3000.00, "maxInstallmentCommitmentRatio": 0.25,
                                 "monthlyOpportunityRate": 0.008, "budgetWarningThreshold": 0.75}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimumCashBuffer").value(3000.00))
                .andExpect(jsonPath("$.monthlyOpportunityRate").value(0.008));

        mockMvc.perform(get("/api/settings").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxInstallmentCommitmentRatio").value(0.25));
    }
}
