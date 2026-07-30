import 'fake-indexeddb/auto'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { VaultProvider, useVault } from './VaultProvider'
import { deleteVault, loadVault } from './vaultStorage'
import { unlockVault } from './vaultCrypto'
import type { WireResult } from './outbox/replay'
import { resetCoordination } from './outbox/coordination'

const password = 'senha-local-segura'
const user = {
  id: 7,
  displayName: 'Dona',
  email: 'owner@example.test',
  createdAt: '2026-07-01T00:00:00.000Z',
}

/**
 * Exercises the provider the way the app does: enable, queue, persist, replay,
 * lock. Nothing here reaches into the vault's internals, so what it proves is
 * what a user would actually experience.
 */
function harness(send: (envelopes: unknown[]) => Promise<WireResult[]>) {
  const api = { current: null as ReturnType<typeof useVault> | null }
  function Probe() {
    api.current = useVault()
    return null
  }
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <VaultProvider send={send as never}>
        <Probe />
      </VaultProvider>
    </QueryClientProvider>,
  )
  return api
}

function appliedResult(clientMutationId: string, resourceId: number): WireResult {
  return {
    clientMutationId,
    resourceType: 'TRANSACTION',
    operation: 'CREATE',
    status: 'APPLIED',
    clientResourceId: null,
    resourceId,
    version: 0,
    result: { id: resourceId },
    conflict: null,
    error: null,
  }
}

beforeEach(async () => {
  await deleteVault()
  resetCoordination()
  // The dataset fetch is the only network the provider does while enabling.
  vi.stubGlobal(
    'fetch',
    // A fresh Response per call: a body can only be read once, and enabling
    // the vault fetches the whole prepared dataset.
    vi.fn(async () =>
      new Response(JSON.stringify([]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  )
})

async function enabledVault(send = async () => [] as WireResult[]) {
  const api = harness(send)
  await waitFor(() => expect(api.current?.state).toBe('ABSENT'))
  await act(async () => {
    await api.current!.enable(user, password)
  })
  await waitFor(() => expect(api.current?.state).toBe('UNLOCKED_ONLINE'))
  return api
}

describe('vault outbox lifecycle', () => {
  it('persists a queued mutation as ciphertext that reveals nothing', async () => {
    const api = await enabledVault()

    await act(async () => {
      await api.current!.queue({
        resourceType: 'TRANSACTION',
        operation: 'CREATE',
        clientResourceId: 'resource-1',
        baseVersion: null,
        payload: { amount: 1234.56, description: 'Compra sigilosa' },
        label: 'Compra sigilosa',
      })
    })

    expect(api.current!.counts.total).toBe(1)
    const stored = await loadVault()
    expect(JSON.stringify(stored)).not.toContain('Compra sigilosa')
    expect(JSON.stringify(stored)).not.toContain('1234.56')

    // It survives a reload because it really is on disk, not just in memory.
    const { payload } = await unlockVault(stored!, password)
    expect(payload.outbox).toHaveLength(1)
    expect(payload.outbox[0].label).toBe('Compra sigilosa')
  })

  it('replays a queued mutation and archives it as applied', async () => {
    const send = vi.fn(async (envelopes: { clientMutationId: string }[]) =>
      envelopes.map((envelope) => appliedResult(envelope.clientMutationId, 42)),
    )
    const api = await enabledVault(send as never)

    await act(async () => {
      await api.current!.queue({
        resourceType: 'TRANSACTION',
        operation: 'CREATE',
        clientResourceId: 'resource-1',
        baseVersion: null,
        payload: { amount: 10 },
        label: 'Mercado',
      })
    })
    await act(async () => {
      await api.current!.replay()
    })

    expect(send).toHaveBeenCalledTimes(1)
    expect(api.current!.counts.total).toBe(0)
    expect(api.current!.lastSyncAt).not.toBeNull()
  })

  it('locking clears decrypted state but keeps the encrypted queue on disk', async () => {
    const api = await enabledVault()
    await act(async () => {
      await api.current!.queue({
        resourceType: 'GOAL',
        operation: 'CREATE',
        clientResourceId: 'goal-1',
        baseVersion: null,
        payload: { name: 'Reserva' },
        label: 'Reserva',
      })
    })

    act(() => api.current!.lock())

    expect(api.current!.state).toBe('LOCKED')
    expect(api.current!.entries).toHaveLength(0)
    expect(api.current!.owner).toBeNull()

    // The work is still there — just unreadable without the password.
    const stored = await loadVault()
    const { payload } = await unlockVault(stored!, password)
    expect(payload.outbox).toHaveLength(1)
  })

  it('refuses to replay while locked', async () => {
    const send = vi.fn(async () => [] as WireResult[])
    const api = await enabledVault(send as never)
    await act(async () => {
      await api.current!.queue({
        resourceType: 'GOAL',
        operation: 'CREATE',
        clientResourceId: 'goal-1',
        baseVersion: null,
        payload: { name: 'Reserva' },
        label: 'Reserva',
      })
    })
    act(() => api.current!.lock())

    await act(async () => {
      await expect(api.current!.replay()).resolves.toBeNull()
    })
    expect(send).not.toHaveBeenCalled()
  })

  it('unlocks again and finds the queue exactly as it was', async () => {
    const api = await enabledVault()
    await act(async () => {
      await api.current!.queue({
        resourceType: 'BUDGET',
        operation: 'CREATE',
        clientResourceId: 'budget-1',
        baseVersion: null,
        payload: { limitAmount: 800 },
        label: 'Alimentação · 2026-07',
      })
    })
    act(() => api.current!.lock())

    await act(async () => {
      await api.current!.unlock(password, true)
    })
    expect(api.current!.state).toBe('UNLOCKED_ONLINE')
    expect(api.current!.entries).toHaveLength(1)
    expect(api.current!.entries[0].label).toBe('Alimentação · 2026-07')
  })

  it('reports unsynchronized work so logout can ask before deleting it', async () => {
    const api = await enabledVault()
    expect(api.current!.hasPendingWork).toBe(false)
    await act(async () => {
      await api.current!.queue({
        resourceType: 'GOAL',
        operation: 'CREATE',
        clientResourceId: 'goal-1',
        baseVersion: null,
        payload: { name: 'Reserva' },
        label: 'Reserva',
      })
    })
    expect(api.current!.hasPendingWork).toBe(true)
  })

  it('discarding an entry removes it and leaves the server view intact', async () => {
    const api = await enabledVault()
    await act(async () => {
      await api.current!.queue({
        resourceType: 'GOAL',
        operation: 'CREATE',
        clientResourceId: 'goal-1',
        baseVersion: null,
        payload: { name: 'Reserva' },
        label: 'Reserva',
      })
    })
    const [entry] = api.current!.entries

    await act(async () => {
      await api.current!.discard(entry.clientMutationId)
    })
    expect(api.current!.entries).toHaveLength(0)
    expect(api.current!.hasPendingWork).toBe(false)
  })

  it('applying local values after a conflict mints a new mutation id', async () => {
    const conflicting: WireResult = {
      clientMutationId: '',
      resourceType: 'TRANSACTION',
      operation: 'UPDATE',
      status: 'CONFLICT',
      clientResourceId: null,
      resourceId: null,
      version: null,
      result: null,
      conflict: {
        conflictType: 'VERSION_MISMATCH',
        localBaseVersion: 0,
        serverVersion: 5,
        serverSnapshot: { amount: 80 },
        resolutionOptions: ['KEEP_SERVER', 'APPLY_LOCAL', 'EDIT_AND_RETRY', 'DISCARD_LOCAL'],
        detail: 'Alterado em outro dispositivo.',
      },
      error: null,
    }
    const send = vi.fn(async (envelopes: { clientMutationId: string }[]) => [
      { ...conflicting, clientMutationId: envelopes[0].clientMutationId },
    ])
    const api = await enabledVault(send as never)

    await act(async () => {
      await api.current!.queue({
        resourceType: 'TRANSACTION',
        operation: 'UPDATE',
        clientResourceId: '11',
        serverId: 11,
        baseVersion: 0,
        payload: { amount: 25 },
        label: 'Mercado',
      })
    })
    await act(async () => {
      await api.current!.replay()
    })

    const conflicted = api.current!.entries[0]
    expect(conflicted.status).toBe('CONFLICT')
    expect(conflicted.conflict?.serverVersion).toBe(5)

    await act(async () => {
      await api.current!.resolve(conflicted.clientMutationId, 'APPLY_LOCAL')
    })

    const retried = api.current!.entries[0]
    // A new key, because the content the server refused is being replaced by a
    // different request; and the version the server just reported, because the
    // old one would simply conflict again.
    expect(retried.clientMutationId).not.toBe(conflicted.clientMutationId)
    expect(retried.baseVersion).toBe(5)
    expect(retried.status).toBe('PENDING')
    expect(retried.conflict).toBeNull()
  })

  it('keeping the server value drops the local change entirely', async () => {
    const api = await enabledVault()
    await act(async () => {
      await api.current!.queue({
        resourceType: 'TRANSACTION',
        operation: 'UPDATE',
        clientResourceId: '11',
        serverId: 11,
        baseVersion: 0,
        payload: { amount: 25 },
        label: 'Mercado',
      })
    })
    const [entry] = api.current!.entries

    await act(async () => {
      await api.current!.resolve(entry.clientMutationId, 'KEEP_SERVER')
    })
    expect(api.current!.entries).toHaveLength(0)
  })

  it('editing and retrying carries the revised payload under a new key', async () => {
    const api = await enabledVault()
    await act(async () => {
      await api.current!.queue({
        resourceType: 'TRANSACTION',
        operation: 'UPDATE',
        clientResourceId: '11',
        serverId: 11,
        baseVersion: 0,
        payload: { amount: 25 },
        label: 'Mercado',
      })
    })
    const [entry] = api.current!.entries

    await act(async () => {
      await api.current!.reviseAndRetry(entry.clientMutationId, { amount: 31 })
    })

    const revised = api.current!.entries[0]
    expect(revised.payload).toEqual({ amount: 31 })
    expect(revised.clientMutationId).not.toBe(entry.clientMutationId)
    expect(revised.status).toBe('PENDING')
  })

  it('removing the vault deletes the encrypted record and the queue with it', async () => {
    const api = await enabledVault()
    await act(async () => {
      await api.current!.queue({
        resourceType: 'GOAL',
        operation: 'CREATE',
        clientResourceId: 'goal-1',
        baseVersion: null,
        payload: { name: 'Reserva' },
        label: 'Reserva',
      })
    })

    await act(async () => {
      await api.current!.remove()
    })
    expect(api.current!.state).toBe('ABSENT')
    expect(await loadVault()).toBeNull()
  })
})
