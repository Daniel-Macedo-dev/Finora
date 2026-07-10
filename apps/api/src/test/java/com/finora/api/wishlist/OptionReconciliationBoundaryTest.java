package com.finora.api.wishlist;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

class OptionReconciliationBoundaryTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long itemId;

    @BeforeEach
    void setUp() throws Exception {
        String body = mockMvc.perform(post("/api/wishlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Item limite", "priority": "MEDIUM"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        itemId = objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void acceptsDifferenceExactlyAtTolerance() throws Exception {
        // 10 x 99.90 = 999.00; advertised total 999.10 -> diff 0.10 = 0.01 * 10.
        mockMvc.perform(post("/api/wishlist/{id}/options", itemId)
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
        // Diff 0.11 > 0.10 tolerance for 10 installments.
        mockMvc.perform(post("/api/wishlist/{id}/options", itemId)
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
