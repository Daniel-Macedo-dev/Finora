package com.finora.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import com.finora.api.identity.AuthenticatedUser;
import com.finora.api.identity.User;
import com.finora.api.identity.UserRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Import(NotificationConcurrencyIntegrationTest.FixedClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationConcurrencyIntegrationTest extends AbstractIntegrationTest {
    private static final Instant INITIAL_TIME = Instant.parse("2026-07-21T12:00:00Z");

    @Autowired NotificationSynchronizationService synchronization;
    @Autowired NotificationService notificationService;
    @Autowired NotificationRepository notifications;
    @Autowired UserRepository users;
    @Autowired MutableClock mutableClock;

    @BeforeEach
    void resetClock() {
        mutableClock.setInstant(INITIAL_TIME);
    }

    @Test
    void simultaneousManualAndScheduledSyncCreateOneRowAndDoNotDoubleIncrement() throws Exception {
        TestUser user = registerUser("Concorrência");
        createCommitment(user, LocalDate.of(2026, 7, 22));

        List<NotificationDtos.SyncResponse> first = race(
                () -> synchronization.synchronize(user.id()),
                () -> synchronization.synchronize(user.id()));

        assertThat(first).extracting(NotificationDtos.SyncResponse::created)
                .containsExactlyInAnyOrder(0, 1);
        Notification notification = onlyNotification(user.id());
        assertThat(notification.getRevision()).isEqualTo(1);
        assertThat(notification.getType().name()).isEqualTo("RECURRING_DUE_SOON");

        mutableClock.setInstant(Instant.parse("2026-07-22T12:00:00Z"));
        race(() -> synchronization.synchronize(user.id()),
                () -> synchronization.synchronize(user.id()));

        notification = onlyNotification(user.id());
        assertThat(notification.getRevision()).isEqualTo(2);
        assertThat(notification.getType().name()).isEqualTo("RECURRING_DUE_TODAY");
        assertThat(notification.isUnread()).isTrue();
    }

    @Test
    void nonEscalatingSyncPreservesConcurrentReadAndRepeatedActionsAreIdempotent() throws Exception {
        TestUser user = registerUser("Estado");
        createCommitment(user, LocalDate.of(2026, 7, 22));
        synchronization.synchronize(user.id());
        Long id = onlyNotification(user.id()).getId();

        race(() -> synchronization.synchronize(user.id()),
                () -> asUser(user.id(), () -> notificationService.read(id)));
        asUser(user.id(), () -> {
            notificationService.read(id);
            notificationService.dismiss(id);
            notificationService.dismiss(id);
            notificationService.snooze(id, INITIAL_TIME.plusSeconds(3600));
            notificationService.snooze(id, INITIAL_TIME.plusSeconds(3600));
            return null;
        });

        Notification notification = onlyNotification(user.id());
        assertThat(notification.getReadRevision()).isEqualTo(1);
        assertThat(notification.getDismissedRevision()).isEqualTo(1);
        assertThat(notification.getSnoozedRevision()).isEqualTo(1);
        assertThat(notification.getSnoozedUntil()).isEqualTo(INITIAL_TIME.plusSeconds(3600));
    }

    @Test
    void simultaneousBrowserClaimsReturnOneRevisionToOneTab() throws Exception {
        TestUser user = registerUser("Abas");
        updatePreferences(user.session(), true, true);
        createCommitment(user, LocalDate.of(2026, 7, 22));
        synchronization.synchronize(user.id());

        List<List<NotificationDtos.BrowserClaimResponse>> claims = race(
                () -> asUser(user.id(), notificationService::claimBrowser),
                () -> asUser(user.id(), notificationService::claimBrowser));

        assertThat(claims).flatExtracting(value -> value).hasSize(1);
        assertThat(onlyNotification(user.id()).getBrowserDeliveredRevision()).isEqualTo(1);
    }

    @Test
    void resolveAndConcurrentReactivationLeaveOneConsistentRevision() throws Exception {
        TestUser user = registerUser("Reativação");
        createCommitment(user, LocalDate.of(2026, 7, 22));
        synchronization.synchronize(user.id());
        updatePreferences(user.session(), false, false);
        assertThat(synchronization.synchronize(user.id()).resolved()).isEqualTo(1);
        assertThat(onlyNotification(user.id()).getResolvedAt()).isNotNull();

        updatePreferences(user.session(), true, false);
        race(() -> synchronization.synchronize(user.id()),
                () -> synchronization.synchronize(user.id()));

        Notification notification = onlyNotification(user.id());
        assertThat(notifications.findAllByUserId(user.id(),
                org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isEqualTo(1);
        assertThat(notification.getResolvedAt()).isNull();
        assertThat(notification.getRevision()).isEqualTo(2);
        assertThat(notification.isUnread()).isTrue();
    }

    private void createCommitment(TestUser user, LocalDate scheduledDate) throws Exception {
        long categoryId = categoryId(user, "Assinaturas", CategoryType.EXPENSE);
        var account = mockMvc.perform(post("/api/accounts").cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Conta concorrente","type":"CHECKING","openingBalance":1000}
                                """))
                .andExpect(status().isCreated()).andReturn();
        long accountId = objectMapper.readTree(account.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("id").asLong();
        mockMvc.perform(post("/api/commitments").cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Assinatura concorrente","amount":120,"categoryId":%d,
                                 "cadence":"MONTHLY","dueDay":%d,"startDate":"%s",
                                 "targetKind":"ACCOUNT_TRANSACTION","accountId":%d}
                                """.formatted(categoryId, scheduledDate.getDayOfMonth(),
                                scheduledDate, accountId)))
                .andExpect(status().isCreated());
    }

    private void updatePreferences(Cookie session, boolean enabled, boolean browserEnabled)
            throws Exception {
        mockMvc.perform(put("/api/notification-preferences").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"enabled":%s,"upcomingLeadDays":7,
                                 "recurringDueEnabled":true,"invoiceDueEnabled":true,
                                 "executionFailureEnabled":true,"cashRiskEnabled":true,
                                 "browserEnabled":%s,"browserMinimumSeverity":"INFO",
                                 "browserShowAmounts":false}
                                """.formatted(enabled, browserEnabled)))
                .andExpect(status().isOk());
    }

    private Notification onlyNotification(Long userId) {
        return notifications.findAllByUserId(userId,
                org.springframework.data.domain.Pageable.unpaged()).getContent().getFirst();
    }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<T> gated = () -> {
                ready.countDown();
                start.await();
                return null;
            };
            Future<T> left = executor.submit(() -> { gated.call(); return first.call(); });
            Future<T> right = executor.submit(() -> { gated.call(); return second.call(); });
            ready.await();
            start.countDown();
            return List.of(left.get(), right.get());
        }
    }

    private <T> T asUser(Long userId, Callable<T> action) throws Exception {
        User user = users.findById(userId).orElseThrow();
        AuthenticatedUser principal = new AuthenticatedUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));
        try {
            return action.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        MutableClock notificationTestClock() {
            return new MutableClock(INITIAL_TIME, ZoneOffset.UTC);
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
