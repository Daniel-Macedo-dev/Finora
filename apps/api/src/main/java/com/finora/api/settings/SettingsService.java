package com.finora.api.settings;

import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.settings.SettingsDtos.SettingsRequest;
import com.finora.api.settings.SettingsDtos.SettingsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SettingsService {

    private final SettingsRepository repository;
    private final CurrentUserProvider currentUser;

    public SettingsService(SettingsRepository repository, CurrentUserProvider currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    /** The authenticated user's settings row (created at registration). */
    @Transactional(readOnly = true)
    public AppSettings current() {
        return forUser(currentUser.currentUserId());
    }

    /** Settings for an explicit owner — used by services that already resolved identity. */
    @Transactional(readOnly = true)
    public AppSettings forUser(Long userId) {
        return repository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "settings row missing for user " + userId + "; registration must create it"));
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
