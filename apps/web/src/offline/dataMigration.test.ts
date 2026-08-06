import { describe, expect, it } from 'vitest'
import { migrateQueriesToV3, migrateQueryToV3 } from './dataMigration'
import type { OfflineQuery } from './vaultCrypto'

function query(queryKey: readonly unknown[], data: unknown): OfflineQuery {
  return { queryKey, data, dataUpdatedAt: 1_700_000_000_000 }
}

describe('V2 → V3 cached query migration', () => {
  it('labels settings with the base currency it always had', () => {
    const result = migrateQueryToV3(
      query(['settings'], { minimumCashBuffer: 500, budgetWarningThreshold: 0.8 }),
    )
    expect(result?.data).toMatchObject({ baseCurrency: 'BRL', minimumCashBuffer: 500 })
  })

  it('labels resource lists without inventing anything else', () => {
    const result = migrateQueryToV3(
      query(['accounts'], [
        { id: 1, name: 'Conta', currentBalance: 800 },
        { id: 2, name: 'Carteira', currentBalance: 0 },
      ]),
    )
    expect(result?.data).toEqual([
      { id: 1, name: 'Conta', currentBalance: 800, currency: 'BRL' },
      { id: 2, name: 'Carteira', currentBalance: 0, currency: 'BRL' },
    ])
  })

  it('never overwrites a currency that is already there', () => {
    const result = migrateQueryToV3(
      query(['accounts'], [{ id: 1, currentBalance: 100, currency: 'USD' }]),
    )
    expect(result?.data).toEqual([{ id: 1, currentBalance: 100, currency: 'USD' }])
  })

  it('labels the content of a paged response', () => {
    const result = migrateQueryToV3(
      query(['transactions', { month: '2026-07', page: 0 }], {
        content: [{ id: 9, amount: 25.9 }],
        totalElements: 1,
      }),
    )
    expect(result?.data).toEqual({
      content: [{ id: 9, amount: 25.9, currency: 'BRL' }],
      totalElements: 1,
    })
  })

  it('derives option and snapshot currency from the item context', () => {
    const result = migrateQueryToV3(
      query(['wishlist', 7], {
        id: 7,
        name: 'Notebook',
        targetPrice: 5000,
        options: [{ id: 1, totalAmount: 4800 }],
        priceHistory: [{ id: 3, price: 4900 }],
      }),
    )
    const data = result?.data as Record<string, unknown>
    expect(data.currency).toBe('BRL')
    expect(data.options).toEqual([{ id: 1, totalAmount: 4800, currency: 'BRL' }])
    expect(data.priceHistory).toEqual([{ id: 3, price: 4900, currency: 'BRL' }])
  })

  it('labels a notification only when it actually carries an amount', () => {
    const result = migrateQueryToV3(
      query(['notifications', 'ACTIVE', 0, 20], {
        content: [
          { id: 1, title: 'Fatura', amount: 120.5 },
          { id: 2, title: 'Sem valor', amount: null },
        ],
      }),
    )
    expect(result?.data).toEqual({
      content: [
        { id: 1, title: 'Fatura', amount: 120.5, currency: 'BRL' },
        { id: 2, title: 'Sem valor', amount: null },
      ],
    })
  })

  it('leaves categories alone — there is no money in them', () => {
    const categories = query(['categories', 'EXPENSE'], [{ id: 1, name: 'Alimentação' }])
    expect(migrateQueryToV3(categories)).toBe(categories)
  })

  // ── Derived responses that cannot be repaired ─────────────────────────────

  it.each([['dashboard'], ['budgets'], ['forecast'], ['insights']])(
    'drops the stale %s response instead of reinterpreting it',
    (root) => {
      expect(migrateQueryToV3(query([root, '2026-07'], { income: 5000, expense: 1200 }))).toBeNull()
    },
  )

  it('drops an upcoming-commitments window whose totals cannot be rebuilt', () => {
    expect(
      migrateQueryToV3(query(['commitments', 'upcoming', 3], { items: [], totals: { total: 0 } })),
    ).toBeNull()
  })

  it('keeps the commitments list, which is a plain resource list', () => {
    const result = migrateQueryToV3(query(['commitments'], [{ id: 1, amount: 79.8 }]))
    expect(result?.data).toEqual([{ id: 1, amount: 79.8, currency: 'BRL' }])
  })

  it('migrates a whole dataset, keeping what is safe and dropping what is not', () => {
    const migrated = migrateQueriesToV3([
      query(['settings'], { minimumCashBuffer: 500 }),
      query(['accounts'], [{ id: 1, currentBalance: 800 }]),
      query(['dashboard', '2026-07'], { income: 5000 }),
      query(['goals'], [{ id: 4, targetAmount: 10000 }]),
    ])

    expect(migrated.map((entry) => entry.queryKey[0])).toEqual(['settings', 'accounts', 'goals'])
    // Timestamps survive: a migrated copy is not a fresher copy.
    expect(migrated.every((entry) => entry.dataUpdatedAt === 1_700_000_000_000)).toBe(true)
  })

  it('does not stamp a currency onto nested objects it does not understand', () => {
    const result = migrateQueryToV3(
      query(['accounts'], [{ id: 1, currentBalance: 100, meta: { amount: 5 } }]),
    )
    const [account] = result?.data as Array<Record<string, unknown>>
    expect(account.currency).toBe('BRL')
    expect(account.meta).toEqual({ amount: 5 })
  })
})
