import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../lib/api'
import type {
  Commitment,
  CommitmentRequest,
  Occurrence,
  OccurrencePreview,
  ProcessDueResult,
  UpcomingCommitments,
} from './types'

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

export function useOccurrencePreview(commitmentId: number | null, from: string, to: string) {
  return useQuery({
    queryKey: ['commitments', commitmentId, 'occurrences', from, to],
    queryFn: () =>
      api.get<OccurrencePreview>(
        `/commitments/${commitmentId}/occurrences?from=${from}&to=${to}`,
      ),
    enabled: commitmentId !== null,
  })
}

/** Definition-level changes reshape projections only. */
function invalidateDefinitions(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: ['commitments'] })
  queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  queryClient.invalidateQueries({ queryKey: ['insights'] })
  queryClient.invalidateQueries({ queryKey: ['forecast'] })
}

/**
 * Occurrence actions create or undo real financial records, so everything
 * derived from transactions, accounts and card invoices refreshes too.
 */
function invalidateFinancialRecords(queryClient: ReturnType<typeof useQueryClient>) {
  invalidateDefinitions(queryClient)
  queryClient.invalidateQueries({ queryKey: ['transactions'] })
  queryClient.invalidateQueries({ queryKey: ['accounts'] })
  queryClient.invalidateQueries({ queryKey: ['credit-cards'] })
  queryClient.invalidateQueries({ queryKey: ['budgets'] })
}

export function useCreateCommitment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: CommitmentRequest) => api.post<Commitment>('/commitments', request),
    onSuccess: () => invalidateDefinitions(queryClient),
  })
}

export function useUpdateCommitment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, request }: { id: number; request: CommitmentRequest }) =>
      api.put<Commitment>(`/commitments/${id}`, request),
    onSuccess: () => invalidateDefinitions(queryClient),
  })
}

export function useDeleteCommitment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete(`/commitments/${id}`),
    onSuccess: () => invalidateDefinitions(queryClient),
  })
}

export function useSetCommitmentActive() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, active }: { id: number; active: boolean }) =>
      api.post<Commitment>(`/commitments/${id}/${active ? 'resume' : 'pause'}`, {}),
    onSuccess: () => invalidateDefinitions(queryClient),
  })
}

export interface MapLegacyCreditRequest {
  creditCardId: number
  installmentCount: number
  executionMode: 'MANUAL' | 'AUTOMATIC'
}

/**
 * Maps a "Crédito legado" definition to a real card target. Only future,
 * still-unmaterialized occurrences use the card — the backend records an
 * automation horizon so historical occurrences are never backfilled. Card
 * projections and the forecast change, hence the financial-records set.
 */
export function useMapLegacyCredit() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, request }: { id: number; request: MapLegacyCreditRequest }) =>
      api.post<Commitment>(`/commitments/${id}/legacy-card-mapping`, request),
    onSuccess: () => invalidateFinancialRecords(queryClient),
  })
}

type OccurrenceAction = 'materialize' | 'retry' | 'skip' | 'unskip' | 'reverse'

export function useOccurrenceAction(commitmentId: number | null) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ date, action }: { date: string; action: OccurrenceAction }) =>
      api.post<Occurrence>(`/commitments/${commitmentId}/occurrences/${date}/${action}`, {}),
    // A failed materialization still persists a FAILED occurrence, so the
    // refresh must happen on settle, not only on success.
    onSettled: () => invalidateFinancialRecords(queryClient),
  })
}

export function useRescheduleOccurrence(commitmentId: number | null) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ date, newDate }: { date: string; newDate: string }) =>
      api.post<Occurrence>(`/commitments/${commitmentId}/occurrences/${date}/reschedule`, {
        newDate,
      }),
    onSuccess: () => invalidateFinancialRecords(queryClient),
  })
}

export function useProcessDue() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<ProcessDueResult>('/commitments/process-due', {}),
    onSuccess: () => invalidateFinancialRecords(queryClient),
  })
}
