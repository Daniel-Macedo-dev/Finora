/**
 * Centralized pt-BR formatting. Components never build currency or date
 * strings by hand.
 */

const brl = new Intl.NumberFormat('pt-BR', {
  style: 'currency',
  currency: 'BRL',
})

const decimal = new Intl.NumberFormat('pt-BR', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 1,
})

const dateFormat = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeZone: 'UTC' })

const monthFormat = new Intl.DateTimeFormat('pt-BR', {
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
})

/** Formats a numeric amount (API sends JSON numbers) as BRL currency. */
export function formatBRL(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '—'
  }
  return brl.format(value)
}

/** Formats an ISO date string (yyyy-MM-dd) as dd/mm/yyyy. */
export function formatDate(isoDate: string | null | undefined): string {
  if (!isoDate) {
    return '—'
  }
  const parsed = new Date(`${isoDate}T00:00:00Z`)
  if (Number.isNaN(parsed.getTime())) {
    return '—'
  }
  return dateFormat.format(parsed)
}

/** Formats a month key (yyyy-MM) as "julho de 2026". */
export function formatMonth(month: string | null | undefined): string {
  if (!month || !/^\d{4}-\d{2}$/.test(month)) {
    return '—'
  }
  return monthFormat.format(new Date(`${month}-01T00:00:00Z`))
}

/** Formats a percentage value (already 0-100 scaled) with pt-BR separators. */
export function formatPercent(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '—'
  }
  return `${decimal.format(value)}%`
}

/** Parses a pt-BR money input ("1.234,56" or "1234.56") into a number. */
export function parseMoneyInput(raw: string): number | null {
  const trimmed = raw.trim()
  if (!trimmed) {
    return null
  }
  const normalized = trimmed.includes(',')
    ? trimmed.replace(/\./g, '').replace(',', '.')
    : trimmed
  const value = Number(normalized)
  return Number.isFinite(value) ? value : null
}
