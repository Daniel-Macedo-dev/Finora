package com.finora.api.creditcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the pessimistic card lock: two simultaneous purchases that each fit
 * the limit — but not together — cannot both succeed. Runs without the usual
 * test transaction so each MockMvc request commits in its own transaction,
 * exactly like production.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PurchaseLimitConcurrencyTest extends AbstractIntegrationTest {

    @Test
    void concurrentPurchasesCannotOverspendTheLimit() throws Exception {
        TestUser user = registerUser();
        Long categoryId = categoryId(user, "Compras", CategoryType.EXPENSE);

        MvcResult card = mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Cartão Concorrente", "brand": "VISA",
                                 "creditLimit": 100.00, "closingDay": 10, "dueDay": 17}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long cardId = objectMapper.readTree(
                        card.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();

        // Two R$ 60,00 purchases: each fits the R$ 100,00 limit alone, never both.
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> attempt = () -> {
            start.await();
            return mockMvc.perform(post("/api/credit-cards/%d/purchases".formatted(cardId))
                            .cookie(user.session()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"description": "Compra simultânea", "categoryId": %d,
                                     "purchaseDate": "2031-03-05",
                                     "totalAmount": 60.00, "installmentCount": 1}
                                    """.formatted(categoryId)))
                    .andReturn().getResponse().getStatus();
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = List.of(executor.submit(attempt), executor.submit(attempt));
            start.countDown();
            List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
            assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        } finally {
            executor.shutdown();
        }

        // The surviving state is one purchase and R$ 40,00 available.
        mockMvc.perform(get("/api/credit-cards/" + cardId).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.limit.usedLimit").value(60.00))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.limit.availableLimit").value(40.00));
    }
}
