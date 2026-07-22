import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../lib/api'
import type {
  ExecutePurchaseRequest,
  ExecutePurchaseResponse,
  PurchaseAnalysis,
  PurchaseOption,
  PurchaseOptionRequest,
  PageResponse,
  PriceChartResponse,
  PriceHistorySummary,
  PriceSnapshot,
  PriceSnapshotRequest,
  PriceSnapshotUpdateRequest,
  WishlistItemDetail,
  WishlistItemRequest,
  WishlistItemSummary,
} from './types'

export function useWishlist() {
  return useQuery({
    queryKey: ['wishlist'],
    queryFn: () => api.get<WishlistItemSummary[]>('/wishlist'),
  })
}

export function usePriceHistory(itemId: number, page = 0, merchant = '') {
  const params = new URLSearchParams({ page: String(page), size: '20', sort: 'NEWEST' })
  if (merchant.trim()) params.set('merchant', merchant.trim())
  return useQuery({
    queryKey: ['wishlist', itemId, 'price-history', page, merchant],
    queryFn: () => api.get<PageResponse<PriceSnapshot>>(`/wishlist/${itemId}/price-snapshots?${params}`),
  })
}

export function usePriceHistorySummary(itemId: number) {
  return useQuery({
    queryKey: ['wishlist', itemId, 'price-history-summary'],
    queryFn: () => api.get<PriceHistorySummary>(`/wishlist/${itemId}/price-history-summary`),
  })
}

export function usePriceHistoryChart(itemId: number) {
  return useQuery({
    queryKey: ['wishlist', itemId, 'price-history-chart'],
    queryFn: () => api.get<PriceChartResponse>(`/wishlist/${itemId}/price-history-series`),
  })
}

function invalidatePriceHistory(client: ReturnType<typeof useQueryClient>, itemId: number,
                                analysis: boolean) {
  client.invalidateQueries({ queryKey: ['wishlist'] })
  client.invalidateQueries({ queryKey: ['wishlist', itemId] })
  client.invalidateQueries({ queryKey: ['wishlist', itemId, 'price-history'] })
  client.invalidateQueries({ queryKey: ['wishlist', itemId, 'price-history-summary'] })
  client.invalidateQueries({ queryKey: ['wishlist', itemId, 'price-history-chart'] })
  if (analysis) client.invalidateQueries({ queryKey: ['wishlist', itemId, 'analysis'] })
}

export function useCreatePriceSnapshot(itemId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (request: PriceSnapshotRequest) =>
      api.post<PriceSnapshot>(`/wishlist/${itemId}/price-snapshots`, request),
    onSuccess: (_data, request) => invalidatePriceHistory(client, itemId, request.updateLinkedOption),
  })
}

export function useCaptureOptionPrice(itemId: number, optionId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (request: Pick<PriceSnapshotRequest, 'clientRequestId' | 'observedOn' | 'offerUrl' | 'notes'>) =>
      api.post<PriceSnapshot>(`/wishlist/${itemId}/options/${optionId}/price-snapshots`, request),
    onSuccess: () => invalidatePriceHistory(client, itemId, false),
  })
}

export function useUpdatePriceSnapshot(itemId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, request }: { id: number; request: PriceSnapshotUpdateRequest }) =>
      api.put<PriceSnapshot>(`/wishlist/${itemId}/price-snapshots/${id}`, request),
    onSuccess: () => invalidatePriceHistory(client, itemId, false),
  })
}

export function useDeletePriceSnapshot(itemId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete(`/wishlist/${itemId}/price-snapshots/${id}`),
    onSuccess: () => invalidatePriceHistory(client, itemId, false),
  })
}

export function useWishlistItem(id: number) {
  return useQuery({
    queryKey: ['wishlist', id],
    queryFn: () => api.get<WishlistItemDetail>(`/wishlist/${id}`),
  })
}

export function usePurchaseAnalysis(id: number, enabled: boolean) {
  return useQuery({
    queryKey: ['wishlist', id, 'analysis'],
    queryFn: () => api.get<PurchaseAnalysis>(`/wishlist/${id}/analysis`),
    enabled,
  })
}

function invalidateItem(queryClient: ReturnType<typeof useQueryClient>, id?: number) {
  queryClient.invalidateQueries({ queryKey: ['wishlist'] })
  queryClient.invalidateQueries({ queryKey: ['insights'] })
  if (id !== undefined) {
    queryClient.invalidateQueries({ queryKey: ['wishlist', id] })
  }
}

export function useCreateWishlistItem() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: WishlistItemRequest) =>
      api.post<WishlistItemDetail>('/wishlist', request),
    onSuccess: () => invalidateItem(queryClient),
  })
}

export function useUpdateWishlistItem() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, request }: { id: number; request: WishlistItemRequest }) =>
      api.put<WishlistItemDetail>(`/wishlist/${id}`, request),
    onSuccess: (_data, variables) => invalidateItem(queryClient, variables.id),
  })
}

export function useDeleteWishlistItem() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete(`/wishlist/${id}`),
    onSuccess: () => invalidateItem(queryClient),
  })
}

export function useAddOption(itemId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: PurchaseOptionRequest) =>
      api.post<PurchaseOption>(`/wishlist/${itemId}/options`, request),
    onSuccess: () => invalidateItem(queryClient, itemId),
  })
}

export function useUpdateOption(itemId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ optionId, request }: { optionId: number; request: PurchaseOptionRequest }) =>
      api.put<PurchaseOption>(`/wishlist/${itemId}/options/${optionId}`, request),
    onSuccess: () => invalidateItem(queryClient, itemId),
  })
}

export function useDeleteOption(itemId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (optionId: number) => api.delete(`/wishlist/${itemId}/options/${optionId}`),
    onSuccess: () => invalidateItem(queryClient, itemId),
  })
}

/**
 * Executes a selected option as a real financial event (expense transaction
 * or card purchase). Every financial aggregate may change, so the whole
 * financial cache is invalidated.
 */
export function useExecutePurchase(itemId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: ExecutePurchaseRequest) =>
      api.post<ExecutePurchaseResponse>(`/wishlist/${itemId}/purchase`, request),
    onSuccess: () => {
      invalidateItem(queryClient, itemId)
      queryClient.invalidateQueries({ queryKey: ['transactions'] })
      queryClient.invalidateQueries({ queryKey: ['credit-cards'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      queryClient.invalidateQueries({ queryKey: ['budgets'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}
