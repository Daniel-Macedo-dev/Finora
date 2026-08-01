import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../lib/api'
import { useOfflineOutbox, type QueuedMutation } from '../../offline/outbox/useOutbox'
import type { Budget, BudgetRequest, BudgetSummary } from './types'

export function useBudgets(month: string) {
  return useQuery({
    queryKey: ['budgets', month],
    queryFn: () => api.get<BudgetSummary>(`/budgets?month=${month}`),
  })
}

function invalidate(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: ['budgets'] })
  queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  queryClient.invalidateQueries({ queryKey: ['insights'] })
}

function toPayload(request: BudgetRequest): Record<string, unknown> {
  return {
    month: request.month,
    categoryId: request.categoryId,
    limitAmount: request.limitAmount,
  }
}

/** "Alimentação · 2026-07" — enough to recognise the row in the sync centre. */
function label(request: BudgetRequest, categoryName?: string): string {
  return `${categoryName ?? `Categoria ${request.categoryId}`} · ${request.month}`
}

export interface BudgetMutationInput {
  request: BudgetRequest
  /** Only for the queue label; ownership is always resolved server-side. */
  categoryName?: string
}

export function useCreateBudget() {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async ({
      request,
      categoryName,
    }: BudgetMutationInput): Promise<Budget | QueuedMutation> => {
      if (outbox.enabled) {
        return outbox.enqueue({
          resourceType: 'BUDGET',
          operation: 'CREATE',
          clientResourceId: outbox.newResourceId(),
          baseVersion: null,
          payload: toPayload(request),
          label: label(request, categoryName),
        })
      }
      return api.post<Budget>('/budgets', request)
    },
    onSuccess: () => invalidate(queryClient),
  })
}

export function useUpdateBudget() {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async ({
      id,
      request,
      version,
      categoryName,
    }: BudgetMutationInput & { id: number; version?: number }): Promise<
      Budget | QueuedMutation
    > => {
      if (outbox.enabled) {
        const local = outbox.localResourceId(id)
        return outbox.enqueue({
          resourceType: 'BUDGET',
          operation: 'UPDATE',
          clientResourceId: local ?? String(id),
          ...(local ? {} : { serverId: id }),
          baseVersion: local ? null : (version ?? 0),
          payload: toPayload(request),
          label: label(request, categoryName),
        })
      }
      return api.put<Budget>(`/budgets/${id}`, request)
    },
    onSuccess: () => invalidate(queryClient),
  })
}

export function useDeleteBudget() {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async (budget: Budget): Promise<void | QueuedMutation> => {
      if (outbox.enabled) {
        // A row still waiting in the queue is addressed by the identity it was
        // created with, never by its placeholder id.
        const local = outbox.localResourceId(budget.id)
        return outbox.enqueue({
          resourceType: 'BUDGET',
          operation: 'DELETE',
          clientResourceId: local ?? String(budget.id),
          ...(local ? {} : { serverId: budget.id }),
          baseVersion: local ? null : (budget.version ?? 0),
          payload: {},
          label: `${budget.category.name} · ${budget.month}`,
        })
      }
      return api.delete(`/budgets/${budget.id}`)
    },
    onSuccess: () => invalidate(queryClient),
  })
}
