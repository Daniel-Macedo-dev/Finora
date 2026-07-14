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

export interface Forecast {
  from: string
  to: string
  accountId: number | null
  openingBalance: number
  projectedIncome: number
  projectedAccountExpenses: number
  projectedInvoiceOutflows: number
  closingBalance: number
  lowestBalance: number
  lowestBalanceDate: string
  firstNegativeDate: string | null
  unassignedInflows: number
  unassignedOutflows: number
  events: ForecastEvent[]
  months: ForecastMonth[]
}
