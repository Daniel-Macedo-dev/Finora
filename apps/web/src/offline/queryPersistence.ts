import type { QueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import type { OfflineQuery } from './vaultCrypto'

const ALLOWED_ROOTS = new Set([
  'dashboard', 'accounts', 'transactions', 'credit-cards', 'budgets',
  'commitments', 'forecast', 'goals', 'wishlist', 'notifications',
  'settings', 'notification-preferences',
  // Offline forms for transactions, budgets and wishlist items all need the
  // owner's own categories; without them those forms cannot be rendered at all.
  'categories',
])

/**
 * How many wishlist items keep a full detail copy offline.
 *
 * Persisting every item's detail and history would grow without bound with the
 * wishlist itself. Only recently viewed items are kept — those are the ones a
 * user is plausibly about to record an option or an observation against.
 */
export const MAX_PREPARED_WISHLIST_DETAILS = 10

/** First page of observations per prepared item; never the whole series. */
export const MAX_PREPARED_HISTORY_PER_ITEM = 50

export function isAllowedOfflineKey(key: readonly unknown[]): boolean {
  if (typeof key[0] !== 'string' || !ALLOWED_ROOTS.has(key[0])) return false
  if (key[0] === 'transactions') {
    const filters = key[1] as { page?: number } | undefined
    return !!filters && filters.page === 0
  }
  if (key[0] === 'forecast') return key[1] === 90 && key[2] === null
  if (key[0] === 'notifications') return key[1] === 'ACTIVE' && key[2] === 0 && key[3] === 20
  if (key[0] === 'credit-cards') return key.length === 1
  if (key[0] === 'wishlist') {
    // The list itself, one item's detail, or that item's first history page.
    if (key.length === 1) return true
    if (typeof key[1] !== 'number') return false
    if (key.length === 2) return true
    return key[2] === 'price-history' && key[3] === 0
  }
  if (key[0] === 'commitments') return key.length === 1 || (key[1] === 'upcoming' && key[2] === 3)
  return true
}

export function serializeAllowedQueries(queryClient: QueryClient): OfflineQuery[] {
  const details: OfflineQuery[] = []
  const rest: OfflineQuery[] = []
  for (const query of queryClient.getQueryCache().getAll()) {
    if (
      !isAllowedOfflineKey(query.queryKey) ||
      query.state.status !== 'success' ||
      query.state.data === undefined
    ) {
      continue
    }
    const persisted = {
      queryKey: query.queryKey,
      data: query.state.data,
      dataUpdatedAt: query.state.dataUpdatedAt,
    }
    const itemScoped = query.queryKey[0] === 'wishlist' && query.queryKey.length > 1
    if (itemScoped) {
      details.push(persisted)
    } else {
      rest.push(persisted)
    }
  }
  // Recency decides which item details survive the bound: most recently
  // updated first, then truncated.
  details.sort((left, right) => right.dataUpdatedAt - left.dataUpdatedAt)
  const kept = new Set<number>()
  const boundedDetails: OfflineQuery[] = []
  for (const detail of details) {
    const itemId = detail.queryKey[1] as number
    if (!kept.has(itemId)) {
      if (kept.size >= MAX_PREPARED_WISHLIST_DETAILS) continue
      kept.add(itemId)
    }
    boundedDetails.push(detail)
  }
  return [...rest, ...boundedDetails]
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
    // All three keys, because `useCategories(type)` caches per type and the
    // forms that queue offline ask for a type: the transaction form for the
    // type being entered, the budget and wishlist forms for EXPENSE. Caching
    // only the untyped list left every one of those selects empty offline, and
    // a category is required, so nothing could be queued at all.
    [['categories', 'all'], '/categories'],
    [['categories', 'EXPENSE'], '/categories?type=EXPENSE'],
    [['categories', 'INCOME'], '/categories?type=INCOME'],
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
  await Promise.all(
    requests.map(([queryKey, path]) =>
      queryClient.fetchQuery({ queryKey, queryFn: () => api.get(path) }),
    ),
  )
}
