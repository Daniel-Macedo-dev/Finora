import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../lib/api'
import { useOfflineOutbox, type QueuedMutation } from '../../offline/outbox/useOutbox'
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

export interface PriceHistoryFilters {
  merchant?: string
  from?: string
  to?: string
  purchaseOptionId?: number
  paymentKind?: 'CASH' | 'INSTALLMENT'
  sort?: 'NEWEST' | 'OLDEST' | 'LOWEST' | 'HIGHEST'
}

function priceHistoryParams(filters: PriceHistoryFilters) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  return params
}

export function usePriceHistory(itemId: number, page = 0, filters: PriceHistoryFilters = {}) {
  const params = priceHistoryParams({ ...filters, sort: filters.sort ?? 'NEWEST' })
  params.set('page', String(page)); params.set('size', '20')
  return useQuery({
    queryKey: ['wishlist', itemId, 'price-history', page, filters],
    queryFn: () => api.get<PageResponse<PriceSnapshot>>(`/wishlist/${itemId}/price-snapshots?${params}`),
  })
}

export function usePriceHistorySummary(itemId: number) {
  return useQuery({
    queryKey: ['wishlist', itemId, 'price-history-summary'],
    queryFn: () => api.get<PriceHistorySummary>(`/wishlist/${itemId}/price-history-summary`),
  })
}

export function usePriceHistoryChart(itemId: number, filters: PriceHistoryFilters = {}) {
  const params = priceHistoryParams(filters)
  return useQuery({
    queryKey: ['wishlist', itemId, 'price-history-chart', filters],
    queryFn: () => api.get<PriceChartResponse>(`/wishlist/${itemId}/price-history-series?${params}`),
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

function snapshotPayload(
  request: PriceSnapshotUpdateRequest,
  item: { serverId?: number; clientResourceId?: string },
  purchaseOption: { serverId?: number; clientResourceId?: string } | null,
): Record<string, unknown> {
  return {
    item,
    ...(purchaseOption ? { purchaseOption } : {}),
    merchant: request.merchant,
    paymentKind: request.paymentKind,
    basePrice: request.basePrice,
    ...(request.shipping != null ? { shipping: request.shipping } : {}),
    ...(request.fees != null ? { fees: request.fees } : {}),
    ...(request.installmentCount != null ? { installmentCount: request.installmentCount } : {}),
    ...(request.installmentAmount != null
      ? { installmentAmount: request.installmentAmount }
      : {}),
    observedOn: request.observedOn,
    ...(request.offerUrl ? { offerUrl: request.offerUrl } : {}),
    ...(request.notes ? { notes: request.notes } : {}),
  }
}

/**
 * Offline references to a parent that may not exist on the server yet.
 *
 * Both are client resource ids, set only when the item or the option was itself
 * created offline. The queue resolves them to server ids as the parents apply.
 */
export interface OfflineSnapshotParents {
  itemClientResourceId?: string
  optionClientResourceId?: string
}

export function useCreatePriceSnapshot(itemId: number, parents: OfflineSnapshotParents = {}) {
  const client = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async (request: PriceSnapshotRequest): Promise<PriceSnapshot | QueuedMutation> => {
      if (outbox.enabled) {
        const item = parents.itemClientResourceId
          ? { clientResourceId: parents.itemClientResourceId }
          : { serverId: itemId }
        const purchaseOption = parents.optionClientResourceId
          ? { clientResourceId: parents.optionClientResourceId }
          : request.purchaseOptionId != null
            ? { serverId: request.purchaseOptionId }
            : null
        return outbox.enqueue({
          resourceType: 'PRICE_SNAPSHOT',
          operation: 'CREATE',
          // The snapshot endpoint already treats clientRequestId as an
          // owner-scoped idempotency key, so it doubles as this resource's
          // stable client identity rather than adding a second UUID that would
          // have to be kept in step with it.
          clientResourceId: request.clientRequestId,
          baseVersion: null,
          payload: snapshotPayload(request, item, purchaseOption),
          dependencies: [
            parents.itemClientResourceId,
            parents.optionClientResourceId,
          ].filter((value): value is string => value != null),
          label: `${request.merchant} · ${request.observedOn}`,
        })
      }
      return api.post<PriceSnapshot>(`/wishlist/${itemId}/price-snapshots`, request)
    },
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
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async ({
      id,
      request,
      version,
    }: {
      id: number
      request: PriceSnapshotUpdateRequest
      version?: number
    }): Promise<PriceSnapshot | QueuedMutation> => {
      if (outbox.enabled) {
        return outbox.enqueue({
          resourceType: 'PRICE_SNAPSHOT',
          operation: 'UPDATE',
          clientResourceId: String(id),
          serverId: id,
          baseVersion: version ?? 0,
          payload: snapshotPayload(request, { serverId: itemId }, null),
          label: `${request.merchant} · ${request.observedOn}`,
        })
      }
      return api.put<PriceSnapshot>(`/wishlist/${itemId}/price-snapshots/${id}`, request)
    },
    onSuccess: () => invalidatePriceHistory(client, itemId, false),
  })
}

export function useDeletePriceSnapshot(itemId: number) {
  const client = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async (
      snapshot: { id: number; merchant: string; observedOn: string; version?: number },
    ): Promise<void | QueuedMutation> => {
      if (outbox.enabled) {
        return outbox.enqueue({
          resourceType: 'PRICE_SNAPSHOT',
          operation: 'DELETE',
          clientResourceId: String(snapshot.id),
          serverId: snapshot.id,
          baseVersion: snapshot.version ?? 0,
          payload: {},
          label: `${snapshot.merchant} · ${snapshot.observedOn}`,
        })
      }
      return api.delete(`/wishlist/${itemId}/price-snapshots/${snapshot.id}`)
    },
    onSuccess: () => invalidatePriceHistory(client, itemId, false),
  })
}

/**
 * @param enabled false for an item that exists only in the offline queue: it
 *   has no server id, so fetching it would ask the API for a negative one
 */
export function useWishlistItem(id: number, enabled = true) {
  return useQuery({
    queryKey: ['wishlist', id],
    queryFn: () => api.get<WishlistItemDetail>(`/wishlist/${id}`),
    enabled,
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

function itemPayload(request: WishlistItemRequest): Record<string, unknown> {
  return {
    name: request.name,
    priority: request.priority,
    ...(request.notes ? { notes: request.notes } : {}),
    ...(request.categoryId != null ? { categoryId: request.categoryId } : {}),
    ...(request.referencePrice != null ? { referencePrice: request.referencePrice } : {}),
    ...(request.targetPrice != null ? { targetPrice: request.targetPrice } : {}),
    ...(request.desiredDate ? { desiredDate: request.desiredDate } : {}),
    ...(request.status ? { status: request.status } : {}),
  }
}

export function useCreateWishlistItem() {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async (
      request: WishlistItemRequest,
    ): Promise<WishlistItemDetail | QueuedMutation> => {
      if (outbox.enabled) {
        return outbox.enqueue({
          resourceType: 'WISHLIST_ITEM',
          operation: 'CREATE',
          clientResourceId: outbox.newResourceId(),
          baseVersion: null,
          payload: itemPayload(request),
          label: request.name,
        })
      }
      return api.post<WishlistItemDetail>('/wishlist', request)
    },
    onSuccess: () => invalidateItem(queryClient),
  })
}

export function useUpdateWishlistItem() {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async ({
      id,
      request,
      version,
    }: {
      id: number
      request: WishlistItemRequest
      version?: number
    }): Promise<WishlistItemDetail | QueuedMutation> => {
      if (outbox.enabled) {
        return outbox.enqueue({
          resourceType: 'WISHLIST_ITEM',
          operation: 'UPDATE',
          clientResourceId: String(id),
          serverId: id,
          baseVersion: version ?? 0,
          payload: itemPayload(request),
          label: request.name,
        })
      }
      return api.put<WishlistItemDetail>(`/wishlist/${id}`, request)
    },
    onSuccess: (_data, variables) => invalidateItem(queryClient, variables.id),
  })
}

export function useDeleteWishlistItem() {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async (item: {
      id: number
      name: string
      version?: number
    }): Promise<void | QueuedMutation> => {
      if (outbox.enabled) {
        return outbox.enqueue({
          resourceType: 'WISHLIST_ITEM',
          operation: 'DELETE',
          clientResourceId: String(item.id),
          serverId: item.id,
          baseVersion: item.version ?? 0,
          payload: {},
          label: item.name,
        })
      }
      return api.delete(`/wishlist/${item.id}`)
    },
    onSuccess: () => invalidateItem(queryClient),
  })
}

function optionPayload(
  request: PurchaseOptionRequest,
  item: { serverId?: number; clientResourceId?: string },
): Record<string, unknown> {
  return {
    item,
    merchant: request.merchant,
    kind: request.kind,
    basePrice: request.basePrice,
    ...(request.shipping != null ? { shipping: request.shipping } : {}),
    ...(request.fees != null ? { fees: request.fees } : {}),
    ...(request.installmentCount != null ? { installmentCount: request.installmentCount } : {}),
    ...(request.installmentAmount != null
      ? { installmentAmount: request.installmentAmount }
      : {}),
    ...(request.creditCardId != null ? { creditCardId: request.creditCardId } : {}),
    ...(request.notes ? { notes: request.notes } : {}),
  }
}

/**
 * @param itemClientResourceId set when the parent item was itself created
 *   offline, so the option can name a parent that has no server id yet
 */
export function useAddOption(itemId: number, itemClientResourceId?: string) {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async (
      request: PurchaseOptionRequest,
    ): Promise<PurchaseOption | QueuedMutation> => {
      if (outbox.enabled) {
        const parent = itemClientResourceId
          ? { clientResourceId: itemClientResourceId }
          : { serverId: itemId }
        return outbox.enqueue({
          resourceType: 'PURCHASE_OPTION',
          operation: 'CREATE',
          clientResourceId: outbox.newResourceId(),
          baseVersion: null,
          payload: optionPayload(request, parent),
          // A child queued under an unsynchronized parent must never be sent
          // before it; the replay engine orders on exactly this.
          dependencies: itemClientResourceId ? [itemClientResourceId] : [],
          label: request.merchant,
        })
      }
      return api.post<PurchaseOption>(`/wishlist/${itemId}/options`, request)
    },
    onSuccess: () => invalidateItem(queryClient, itemId),
  })
}

export function useUpdateOption(itemId: number) {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async ({
      optionId,
      request,
      version,
    }: {
      optionId: number
      request: PurchaseOptionRequest
      version?: number
    }): Promise<PurchaseOption | QueuedMutation> => {
      if (outbox.enabled) {
        return outbox.enqueue({
          resourceType: 'PURCHASE_OPTION',
          operation: 'UPDATE',
          clientResourceId: String(optionId),
          serverId: optionId,
          baseVersion: version ?? 0,
          payload: optionPayload(request, { serverId: itemId }),
          label: request.merchant,
        })
      }
      return api.put<PurchaseOption>(`/wishlist/${itemId}/options/${optionId}`, request)
    },
    onSuccess: () => invalidateItem(queryClient, itemId),
  })
}

export function useDeleteOption(itemId: number) {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async (option: {
      id: number
      merchant: string
      version?: number
    }): Promise<void | QueuedMutation> => {
      if (outbox.enabled) {
        return outbox.enqueue({
          resourceType: 'PURCHASE_OPTION',
          operation: 'DELETE',
          clientResourceId: String(option.id),
          serverId: option.id,
          baseVersion: option.version ?? 0,
          payload: {},
          label: option.merchant,
        })
      }
      return api.delete(`/wishlist/${itemId}/options/${option.id}`)
    },
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
