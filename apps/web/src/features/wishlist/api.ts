import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../lib/api'
import type {
  ExecutePurchaseRequest,
  ExecutePurchaseResponse,
  PurchaseAnalysis,
  PurchaseOption,
  PurchaseOptionRequest,
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
