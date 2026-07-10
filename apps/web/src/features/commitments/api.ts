import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../lib/api'
import type { Commitment, CommitmentRequest, UpcomingCommitments } from './types'

export function useCommitments() {
  return useQuery({
    queryKey: ['commitments'],
    queryFn: () => api.get<Commitment[]>('/commitments'),
  })
}

export function useUpcomingCommitments(months = 2) {
  return useQuery({
    queryKey: ['commitments', 'upcoming', months],
    queryFn: () => api.get<UpcomingCommitments>(`/commitments/upcoming?months=${months}`),
  })
}

function invalidate(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: ['commitments'] })
  queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  queryClient.invalidateQueries({ queryKey: ['insights'] })
}

export function useCreateCommitment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: CommitmentRequest) => api.post<Commitment>('/commitments', request),
    onSuccess: () => invalidate(queryClient),
  })
}

export function useUpdateCommitment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, request }: { id: number; request: CommitmentRequest }) =>
      api.put<Commitment>(`/commitments/${id}`, request),
    onSuccess: () => invalidate(queryClient),
  })
}

export function useDeleteCommitment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete(`/commitments/${id}`),
    onSuccess: () => invalidate(queryClient),
  })
}
