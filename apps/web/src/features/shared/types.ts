/** Enums and reference entities shared across features (mirror the API contracts). */

export type TransactionType = 'INCOME' | 'EXPENSE'

export type PaymentMethod = 'PIX' | 'DEBIT' | 'CREDIT' | 'CASH' | 'BANK_TRANSFER' | 'OTHER'

export type AccountType = 'CHECKING' | 'SAVINGS' | 'CASH' | 'OTHER'

export interface Category {
  id: number
  name: string
  type: TransactionType
  active: boolean
  isDefault: boolean
}

export interface Account {
  id: number
  name: string
  type: AccountType
  openingBalance: number
  currentBalance: number
  archived: boolean
  displayOrder: number
}

export const PAYMENT_METHOD_LABELS: Record<PaymentMethod, string> = {
  PIX: 'Pix',
  DEBIT: 'Débito',
  CREDIT: 'Crédito',
  CASH: 'Dinheiro',
  BANK_TRANSFER: 'Transferência',
  OTHER: 'Outro',
}

export const ACCOUNT_TYPE_LABELS: Record<AccountType, string> = {
  CHECKING: 'Conta corrente',
  SAVINGS: 'Poupança',
  CASH: 'Dinheiro físico',
  OTHER: 'Outra',
}
