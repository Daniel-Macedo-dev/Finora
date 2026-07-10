import { useQuery } from '@tanstack/react-query'
import { api } from '../../lib/api'
import type { DashboardData, InsightsData } from './types'

export function useDashboard(month: string) {
  return useQuery({
    queryKey: ['dashboard', month],
    queryFn: () => api.get<DashboardData>(`/dashboard?month=${month}`),
  })
}

export function useInsights(month: string) {
  return useQuery({
    queryKey: ['insights', month],
    queryFn: () => api.get<InsightsData>(`/insights?month=${month}`),
  })
}
