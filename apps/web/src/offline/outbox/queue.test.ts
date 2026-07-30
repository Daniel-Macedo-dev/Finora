import { describe, expect, it } from 'vitest'
import { emptyPayload, type VaultPayload } from '../vaultCrypto'
import {
  archive,
  collectDependents,
  countByStatus,
  hasUnsyncedWork,
  orderForReplay,
  pruneMappings,
  queueMutation,
  QueueRejectedError,
  rememberMapping,
  resolveTarget,
  type QueueRequest,
} from './queue'
import { OUTBOX_LIMITS, type OutboxEntry, type ResourceMapping } from './types'

const owner = { id: 1, displayName: 'Dona', email: 'owner@example.test' }

function vault(entries: OutboxEntry[] = [], mappings: ResourceMapping[] = []): VaultPayload {
  return { ...emptyPayload(owner, '2026-07-01T00:00:00.000Z', []), outbox: entries, resourceMappings: mappings }
}

function request(overrides: Partial<QueueRequest> = {}): QueueRequest {
  return {
    resourceType: 'TRANSACTION',
    operation: 'CREATE',
    clientResourceId: 'resource-a',
    baseVersion: null,
    payload: { amount: 10, description: 'Mercado' },
    label: 'Mercado',
    ...overrides,
  }
}

function entry(overrides: Partial<OutboxEntry> = {}): OutboxEntry {
  return {
    clientMutationId: `mutation-${Math.random().toString(16).slice(2)}`,
    resourceType: 'TRANSACTION',
    operation: 'CREATE',
    target: { clientResourceId: 'resource-a' },
    clientResourceId: 'resource-a',
    baseVersion: null,
    payload: {},
    dependencies: [],
    status: 'PENDING',
    createdAt: '2026-07-01T00:00:00.000Z',
    updatedAt: '2026-07-01T00:00:00.000Z',
    attemptCount: 0,
    nextAttemptAt: null,
    lastError: null,
    conflict: null,
    label: 'Entrada',
    ...overrides,
  }
}

describe('outbox compaction', () => {
  it('folds a create and a later edit into one create', () => {
    const created = queueMutation(vault(), request())
    const edited = queueMutation(
      created.payload,
      request({ operation: 'UPDATE', payload: { amount: 25, description: 'Mercado corrigido' } }),
    )

    expect(edited.payload.outbox).toHaveLength(1)
    const only = edited.payload.outbox[0]
    expect(only.operation).toBe('CREATE')
    expect(only.payload).toEqual({ amount: 25, description: 'Mercado corrigido' })
    // A folded entry is a different request, so it carries a new key.
    expect(only.clientMutationId).not.toBe(created.entry?.clientMutationId)
    expect(edited.removed).toContain(created.entry?.clientMutationId)
  })

  it('cancels a create that is deleted before it ever synchronized', () => {
    const created = queueMutation(vault(), request())
    const removed = queueMutation(created.payload, request({ operation: 'DELETE', payload: {} }))

    expect(removed.entry).toBeNull()
    expect(removed.payload.outbox).toHaveLength(0)
    expect(removed.removed).toContain(created.entry?.clientMutationId)
  })

  it('folds repeated edits without moving the base version', () => {
    const first = queueMutation(
      vault(),
      request({ operation: 'UPDATE', serverId: 7, baseVersion: 3, payload: { amount: 11 } }),
    )
    const second = queueMutation(
      first.payload,
      request({ operation: 'UPDATE', serverId: 7, baseVersion: 3, payload: { amount: 12 } }),
    )

    expect(second.payload.outbox).toHaveLength(1)
    // The base version is the state the user actually started from; a second
    // local edit never observed a newer server version.
    expect(second.payload.outbox[0].baseVersion).toBe(3)
    expect(second.payload.outbox[0].payload).toEqual({ amount: 12 })
  })

  it('folds an edit followed by a delete into one delete on the original version', () => {
    const edited = queueMutation(
      vault(),
      request({ operation: 'UPDATE', serverId: 7, baseVersion: 4, payload: { amount: 11 } }),
    )
    const removed = queueMutation(
      edited.payload,
      request({ operation: 'DELETE', serverId: 7, baseVersion: 4, payload: {} }),
    )

    expect(removed.payload.outbox).toHaveLength(1)
    expect(removed.payload.outbox[0].operation).toBe('DELETE')
    expect(removed.payload.outbox[0].baseVersion).toBe(4)
  })

  it('refuses to edit something already queued for deletion', () => {
    const removed = queueMutation(
      vault(),
      request({ operation: 'DELETE', serverId: 7, baseVersion: 1, payload: {} }),
    )
    expect(() =>
      queueMutation(
        removed.payload,
        request({ operation: 'UPDATE', serverId: 7, baseVersion: 1, payload: { amount: 5 } }),
      ),
    ).toThrow(QueueRejectedError)
  })

  it('never folds across different resources', () => {
    const first = queueMutation(vault(), request({ clientResourceId: 'resource-a' }))
    const second = queueMutation(first.payload, request({ clientResourceId: 'resource-b' }))
    expect(second.payload.outbox).toHaveLength(2)
  })

  it('never folds an entry the server may already have seen', () => {
    const inFlight = entry({ status: 'SYNCING' })
    const result = queueMutation(vault([inFlight]), request({ operation: 'UPDATE', baseVersion: 0 }))
    expect(result.payload.outbox).toHaveLength(2)
    expect(result.payload.outbox).toContain(inFlight)
  })

  it('cancels dependents when their offline parent is deleted', () => {
    const item = queueMutation(
      vault(),
      request({ resourceType: 'WISHLIST_ITEM', clientResourceId: 'item-1', label: 'Notebook' }),
    )
    const option = queueMutation(
      item.payload,
      request({
        resourceType: 'PURCHASE_OPTION',
        clientResourceId: 'option-1',
        dependencies: ['item-1'],
        label: 'Loja',
      }),
    )
    const snapshot = queueMutation(
      option.payload,
      request({
        resourceType: 'PRICE_SNAPSHOT',
        clientResourceId: 'snapshot-1',
        dependencies: ['option-1'],
        label: 'Observação',
      }),
    )

    const removed = queueMutation(
      snapshot.payload,
      request({ resourceType: 'WISHLIST_ITEM', clientResourceId: 'item-1', operation: 'DELETE', payload: {} }),
    )

    expect(removed.payload.outbox).toHaveLength(0)
    expect(removed.removed).toHaveLength(3)
  })
})

describe('outbox bounds', () => {
  it('refuses a payload larger than the allowed ceiling', () => {
    const huge = { notes: 'x'.repeat(OUTBOX_LIMITS.maxPayloadBytes + 1) }
    expect(() => queueMutation(vault(), request({ payload: huge }))).toThrow(QueueRejectedError)
  })

  it('blocks new mutations rather than silently dropping the oldest', () => {
    const full = Array.from({ length: OUTBOX_LIMITS.maxEntries }, (_, index) =>
      entry({ clientResourceId: `resource-${index}`, clientMutationId: `mutation-${index}` }),
    )
    try {
      queueMutation(vault(full), request({ clientResourceId: 'resource-new' }))
      throw new Error('expected the queue to reject a new mutation')
    } catch (error) {
      expect(error).toBeInstanceOf(QueueRejectedError)
      expect((error as QueueRejectedError).reason).toBe('QUEUE_FULL')
      // The message tells the user what to do, not just that it failed.
      expect((error as QueueRejectedError).message).toContain('Sincronize')
    }
  })
})

describe('dependency ordering', () => {
  it('keeps independent entries in creation order', () => {
    const entries = [
      entry({ clientResourceId: 'a', createdAt: '2026-07-01T00:00:01.000Z' }),
      entry({ clientResourceId: 'b', createdAt: '2026-07-01T00:00:02.000Z' }),
    ]
    expect(orderForReplay(entries, []).ready.map((item) => item.clientResourceId)).toEqual(['a', 'b'])
  })

  it('sends a parent before its child even when queued out of order', () => {
    const child = entry({
      clientResourceId: 'option',
      dependencies: ['item'],
      createdAt: '2026-07-01T00:00:01.000Z',
    })
    const parent = entry({ clientResourceId: 'item', createdAt: '2026-07-01T00:00:02.000Z' })
    expect(orderForReplay([child, parent], []).ready.map((item) => item.clientResourceId)).toEqual([
      'item',
      'option',
    ])
  })

  it('orders a three-level chain', () => {
    const entries = [
      entry({ clientResourceId: 'snapshot', dependencies: ['option'], createdAt: '3' }),
      entry({ clientResourceId: 'option', dependencies: ['item'], createdAt: '2' }),
      entry({ clientResourceId: 'item', createdAt: '1' }),
    ]
    expect(orderForReplay(entries, []).ready.map((item) => item.clientResourceId)).toEqual([
      'item',
      'option',
      'snapshot',
    ])
  })

  it('blocks a child whose parent is not queued at all', () => {
    const orphan = entry({ clientResourceId: 'option', dependencies: ['missing-item'] })
    const order = orderForReplay([orphan], [])
    expect(order.ready).toHaveLength(0)
    expect(order.blocked).toEqual([orphan])
    expect(order.cyclic).toHaveLength(0)
  })

  it('unblocks a child once its parent has been mapped to a server id', () => {
    const child = entry({ clientResourceId: 'option', dependencies: ['item'] })
    const mapping: ResourceMapping = {
      resourceType: 'WISHLIST_ITEM',
      clientResourceId: 'item',
      serverId: 99,
      serverVersion: 0,
      mappedAt: '2026-07-01T00:00:00.000Z',
    }
    expect(orderForReplay([child], [mapping]).ready).toEqual([child])
  })

  it('separates a dependency cycle instead of retrying it forever', () => {
    const left = entry({ clientResourceId: 'a', dependencies: ['b'], clientMutationId: 'm-a' })
    const right = entry({ clientResourceId: 'b', dependencies: ['a'], clientMutationId: 'm-b' })
    const order = orderForReplay([left, right], [])
    expect(order.ready).toHaveLength(0)
    expect(order.cyclic.map((item) => item.clientMutationId).sort()).toEqual(['m-a', 'm-b'])
  })

  it('collects the whole dependent chain of a resource', () => {
    const entries = [
      entry({ clientResourceId: 'item', clientMutationId: 'm-item' }),
      entry({ clientResourceId: 'option', dependencies: ['item'], clientMutationId: 'm-option' }),
      entry({ clientResourceId: 'snapshot', dependencies: ['option'], clientMutationId: 'm-snapshot' }),
      entry({ clientResourceId: 'unrelated', clientMutationId: 'm-unrelated' }),
    ]
    expect([...collectDependents(entries, 'item')].sort()).toEqual(['m-option', 'm-snapshot'])
  })
})

describe('resource mappings', () => {
  const mapping: ResourceMapping = {
    resourceType: 'WISHLIST_ITEM',
    clientResourceId: 'item',
    serverId: 55,
    serverVersion: 0,
    mappedAt: '2026-07-01T00:00:00.000Z',
  }

  it('addresses a create by its client identity and everything else by server id', () => {
    const create = entry({ operation: 'CREATE', clientResourceId: 'item' })
    expect(resolveTarget(create, [mapping])).toEqual({ clientResourceId: 'item' })

    const update = entry({ operation: 'UPDATE', clientResourceId: 'item', target: {} })
    expect(resolveTarget(update, [mapping])).toEqual({ serverId: 55 })
  })

  it('is idempotent when the same mapping arrives twice', () => {
    const once = rememberMapping(vault(), mapping)
    const twice = rememberMapping(once, { ...mapping, serverVersion: 1 })
    expect(twice.resourceMappings).toHaveLength(1)
    expect(twice.resourceMappings[0].serverVersion).toBe(1)
  })

  it('drops mappings nothing refers to any more', () => {
    const withMapping = rememberMapping(vault([entry({ clientResourceId: 'item' })]), mapping)
    expect(pruneMappings(withMapping).resourceMappings).toHaveLength(1)
    expect(pruneMappings({ ...withMapping, outbox: [] }).resourceMappings).toHaveLength(0)
  })
})

describe('queue accounting', () => {
  it('counts entries by what the user has to do about them', () => {
    const counts = countByStatus(
      vault([
        entry({ status: 'PENDING', clientResourceId: 'a' }),
        entry({ status: 'CONFLICT', clientResourceId: 'b' }),
        entry({ status: 'FAILED_PERMANENT', clientResourceId: 'c' }),
        entry({ status: 'BLOCKED', clientResourceId: 'd' }),
      ]),
    )
    expect(counts).toMatchObject({ total: 4, pending: 1, conflicts: 1, permanent: 1, blocked: 1 })
  })

  it('treats any active entry as unsynchronized work', () => {
    expect(hasUnsyncedWork(vault())).toBe(false)
    expect(hasUnsyncedWork(vault([entry({ status: 'CONFLICT' })]))).toBe(true)
  })

  it('caps the local log and keeps the newest records', () => {
    let current = vault()
    for (let index = 0; index < OUTBOX_LIMITS.maxHistory + 10; index += 1) {
      const finished = entry({ clientMutationId: `m-${index}`, label: `Entrada ${index}` })
      current = archive({ ...current, outbox: [finished] }, finished, 'APPLIED')
    }
    expect(current.syncHistory).toHaveLength(OUTBOX_LIMITS.maxHistory)
    expect(current.syncHistory[0].label).toBe(`Entrada ${OUTBOX_LIMITS.maxHistory + 9}`)
  })
})
