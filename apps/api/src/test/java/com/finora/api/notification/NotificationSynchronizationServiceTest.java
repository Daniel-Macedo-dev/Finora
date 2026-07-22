package com.finora.api.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.api.forecast.DueEventService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationSynchronizationServiceTest {
    @Mock NotificationRepository notifications;
    @Mock NotificationPreferencesService preferencesService;
    @Mock DueEventService dueEvents;
    @Mock Clock clock;
    @Mock NotificationPreferences preferences;
    @InjectMocks NotificationSynchronizationService synchronization;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(Instant.parse("2026-07-21T12:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @Test
    void synchronize_whenSourceGenerationFails_doesNotResolveExistingNotifications() {
        when(preferencesService.forUser(41L)).thenReturn(preferences);
        when(preferences.isEnabled()).thenReturn(true);
        when(preferences.getUpcomingLeadDays()).thenReturn(7);
        when(dueEvents.eventsForUser(
                41L,
                java.time.LocalDate.of(2026, 6, 21),
                java.time.LocalDate.of(2026, 7, 28),
                7)).thenThrow(new IllegalStateException("synthetic source failure"));

        assertThatThrownBy(() -> synchronization.synchronize(41L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic source failure");

        verify(notifications).lockSynchronization(41L);
        verify(notifications, never()).lockAllActiveByUserId(41L);
    }
}
