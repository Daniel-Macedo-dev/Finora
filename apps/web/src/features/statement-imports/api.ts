import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, queryString, type PageResponse } from '../../lib/api'
import type {
  BatchDetail,
  BatchSummary,
  CategoryRule,
  CategoryRuleRequest,
  ConfirmResponse,
  CsvMappingRequest,
  ItemPatchRequest,
  MappingPreview,
} from './types'

const PAGE_SIZE = 20

export interface HistoryFilters {
  accountId?: number
  page: number
}

export function useImportHistory(filters: HistoryFilters) {
  return useQuery({
    queryKey: ['statement-imports', 'history', filters],
    queryFn: () =>
      api.get<PageResponse<BatchSummary>>(
        `/statement-imports${queryString({
          accountId: filters.accountId,
          page: filters.page,
          size: PAGE_SIZE,
        })}`,
      ),
  })
}

export function useImportBatch(batchId: number | null) {
  return useQuery({
    queryKey: ['statement-imports', 'detail', batchId],
    queryFn: () => api.get<BatchDetail>(`/statement-imports/${batchId}`),
    enabled: batchId !== null,
  })
}

function invalidateBatch(queryClient: ReturnType<typeof useQueryClient>, batchId: number) {
  queryClient.invalidateQueries({ queryKey: ['statement-imports', 'detail', batchId] })
  queryClient.invalidateQueries({ queryKey: ['statement-imports', 'history'] })
}

/**
 * A confirmation or undo creates/removes real transactions: every financial
 * aggregate the app shows must refresh, plus the import ledger itself.
 * Category rules refresh too — a confirmation records rule usage.
 */
function invalidateFinancialData(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: ['statement-imports'] })
  queryClient.invalidateQueries({ queryKey: ['category-mapping-rules'] })
  queryClient.invalidateQueries({ queryKey: ['transactions'] })
  queryClient.invalidateQueries({ queryKey: ['accounts'] })
  queryClient.invalidateQueries({ queryKey: ['budgets'] })
  queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  queryClient.invalidateQueries({ queryKey: ['insights'] })
  queryClient.invalidateQueries({ queryKey: ['forecast'] })
}

export function useUploadStatement() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ accountId, file }: { accountId: number; file: File }) => {
      const form = new FormData()
      form.append('file', file)
      form.append('accountId', String(accountId))
      return api.postMultipart<BatchDetail>('/statement-imports', form)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['statement-imports', 'history'] })
    },
  })
}

/** Candidate CSV mapping: returns a bounded preview, persists the config. */
export function useCsvMapping() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ batchId, mapping }: { batchId: number; mapping: CsvMappingRequest }) =>
      api.put<MappingPreview>(`/statement-imports/${batchId}/csv-mapping`, mapping),
    onSuccess: (_data, { batchId }) => invalidateBatch(queryClient, batchId),
  })
}

/** Authoritative parse with the saved mapping; discards the raw file. */
export function useReparse() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ batchId }: { batchId: number }) =>
      api.post<BatchDetail>(`/statement-imports/${batchId}/reparse`),
    onSuccess: (_data, { batchId }) => invalidateBatch(queryClient, batchId),
  })
}

/** Destination-account change before confirmation; reruns duplicate checks. */
export function useChangeAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ batchId, accountId }: { batchId: number; accountId: number }) =>
      api.patch<BatchDetail>(`/statement-imports/${batchId}`, { accountId }),
    onSuccess: (_data, { batchId }) => invalidateBatch(queryClient, batchId),
  })
}

export function usePatchItem() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      batchId,
      itemId,
      patch,
    }: {
      batchId: number
      itemId: number
      patch: ItemPatchRequest
    }) => api.patch<BatchDetail['items'][number]>(
      `/statement-imports/${batchId}/items/${itemId}`,
      patch,
    ),
    onSuccess: (_data, { batchId }) => invalidateBatch(queryClient, batchId),
  })
}

export function useConfirmImport() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ batchId, itemIds }: { batchId: number; itemIds?: number[] }) =>
      api.post<ConfirmResponse>(
        `/statement-imports/${batchId}/confirm`,
        itemIds && itemIds.length > 0 ? { itemIds } : undefined,
      ),
    // Confirmation commits per item: even a response with failures may have
    // persisted successes — refresh on settle, not only on full success.
    onSettled: () => invalidateFinancialData(queryClient),
  })
}

export function useUndoItem() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ batchId, itemId }: { batchId: number; itemId: number }) =>
      api.post<ConfirmResponse>(`/statement-imports/${batchId}/items/${itemId}/undo`),
    onSettled: () => invalidateFinancialData(queryClient),
  })
}

export function useUndoBatch() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ batchId }: { batchId: number }) =>
      api.post<ConfirmResponse>(`/statement-imports/${batchId}/undo`),
    onSettled: () => invalidateFinancialData(queryClient),
  })
}

/* ---------- category-mapping rules ---------- */

export function useCategoryRules() {
  return useQuery({
    queryKey: ['category-mapping-rules'],
    queryFn: () => api.get<CategoryRule[]>('/category-mapping-rules'),
  })
}

/**
 * Rule changes alter future suggestions and any open preview showing them,
 * so both caches refresh together.
 */
function invalidateRules(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: ['category-mapping-rules'] })
  queryClient.invalidateQueries({ queryKey: ['statement-imports'] })
}

export function useCreateCategoryRule() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: CategoryRuleRequest) =>
      api.post<CategoryRule>('/category-mapping-rules', request),
    onSuccess: () => invalidateRules(queryClient),
  })
}

export function useUpdateCategoryRule() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, request }: { id: number; request: CategoryRuleRequest }) =>
      api.put<CategoryRule>(`/category-mapping-rules/${id}`, request),
    onSuccess: () => invalidateRules(queryClient),
  })
}

export function useDeleteCategoryRule() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete(`/category-mapping-rules/${id}`),
    onSuccess: () => invalidateRules(queryClient),
  })
}
