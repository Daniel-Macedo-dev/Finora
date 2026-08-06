import type { CurrencyCode } from '../../lib/money'

export type ForecastSource =
  | 'ACTUAL_TRANSACTION'
  | 'RECURRING_ACCOUNT_OCCURRENCE'
  | 'CARD_INVOICE'
  | 'PROJECTED_RECURRING_CARD_PURCHASE'

export const FORECAST_SOURCE_LABELS: Record<ForecastSource, string> = {
  ACTUAL_TRANSACTION: 'Lançamento registrado',
  RECURRING_ACCOUNT_OCCURRENCE: 'Recorrente projetado',
  CARD_INVOICE: 'Fatura de cartão',
  PROJECTED_RECURRING_CARD_PURCHASE: 'Compra recorrente projetada',
}

export interface ForecastEvent {
  date: string
  description: string
  amount: number
  /** Authoritative currency, derived from the source resource. */
  currency: CurrencyCode
  source: ForecastSource
  accountId: number | null
  accountName: string | null
  unassigned: boolean
  commitmentId: number | null
  transactionId: number | null
  invoiceId: number | null
  creditCardId: number | null
  balanceAfter: number | null
}

export interface ForecastMonth {
  month: string
  inflows: number
  outflows: number
  net: number
  endBalance: number
}

/**
 * One currency's own running forecast.
 *
 * A balance only means something in one denomination, so each currency gets an
 * independent opening balance, series and set of conclusions — every one of
 * them a real, addable number.
 */
export interface ForecastCurrencySummary {
  currency: CurrencyCode
  openingBalance: number
  income: number
  accountExpenses: number
  invoiceOutflows: number
  closingBalance: number
  lowestBalance: number
  lowestBalanceDate: string
  firstNegativeDate: string | null
  unassignedInflows: number
  unassignedOutflows: number
  /** Events that actually moved this balance; unassigned ones are reported apart. */
  assignedEventCount: number
  months: ForecastMonth[]
}

/**
 * The forecast, partitioned by currency.
 *
 * `byCurrency` is always the authoritative answer. The scalar fields beside it
 * are the pre-multi-currency shape, populated *only* when the forecast is
 * homogeneous, with `currency` naming the denomination they are in. A mixed
 * forecast leaves every one of them null rather than sending a number somebody
 * would act on. An account-filtered forecast is homogeneous by construction.
 */
export interface Forecast {
  from: string
  to: string
  accountId: number | null
  baseCurrency: CurrencyCode
  /** The single denomination of the scalars below; null when mixed. */
  currency: CurrencyCode | null
  openingBalance: number | null
  projectedIncome: number | null
  projectedAccountExpenses: number | null
  projectedInvoiceOutflows: number | null
  closingBalance: number | null
  lowestBalance: number | null
  lowestBalanceDate: string | null
  firstNegativeDate: string | null
  unassignedInflows: number | null
  unassignedOutflows: number | null
  byCurrency: ForecastCurrencySummary[]
  events: ForecastEvent[]
  months: ForecastMonth[]
}
