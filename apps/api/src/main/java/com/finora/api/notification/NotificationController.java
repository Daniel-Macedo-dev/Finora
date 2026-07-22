package com.finora.api.notification;

import com.finora.api.common.web.PageResponse;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.notification.NotificationDtos.BrowserClaimResponse;
import com.finora.api.notification.NotificationDtos.NotificationResponse;
import com.finora.api.notification.NotificationDtos.SnoozeRequest;
import com.finora.api.notification.NotificationDtos.SyncResponse;
import com.finora.api.notification.NotificationDtos.UnreadCountResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/notifications")
/** Authenticated HTTP surface for synchronization and inbox lifecycle actions. */
public class NotificationController {
    private final NotificationService service;
    private final NotificationSynchronizationService synchronization;
    private final CurrentUserProvider currentUser;

    public NotificationController(NotificationService service,
                                  NotificationSynchronizationService synchronization,
                                  CurrentUserProvider currentUser) {
        this.service = service;
        this.synchronization = synchronization;
        this.currentUser = currentUser;
    }

    @PostMapping("/sync") public SyncResponse sync() {
        return synchronization.synchronize(currentUser.currentUserId());
    }
    @GetMapping public PageResponse<NotificationResponse> list(
            @RequestParam(defaultValue = "ACTIVE") String filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) { return service.list(filter, page, size); }
    @GetMapping("/unread-count") public UnreadCountResponse unreadCount() { return service.unreadCount(); }
    @PostMapping("/{id}/read") public NotificationResponse read(@PathVariable Long id) { return service.read(id); }
    @PostMapping("/{id}/unread") public NotificationResponse unread(@PathVariable Long id) { return service.unread(id); }
    @PostMapping("/read-all") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll() { service.readAll(); }
    @PostMapping("/{id}/dismiss") public NotificationResponse dismiss(@PathVariable Long id) { return service.dismiss(id); }
    @PostMapping("/{id}/restore") public NotificationResponse restore(@PathVariable Long id) { return service.restore(id); }
    @PostMapping("/{id}/snooze") public NotificationResponse snooze(
            @PathVariable Long id, @Valid @RequestBody SnoozeRequest request) {
        return service.snooze(id, request.until());
    }
    @PostMapping("/browser-claims") public List<BrowserClaimResponse> browserClaims() {
        return service.claimBrowser();
    }
}
