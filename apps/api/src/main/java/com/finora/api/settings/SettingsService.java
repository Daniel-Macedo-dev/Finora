package com.finora.api.settings;

import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.money.CurrencyCode;
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
    private final BaseCurrencyGuard baseCurrencyGuard;

    public SettingsService(SettingsRepository repository, CurrentUserProvider currentUser,
            BaseCurrencyGuard baseCurrencyGuard) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.baseCurrencyGuard = baseCurrencyGuard;
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
        AppSettings settings = current();
        return SettingsResponse.from(settings, isBaseCurrencyChangeable(settings));
    }

    public SettingsResponse update(SettingsRequest request) {
        AppSettings settings = current();
        applyBaseCurrency(settings, request.baseCurrency());
        settings.setMinimumCashBuffer(request.minimumCashBuffer());
        settings.setMaxInstallmentCommitmentRatio(request.maxInstallmentCommitmentRatio());
        settings.setMonthlyOpportunityRate(request.monthlyOpportunityRate());
        settings.setBudgetWarningThreshold(request.budgetWarningThreshold());
        return SettingsResponse.from(settings, isBaseCurrencyChangeable(settings));
    }

    /**
     * Applies a requested base currency.
     *
     * <p>Omitting the field keeps the current currency, so a client that
     * predates multi-currency cannot reset anyone to BRL. Re-sending the
     * current value is a no-op rather than a blocked change: only an actual
     * switch has to prove the ledger is empty.
     */
    private void applyBaseCurrency(AppSettings settings, String requested) {
        CurrencyCode target = CurrencyCode.parseOrNull(requested);
        if (target == null || target == settings.getBaseCurrency()) {
            return;
        }
        baseCurrencyGuard.assertChangeAllowed(settings.getUserId(), settings);
        settings.setBaseCurrency(target);
    }

    private boolean isBaseCurrencyChangeable(AppSettings settings) {
        try {
            baseCurrencyGuard.assertChangeAllowed(settings.getUserId(), settings);
            return true;
        } catch (BusinessRuleException blocked) {
            return false;
        }
    }
}
