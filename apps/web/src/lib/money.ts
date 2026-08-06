/**
 * Currency-aware money presentation.
 *
 * <p>Finora used to format every amount as BRL because every amount was BRL.
 * Now a value only means something together with its currency: the same 1.250
 * is a very different sum in reais, dollars or yen. Every helper here therefore
 * takes the currency explicitly — there is no ambient default to fall back on.
 */

/** The closed catalogue, mirroring the backend CurrencyCode enum. */
export const CURRENCY_CODES = [
  'BRL',
  'USD',
  'EUR',
  'GBP',
  'CAD',
  'AUD',
  'CHF',
  'JPY',
] as const

export type CurrencyCode = (typeof CURRENCY_CODES)[number]

interface CurrencyMeta {
  /** pt-BR name, used in selectors and explanations. */
  readonly label: string
  /**
   * Fraction digits the currency actually has. JPY has none: 100,50 yen is not
   * a quantity of money that exists.
   */
  readonly fractionDigits: 0 | 2
}

const CURRENCIES: Record<CurrencyCode, CurrencyMeta> = {
  BRL: { label: 'Real brasileiro', fractionDigits: 2 },
  USD: { label: 'Dólar americano', fractionDigits: 2 },
  EUR: { label: 'Euro', fractionDigits: 2 },
  GBP: { label: 'Libra esterlina', fractionDigits: 2 },
  CAD: { label: 'Dólar canadense', fractionDigits: 2 },
  AUD: { label: 'Dólar australiano', fractionDigits: 2 },
  CHF: { label: 'Franco suíço', fractionDigits: 2 },
  JPY: { label: 'Iene japonês', fractionDigits: 0 },
}

export function isCurrencyCode(value: unknown): value is CurrencyCode {
  return typeof value === 'string' && value in CURRENCIES
}

/** Options for a currency selector, in catalogue order. */
export const currencyOptions: ReadonlyArray<{ code: CurrencyCode; label: string }> =
  CURRENCY_CODES.map((code) => ({ code, label: `${code} — ${CURRENCIES[code].label}` }))

export function currencyFractionDigits(currency: CurrencyCode): number {
  return CURRENCIES[currency].fractionDigits
}

/** "USD — Dólar americano", for labels and accessible descriptions. */
export function currencyLabel(currency: CurrencyCode): string {
  return `${currency} — ${CURRENCIES[currency].label}`
}

/**
 * Formatters are expensive to build and are created per render otherwise, so
 * they are cached by currency. The catalogue is closed, so this map is bounded.
 */
const formatterCache = new Map<string, Intl.NumberFormat>()

function formatterFor(currency: CurrencyCode): Intl.NumberFormat {
  const key = `pt-BR:${currency}`
  let formatter = formatterCache.get(key)
  if (!formatter) {
    const digits = currencyFractionDigits(currency)
    formatter = new Intl.NumberFormat('pt-BR', {
      style: 'currency',
      currency,
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    })
    formatterCache.set(key, formatter)
  }
  return formatter
}

/**
 * Formats an amount in its own currency.
 *
 * <p>pt-BR renders USD, CAD and AUD as "US$", "CA$" and "A$", which stay
 * distinguishable. Any locale output that would collapse to a bare "$" is
 * prefixed with the ISO code instead, because "$ 1.250,00" does not say whose
 * dollars those are.
 */
export function formatMoney(
  value: number | null | undefined,
  currency: CurrencyCode,
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '—'
  }
  const formatted = formatterFor(currency).format(value)
  return /(?:^|[\s ])\$/.test(formatted) ? `${currency} ${formatted}` : formatted
}

/**
 * Parses a pt-BR money input ("1.234,56" or "1234.56") for a given currency.
 *
 * <p>Returns null for anything unparseable, and for a fractional amount in a
 * zero-decimal currency: silently rounding 100,50 JPY to 101 would change what
 * the user typed.
 */
export function parseMoneyInput(
  raw: string,
  currency: CurrencyCode,
): number | null {
  const trimmed = raw.trim()
  if (!trimmed) {
    return null
  }
  const normalized = trimmed.includes(',')
    ? trimmed.replace(/\./g, '').replace(',', '.')
    : trimmed
  const value = Number(normalized)
  if (!Number.isFinite(value)) {
    return null
  }
  if (currencyFractionDigits(currency) === 0 && !Number.isInteger(value)) {
    return null
  }
  return value
}

/** Grouped native totals, as returned by the backend CurrencyTotals record. */
export interface CurrencyTotals {
  baseCurrency: CurrencyCode
  /** False when foreign amounts are present; `total` is then absent. */
  complete: boolean
  total: number | null
  byCurrency: Array<{ amount: number; currency: CurrencyCode }>
  /** Currencies a future exchange-rate stage would have to convert. */
  unconvertedCurrencies: CurrencyCode[]
}
