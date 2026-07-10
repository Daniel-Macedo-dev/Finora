package com.finora.api.settings;

import com.finora.api.settings.SettingsDtos.SettingsRequest;
import com.finora.api.settings.SettingsDtos.SettingsResponse;
import java.lang.IllegalStateException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SettingsService {

    private final SettingsRepository repository;

    public SettingsService(SettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AppSettings current() {
        return repository.findById(AppSettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "app_settings singleton row missing; check Flyway migrations"));
    }

    @Transactional(readOnly = true)
    public SettingsResponse get() {
        return SettingsResponse.from(current());
    }

    public SettingsResponse update(SettingsRequest request) {
        AppSettings settings = current();
        settings.setMinimumCashBuffer(request.minimumCashBuffer());
        settings.setMaxInstallmentCommitmentRatio(request.maxInstallmentCommitmentRatio());
        settings.setMonthlyOpportunityRate(request.monthlyOpportunityRate());
        settings.setBudgetWarningThreshold(request.budgetWarningThreshold());
        return SettingsResponse.from(settings);
    }
}
