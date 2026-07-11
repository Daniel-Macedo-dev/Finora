/** Contracts mirroring the credit-card API DTOs. */

export type CreditCardBrand = 'VISA' | 'MASTERCARD' | 'ELO' | 'AMEX' | 'HIPERCARD' | 'OTHER'

export type InvoiceStatus =
  | 'UPCOMING'
  | 'OPEN'
  | 'CLOSED'
  | 'PARTIALLY_PAID'
  | 'OVERDUE'
  | 'PAID'

export type PurchaseStatus = 'ACTIVE' | 'CANCELLED'

export type InstallmentStatus = 'ACTIVE' | 'CANCELLED'

export type PaymentStatus = 'COMPLETED' | 'REVERSED'

export type AdjustmentKind = 'FEE' | 'INTEREST' | 'OTHER_DEBIT' | 'CREDIT' | 'REFUND'

export type AdjustmentStatus = 'ACTIVE' | 'REVERSED'

export interface CardLimit {
  creditLimit: number
  usedLimit: number
  availableLimit: number
  utilizationPercent: number
}

export interface CurrentCycle {
  invoiceId: number | null
  referenceMonth: string
  closingDate: string
  dueDate: string
}

export interface InvoiceSummary {
  id: number
  cardId: number
  referenceMonth: string
  closingDate: string
  dueDate: string
  status: InvoiceStatus
  purchaseTotal: number
  adjustmentsNet: number
  invoiceTotal: number
  amountPaid: number
  outstandingAmount: number
  installmentCount: number
}

export interface CreditCard {
  id: number
  name: string
  issuer: string | null
  brand: CreditCardBrand
  lastFourDigits: string | null
  closingDay: number
  dueDay: number
  defaultPaymentAccountId: number | null
  defaultPaymentAccountName: string | null
  archived: boolean
  limit: CardLimit
  currentCycle: CurrentCycle
  nextDueInvoice: InvoiceSummary | null
}

export interface CreditCardRequest {
  name: string
  issuer?: string | null
  brand: CreditCardBrand
  lastFourDigits?: string | null
  creditLimit: number
  closingDay: number
  dueDay: number
  defaultPaymentAccountId?: number | null
}

export interface PurchaseInstallment {
  id: number
  sequenceNumber: number
  totalInstallments: number
  amount: number
  invoiceId: number
  invoiceMonth: string
  invoiceDueDate: string
  status: InstallmentStatus
}

export interface CardPurchase {
  id: number
  cardId: number
  description: string
  merchant: string | null
  category: { id: number; name: string }
  purchaseDate: string
  totalAmount: number
  installmentCount: number
  status: PurchaseStatus
  wishlistItemId: number | null
  notes: string | null
  installments: PurchaseInstallment[]
}

export interface PurchaseRequest {
  description: string
  merchant?: string | null
  categoryId: number
  purchaseDate: string
  totalAmount: number
  installmentCount: number
  notes?: string | null
}

export interface InvoiceInstallmentLine {
  id: number
  purchaseId: number
  description: string
  merchant: string | null
  categoryName: string
  purchaseDate: string
  sequenceNumber: number
  totalInstallments: number
  amount: number
  status: InstallmentStatus
}

export interface InvoiceAdjustmentLine {
  id: number
  kind: AdjustmentKind
  description: string
  categoryName: string | null
  amount: number
  status: AdjustmentStatus
  reversedAt: string | null
}

export interface InvoicePaymentLine {
  id: number
  accountId: number
  accountName: string
  amount: number
  paidOn: string
  status: PaymentStatus
  notes: string | null
  reversedAt: string | null
}

export interface InvoiceDetail {
  invoice: InvoiceSummary
  installments: InvoiceInstallmentLine[]
  adjustments: InvoiceAdjustmentLine[]
  payments: InvoicePaymentLine[]
}

export interface PaymentRequest {
  accountId: number
  amount: number
  paidOn: string
  notes?: string | null
}

export interface PaymentResponse {
  id: number
  invoiceId: number
  accountId: number
  accountName: string
  amount: number
  paidOn: string
  status: PaymentStatus
  notes: string | null
  reversedAt: string | null
  invoiceOutstandingAmount: number
}

export const CARD_BRAND_LABELS: Record<CreditCardBrand, string> = {
  VISA: 'Visa',
  MASTERCARD: 'Mastercard',
  ELO: 'Elo',
  AMEX: 'American Express',
  HIPERCARD: 'Hipercard',
  OTHER: 'Outra',
}

export const INVOICE_STATUS_LABELS: Record<InvoiceStatus, string> = {
  UPCOMING: 'Futura',
  OPEN: 'Aberta',
  CLOSED: 'Fechada',
  PARTIALLY_PAID: 'Parcialmente paga',
  OVERDUE: 'Vencida',
  PAID: 'Paga',
}

export const ADJUSTMENT_KIND_LABELS: Record<AdjustmentKind, string> = {
  FEE: 'Tarifa',
  INTEREST: 'Juros',
  OTHER_DEBIT: 'Outro débito',
  CREDIT: 'Crédito',
  REFUND: 'Estorno da loja',
}
