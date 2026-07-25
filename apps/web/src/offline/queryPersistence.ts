import type { QueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import type { OfflineQuery } from './vaultCrypto'

const ALLOWED_ROOTS = new Set([
  'dashboard', 'accounts', 'transactions', 'credit-cards', 'budgets',
  'commitments', 'forecast', 'goals', 'wishlist', 'notifications',
  'settings', 'notification-preferences',
])

export function isAllowedOfflineKey(key: readonly unknown[]): boolean {
  if (typeof key[0] !== 'string' || !ALLOWED_ROOTS.has(key[0])) return false
  if (key[0] === 'transactions') {
    const filters = key[1] as { page?: number } | undefined
    return !!filters && filters.page === 0
  }
  if (key[0] === 'forecast') return key[1] === 90 && key[2] === null
  if (key[0] === 'notifications') return key[1] === 'ACTIVE' && key[2] === 0 && key[3] === 20
  if (key[0] === 'credit-cards' || key[0] === 'wishlist') return key.length === 1
  if (key[0] === 'commitments') return key.length === 1 || (key[1] === 'upcoming' && key[2] === 3)
  return true
}

export function serializeAllowedQueries(queryClient: QueryClient): OfflineQuery[] {
  return queryClient.getQueryCache().getAll().flatMap((query) => {
    if (!isAllowedOfflineKey(query.queryKey) || query.state.status !== 'success' || query.state.data === undefined) return []
    return [{ queryKey: query.queryKey, data: query.state.data, dataUpdatedAt: query.state.dataUpdatedAt }]
  })
}

export function hydrateAllowedQueries(queryClient: QueryClient, queries: OfflineQuery[]): void {
  for (const query of queries) {
    if (!isAllowedOfflineKey(query.queryKey)) continue
    queryClient.setQueryData(query.queryKey, query.data, { updatedAt: query.dataUpdatedAt })
  }
}

export async function fetchOfflineDataset(queryClient: QueryClient, month: string): Promise<void> {
  const requests: Array<[readonly unknown[], string]> = [
    [['dashboard', month], `/dashboard?month=${month}`],
    [['accounts'], '/accounts'],
    [['transactions', { month, page: 0 }], `/transactions?month=${month}&page=0&size=20`],
    [['credit-cards'], '/credit-cards'],
    [['budgets', month], `/budgets?month=${month}`],
    [['commitments'], '/commitments'],
    [['commitments', 'upcoming', 3], '/commitments/upcoming?months=3'],
    [['forecast', 90, null], '/forecast?days=90'],
    [['goals'], '/goals'],
    [['wishlist'], '/wishlist'],
    [['notifications', 'ACTIVE', 0, 20], '/notifications?filter=ACTIVE&page=0&size=20'],
    [['settings'], '/settings'],
    [['notification-preferences'], '/notification-preferences'],
  ]
  await Promise.all(requests.map(([queryKey, path]) => queryClient.fetchQuery({ queryKey, queryFn: () => api.get(path) })))
}
