import { describe, expect, it } from 'vitest'
import { formatBRL, formatDate, formatMonth, formatPercent, parseMoneyInput } from './format'

describe('formatBRL', () => {
  it('formats using pt-BR currency conventions', () => {
    // Intl uses a non-breaking space between symbol and value.
    expect(formatBRL(1234.56)).toBe('R$ 1.234,56')
    expect(formatBRL(0)).toBe('R$ 0,00')
    expect(formatBRL(-99.9)).toBe('-R$ 99,90')
  })

  it('renders a dash for missing values instead of a misleading zero', () => {
    expect(formatBRL(null)).toBe('—')
    expect(formatBRL(undefined)).toBe('—')
    expect(formatBRL(Number.NaN)).toBe('—')
  })
})

describe('formatDate', () => {
  it('formats ISO dates as dd/mm/yyyy', () => {
    expect(formatDate('2026-07-05')).toBe('05/07/2026')
  })

  it('is not shifted by the local timezone', () => {
    expect(formatDate('2026-01-01')).toBe('01/01/2026')
    expect(formatDate('2026-12-31')).toBe('31/12/2026')
  })

  it('handles missing or invalid values', () => {
    expect(formatDate(null)).toBe('—')
    expect(formatDate('not-a-date')).toBe('—')
  })
})

describe('formatMonth', () => {
  it('formats month keys with the full month name', () => {
    expect(formatMonth('2026-07')).toBe('julho de 2026')
  })

  it('rejects malformed keys', () => {
    expect(formatMonth('2026-7')).toBe('—')
    expect(formatMonth(null)).toBe('—')
  })
})

describe('formatPercent', () => {
  it('formats percentages with pt-BR separators', () => {
    expect(formatPercent(87.5)).toBe('87,5%')
    expect(formatPercent(100)).toBe('100%')
  })

  it('handles missing values', () => {
    expect(formatPercent(null)).toBe('—')
  })
})

describe('parseMoneyInput', () => {
  it('parses pt-BR formatted values', () => {
    expect(parseMoneyInput('1.234,56')).toBe(1234.56)
    expect(parseMoneyInput('89,90')).toBe(89.9)
  })

  it('parses plain decimal values', () => {
    expect(parseMoneyInput('1234.56')).toBe(1234.56)
    expect(parseMoneyInput('100')).toBe(100)
  })

  it('returns null for invalid input', () => {
    expect(parseMoneyInput('')).toBeNull()
    expect(parseMoneyInput('abc')).toBeNull()
  })
})
