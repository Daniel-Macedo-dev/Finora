import type { OutboxEntry, OutboxStatus, SyncResourceType } from './types'

/**
 * How a queued mutation should be shown next to the server's own data.
 *
 * The base snapshot is never rewritten. Instead the queue is projected over it
 * at render time, so discarding a pending change restores the server view
 * exactly, with nothing to undo.
 */
export type PendingState =
  | 'CREATED'
  | 'UPDATED'
  | 'DELETED'
  | 'SYNCING'
  | 'CONFLICT'
  | 'FAILED'

export interface Projected<T> {
  item: T
  /** Null when the row is exactly what the server last sent. */
  pending: PendingState | null
  entry: OutboxEntry | null
}

/** Portuguese labels. Status is always text — never colour alone. */
export const PENDING_LABELS: Record<PendingState, string> = {
  CREATED: 'Criado offline',
  UPDATED: 'Pendente',
  DELETED: 'Exclusão pendente',
  SYNCING: 'Sincronizando',
  CONFLICT: 'Conflito',
  FAILED: 'Falha',
}

function stateFor(entry: OutboxEntry): PendingState {
  switch (entry.status) {
    case 'CONFLICT':
      return 'CONFLICT'
    case 'FAILED_PERMANENT':
    case 'FAILED_RETRYABLE':
      return 'FAILED'
    case 'SYNCING':
      return 'SYNCING'
    default:
      return entry.operation === 'CREATE'
        ? 'CREATED'
        : entry.operation === 'DELETE'
          ? 'DELETED'
          : 'UPDATED'
  }
}

const VISIBLE_STATUSES: readonly OutboxStatus[] = [
  'PENDING',
  'BLOCKED',
  'SYNCING',
  'CONFLICT',
  'FAILED_RETRYABLE',
  'FAILED_PERMANENT',
]

/**
 * Locally created rows need an id the list can key on before the server has
 * assigned one. Negative ids are used so they can never collide with a real
 * one, and so any code that accidentally sends one fails loudly instead of
 * addressing someone else's row.
 */
export function localId(clientResourceId: string): number {
  let hash = 0
  for (let index = 0; index < clientResourceId.length; index += 1) {
    hash = (hash * 31 + clientResourceId.charCodeAt(index)) | 0
  }
  return -Math.abs(hash === 0 ? 1 : hash)
}

/**
 * Combines the last server snapshot with the queue.
 *
 * Locally created resources appear first (they are the newest thing the user
 * did), edits show their pending values, and deletions stay visible but marked
 * — hiding them outright would make a failed sync look like data loss.
 */
export function projectList<T extends { id: number }>(
  serverItems: readonly T[],
  entries: readonly OutboxEntry[],
  resourceType: SyncResourceType,
  build: (base: T | null, entry: OutboxEntry) => T | null,
): Projected<T>[] {
  const relevant = entries.filter(
    (entry) => entry.resourceType === resourceType && VISIBLE_STATUSES.includes(entry.status),
  )
  const byServerId = new Map<number, OutboxEntry>()
  const creations: OutboxEntry[] = []
  for (const entry of relevant) {
    if (entry.operation === 'CREATE') {
      creations.push(entry)
    } else if (entry.target.serverId != null) {
      byServerId.set(entry.target.serverId, entry)
    }
  }

  const existing: Projected<T>[] = serverItems.map((item) => {
    const entry = byServerId.get(item.id)
    if (!entry) return { item, pending: null, entry: null }
    const projected = entry.operation === 'DELETE' ? item : (build(item, entry) ?? item)
    return { item: projected, pending: stateFor(entry), entry }
  })

  const created: Projected<T>[] = creations.flatMap((entry) => {
    const item = build(null, entry)
    return item ? [{ item, pending: stateFor(entry), entry }] : []
  })

  return [...created, ...existing]
}

/**
 * Whether derived figures on this screen may be out of date.
 *
 * Dashboards, forecasts and balances stay on the last server snapshot on
 * purpose: recomputing them locally would mean maintaining a second financial
 * engine in the browser, and a subtly different one is worse than an honestly
 * stale one. So the screens say so instead of pretending.
 */
export const STALE_TOTALS_WARNING =
  'Alguns totais ainda não incluem alterações offline pendentes.'
