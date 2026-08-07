import type { CurrencyCode, CurrencyTotals } from '../../lib/money'
import type { PaymentMethod, TransactionType } from '../shared/types'
import type { Transaction } from '../transactions/types'

export interface CategoryShare {
  categoryId: number
  categoryName: string
  amount: number
  /** Authoritative currency of `amount`. Rows are per category and currency. */
  currency: CurrencyCode
  /** Share of that same currency's monthly expenses; null when unmeasurable. */
  percentOfTotal: number | null
}

export interface BudgetOverview {
  totalLimit: number
  totalConsumed: number
  /** Null when any budget's consumption is incomplete. */
  percentUsed: number | null
  budgetCount: number
  warningCount: number
  exceededCount: number
  /** Budgets whose category holds spending in another currency. */
  incompleteCount: number
}

export interface MonthTrendPoint {
  month: string
  income: number
  expense: number
}

/** One homogeneous trend line; a chart axis carries only one denomination. */
export interface MonthTrendSeries {
  currency: CurrencyCode
  points: MonthTrendPoint[]
}

export interface UpcomingCommitment {
  commitmentId: number
  description: string
  amount: number
  /** Currency of `amount`; upcoming items are never summed across currencies. */
  currency: CurrencyCode
  category: { id: number; name: string; type: TransactionType }
  dueDate: string
  paymentMethod: PaymentMethod | null
}

export interface GoalSnapshot {
  id: number
  name: string
  targetAmount: number
  currentAmount: number
  remainingAmount: number
  percentAchieved: number
  targetDate: string | null
  status: 'IN_PROGRESS' | 'COMPLETED' | 'ARCHIVED'
  suggestedMonthlyContribution: number | null
}

export interface CardInvoiceBrief {
  cardId: number
  cardName: string
  invoiceId: number
  referenceMonth: string
  dueDate: string
  status: import('../credit-cards/types').InvoiceStatus
  outstandingAmount: number
  /** The card's currency, which is what the invoice bills in. */
  currency: CurrencyCode
}

export interface RecentCardPurchase {
  id: number
  cardId: number
  cardName: string
  description: string
  purchaseDate: string
  totalAmount: number
  currency: CurrencyCode
  installmentCount: number
}

/** Card debt view — deliberately separate from cash balance. */
export interface CardsOverview {
  cardCount: number
  outstanding: CurrencyTotals
  availableLimit: CurrencyTotals
  monthCardExpense: CurrencyTotals
  overdueCount: number
  nextDueInvoice: CardInvoiceBrief | null
  recentPurchases: RecentCardPurchase[]
}

export interface FutureCashEvent {
  date: string
  description: string
  amount: number
  currency: CurrencyCode
}

/** One currency's projected 30-day balance and its first negative day. */
export interface ProjectedBalance {
  currency: CurrencyCode
  balance: number
  firstNegativeDate: string | null
}

/**
 * Compact 30-day forecast summary served by the backend forecast engine.
 *
 * A running balance only means something in one denomination, so this is a list
 * rather than a scalar: one entry per currency the forecast projects. A
 * base-currency-only user gets exactly one; a mixed user gets one per currency
 * and no consolidated figure.
 */
export interface FutureCashOverview {
  baseCurrency: CurrencyCode
  projections: ProjectedBalance[]
  nextRecurringEvent: FutureCashEvent | null
  nextInvoiceObligation: FutureCashEvent | null
  failedOccurrences: number
}

export interface DashboardData {
  month: string
  baseCurrency: CurrencyCode
  /** Current balances of active accounts, grouped by currency. */
  accountBalances: CurrencyTotals
  income: CurrencyTotals
  expense: CurrencyTotals
  monthResult: CurrencyTotals
  /** Null when income or expense is not complete in the base currency. */
  savingsRate: number | null
  previousMonthExpense: CurrencyTotals
  /** Null when either period is incomplete in the base currency. */
  expenseVariationPercent: number | null
  budgets: BudgetOverview
  topCategories: CategoryShare[]
  /** One homogeneous series per currency present in the window. */
  trend: MonthTrendSeries[]
  upcomingCommitments: UpcomingCommitment[]
  /** Grouped native totals; commitments may settle in different currencies. */
  upcomingCommitmentsTotal: CurrencyTotals
  goals: GoalSnapshot[]
  recentTransactions: Transaction[]
  cards: CardsOverview | null
  futureCash: FutureCashOverview | null
}

export type InsightSeverity = 'POSITIVE' | 'INFO' | 'WARNING' | 'CRITICAL'

export interface Insight {
  type: string
  severity: InsightSeverity
  title: string
  message: string
  amount: number | null
  /**
   * Authoritative denomination of `amount`, null exactly when it is.
   *
   * Not optional and never inferred: a native card insight is stated in the
   * card's currency while an aggregate one is stated in the base currency, so
   * assuming either would mislabel the other.
   */
  currency: CurrencyCode | null
}

/**
 * Which aggregate conclusions the month's currencies allowed.
 *
 * A rule that simply had nothing to say never appears here — this reports only
 * the ones that had real input and would have needed an exchange rate.
 */
export interface AggregateCoverage {
  complete: boolean
  /** Non-base currencies that actually blocked a rule, in catalogue order. */
  missingCurrencies: CurrencyCode[]
  /** Stable rule identifiers; the UI turns them into prose, never shows them. */
  unavailableRules: string[]
}

export interface InsightsData {
  month: string
  baseCurrency: CurrencyCode
  insights: Insight[]
  aggregateCoverage: AggregateCoverage
}
