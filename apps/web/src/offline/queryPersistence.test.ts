import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { hydrateAllowedQueries, isAllowedOfflineKey, serializeAllowedQueries } from './queryPersistence'

describe('bounded offline query persistence', () => {
  it('allowlists bounded read keys and rejects sensitive or unbounded keys', () => {
    expect(isAllowedOfflineKey(['dashboard', '2026-07'])).toBe(true)
    expect(isAllowedOfflineKey(['transactions', { month: '2026-07', page: 0 }])).toBe(true)
    expect(isAllowedOfflineKey(['transactions', { month: '2026-07', page: 2 }])).toBe(false)
    expect(isAllowedOfflineKey(['statement-imports', 'detail', 1])).toBe(false)
    expect(isAllowedOfflineKey(['wishlist', 1, 'price-history'])).toBe(false)
    expect(isAllowedOfflineKey(['auth', 'me'])).toBe(false)
  })

  it('serializes successful allowlisted queries only and never mutation state or errors', () => {
    const client = new QueryClient()
    client.setQueryData(['accounts'], [{ id: 1 }], { updatedAt: 123 })
    client.setQueryData(['statement-imports', 'detail', 1], { raw: 'excluded' })
    const serialized = serializeAllowedQueries(client)
    expect(serialized).toEqual([{ queryKey: ['accounts'], data: [{ id: 1 }], dataUpdatedAt: 123 }])
  })

  it('rejects unknown keys again during hydration and preserves stale timestamps', () => {
    const client = new QueryClient()
    hydrateAllowedQueries(client, [
      { queryKey: ['goals'], data: [{ id: 2 }], dataUpdatedAt: 456 },
      { queryKey: ['future-secret'], data: 'no', dataUpdatedAt: 999 },
    ])
    expect(client.getQueryData(['goals'])).toEqual([{ id: 2 }])
    expect(client.getQueryState(['goals'])?.dataUpdatedAt).toBe(456)
    expect(client.getQueryData(['future-secret'])).toBeUndefined()
  })
})
