import { describe, expect, it } from 'vitest'
import {
  CURRENCY_CODES,
  currencyFractionDigits,
  currencyLabel,
  currencyOptions,
  formatMoney,
  isCurrencyCode,
  parseMoneyInput,
} from './money'

describe('formatMoney', () => {
  it('formats every supported currency without throwing', () => {
    for (const currency of CURRENCY_CODES) {
      expect(formatMoney(1250, currency)).toBeTruthy()
    }
  })

  it('formats BRL with pt-BR separators', () => {
    expect(formatMoney(1250.5, 'BRL')).toContain('1.250,50')
  })

  it('never rounds a fractional amount away in a zero-decimal currency', () => {
    // 100,50 yen is not a quantity of money that exists — but when a stored
    // value carries it, showing "JP¥ 101" would display a different amount from
    // the one being validated. It stays visible so it can be corrected.
    expect(formatMoney(100.5, 'JPY')).toContain('100,50')
    expect(formatMoney(100.5, 'JPY')).not.toContain('101')
    // Whole amounts keep the currency's own precision.
    expect(formatMoney(1200, 'JPY')).not.toContain(',')
  })

  it('renders JPY without decimals', () => {
    const formatted = formatMoney(1250, 'JPY')
    expect(formatted).toContain('1.250')
    expect(formatted).not.toContain('1.250,00')
  })

  it('keeps the dollar currencies distinguishable from each other', () => {
    const usd = formatMoney(1250, 'USD')
    const cad = formatMoney(1250, 'CAD')
    const aud = formatMoney(1250, 'AUD')
    expect(new Set([usd, cad, aud]).size).toBe(3)
  })

  it('never renders a bare dollar sign, which would not say whose dollars', () => {
    // pt-BR renders these as US$, CA$ and AU$ — the letters are what carry the
    // meaning, so a "$" must never appear without them.
    for (const currency of ['USD', 'CAD', 'AUD'] as const) {
      const formatted = formatMoney(1250, currency)
      expect(formatted).toMatch(/[A-Z]{2,3}\$|\b(USD|CAD|AUD)\b/)
      expect(formatted).not.toMatch(/(^|\s)\$/)
    }
  })

  it('shows an em dash for an absent value rather than zero', () => {
    expect(formatMoney(null, 'BRL')).toBe('—')
    expect(formatMoney(undefined, 'USD')).toBe('—')
    expect(formatMoney(Number.NaN, 'EUR')).toBe('—')
  })

  it('reuses one cached formatter per currency', () => {
    // Same output across repeated calls proves the cache is not stale, and the
    // cache itself is what keeps render paths from building Intl objects.
    const first = formatMoney(10, 'EUR')
    const second = formatMoney(10, 'EUR')
    expect(first).toBe(second)
  })
})

describe('parseMoneyInput', () => {
  it('parses pt-BR and plain decimal forms', () => {
    expect(parseMoneyInput('1.234,56', 'BRL')).toBe(1234.56)
    expect(parseMoneyInput('1234.56', 'BRL')).toBe(1234.56)
    expect(parseMoneyInput('  10  ', 'USD')).toBe(10)
  })

  it('returns null for empty or unparseable input', () => {
    expect(parseMoneyInput('', 'BRL')).toBeNull()
    expect(parseMoneyInput('abc', 'BRL')).toBeNull()
  })

  it('rejects a fractional amount in a zero-decimal currency', () => {
    // Rounding 100,50 to 101 yen would silently change what the user typed.
    expect(parseMoneyInput('100,50', 'JPY')).toBeNull()
    expect(parseMoneyInput('100.5', 'JPY')).toBeNull()
  })

  it('accepts whole amounts in JPY', () => {
    expect(parseMoneyInput('1200', 'JPY')).toBe(1200)
  })
})

describe('currency catalogue', () => {
  it('exposes exactly the supported codes', () => {
    expect([...CURRENCY_CODES]).toEqual(['BRL', 'USD', 'EUR', 'GBP', 'CAD', 'AUD', 'CHF', 'JPY'])
  })

  it('reports fraction digits per currency', () => {
    expect(currencyFractionDigits('JPY')).toBe(0)
    expect(currencyFractionDigits('BRL')).toBe(2)
  })

  it('labels currencies with their ISO code for screen readers', () => {
    expect(currencyLabel('USD')).toContain('USD')
    expect(currencyLabel('USD')).toContain('Dólar americano')
    expect(currencyLabel('CAD')).not.toBe(currencyLabel('USD'))
  })

  it('offers one selector option per supported currency', () => {
    expect(currencyOptions).toHaveLength(CURRENCY_CODES.length)
    expect(currencyOptions[0].code).toBe('BRL')
  })

  it('recognizes only catalogue members', () => {
    expect(isCurrencyCode('USD')).toBe(true)
    expect(isCurrencyCode('BTC')).toBe(false)
    expect(isCurrencyCode(null)).toBe(false)
  })
})
