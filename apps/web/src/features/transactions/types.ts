import type { AccountType, PaymentMethod, TransactionType } from '../shared/types'

export interface TransactionCategoryRef {
  id: number
  name: string
  type: TransactionType
}

export interface TransactionAccountRef {
  id: number
  name: string
  type: AccountType
}

export interface Transaction {
  id: number
  type: TransactionType
  amount: number
  description: string
  date: string
  category: TransactionCategoryRef
  account: TransactionAccountRef | null
  paymentMethod: PaymentMethod | null
  notes: string | null
}

export interface TransactionRequest {
  type: TransactionType
  amount: number
  description: string
  date: string
  categoryId: number
  accountId?: number | null
  paymentMethod?: PaymentMethod | null
  notes?: string | null
}

export interface TransactionFilters {
  month: string
  type?: TransactionType
  categoryId?: number
  search?: string
  page: number
}
