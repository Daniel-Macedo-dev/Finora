import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type PageResponse } from '../../lib/api'
import type {
  CardPurchase,
  CreditCard,
  CreditCardRequest,
  InvoiceDetail,
  InvoiceSummary,
  PaymentRequest,
  PaymentResponse,
  PurchaseRequest,
} from './types'

export function useCreditCards() {
  return useQuery({
    queryKey: ['credit-cards'],
    queryFn: () => api.get<CreditCard[]>('/credit-cards'),
  })
}

export function useCreditCard(cardId: number) {
  return useQuery({
    queryKey: ['credit-cards', cardId],
    queryFn: () => api.get<CreditCard>(`/credit-cards/${cardId}`),
  })
}

export function useCardInvoices(cardId: number) {
  return useQuery({
    queryKey: ['credit-cards', cardId, 'invoices'],
    queryFn: () => api.get<InvoiceSummary[]>(`/credit-cards/${cardId}/invoices`),
  })
}

export function useInvoiceDetail(cardId: number, invoiceId: number) {
  return useQuery({
    queryKey: ['credit-cards', cardId, 'invoices', invoiceId],
    queryFn: () => api.get<InvoiceDetail>(`/credit-cards/${cardId}/invoices/${invoiceId}`),
  })
}

export function useCardPurchases(cardId: number, page: number) {
  return useQuery({
    queryKey: ['credit-cards', cardId, 'purchases', page],
    queryFn: () =>
      api.get<PageResponse<CardPurchase>>(
        `/credit-cards/${cardId}/purchases?page=${page}&size=10`,
      ),
  })
}

/**
 * Card writes ripple into every financial aggregate: limits, invoices,
 * budgets (installments), dashboard and insights, plus account balances for
 * payments. One helper keeps the invalidation set consistent.
 */
function invalidateCardData(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: ['credit-cards'] })
  queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  queryClient.invalidateQueries({ queryKey: ['insights'] })
  queryClient.invalidateQueries({ queryKey: ['budgets'] })
  queryClient.invalidateQueries({ queryKey: ['accounts'] })
}

export function useCreateCreditCard() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: CreditCardRequest) => api.post<CreditCard>('/credit-cards', request),
    onSuccess: () => invalidateCardData(queryClient),
  })
}

export function useUpdateCreditCard() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, request }: { id: number; request: CreditCardRequest }) =>
      api.put<CreditCard>(`/credit-cards/${id}`, request),
    onSuccess: () => invalidateCardData(queryClient),
  })
}

export function useArchiveCreditCard() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, archive }: { id: number; archive: boolean }) =>
      api.post<CreditCard>(`/credit-cards/${id}/${archive ? 'archive' : 'unarchive'}`),
    onSuccess: () => invalidateCardData(queryClient),
  })
}

export function useDeleteCreditCard() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete(`/credit-cards/${id}`),
    onSuccess: () => invalidateCardData(queryClient),
  })
}

export function useCreatePurchase(cardId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: PurchaseRequest) =>
      api.post<CardPurchase>(`/credit-cards/${cardId}/purchases`, request),
    onSuccess: () => invalidateCardData(queryClient),
  })
}

export function useUpdatePurchase(cardId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ purchaseId, request }: { purchaseId: number; request: PurchaseRequest }) =>
      api.put<CardPurchase>(`/credit-cards/${cardId}/purchases/${purchaseId}`, request),
    onSuccess: () => invalidateCardData(queryClient),
  })
}

export function useCancelPurchase(cardId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (purchaseId: number) =>
      api.post<CardPurchase>(`/credit-cards/${cardId}/purchases/${purchaseId}/cancel`),
    onSuccess: () => invalidateCardData(queryClient),
  })
}

export function usePayInvoice(cardId: number, invoiceId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: PaymentRequest) =>
      api.post<PaymentResponse>(
        `/credit-cards/${cardId}/invoices/${invoiceId}/payments`,
        request,
      ),
    onSuccess: () => invalidateCardData(queryClient),
  })
}

export function useReversePayment(cardId: number, invoiceId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (paymentId: number) =>
      api.post<PaymentResponse>(
        `/credit-cards/${cardId}/invoices/${invoiceId}/payments/${paymentId}/reverse`,
      ),
    onSuccess: () => invalidateCardData(queryClient),
  })
}
