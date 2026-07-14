import type { PaymentMethod, TransactionType } from '../shared/types'
import type { Transaction } from '../transactions/types'

export interface CategoryShare {
  categoryId: number
  categoryName: string
  amount: number
  percentOfTotal: number
}

export interface BudgetOverview {
  totalLimit: number
  totalConsumed: number
  percentUsed: number
  budgetCount: number
  warningCount: number
  exceededCount: number
}

export interface MonthTrendPoint {
  month: string
  income: number
  expense: number
}

export interface UpcomingCommitment {
  commitmentId: number
  description: string
  amount: number
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
}

export interface RecentCardPurchase {
  id: number
  cardId: number
  cardName: string
  description: string
  purchaseDate: string
  totalAmount: number
  installmentCount: number
}

/** Card debt view — deliberately separate from cash balance. */
export interface CardsOverview {
  cardCount: number
  totalOutstanding: number
  totalAvailableLimit: number
  monthCardExpense: number
  overdueCount: number
  nextDueInvoice: CardInvoiceBrief | null
  recentPurchases: RecentCardPurchase[]
}

export interface FutureCashEvent {
  date: string
  description: string
  amount: number
}

/** Compact 30-day forecast summary served by the backend forecast engine. */
export interface FutureCashOverview {
  projectedBalance30d: number
  nextRecurringEvent: FutureCashEvent | null
  nextInvoiceObligation: FutureCashEvent | null
  firstNegativeDate: string | null
  failedOccurrences: number
}

export interface DashboardData {
  month: string
  totalBalance: number
  income: number
  expense: number
  monthResult: number
  savingsRate: number | null
  previousMonthExpense: number
  expenseVariationPercent: number | null
  budgets: BudgetOverview
  topCategories: CategoryShare[]
  trend: MonthTrendPoint[]
  upcomingCommitments: UpcomingCommitment[]
  upcomingCommitmentsTotal: number
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
}

export interface InsightsData {
  month: string
  insights: Insight[]
}
