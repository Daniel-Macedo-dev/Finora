import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, queryString, type PageResponse } from '../../lib/api'
import { useOfflineOutbox, type QueuedMutation } from '../../offline/outbox/useOutbox'
import type { Transaction, TransactionFilters, TransactionRequest } from './types'

const PAGE_SIZE = 20

export function useTransactions(filters: TransactionFilters) {
  return useQuery({
    queryKey: ['transactions', filters],
    queryFn: () =>
      api.get<PageResponse<Transaction>>(
        `/transactions${queryString({
          month: filters.month,
          type: filters.type,
          categoryId: filters.categoryId,
          search: filters.search,
          page: filters.page,
          size: PAGE_SIZE,
        })}`,
      ),
  })
}

/** Server data affected by a transaction write: lists plus every aggregate view. */
function invalidateFinancialData(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: ['transactions'] })
  queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  queryClient.invalidateQueries({ queryKey: ['insights'] })
  queryClient.invalidateQueries({ queryKey: ['budgets'] })
  queryClient.invalidateQueries({ queryKey: ['accounts'] })
}

/** The queued form of a request: plain JSON, exactly what the endpoint expects. */
function toPayload(request: TransactionRequest): Record<string, unknown> {
  return {
    type: request.type,
    amount: request.amount,
    description: request.description,
    date: request.date,
    categoryId: request.categoryId,
    ...(request.accountId != null ? { accountId: request.accountId } : {}),
    ...(request.paymentMethod ? { paymentMethod: request.paymentMethod } : {}),
    ...(request.notes ? { notes: request.notes } : {}),
  }
}

export function useCreateTransaction() {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async (request: TransactionRequest): Promise<Transaction | QueuedMutation> => {
      if (outbox.enabled) {
        // No CSRF bootstrap and no fetch: the change goes straight into the
        // encrypted queue and the app stays entirely offline.
        return outbox.enqueue({
          resourceType: 'TRANSACTION',
          operation: 'CREATE',
          clientResourceId: outbox.newResourceId(),
          baseVersion: null,
          payload: toPayload(request),
          label: request.description,
        })
      }
      return api.post<Transaction>('/transactions', request)
    },
    onSuccess: () => invalidateFinancialData(queryClient),
  })
}

export interface UpdateTransactionInput {
  id: number
  request: TransactionRequest
  /** The version the user was looking at; the conflict check depends on it. */
  version?: number
  /** Set when editing something this device created offline. */
  clientResourceId?: string
}

export function useUpdateTransaction() {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async ({
      id,
      request,
      version,
      clientResourceId,
    }: UpdateTransactionInput): Promise<Transaction | QueuedMutation> => {
      if (outbox.enabled) {
        return outbox.enqueue({
          resourceType: 'TRANSACTION',
          operation: 'UPDATE',
          clientResourceId: clientResourceId ?? String(id),
          ...(clientResourceId ? {} : { serverId: id }),
          baseVersion: version ?? 0,
          payload: toPayload(request),
          label: request.description,
        })
      }
      return api.put<Transaction>(`/transactions/${id}`, request)
    },
    onSuccess: () => invalidateFinancialData(queryClient),
  })
}

export function useDeleteTransaction() {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async (transaction: Transaction): Promise<void | QueuedMutation> => {
      if (outbox.enabled) {
        return outbox.enqueue({
          resourceType: 'TRANSACTION',
          operation: 'DELETE',
          clientResourceId: String(transaction.id),
          serverId: transaction.id,
          baseVersion: transaction.version ?? 0,
          payload: {},
          label: transaction.description,
        })
      }
      return api.delete(`/transactions/${transaction.id}`)
    },
    onSuccess: () => invalidateFinancialData(queryClient),
  })
}
