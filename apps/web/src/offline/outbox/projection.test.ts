import { describe, expect, it } from 'vitest'
import { localId, PENDING_LABELS, projectList } from './projection'
import type { OutboxEntry } from './types'

interface Row {
  id: number
  description: string
  amount: number
}

const serverRows: Row[] = [
  { id: 1, description: 'Do servidor', amount: 10 },
  { id: 2, description: 'Intocada', amount: 20 },
]

function entry(overrides: Partial<OutboxEntry> = {}): OutboxEntry {
  return {
    clientMutationId: 'mutation-1',
    resourceType: 'TRANSACTION',
    operation: 'CREATE',
    target: { clientResourceId: 'resource-1' },
    clientResourceId: 'resource-1',
    baseVersion: null,
    payload: { description: 'Criada offline', amount: 30 },
    dependencies: [],
    status: 'PENDING',
    createdAt: '2026-07-01T00:00:00.000Z',
    updatedAt: '2026-07-01T00:00:00.000Z',
    attemptCount: 0,
    nextAttemptAt: null,
    lastError: null,
    conflict: null,
    label: 'Criada offline',
    ...overrides,
  }
}

const build = (base: Row | null, item: OutboxEntry): Row | null => {
  const payload = item.payload as Partial<Row>
  if (!base) {
    return {
      id: localId(item.clientResourceId),
      description: String(payload.description ?? item.label),
      amount: Number(payload.amount ?? 0),
    }
  }
  return { ...base, ...payload, id: base.id }
}

describe('pending projection', () => {
  it('shows a locally created row first, marked as created offline', () => {
    const projected = projectList(serverRows, [entry()], 'TRANSACTION', build)
    expect(projected).toHaveLength(3)
    expect(projected[0].pending).toBe('CREATED')
    expect(projected[0].item.description).toBe('Criada offline')
    expect(projected[0].item.id).toBeLessThan(0)
    expect(PENDING_LABELS.CREATED).toBe('Criado offline')
  })

  it('shows pending values on an edited row without touching the others', () => {
    const edit = entry({
      operation: 'UPDATE',
      target: { serverId: 1 },
      payload: { description: 'Editada offline', amount: 99 },
    })
    const projected = projectList(serverRows, [edit], 'TRANSACTION', build)
    const edited = projected.find((row) => row.item.id === 1)
    const untouched = projected.find((row) => row.item.id === 2)

    expect(edited?.pending).toBe('UPDATED')
    expect(edited?.item.description).toBe('Editada offline')
    expect(untouched?.pending).toBeNull()
    expect(untouched?.item.description).toBe('Intocada')
  })

  it('keeps a locally deleted row visible and marked', () => {
    const removal = entry({ operation: 'DELETE', target: { serverId: 1 }, payload: {} })
    const projected = projectList(serverRows, [removal], 'TRANSACTION', build)
    const deleted = projected.find((row) => row.item.id === 1)

    // Hiding it outright would make a failed sync look like data loss.
    expect(deleted?.pending).toBe('DELETED')
    expect(deleted?.item.description).toBe('Do servidor')
  })

  it('surfaces conflict and failure over the operation kind', () => {
    const conflicted = entry({
      operation: 'UPDATE',
      target: { serverId: 1 },
      status: 'CONFLICT',
    })
    const failed = entry({
      clientMutationId: 'mutation-2',
      operation: 'UPDATE',
      target: { serverId: 2 },
      status: 'FAILED_PERMANENT',
    })
    const projected = projectList(serverRows, [conflicted, failed], 'TRANSACTION', build)
    expect(projected.find((row) => row.item.id === 1)?.pending).toBe('CONFLICT')
    expect(projected.find((row) => row.item.id === 2)?.pending).toBe('FAILED')
  })

  it('ignores entries belonging to another resource type', () => {
    const budget = entry({ resourceType: 'BUDGET' })
    const projected = projectList(serverRows, [budget], 'TRANSACTION', build)
    expect(projected).toHaveLength(2)
    expect(projected.every((row) => row.pending === null)).toBe(true)
  })

  it('ignores finished entries so the server view returns after a discard', () => {
    const applied = entry({ status: 'APPLIED' })
    const discarded = entry({ clientMutationId: 'mutation-2', status: 'DISCARDED' })
    const projected = projectList(serverRows, [applied, discarded], 'TRANSACTION', build)
    expect(projected).toEqual(serverRows.map((item) => ({ item, pending: null, entry: null })))
  })

  it('gives locally created rows ids that can never collide with server ids', () => {
    expect(localId('resource-a')).toBeLessThan(0)
    expect(localId('resource-a')).toBe(localId('resource-a'))
    expect(localId('resource-a')).not.toBe(localId('resource-b'))
  })
})
