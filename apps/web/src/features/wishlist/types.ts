import type { CurrencyCode } from '../../lib/money'
import type { TransactionType } from '../shared/types'

export type WishlistStatus =
  | 'PLANNING'
  | 'MONITORING'
  | 'READY_TO_BUY'
  | 'PURCHASED'
  | 'ARCHIVED'

export type WishlistPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'ESSENTIAL'

export type PurchaseOptionKind = 'CASH' | 'INSTALLMENT'

export interface WishlistCategoryRef {
  id: number
  name: string
  type: TransactionType
}

export interface WishlistItemSummary {
  id: number
  name: string
  notes: string | null
  category: WishlistCategoryRef | null
  referencePrice: number | null
  targetPrice: number | null
  priority: WishlistPriority
  desiredDate: string | null
  status: WishlistStatus
  optionCount: number
  bestNominalCost: number | null
  priceObservationCount: number
  latestObservedPrice: number | null
  latestObservedOn: string | null
  historicalMinimum: number | null
  targetReached: boolean | null
  /** Optimistic version; an offline edit sends the one the user actually saw. */
  version: number
}

export interface PurchaseOption {
  id: number
  merchant: string
  kind: PurchaseOptionKind
  basePrice: number
  shipping: number
  fees: number
  nominalCost: number
  installmentCount: number | null
  installmentAmount: number | null
  creditCardId: number | null
  creditCardName: string | null
  notes: string | null
  /** Optimistic version; an offline edit sends the one the user actually saw. */
  version: number
}

export interface WishlistItemDetail {
  id: number
  name: string
  notes: string | null
  category: WishlistCategoryRef | null
  referencePrice: number | null
  targetPrice: number | null
  priority: WishlistPriority
  desiredDate: string | null
  status: WishlistStatus
  options: PurchaseOption[]
  /** Optimistic version; an offline edit sends the one the user actually saw. */
  version: number
}

export interface WishlistItemRequest {
  name: string
  notes?: string | null
  categoryId?: number | null
  referencePrice?: number | null
  targetPrice?: number | null
  priority: WishlistPriority
  desiredDate?: string | null
  status?: WishlistStatus
}

export interface PurchaseOptionRequest {
  merchant: string
  kind: PurchaseOptionKind
  basePrice: number
  shipping?: number
  fees?: number
  installmentCount?: number | null
  installmentAmount?: number | null
  creditCardId?: number | null
  notes?: string | null
}

export interface ExecutePurchaseRequest {
  optionId: number
  accountId?: number | null
  creditCardId?: number | null
  purchasedOn?: string | null
}

export interface ExecutePurchaseResponse {
  itemId: number
  status: WishlistStatus
  transactionId: number | null
  cardPurchaseId: number | null
}

export interface PriceSnapshot {
  id: number
  purchaseOptionId: number | null
  linkedOptionAvailable: boolean
  seriesKey: string
  merchant: string
  paymentKind: PurchaseOptionKind
  basePrice: number
  shipping: number
  fees: number
  nominalCost: number
  installmentCount: number | null
  installmentAmount: number | null
  observedOn: string
  offerUrl: string | null
  notes: string | null
  /** Optimistic version; an offline edit sends the one the user actually saw. */
  version: number
}

export interface PriceSnapshotRequest {
  clientRequestId: string
  purchaseOptionId?: number | null
  merchant: string
  paymentKind: PurchaseOptionKind
  basePrice: number
  shipping?: number
  fees?: number
  installmentCount?: number | null
  installmentAmount?: number | null
  observedOn: string
  offerUrl?: string | null
  notes?: string | null
  updateLinkedOption: boolean
}

export type PriceSnapshotUpdateRequest = Omit<PriceSnapshotRequest, 'clientRequestId' | 'updateLinkedOption'>

export interface PriceHistorySummary {
  observationCount: number
  seriesCount: number
  firstObservedOn: string | null
  lastObservedOn: string | null
  bestCurrentOptionCost: number | null
  latestObservedBestCost: number | null
  previousComparableCost: number | null
  absoluteChange: number | null
  percentageChange: number | null
  historicalMinimum: number | null
  historicalMaximum: number | null
  historicalAverage: number | null
  targetPrice: number | null
  distanceToTarget: number | null
  distanceToTargetPercentage: number | null
  targetReached: boolean | null
  latestMerchant: string | null
  latestPaymentKind: PurchaseOptionKind | null
  latestSeriesKey: string | null
  daysSinceLatestObservation: number | null
}

export interface PriceChartPoint {
  observedOn: string
  nominalCost: number
  observationCount: number
  snapshotId: number
}

export interface PriceChartResponse {
  from: string
  to: string
  points: PriceChartPoint[]
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/* ---- analysis ---- */

export type RecommendationType = 'BUY_CASH' | 'BUY_INSTALLMENT' | 'WAIT' | 'NO_OPTIONS'

export interface OptionIssue {
  code: string
  message: string
  blocking: boolean
}

export interface CardAnalysis {
  cardId: number
  cardName: string
  availableLimit: number
  availableLimitAfter: number
  utilizationAfterPercent: number
  firstInvoiceMonth: string
  limitSufficient: boolean
}

export interface OptionAnalysis {
  optionId: number
  merchant: string
  kind: PurchaseOptionKind
  nominalCost: number
  presentValue: number
  upfrontCost: number
  monthlyBurden: number | null
  installmentCount: number | null
  cashAfterPurchase: number
  card: CardAnalysis | null
  safe: boolean
  issues: OptionIssue[]
}

export interface AnalysisAssumptions {
  availableCash: number
  minimumCashBuffer: number
  monthlyOpportunityRate: number
  maxInstallmentCommitmentRatio: number
  avgMonthlyIncome: number | null
  avgMonthlyExpense: number | null
  avgMonthlySurplus: number | null
  monthlyCommitments: number
  cardOutstandingTotal: number
  nextMonthCardInstallments: number
  historyMonthsUsed: number
}

export interface Recommendation {
  type: RecommendationType
  recommendedOptionId: number | null
  reasonCodes: string[]
  explanation: string
  warnings: string[]
  requiredAdditionalCash: number | null
  estimatedMonthsToAfford: number | null
}

/** Stable machine-readable reason a complete analysis could not be produced. */
export interface UnavailableReason {
  code: string
  message: string
}

/**
 * The complete analysis: every operand was denominated in one currency.
 *
 * `itemCurrency` always equals `baseCurrency` here — that equality is one of
 * the conditions for the analysis to be available at all.
 */
export interface AvailablePurchaseAnalysis {
  availability: 'AVAILABLE'
  itemId: number
  itemName: string
  baseCurrency: CurrencyCode
  itemCurrency: CurrencyCode
  assumptions: AnalysisAssumptions
  options: OptionAnalysis[]
  recommendation: Recommendation
}

/**
 * The analysis is deliberately unavailable because it would need a rate.
 *
 * Not an error: the request succeeded and the item, its options and its price
 * history are all still readable. There is simply no honest recommendation to
 * make, so `assumptions`, `options` and `recommendation` do not exist on this
 * variant at all — a shape where they were merely optional would let a `WAIT`
 * slip through the type checker.
 */
export interface UnavailablePurchaseAnalysis {
  availability: 'EXCHANGE_RATE_REQUIRED'
  itemId: number
  itemName: string
  baseCurrency: CurrencyCode
  itemCurrency: CurrencyCode
  missingCurrencies: CurrencyCode[]
  unavailableReasons: UnavailableReason[]
}

export type PurchaseAnalysis = AvailablePurchaseAnalysis | UnavailablePurchaseAnalysis

export const STATUS_LABELS: Record<WishlistStatus, string> = {
  PLANNING: 'Planejando',
  MONITORING: 'Monitorando',
  READY_TO_BUY: 'Pronto para comprar',
  PURCHASED: 'Comprado',
  ARCHIVED: 'Arquivado',
}

export const PRIORITY_LABELS: Record<WishlistPriority, string> = {
  LOW: 'Baixa',
  MEDIUM: 'Média',
  HIGH: 'Alta',
  ESSENTIAL: 'Essencial',
}
