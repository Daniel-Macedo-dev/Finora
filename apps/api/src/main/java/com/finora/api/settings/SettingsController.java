package com.finora.api.settings;

import com.finora.api.settings.SettingsDtos.SettingsRequest;
import com.finora.api.settings.SettingsDtos.SettingsResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping
    public SettingsResponse get() {
        return service.get();
    }

    @PutMapping
    public SettingsResponse update(@Valid @RequestBody SettingsRequest request) {
        return service.update(request);
    }
}
