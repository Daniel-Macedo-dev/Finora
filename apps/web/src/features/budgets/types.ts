import type { TransactionType } from '../shared/types'

export type BudgetStatus = 'HEALTHY' | 'WARNING' | 'EXCEEDED'

export interface Budget {
  id: number
  month: string
  category: { id: number; name: string; type: TransactionType }
  limitAmount: number
  consumedAmount: number
  remainingAmount: number
  percentUsed: number
  status: BudgetStatus
  /** Optimistic version; an offline edit sends the one the user actually saw. */
  version: number
}

export interface BudgetSummary {
  month: string
  totalLimit: number
  totalConsumed: number
  totalRemaining: number
  percentUsed: number
  exceededCount: number
  warningCount: number
  budgets: Budget[]
}

export interface BudgetRequest {
  month: string
  categoryId: number
  limitAmount: number
}
