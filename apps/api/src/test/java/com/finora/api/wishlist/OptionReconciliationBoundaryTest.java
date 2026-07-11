package com.finora.api.wishlist;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class OptionReconciliationBoundaryTest extends AbstractIntegrationTest {

    private TestUser user;
    private long itemId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        String body = mockMvc.perform(post("/api/wishlist")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Item limite", "priority": "MEDIUM"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        itemId = objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void acceptsDifferenceExactlyAtTolerance() throws Exception {
        // 10 x 99.90 = 999.00; advertised total 999.10 -> diff 0.10 = 0.01 * 10.
        mockMvc.perform(post("/api/wishlist/{id}/options", itemId)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja Limite", "kind": "INSTALLMENT",
                                 "basePrice": 999.10, "installmentCount": 10,
                                 "installmentAmount": 99.90}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsDifferenceJustAboveTolerance() throws Exception {
        mockMvc.perform(post("/api/wishlist/{id}/options", itemId)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja Estourada", "kind": "INSTALLMENT",
                                 "basePrice": 999.11, "installmentCount": 10,
                                 "installmentAmount": 99.90}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPTION_INSTALLMENTS_DONT_RECONCILE"));
    }
}
