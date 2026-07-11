package com.finora.api.settings;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingsRepository extends JpaRepository<AppSettings, Long> {

    Optional<AppSettings> findByUserId(Long userId);
}
