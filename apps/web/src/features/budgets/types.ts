import type { CurrencyCode, CurrencyTotals } from '../../lib/money'
import type { TransactionType } from '../shared/types'

/**
 * `INCOMPLETE` is not a fourth severity — it is the absence of an answer.
 *
 * A category holding spending in a currency the budget is not denominated in
 * cannot be scored: treating that spending as zero would let a genuinely blown
 * budget report as HEALTHY.
 */
export type BudgetStatus = 'HEALTHY' | 'WARNING' | 'EXCEEDED' | 'INCOMPLETE'

export interface Budget {
  id: number
  month: string
  category: { id: number; name: string; type: TransactionType }
  limitAmount: number
  /** Base currency; `limitAmount` and `consumedAmount` are denominated in it. */
  currency: CurrencyCode
  /** A floor rather than a total when `status` is INCOMPLETE. */
  consumedAmount: number
  /** Every expense in this category and month, grouped by its own currency. */
  consumedTotals: CurrencyTotals
  /** Null when consumption is incomplete. */
  remainingAmount: number | null
  /** Null when consumption is incomplete. */
  percentUsed: number | null
  status: BudgetStatus
  /** Optimistic version; an offline edit sends the one the user actually saw. */
  version: number
}

export interface BudgetSummary {
  month: string
  baseCurrency: CurrencyCode
  totalLimit: number
  totalConsumed: number
  consumedTotals: CurrencyTotals
  /** Null when any budget's consumption is incomplete. */
  totalRemaining: number | null
  /** Null when any budget's consumption is incomplete. */
  percentUsed: number | null
  exceededCount: number
  warningCount: number
  incompleteCount: number
  budgets: Budget[]
}

export interface BudgetRequest {
  month: string
  categoryId: number
  limitAmount: number
}
