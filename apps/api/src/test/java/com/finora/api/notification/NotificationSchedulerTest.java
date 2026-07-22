package com.finora.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.api.identity.User;
import com.finora.api.identity.UserRepository;
import com.finora.api.identity.UserStatus;
import com.finora.api.notification.NotificationDtos.SyncResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

class NotificationSchedulerTest {

    @Test
    void pagesActiveUsersInBoundedBatchesAndIsolatesPerUserFailures() {
        UserRepository users = mock(UserRepository.class);
        NotificationSynchronizationService synchronization =
                mock(NotificationSynchronizationService.class);
        User first = user(11L);
        User failing = user(12L);
        User last = user(13L);
        when(users.findAllByStatus(UserStatus.ACTIVE, PageRequest.of(0, 2)))
                .thenReturn(new PageImpl<>(List.of(first, failing), PageRequest.of(0, 2), 3));
        when(users.findAllByStatus(UserStatus.ACTIVE, PageRequest.of(1, 2)))
                .thenReturn(new PageImpl<>(List.of(last), PageRequest.of(1, 2), 3));
        when(synchronization.synchronize(any())).thenReturn(new SyncResponse(0, 0, 0, 0, 0, 0));
        doThrow(new IllegalStateException("synthetic owner failure"))
                .when(synchronization).synchronize(12L);

        new NotificationScheduler(users, synchronization, 2).synchronizeActiveUsers();

        verify(synchronization).synchronize(11L);
        verify(synchronization).synchronize(12L);
        verify(synchronization).synchronize(13L);
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(users, times(2)).findAllByStatus(
                org.mockito.ArgumentMatchers.eq(UserStatus.ACTIVE), pages.capture());
        assertThat(pages.getAllValues()).extracting(Pageable::getPageNumber)
                .containsExactly(0, 1);
        assertThat(pages.getAllValues()).allSatisfy(page -> assertThat(page.getPageSize()).isEqualTo(2));
    }

    @Test
    void clampsConfiguredBatchSize() {
        UserRepository users = mock(UserRepository.class);
        NotificationSynchronizationService synchronization =
                mock(NotificationSynchronizationService.class);
        when(users.findAllByStatus(UserStatus.ACTIVE, PageRequest.of(0, 500)))
                .thenReturn(new PageImpl<>(List.of()));

        new NotificationScheduler(users, synchronization, 50_000).synchronizeActiveUsers();

        verify(users).findAllByStatus(UserStatus.ACTIVE, PageRequest.of(0, 500));
    }

    private static User user(Long id) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        return user;
    }
}
