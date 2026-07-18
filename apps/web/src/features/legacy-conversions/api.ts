import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, queryString } from '../../lib/api'
import type {
  BatchConversionResponse,
  BatchConvertRequest,
  Conversion,
  ConversionInventoryResponse,
  ConversionPreview,
  ConvertRequest,
  InventoryFilters,
  PreviewRequest,
} from './types'

const PAGE_SIZE = 20

export function useConversionInventory(filters: InventoryFilters) {
  return useQuery({
    queryKey: ['legacy-conversions', 'inventory', filters],
    queryFn: () =>
      api.get<ConversionInventoryResponse>(
        `/legacy-conversions${queryString({
          month: filters.month,
          from: filters.from,
          to: filters.to,
          categoryId: filters.categoryId,
          minAmount: filters.minAmount,
          maxAmount: filters.maxAmount,
          state: filters.state,
          page: filters.page,
          size: PAGE_SIZE,
        })}`,
      ),
  })
}

export function useConversion(conversionId: number | null) {
  return useQuery({
    queryKey: ['legacy-conversions', 'detail', conversionId],
    queryFn: () => api.get<Conversion>(`/legacy-conversions/${conversionId}`),
    enabled: conversionId !== null,
  })
}

/**
 * Deterministic preview, fetched on demand as the user adjusts card, date and
 * installments. A mutation (not a query): each call is an explicit
 * recalculation and never persists anything.
 */
export function useConversionPreview() {
  return useMutation({
    mutationFn: (request: PreviewRequest) =>
      api.post<ConversionPreview>('/legacy-conversions/preview', request),
  })
}

/**
 * A conversion (or its reversal) moves the expense between the source month
 * and invoice months: every aggregate the app shows — transactions, cards and
 * invoices, budgets, dashboard, insights, forecast and account balances —
 * must refresh, plus the conversion inventory itself.
 */
function invalidateConversionData(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: ['legacy-conversions'] })
  queryClient.invalidateQueries({ queryKey: ['transactions'] })
  queryClient.invalidateQueries({ queryKey: ['credit-cards'] })
  queryClient.invalidateQueries({ queryKey: ['budgets'] })
  queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  queryClient.invalidateQueries({ queryKey: ['insights'] })
  queryClient.invalidateQueries({ queryKey: ['forecast'] })
  queryClient.invalidateQueries({ queryKey: ['accounts'] })
}

export function useConvertLegacy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: ConvertRequest) =>
      api.post<Conversion>('/legacy-conversions', request),
    onSuccess: () => invalidateConversionData(queryClient),
  })
}

export function useReverseConversion() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, reason }: { id: number; reason?: string }) =>
      api.post<Conversion>(`/legacy-conversions/${id}/reverse`, reason ? { reason } : {}),
    onSuccess: () => invalidateConversionData(queryClient),
  })
}

export function useBatchConvert() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: BatchConvertRequest) =>
      api.post<BatchConversionResponse>('/legacy-conversions/batch', request),
    // A batch commits per item, so even a response with failures may have
    // persisted successes — refresh on settle, not only on full success.
    onSettled: () => invalidateConversionData(queryClient),
  })
}
