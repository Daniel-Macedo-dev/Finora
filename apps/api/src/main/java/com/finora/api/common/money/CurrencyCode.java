package com.finora.api.common.money;

import com.finora.api.common.error.BusinessRuleException;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Closed catalogue of the currencies Finora supports.
 *
 * <p>Currency is a domain concept here, not an arbitrary three-letter string:
 * every monetary root stores one of these codes and the database mirrors the
 * same catalogue as a CHECK constraint. Codes outside the catalogue are
 * rejected at every trust boundary rather than being stored and discovered
 * later by an aggregate that cannot format them.
 *
 * <p>The catalogue is deliberately limited to currencies with at most two
 * fraction digits. Cryptocurrencies and commodities are out of scope.
 */
public enum CurrencyCode {

    BRL(2, "Real brasileiro", "R$"),
    USD(2, "Dólar americano", "US$"),
    EUR(2, "Euro", "€"),
    GBP(2, "Libra esterlina", "£"),
    CAD(2, "Dólar canadense", "CA$"),
    AUD(2, "Dólar australiano", "AU$"),
    CHF(2, "Franco suíço", "CHF"),
    JPY(0, "Iene japonês", "JP¥");

    private static final Map<String, CurrencyCode> BY_CODE = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

    private final int fractionDigits;
    private final String displayName;
    private final String symbol;

    CurrencyCode(int fractionDigits, String displayName, String symbol) {
        this.fractionDigits = fractionDigits;
        this.displayName = displayName;
        this.symbol = symbol;
    }

    /**
     * Number of fraction digits the currency accepts. JPY is a zero-decimal
     * currency: an amount like 100.50 JPY does not exist and is rejected.
     */
    public int getFractionDigits() {
        return fractionDigits;
    }

    /** pt-BR name used in user-facing messages and currency selectors. */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Unambiguous symbol. Deliberately not a bare {@code $}, because USD, CAD
     * and AUD would be indistinguishable to a reader.
     */
    public String getSymbol() {
        return symbol;
    }

    /**
     * Parses a client-supplied code, rejecting anything outside the catalogue.
     *
     * @throws BusinessRuleException with code {@code CURRENCY_UNSUPPORTED}
     */
    public static CurrencyCode parse(String raw) {
        CurrencyCode parsed = raw == null ? null : BY_CODE.get(raw.trim().toUpperCase(Locale.ROOT));
        if (parsed == null) {
            throw new BusinessRuleException(
                    "CURRENCY_UNSUPPORTED",
                    "Moeda não suportada: %s. Moedas aceitas: %s."
                            .formatted(raw, supportedCodes()));
        }
        return parsed;
    }

    /** Parses a code, or returns {@code null} when none was supplied. */
    public static CurrencyCode parseOrNull(String raw) {
        return raw == null || raw.isBlank() ? null : parse(raw);
    }

    /** Comma-separated catalogue, used in error messages and documentation. */
    public static String supportedCodes() {
        return Stream.of(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
