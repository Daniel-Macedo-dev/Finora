package com.finora.api.statementimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.api.common.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The currency-source contract, pinned directly rather than inferred from the
 * flows that consume it.
 *
 * <p>Two decisions live in this enum and both are load-bearing: which sources
 * are an assumption the user must confirm before money is created, and which one
 * may carry a currency the file itself declared. A flow test would fail if
 * either flipped, but only after the fact and only for the paths it happens to
 * exercise; these assert the rule for every value that exists.
 */
class StatementCurrencySourceTest {

    @Test
    void onlyAssumedSourcesRequireAnAcknowledgement() {
        // A declaration Finora read, and an account the user picked, are both
        // statements someone made. The other two are Finora's own guesses.
        assertThat(StatementCurrencySource.ACCOUNT.requiresAccountCurrencyAcknowledgement())
                .isFalse();
        assertThat(StatementCurrencySource.FILE.requiresAccountCurrencyAcknowledgement())
                .isFalse();
        assertThat(StatementCurrencySource.ACCOUNT_ASSUMED
                .requiresAccountCurrencyAcknowledgement()).isTrue();
        assertThat(StatementCurrencySource.LEGACY_UNKNOWN
                .requiresAccountCurrencyAcknowledgement()).isTrue();
    }

    @Test
    void onlyTheFileSourceDeclaresACurrency() {
        assertThat(StatementCurrencySource.FILE.declaresFileCurrency()).isTrue();
        assertThat(StatementCurrencySource.ACCOUNT.declaresFileCurrency()).isFalse();
        assertThat(StatementCurrencySource.ACCOUNT_ASSUMED.declaresFileCurrency()).isFalse();
        assertThat(StatementCurrencySource.LEGACY_UNKNOWN.declaresFileCurrency()).isFalse();
    }

    /* ---------- the same pairing, enforced by the entity ---------- */

    @ParameterizedTest
    @EnumSource(StatementCurrencySource.class)
    void batchRejectsASourceThatDisagreesWithItsDeclaredCurrency(
            StatementCurrencySource source) {
        // Exactly one arrangement is legal per source, and the constructor
        // refuses the other — a FILE batch without the code it supposedly read
        // is meaningless, and a declared code under any other source is a claim
        // that source cannot support. The database says the same thing; this is
        // the half that fails before a round trip.
        if (source.declaresFileCurrency()) {
            assertThatCode(() -> batch(source, CurrencyCode.USD)).doesNotThrowAnyException();
            assertThatThrownBy(() -> batch(source, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(source.name());
        } else {
            assertThatCode(() -> batch(source, null)).doesNotThrowAnyException();
            assertThatThrownBy(() -> batch(source, CurrencyCode.USD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(source.name());
        }
    }

    private static StatementImportBatch batch(StatementCurrencySource source,
                                              CurrencyCode declared) {
        return new StatementImportBatch(1L, 2L, "extrato.ofx", StatementImportFormat.OFX,
                "a".repeat(64), 10L, 2, 1, source, declared,
                StatementImportStatus.PREVIEW_READY);
    }
}
