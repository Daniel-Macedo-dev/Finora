import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, queryString, type PageResponse } from '../../lib/api'
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

export function useCreateTransaction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: TransactionRequest) => api.post<Transaction>('/transactions', request),
    onSuccess: () => invalidateFinancialData(queryClient),
  })
}

export function useUpdateTransaction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, request }: { id: number; request: TransactionRequest }) =>
      api.put<Transaction>(`/transactions/${id}`, request),
    onSuccess: () => invalidateFinancialData(queryClient),
  })
}

export function useDeleteTransaction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete(`/transactions/${id}`),
    onSuccess: () => invalidateFinancialData(queryClient),
  })
}
