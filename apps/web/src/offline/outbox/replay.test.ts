import { describe, expect, it, vi } from 'vitest'
import { emptyPayload, type VaultPayload } from '../vaultCrypto'
import { backoffDelay, isDue, replayOnce, REPLAY_BATCH_SIZE, RETRY_POLICY, type WireResult } from './replay'
import type { OutboxEntry } from './types'

const owner = { id: 1, displayName: 'Dona', email: 'owner@example.test' }

function vault(entries: OutboxEntry[]): VaultPayload {
  return { ...emptyPayload(owner, '2026-07-01T00:00:00.000Z', []), outbox: entries }
}

function entry(overrides: Partial<OutboxEntry> = {}): OutboxEntry {
  return {
    clientMutationId: 'mutation-1',
    resourceType: 'TRANSACTION',
    operation: 'CREATE',
    target: { clientResourceId: 'resource-1' },
    clientResourceId: 'resource-1',
    baseVersion: null,
    payload: { amount: 10 },
    dependencies: [],
    status: 'PENDING',
    createdAt: '2026-07-01T00:00:00.000Z',
    updatedAt: '2026-07-01T00:00:00.000Z',
    attemptCount: 0,
    nextAttemptAt: null,
    lastError: null,
    conflict: null,
    label: 'Mercado',
    ...overrides,
  }
}

function result(overrides: Partial<WireResult> = {}): WireResult {
  return {
    clientMutationId: 'mutation-1',
    resourceType: 'TRANSACTION',
    operation: 'CREATE',
    status: 'APPLIED',
    clientResourceId: 'resource-1',
    resourceId: 42,
    version: 0,
    result: { id: 42, amount: 10 },
    conflict: null,
    error: null,
    ...overrides,
  }
}

describe('replay', () => {
  it('applies a mutation, maps its server id and archives it', async () => {
    const outcome = await replayOnce(vault([entry()]), async () => [result()])

    expect(outcome.applied).toBe(1)
    expect(outcome.payload.outbox).toHaveLength(0)
    expect(outcome.payload.syncHistory[0]).toMatchObject({
      clientMutationId: 'mutation-1',
      outcome: 'APPLIED',
      label: 'Mercado',
    })
    expect(outcome.payload.resourceMappings[0]).toMatchObject({
      clientResourceId: 'resource-1',
      serverId: 42,
    })
  })

  it('treats an already-applied result exactly like a success', async () => {
    const outcome = await replayOnce(vault([entry()]), async () => [
      result({ status: 'ALREADY_APPLIED' }),
    ])
    expect(outcome.applied).toBe(1)
    expect(outcome.payload.outbox).toHaveLength(0)
    expect(outcome.payload.resourceMappings[0].serverId).toBe(42)
  })

  it('sends the same mutation id on every attempt', async () => {
    const send = vi.fn(async () => {
      throw new Error('conexão perdida')
    })
    const first = await replayOnce(vault([entry()]), send)
    const retried = first.payload.outbox.map((item) => ({ ...item, status: 'PENDING' as const }))
    await replayOnce({ ...first.payload, outbox: retried }, send)

    const [firstBatch] = send.mock.calls[0] as unknown as [{ clientMutationId: string }[]]
    const [secondBatch] = send.mock.calls[1] as unknown as [{ clientMutationId: string }[]]
    expect(secondBatch[0].clientMutationId).toBe(firstBatch[0].clientMutationId)
  })

  it('marks a lost connection retryable rather than failed', async () => {
    const outcome = await replayOnce(vault([entry()]), async () => {
      throw new Error('conexão perdida')
    })

    // The batch may or may not have been applied — assuming failure would be a
    // guess, and assuming success would hide real work.
    expect(outcome.transportError).toBe('conexão perdida')
    const [only] = outcome.payload.outbox
    expect(only.status).toBe('FAILED_RETRYABLE')
    expect(only.attemptCount).toBe(1)
    expect(only.nextAttemptAt).not.toBeNull()
  })

  it('stops scheduling automatic attempts after the cap', async () => {
    const exhausted = entry({ attemptCount: RETRY_POLICY.maxAttempts - 1 })
    const outcome = await replayOnce(vault([exhausted]), async () => {
      throw new Error('sem rede')
    })

    const [only] = outcome.payload.outbox
    expect(only.attemptCount).toBe(RETRY_POLICY.maxAttempts)
    expect(only.nextAttemptAt).toBeNull()
    expect(only.lastError?.code).toBe('SYNC_RETRIES_EXHAUSTED')
  })

  it('never auto-retries a permanent rejection', async () => {
    const outcome = await replayOnce(vault([entry()]), async () => [
      result({
        status: 'REJECTED',
        resourceId: null,
        result: null,
        error: { code: 'CATEGORY_TYPE_MISMATCH', detail: 'Categoria incompatível.' },
      }),
    ])

    const [only] = outcome.payload.outbox
    expect(only.status).toBe('FAILED_PERMANENT')
    expect(only.nextAttemptAt).toBeNull()
    expect(only.lastError?.code).toBe('CATEGORY_TYPE_MISMATCH')
    expect(isDue(only)).toBe(false)
  })

  it('pauses a conflicting mutation and keeps the server snapshot for the user', async () => {
    const outcome = await replayOnce(vault([entry({ operation: 'UPDATE', baseVersion: 0 })]), async () => [
      result({
        status: 'CONFLICT',
        operation: 'UPDATE',
        resourceId: null,
        result: null,
        conflict: {
          conflictType: 'VERSION_MISMATCH',
          localBaseVersion: 0,
          serverVersion: 3,
          serverSnapshot: { amount: 80, description: 'Do servidor' },
          resolutionOptions: ['KEEP_SERVER', 'APPLY_LOCAL', 'EDIT_AND_RETRY', 'DISCARD_LOCAL'],
          detail: 'Alterado em outro dispositivo.',
        },
      }),
    ])

    expect(outcome.conflicts).toBe(1)
    const [only] = outcome.payload.outbox
    expect(only.status).toBe('CONFLICT')
    expect(only.conflict?.serverVersion).toBe(3)
    expect(only.conflict?.serverSnapshot).toEqual({ amount: 80, description: 'Do servidor' })
    expect(isDue(only)).toBe(false)
  })

  it('holds a child whose parent has not landed rather than failing it', async () => {
    const child = entry({ dependencies: [] })
    const outcome = await replayOnce(vault([child]), async () => [
      result({
        status: 'DEPENDENCY_MISSING',
        resourceId: null,
        result: null,
        error: { code: 'SYNC_DEPENDENCY_MISSING', detail: 'O item ainda não foi sincronizado.' },
      }),
    ])

    const [only] = outcome.payload.outbox
    expect(only.status).toBe('BLOCKED')
    expect(only.lastError?.code).toBe('SYNC_DEPENDENCY_MISSING')
  })

  it('retries when the server skips a result instead of assuming success', async () => {
    const outcome = await replayOnce(vault([entry()]), async () => [])
    const [only] = outcome.payload.outbox
    expect(only.status).toBe('FAILED_RETRYABLE')
    expect(only.lastError?.detail).toContain('não respondeu')
  })

  it('turns a dependency cycle into a permanent, explained failure', async () => {
    const left = entry({ clientMutationId: 'm-a', clientResourceId: 'a', dependencies: ['b'] })
    const right = entry({ clientMutationId: 'm-b', clientResourceId: 'b', dependencies: ['a'] })
    const send = vi.fn(async () => [] as WireResult[])

    const outcome = await replayOnce(vault([left, right]), send)

    expect(send).not.toHaveBeenCalled()
    expect(outcome.sent).toBe(false)
    expect(outcome.payload.outbox.every((item) => item.status === 'FAILED_PERMANENT')).toBe(true)
    expect(outcome.payload.outbox[0].lastError?.code).toBe('SYNC_DEPENDENCY_CYCLE')
  })

  it('sends a bounded batch and leaves the rest queued', async () => {
    const many = Array.from({ length: REPLAY_BATCH_SIZE + 5 }, (_, index) =>
      entry({
        clientMutationId: `m-${index}`,
        clientResourceId: `r-${index}`,
        createdAt: `2026-07-01T00:00:${String(index).padStart(2, '0')}.000Z`,
      }),
    )
    const send = vi.fn(async (envelopes: { clientMutationId: string }[]) =>
      envelopes.map((envelope) =>
        result({ clientMutationId: envelope.clientMutationId, resourceId: 1 }),
      ),
    )

    const outcome = await replayOnce(vault(many), send)
    expect(send.mock.calls[0][0]).toHaveLength(REPLAY_BATCH_SIZE)
    expect(outcome.payload.outbox).toHaveLength(5)
  })

  it('sends a parent ahead of its child in the same batch', async () => {
    const child = entry({
      clientMutationId: 'm-option',
      clientResourceId: 'option',
      dependencies: ['item'],
      createdAt: '2026-07-01T00:00:01.000Z',
    })
    const parent = entry({
      clientMutationId: 'm-item',
      clientResourceId: 'item',
      createdAt: '2026-07-01T00:00:02.000Z',
    })
    const send = vi.fn(async (envelopes: { clientMutationId: string }[]) =>
      envelopes.map((envelope) => result({ clientMutationId: envelope.clientMutationId })),
    )

    await replayOnce(vault([child, parent]), send)
    expect(send.mock.calls[0][0].map((envelope) => envelope.clientMutationId)).toEqual([
      'm-item',
      'm-option',
    ])
  })
})

describe('backoff', () => {
  it('grows exponentially and never exceeds the ceiling', () => {
    const noJitter = () => 0.5
    expect(backoffDelay(1, noJitter)).toBe(RETRY_POLICY.baseDelayMs)
    expect(backoffDelay(2, noJitter)).toBe(RETRY_POLICY.baseDelayMs * 2)
    expect(backoffDelay(20, noJitter)).toBe(RETRY_POLICY.maxDelayMs)
  })

  it('spreads reconnect storms with jitter', () => {
    const low = backoffDelay(5, () => 0)
    const high = backoffDelay(5, () => 1)
    expect(low).toBeLessThan(high)
    expect(low).toBeGreaterThanOrEqual(RETRY_POLICY.baseDelayMs)
  })

  it('only considers an entry due once its delay has elapsed', () => {
    const future = entry({
      status: 'FAILED_RETRYABLE',
      nextAttemptAt: new Date(Date.now() + 60_000).toISOString(),
    })
    const past = entry({
      status: 'FAILED_RETRYABLE',
      nextAttemptAt: new Date(Date.now() - 1_000).toISOString(),
    })
    expect(isDue(future)).toBe(false)
    expect(isDue(past)).toBe(true)
    expect(isDue(entry({ status: 'PENDING' }))).toBe(true)
  })
})
