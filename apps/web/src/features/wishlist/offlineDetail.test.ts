import { describe, expect, it } from 'vitest'
import { localId } from '../../offline/outbox/projection'
import type { OutboxEntry } from '../../offline/outbox/types'
import type { PurchaseOption } from './types'
import { findLocalItemEntry, localItemDetail, projectOptions } from './offlineDetail'

function entry(overrides: Partial<OutboxEntry> = {}): OutboxEntry {
  return {
    clientMutationId: 'mutation-1',
    resourceType: 'WISHLIST_ITEM',
    operation: 'CREATE',
    target: { clientResourceId: 'item-1' },
    clientResourceId: 'item-1',
    baseVersion: null,
    payload: { name: 'Notebook', priority: 'HIGH' },
    dependencies: [],
    status: 'PENDING',
    createdAt: '2026-07-01T00:00:00.000Z',
    updatedAt: '2026-07-01T00:00:00.000Z',
    attemptCount: 0,
    nextAttemptAt: null,
    lastError: null,
    conflict: null,
    label: 'Notebook',
    ...overrides,
  }
}

const optionEntry = entry({
  clientMutationId: 'mutation-2',
  resourceType: 'PURCHASE_OPTION',
  clientResourceId: 'option-1',
  target: { clientResourceId: 'option-1' },
  dependencies: ['item-1'],
  payload: {
    item: { clientResourceId: 'item-1' },
    merchant: 'Loja A',
    kind: 'CASH',
    basePrice: 100,
    shipping: 20,
    fees: 5,
  },
  label: 'Loja A',
})

const serverOptions: PurchaseOption[] = [
  {
    id: 7,
    merchant: 'Loja do servidor',
    kind: 'CASH',
    basePrice: 500,
    shipping: 0,
    fees: 0,
    nominalCost: 500,
    installmentCount: null,
    installmentAmount: null,
    creditCardId: null,
    creditCardName: null,
    notes: null,
    version: 3,
  },
]

describe('offline wishlist item detail', () => {
  it('finds the queued creation a negative list id stands for', () => {
    const item = entry()
    const found = findLocalItemEntry([item], localId('item-1'))
    expect(found).toBe(item)
  })

  it('ignores positive ids, which belong to the server', () => {
    expect(findLocalItemEntry([entry()], 42)).toBeNull()
  })

  it('builds a detail view out of the queued payload', () => {
    const detail = localItemDetail([entry()], entry())
    expect(detail.name).toBe('Notebook')
    expect(detail.priority).toBe('HIGH')
    expect(detail.id).toBeLessThan(0)
    expect(detail.options).toHaveLength(0)
  })

  it('attaches options queued against the item client id', () => {
    const detail = localItemDetail([entry(), optionEntry], entry())
    expect(detail.options).toHaveLength(1)
    expect(detail.options[0].merchant).toBe('Loja A')
    // Base price plus shipping plus fees — the three numbers the user typed.
    expect(detail.options[0].nominalCost).toBe(125)
    expect(detail.options[0].id).toBe(localId('option-1'))
  })

  it('does not attach an option queued against a different item', () => {
    const foreign = { ...optionEntry, dependencies: ['item-2'] }
    expect(localItemDetail([entry(), foreign], entry()).options).toHaveLength(0)
  })
})

describe('purchase option projection', () => {
  it('keeps untouched server options as they are', () => {
    const rows = projectOptions(serverOptions, [], 7)
    expect(rows).toHaveLength(1)
    expect(rows[0].pending).toBeNull()
  })

  it('adds an option created offline under this server item', () => {
    const created = {
      ...optionEntry,
      dependencies: [],
      payload: { ...optionEntry.payload, item: { serverId: 7 } },
    }
    const rows = projectOptions(serverOptions, [created], 7)
    expect(rows).toHaveLength(2)
    expect(rows[0].pending).toBe('CREATED')
    expect(rows[0].item.merchant).toBe('Loja A')
  })

  it('never shows an option created offline under a different item', () => {
    const other = {
      ...optionEntry,
      dependencies: [],
      payload: { ...optionEntry.payload, item: { serverId: 99 } },
    }
    expect(projectOptions(serverOptions, [other], 7)).toHaveLength(1)
  })

  it('lays a queued edit over the server option and keeps its id', () => {
    const edit = entry({
      resourceType: 'PURCHASE_OPTION',
      operation: 'UPDATE',
      target: { serverId: 7 },
      clientResourceId: '7',
      baseVersion: 3,
      payload: { merchant: 'Loja renomeada', basePrice: 450, shipping: 0, fees: 0 },
    })
    const rows = projectOptions(serverOptions, [edit], 7)
    expect(rows).toHaveLength(1)
    expect(rows[0].pending).toBe('UPDATED')
    expect(rows[0].item.id).toBe(7)
    expect(rows[0].item.merchant).toBe('Loja renomeada')
    expect(rows[0].item.nominalCost).toBe(450)
  })

  it('keeps a queued deletion visible rather than hiding the row', () => {
    const removal = entry({
      resourceType: 'PURCHASE_OPTION',
      operation: 'DELETE',
      target: { serverId: 7 },
      clientResourceId: '7',
      baseVersion: 3,
      payload: {},
    })
    const rows = projectOptions(serverOptions, [removal], 7)
    expect(rows[0].pending).toBe('DELETED')
    expect(rows[0].item.merchant).toBe('Loja do servidor')
  })

  it('ignores an edit aimed at an option this item does not own', () => {
    const foreign = entry({
      resourceType: 'PURCHASE_OPTION',
      operation: 'UPDATE',
      target: { serverId: 999 },
      clientResourceId: '999',
      baseVersion: 1,
      payload: { merchant: 'De outro item' },
    })
    const rows = projectOptions(serverOptions, [foreign], 7)
    expect(rows).toHaveLength(1)
    expect(rows[0].pending).toBeNull()
  })
})
