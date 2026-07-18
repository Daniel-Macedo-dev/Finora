/**
 * Contracts mirroring the legacy-conversion API DTOs. The backend preview is
 * the single source of truth for every financial number shown in the flow —
 * nothing here recomputes installments, limits or budget effects.
 */

import type { InvoiceStatus } from '../credit-cards/types'

/** Presentation state of one legacy-credit row in the conversion inventory. */
export type ConversionInventoryState = 'ELIGIBLE' | 'CONVERTED' | 'REVERSED' | 'BLOCKED'

/** Lifecycle of a persisted conversion. */
export type ConversionStatus = 'ACTIVE' | 'REVERSED'

export type EligibilityStatus =
  | 'ELIGIBLE'
  | 'ALREADY_CONVERTED'
  | 'REVERSED_CONVERSION'
  | 'INCOMPATIBLE_SOURCE'
  | 'BLOCKED'

export type BatchItemStatus = 'SUCCESS' | 'ALREADY_CONVERTED' | 'FAILED' | 'SKIPPED'

export interface CategorySummary {
  id: number
  name: string
}

/** One legacy-credit transaction in the inventory, with its conversion state. */
export interface ConversionInventoryItem {
  transactionId: number
  description: string
  amount: number
  date: string
  category: CategorySummary
  accountName: string | null
  state: ConversionInventoryState
  stateReasonCode: string | null
  stateMessage: string | null
  conversionId: number | null
  generatedCardPurchaseId: number | null
  cardId: number | null
}

export interface ConversionInventorySummary {
  eligibleCount: number
  convertedCount: number
  reversedCount: number
  pendingAmount: number
}

export interface ConversionInventoryResponse {
  summary: ConversionInventorySummary
  page: {
    content: ConversionInventoryItem[]
    page: number
    size: number
    totalElements: number
    totalPages: number
  }
}

export interface EligibilityResponse {
  transactionId: number
  status: EligibilityStatus
  convertible: boolean
  reasonCode: string | null
  message: string | null
}

/** Full conversion record with its current reversal eligibility. */
export interface Conversion {
  id: number
  sourceTransactionId: number
  sourceDescription: string
  amount: number
  originalTransactionDate: string
  cardPurchaseId: number
  cardId: number
  cardName: string
  effectivePurchaseDate: string
  installmentCount: number
  firstInvoiceMonth: string
  status: ConversionStatus
  convertedAt: string
  reversedAt: string | null
  reversalReason: string | null
  reversible: boolean
  reversalBlockedCode: string | null
  reversalBlockedMessage: string | null
}

/* ---------- preview ---------- */

export interface PreviewSource {
  transactionId: number
  description: string
  amount: number
  date: string
  category: CategorySummary
  accountName: string | null
  affectsAccountBalance: boolean
}

export interface PreviewCard {
  cardId: number
  name: string
  closingDay: number
  dueDay: number
  archived: boolean
}

/** One projected installment with the invoice cycle it lands on. */
export interface PreviewInstallment {
  sequenceNumber: number
  totalInstallments: number
  amount: number
  invoiceMonth: string
  closingDate: string
  dueDate: string
  invoiceExists: boolean
  invoiceStatus: InvoiceStatus | null
  invoiceAmountPaid: number | null
}

export interface PreviewLimit {
  creditLimit: number
  availableBefore: number
  availableAfter: number
  sufficient: boolean
}

/** Expense recognition moving between months (negative leaves, positive enters). */
export interface MonthlyExpenseShift {
  month: string
  delta: number
}

/** A structured, machine-readable message. Blockers prevent conversion. */
export interface PreviewMessage {
  code: string
  message: string
}

export interface CashFlowExplanation {
  sourceAffectsAccountBalance: boolean
  removesSourceCashEffect: boolean
  invoicePaymentAccountAssigned: boolean
  explanation: string
}

export interface ConversionPreview {
  source: PreviewSource
  card: PreviewCard
  totalAmount: number
  installmentCount: number
  firstInvoiceMonth: string
  installments: PreviewInstallment[]
  limit: PreviewLimit
  monthlyExpenseShift: MonthlyExpenseShift[]
  cashFlow: CashFlowExplanation
  forecastExplanation: string
  warnings: PreviewMessage[]
  blockers: PreviewMessage[]
  convertible: boolean
}

/* ---------- requests ---------- */

export interface PreviewRequest {
  transactionId: number
  cardId: number
  effectivePurchaseDate: string
  installmentCount: number
  /** Omitted while exploring; the backend computes and returns it. */
  firstInvoiceMonth?: string
}

/** Confirmation of one conversion; the first invoice must be explicit. */
export interface ConvertRequest {
  transactionId: number
  cardId: number
  effectivePurchaseDate: string
  installmentCount: number
  firstInvoiceMonth: string
}

export interface BatchConvertRequest {
  items: ConvertRequest[]
}

export interface BatchItemResult {
  transactionId: number
  status: BatchItemStatus
  conversionId: number | null
  generatedCardPurchaseId: number | null
  errorCode: string | null
  message: string | null
}

export interface BatchConversionResponse {
  total: number
  succeeded: number
  alreadyConverted: number
  failed: number
  skipped: number
  results: BatchItemResult[]
}

export interface InventoryFilters {
  month?: string
  from?: string
  to?: string
  categoryId?: number
  minAmount?: number
  maxAmount?: number
  state?: ConversionInventoryState
  page: number
}

/** Backend maximum accepted by POST /legacy-conversions/batch. */
export const MAX_BATCH_SIZE = 50

/* ---------- labels ---------- */

export const INVENTORY_STATE_LABELS: Record<ConversionInventoryState, string> = {
  ELIGIBLE: 'Elegível',
  CONVERTED: 'Convertida',
  REVERSED: 'Estornada',
  BLOCKED: 'Bloqueada',
}

export const CONVERSION_STATUS_LABELS: Record<ConversionStatus, string> = {
  ACTIVE: 'Ativa',
  REVERSED: 'Estornada',
}

export const BATCH_ITEM_STATUS_LABELS: Record<BatchItemStatus, string> = {
  SUCCESS: 'Convertida',
  ALREADY_CONVERTED: 'Já convertida',
  FAILED: 'Falhou',
  SKIPPED: 'Ignorada (repetida)',
}
