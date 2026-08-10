package com.finora.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.api.common.money.CurrencyCode;
import com.finora.api.forecast.DueEventDtos.DueEventSeverity;
import com.finora.api.forecast.DueEventDtos.DueEventType;
import com.finora.api.notification.Notification.RefreshOutcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The row's own rules about denomination and reconciliation.
 *
 * <p>Currency is history: a notification says what a sum was, in the money it
 * was in. Nothing here may rewrite that, and nothing may leave the row asserting
 * a figure it can no longer name.
 *
 * <p>Every service that can reach these rules today already refuses to change a
 * source's currency, so the mismatch path is defence in depth rather than a
 * reachable user journey. It is pinned anyway: an unreachable rule that is wrong
 * becomes a reachable rule that is wrong the moment a new source appears.
 */
class NotificationTest {

    private static final Instant T0 = Instant.parse("2026-07-21T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 21);

    private static Notification brlOverdue() {
        return new Notification(7L, "COMMITMENT:2:2026-07-21", "RECURRING_OVERDUE:COMMITMENT:2",
                DueEventType.RECURRING_OVERDUE, DueEventSeverity.CRITICAL, DAY,
                "Assinatura vencida", new BigDecimal("120.00"), CurrencyCode.BRL,
                "COMMITMENT", 2L, "/commitments", T0);
    }

    private RefreshOutcome refresh(Notification notification, BigDecimal amount,
                                   CurrencyCode currency, Instant now) {
        return notification.refresh("RECURRING_OVERDUE:COMMITMENT:2",
                DueEventType.RECURRING_OVERDUE, DueEventSeverity.CRITICAL, DAY,
                "Assinatura vencida", amount, currency, "COMMITMENT", 2L, "/commitments", now);
    }

    @Test
    void aNotificationCannotBeCreatedWithoutNamingItsCurrency() {
        assertThatThrownBy(() -> new Notification(7L, "COMMITMENT:2:2026-07-21", "evt",
                DueEventType.RECURRING_OVERDUE, DueEventSeverity.CRITICAL, DAY, "Título",
                new BigDecimal("10.00"), null, "COMMITMENT", 2L, "/commitments", T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void anUnchangedSourceLeavesTheRowAndItsLifecycleExactlyWhereTheyWere() {
        Notification notification = brlOverdue();
        notification.markRead();

        assertThat(refresh(notification, new BigDecimal("120.00"), CurrencyCode.BRL, T0))
                .isEqualTo(RefreshOutcome.UNCHANGED);
        assertThat(notification.getRevision()).isEqualTo(1);
        assertThat(notification.isUnread()).isFalse();
        assertThat(notification.getAmount()).isEqualByComparingTo("120.00");
        assertThat(notification.getCurrency()).isEqualTo(CurrencyCode.BRL);
    }

    @Test
    void aNewAmountInTheSameCurrencyIsPresentationAndKeepsEveryLifecycleFlag() {
        Notification notification = brlOverdue();
        notification.markRead();

        assertThat(refresh(notification, new BigDecimal("130.00"), CurrencyCode.BRL, T0))
                .isEqualTo(RefreshOutcome.UPDATED);
        assertThat(notification.getRevision()).isEqualTo(1);
        assertThat(notification.isUnread()).as("uma correção de valor não re-notifica").isFalse();
        assertThat(notification.getAmount()).isEqualByComparingTo("130.00");
    }

    @Test
    void aDeEscalatingSourceIsReportedAsChangedRatherThanAsHavingRepeatedItself() {
        // A failed occurrence that goes back to merely overdue lowers both the
        // type's lifecycle rank and nothing else, so no revision is due — but
        // the row really did change, and calling that UNCHANGED would put a
        // wrong number in the synchronization counters.
        Notification notification = new Notification(7L, "COMMITMENT:2:2026-07-21", "evt",
                DueEventType.RECURRING_FAILED, DueEventSeverity.CRITICAL, DAY, "Falhou",
                new BigDecimal("120.00"), CurrencyCode.BRL, "COMMITMENT", 2L, "/commitments", T0);

        RefreshOutcome outcome = notification.refresh("evt", DueEventType.RECURRING_OVERDUE,
                DueEventSeverity.CRITICAL, DAY, "Falhou", new BigDecimal("120.00"),
                CurrencyCode.BRL, "COMMITMENT", 2L, "/commitments", T0);

        assertThat(outcome).isEqualTo(RefreshOutcome.UPDATED);
        assertThat(notification.getType()).isEqualTo(DueEventType.RECURRING_OVERDUE);
        assertThat(notification.getRevision()).as("descida de tipo não escala").isEqualTo(1);
    }

    @Test
    void aSourceInAnotherCurrencyWithdrawsTheAmountOnceAndThenGoesQuiet() {
        Notification notification = brlOverdue();
        notification.markRead();
        notification.claimBrowserDelivery();
        notification.snooze(T0.plusSeconds(3600));

        // The transition: the row cannot state dollars, so it states nothing,
        // and the owner is told by a fresh revision rather than silently.
        assertThat(refresh(notification, new BigDecimal("80.00"), CurrencyCode.USD, T0))
                .isEqualTo(RefreshOutcome.REVISED);
        assertThat(notification.getCurrency())
                .as("a denominação é histórica e nunca é reescrita").isEqualTo(CurrencyCode.BRL);
        assertThat(notification.getAmount()).isNull();
        assertThat(notification.getRevision()).isEqualTo(2);
        assertThat(notification.isUnread()).isTrue();
        assertThat(notification.isSnoozed(T0)).isFalse();

        // And it settles. The stored currency can never move, so the mismatch
        // never clears; without a terminal state this would bump the revision
        // and re-notify on every synchronization, forever.
        for (int sync = 0; sync < 3; sync++) {
            assertThat(refresh(notification, new BigDecimal("80.00"), CurrencyCode.USD, T0))
                    .isEqualTo(RefreshOutcome.UNCHANGED);
        }
        assertThat(notification.getRevision()).isEqualTo(2);
        assertThat(notification.getAmount()).isNull();
    }

    @Test
    void aSourceThatComesBackToTheStoredCurrencyRegainsItsAmountWithoutReNotifying() {
        Notification notification = brlOverdue();
        refresh(notification, new BigDecimal("80.00"), CurrencyCode.USD, T0);
        notification.markRead();

        assertThat(refresh(notification, new BigDecimal("120.00"), CurrencyCode.BRL, T0))
                .isEqualTo(RefreshOutcome.UPDATED);
        assertThat(notification.getAmount()).isEqualByComparingTo("120.00");
        assertThat(notification.getRevision()).isEqualTo(2);
        assertThat(notification.isUnread()).isFalse();
    }

    @Test
    void escalationStillWinsAndStillClearsSnoozeRegardlessOfCurrency() {
        Notification notification = new Notification(7L, "COMMITMENT:2:2026-07-21", "evt",
                DueEventType.RECURRING_DUE_SOON, DueEventSeverity.INFO, DAY, "Vence em breve",
                new BigDecimal("120.00"), CurrencyCode.USD, "COMMITMENT", 2L, "/commitments", T0);
        notification.markRead();
        notification.snooze(T0.plusSeconds(3600));

        RefreshOutcome outcome = notification.refresh("evt", DueEventType.RECURRING_OVERDUE,
                DueEventSeverity.CRITICAL, DAY, "Venceu", new BigDecimal("120.00"),
                CurrencyCode.USD, "COMMITMENT", 2L, "/commitments", T0);

        assertThat(outcome).isEqualTo(RefreshOutcome.REVISED);
        assertThat(notification.getRevision()).isEqualTo(2);
        assertThat(notification.isUnread()).isTrue();
        assertThat(notification.isSnoozed(T0)).isFalse();
        assertThat(notification.getCurrency()).isEqualTo(CurrencyCode.USD);
        assertThat(notification.getAmount()).isEqualByComparingTo("120.00");
    }

    @Test
    void aResolvedRowThatReappearsIsReactivatedWithItsDenominationIntact() {
        Notification notification = brlOverdue();
        notification.resolve(T0);

        assertThat(refresh(notification, new BigDecimal("120.00"), CurrencyCode.BRL, T0))
                .isEqualTo(RefreshOutcome.REVISED);
        assertThat(notification.getResolvedAt()).isNull();
        assertThat(notification.getRevision()).isEqualTo(2);
        assertThat(notification.getCurrency()).isEqualTo(CurrencyCode.BRL);
    }
}
