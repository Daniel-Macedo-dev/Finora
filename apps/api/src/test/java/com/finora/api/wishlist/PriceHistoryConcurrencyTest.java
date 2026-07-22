package com.finora.api.wishlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.identity.AuthenticatedUser;
import com.finora.api.identity.User;
import com.finora.api.identity.UserRepository;
import com.finora.api.wishlist.PriceHistoryDtos.ObservationMetadataRequest;
import com.finora.api.wishlist.PriceHistoryDtos.SnapshotRequest;
import com.finora.api.wishlist.PriceHistoryDtos.SnapshotUpdateRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PriceHistoryConcurrencyTest extends AbstractIntegrationTest {
    @Autowired PriceHistoryService service;
    @Autowired PriceSnapshotRepository snapshots;
    @Autowired UserRepository users;
    @Autowired PurchaseOptionRepository options;
    @Autowired WishlistService wishlist;
    @Autowired WishlistItemRepository items;

    @BeforeEach
    void clearSnapshots() {
        snapshots.deleteAll();
    }

    @Test
    void sameKeyCreatesOneSnapshotAndConflictingPayloadCannotOverwriteIt() throws Exception {
        TestUser user = registerUser("Retentativa");
        long item = createItem(user);
        UUID key = UUID.randomUUID();
        SnapshotRequest request = request(key, null, 1000, false);

        List<Long> ids = race(
                () -> asUser(user.id(), () -> service.create(item, request).id()),
                () -> asUser(user.id(), () -> service.create(item, request).id()));

        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(snapshots.count()).isEqualTo(1);
        assertThatThrownBy(() -> asUser(user.id(), () ->
                service.create(item, request(key, null, 900, false))))
                .hasMessageContaining("dados diferentes");
        assertThat(snapshots.findAll().getFirst().getBasePrice())
                .isEqualByComparingTo("1000.00");
    }

    @Test
    void simultaneousHistoryOnlyRetriesNeverAlterCurrentOption() throws Exception {
        TestUser user = registerUser("Histórico");
        long item = createItem(user);
        long option = createOption(user, item, 1200);
        UUID key = UUID.randomUUID();
        SnapshotRequest request = request(key, option, 1000, false);

        race(() -> asUser(user.id(), () -> service.create(item, request).id()),
                () -> asUser(user.id(), () -> service.create(item, request).id()));

        assertThat(snapshots.count()).isEqualTo(1);
        assertThat(options.findById(option).orElseThrow().getBasePrice())
                .isEqualByComparingTo("1200.00");
        assertThat(serviceSummary(user.id(), item).observationCount()).isEqualTo(1);
    }

    @Test
    void simultaneousUpdateLinkedRetryCreatesAndUpdatesExactlyOnceAcrossOwners() throws Exception {
        TestUser owner = registerUser("Atualização");
        TestUser other = registerUser("Outro dono");
        long item = createItem(owner);
        long option = createOption(owner, item, 1200);
        UUID sharedKey = UUID.randomUUID();
        SnapshotRequest update = request(sharedKey, option, 900, true);

        race(() -> asUser(owner.id(), () -> service.create(item, update).id()),
                () -> asUser(owner.id(), () -> service.create(item, update).id()));
        long otherItem = createItem(other);
        asUser(other.id(), () -> service.create(otherItem,
                request(sharedKey, null, 800, false)).id());

        assertThat(options.findById(option).orElseThrow().getBasePrice())
                .isEqualByComparingTo("900.00");
        assertThat(snapshots.count()).isEqualTo(2);
        assertThat(snapshots.findAll()).extracting(PriceSnapshot::getUserId)
                .containsExactlyInAnyOrder(owner.id(), other.id());
    }

    @Test
    void captureRacingOptionDeletionNeverLeavesADanglingOptionLink() throws Exception {
        TestUser user = registerUser("Exclusão de opção");
        long item = createItem(user);
        long option = createOption(user, item, 1200);
        ObservationMetadataRequest request = new ObservationMetadataRequest(
                UUID.randomUUID(), LocalDate.of(2026, 7, 1), null, null);

        raceOutcomes(
                () -> asUser(user.id(), () -> service.capture(item, option, request).id()),
                () -> asUser(user.id(), () -> { wishlist.deleteOption(item, option); return null; }));

        assertThat(options.findById(option)).isEmpty();
        assertThat(snapshots.findAll()).allMatch(snapshot -> snapshot.getPurchaseOptionId() == null);
    }

    @Test
    void creationRacingItemDeletionNeverLeavesAnOrphan() throws Exception {
        TestUser user = registerUser("Exclusão de item");
        long item = createItem(user);

        raceOutcomes(
                () -> asUser(user.id(), () -> service.create(item,
                        request(UUID.randomUUID(), null, 1000, false)).id()),
                () -> asUser(user.id(), () -> { wishlist.delete(item); return null; }));

        assertThat(items.findById(item)).isEmpty();
        assertThat(snapshots.findAll()).noneMatch(snapshot -> snapshot.getItem().getId().equals(item));
    }

    @Test
    void editRacingDeleteHasAValidDeterministicFinalStateAndRepeatedDeleteIsSafe() throws Exception {
        TestUser user = registerUser("Edição e exclusão");
        long item = createItem(user);
        long snapshot = asUser(user.id(), () -> service.create(item,
                request(UUID.randomUUID(), null, 1000, false)).id());
        SnapshotUpdateRequest update = new SnapshotUpdateRequest(null, "Loja corrigida",
                PurchaseOptionKind.CASH, BigDecimal.valueOf(900), BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, LocalDate.of(2026, 7, 2), null, null);

        raceOutcomes(
                () -> asUser(user.id(), () -> service.update(item, snapshot, update).id()),
                () -> asUser(user.id(), () -> { service.delete(item, snapshot); return null; }));

        snapshots.findById(snapshot).ifPresent(remaining -> {
            assertThat(remaining.getMerchant()).isEqualTo("Loja corrigida");
            assertThat(remaining.getBasePrice()).isEqualByComparingTo("900.00");
            asUserUnchecked(user.id(), () -> service.delete(item, snapshot));
        });
        assertThatThrownBy(() -> asUser(user.id(), () -> {
            service.delete(item, snapshot);
            return null;
        })).hasMessageContaining("não encontrado");
    }

    private PriceHistoryDtos.SummaryResponse serviceSummary(long userId, long itemId)
            throws Exception {
        return asUser(userId, () -> service.summary(itemId));
    }

    private SnapshotRequest request(UUID key, Long optionId, int price, boolean update) {
        return new SnapshotRequest(key, optionId, "Loja concorrente", PurchaseOptionKind.CASH,
                BigDecimal.valueOf(price), BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, LocalDate.of(2026, 7, 1), null, null, update);
    }

    private long createItem(TestUser user) throws Exception {
        String body = mockMvc.perform(post("/api/wishlist").cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Item concorrente","priority":"HIGH",
                                 "targetPrice":950,"status":"MONITORING"}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long createOption(TestUser user, long item, int price) throws Exception {
        String body = mockMvc.perform(post("/api/wishlist/{id}/options", item)
                        .cookie(user.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant":"Loja concorrente","kind":"CASH","basePrice":%d}
                                """.formatted(price)))
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("id").asLong();
    }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<T> gate = () -> { ready.countDown(); start.await(); return null; };
            Future<T> left = executor.submit(() -> { gate.call(); return first.call(); });
            Future<T> right = executor.submit(() -> { gate.call(); return second.call(); });
            ready.await(); start.countDown();
            return List.of(left.get(), right.get());
        }
    }

    private void raceOutcomes(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Void> leftAction = () -> {
                ready.countDown();
                start.await();
                try { first.call(); } catch (Exception ignored) { }
                return null;
            };
            Callable<Void> rightAction = () -> {
                ready.countDown();
                start.await();
                try { second.call(); } catch (Exception ignored) { }
                return null;
            };
            Future<Void> left = executor.submit(leftAction);
            Future<Void> right = executor.submit(rightAction);
            ready.await(); start.countDown(); left.get(); right.get();
        }
    }

    private <T> T asUser(Long userId, Callable<T> action) throws Exception {
        User user = users.findById(userId).orElseThrow();
        AuthenticatedUser principal = new AuthenticatedUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));
        try { return action.call(); } finally { SecurityContextHolder.clearContext(); }
    }

    private void asUserUnchecked(Long userId, Runnable action) {
        try {
            asUser(userId, () -> { action.run(); return null; });
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
