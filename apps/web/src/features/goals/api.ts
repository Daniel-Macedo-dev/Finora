import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../lib/api'
import { useOfflineOutbox, type QueuedMutation } from '../../offline/outbox/useOutbox'
import type { Goal, GoalRequest } from './types'

export function useGoals() {
  return useQuery({
    queryKey: ['goals'],
    queryFn: () => api.get<Goal[]>('/goals'),
  })
}

function invalidate(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: ['goals'] })
  queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  queryClient.invalidateQueries({ queryKey: ['insights'] })
}

function toPayload(request: GoalRequest): Record<string, unknown> {
  return {
    name: request.name,
    targetAmount: request.targetAmount,
    ...(request.currentAmount != null ? { currentAmount: request.currentAmount } : {}),
    ...(request.targetDate ? { targetDate: request.targetDate } : {}),
    ...(request.archived != null ? { archived: request.archived } : {}),
  }
}

export function useCreateGoal() {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async (request: GoalRequest): Promise<Goal | QueuedMutation> => {
      if (outbox.enabled) {
        return outbox.enqueue({
          resourceType: 'GOAL',
          operation: 'CREATE',
          clientResourceId: outbox.newResourceId(),
          baseVersion: null,
          payload: toPayload(request),
          label: request.name,
        })
      }
      return api.post<Goal>('/goals', request)
    },
    onSuccess: () => invalidate(queryClient),
  })
}

export function useUpdateGoal() {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async ({
      id,
      request,
      version,
    }: {
      id: number
      request: GoalRequest
      version?: number
    }): Promise<Goal | QueuedMutation> => {
      if (outbox.enabled) {
        return outbox.enqueue({
          resourceType: 'GOAL',
          operation: 'UPDATE',
          clientResourceId: String(id),
          serverId: id,
          baseVersion: version ?? 0,
          payload: toPayload(request),
          label: request.name,
        })
      }
      return api.put<Goal>(`/goals/${id}`, request)
    },
    onSuccess: () => invalidate(queryClient),
  })
}

/**
 * Contributions stay online-only.
 *
 * A contribution is a delta applied to whatever balance the goal holds when it
 * runs. Replayed hours later it would add to a number the user never saw, so
 * queueing one would quietly change its meaning. Setting the balance through an
 * ordinary edit is a different, explicit statement, and that is what the offline
 * form offers instead.
 */
export function useContributeToGoal() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, amount }: { id: number; amount: number }) =>
      api.post<Goal>(`/goals/${id}/contributions`, { amount }),
    onSuccess: () => invalidate(queryClient),
  })
}

export function useDeleteGoal() {
  const queryClient = useQueryClient()
  const outbox = useOfflineOutbox()
  return useMutation({
    mutationFn: async (goal: Goal): Promise<void | QueuedMutation> => {
      if (outbox.enabled) {
        return outbox.enqueue({
          resourceType: 'GOAL',
          operation: 'DELETE',
          clientResourceId: String(goal.id),
          serverId: goal.id,
          baseVersion: goal.version ?? 0,
          payload: {},
          label: goal.name,
        })
      }
      return api.delete(`/goals/${goal.id}`)
    },
    onSuccess: () => invalidate(queryClient),
  })
}
