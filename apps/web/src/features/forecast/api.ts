import { useQuery } from '@tanstack/react-query'
import { api } from '../../lib/api'
import type { Forecast } from './types'

export function useForecast(days: number, accountId: number | null) {
  const params = new URLSearchParams({ days: String(days) })
  if (accountId !== null) {
    params.set('accountId', String(accountId))
  }
  return useQuery({
    queryKey: ['forecast', days, accountId],
    queryFn: () => api.get<Forecast>(`/forecast?${params}`),
  })
}
