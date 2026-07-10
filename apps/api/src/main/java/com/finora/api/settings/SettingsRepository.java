package com.finora.api.settings;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingsRepository extends JpaRepository<AppSettings, Long> {
}
