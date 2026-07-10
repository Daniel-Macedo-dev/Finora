import type { PaymentMethod, TransactionType } from '../shared/types'

export type CommitmentCadence = 'MONTHLY' | 'YEARLY'

export interface Commitment {
  id: number
  description: string
  amount: number
  category: { id: number; name: string; type: TransactionType }
  cadence: CommitmentCadence
  dueDay: number | null
  startDate: string
  endDate: string | null
  active: boolean
  paymentMethod: PaymentMethod | null
  nextDueDate: string | null
}

export interface CommitmentRequest {
  description: string
  amount: number
  categoryId: number
  cadence: CommitmentCadence
  dueDay?: number | null
  startDate: string
  endDate?: string | null
  active?: boolean
  paymentMethod?: PaymentMethod | null
}

export interface UpcomingCommitments {
  from: string
  to: string
  totalAmount: number
  items: Array<{
    commitmentId: number
    description: string
    amount: number
    category: { id: number; name: string; type: TransactionType }
    dueDate: string
    paymentMethod: PaymentMethod | null
  }>
}
