package com.finora.api.notification;

import com.finora.api.notification.NotificationDtos.PreferencesRequest;
import com.finora.api.notification.NotificationDtos.PreferencesResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notification-preferences")
public class NotificationPreferencesController {
    private final NotificationPreferencesService service;
    public NotificationPreferencesController(NotificationPreferencesService service) { this.service = service; }
    @GetMapping public PreferencesResponse get() { return service.get(); }
    @PutMapping public PreferencesResponse update(@Valid @RequestBody PreferencesRequest request) {
        return service.update(request);
    }
}
