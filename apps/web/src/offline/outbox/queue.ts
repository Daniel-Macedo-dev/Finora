import type { VaultPayload } from '../vaultCrypto'
import {
  isActive,
  isSendable,
  newId,
  OUTBOX_LIMITS,
  type OutboxEntry,
  type ResourceMapping,
  type ResourceRef,
  type SyncHistoryEntry,
  type SyncOperation,
  type SyncResourceType,
} from './types'

/** What a domain hook asks the outbox to remember. */
export interface QueueRequest {
  resourceType: SyncResourceType
  operation: SyncOperation
  /** Stable identity of the resource being changed. */
  clientResourceId: string
  /** Server id when the resource already exists; absent for offline creations. */
  serverId?: number
  /** The version the user was looking at; null for a create. */
  baseVersion: number | null
  payload: Record<string, unknown>
  /** Client resource ids that must land first (parents created offline). */
  dependencies?: string[]
  label: string
}

export type QueueRejection =
  | 'QUEUE_FULL'
  | 'QUEUE_TOO_LARGE'
  | 'PAYLOAD_TOO_LARGE'
  | 'DELETE_PENDING'

export interface QueueResult {
  payload: VaultPayload
  /** The entry now representing this change; null when it cancelled out. */
  entry: OutboxEntry | null
  /** Mutation ids removed by compaction or dependent cancellation. */
  removed: string[]
}

export class QueueRejectedError extends Error {
  readonly reason: QueueRejection

  constructor(reason: QueueRejection, message: string) {
    super(message)
    this.name = 'QueueRejectedError'
    this.reason = reason
  }
}

const REJECTION_MESSAGES: Record<QueueRejection, string> = {
  QUEUE_FULL:
    'A fila offline está cheia. Sincronize as alterações pendentes antes de registrar novas.',
  QUEUE_TOO_LARGE:
    'A fila offline atingiu o tamanho máximo. Sincronize as alterações pendentes antes de registrar novas.',
  PAYLOAD_TOO_LARGE: 'Esta alteração é grande demais para ser guardada offline.',
  DELETE_PENDING:
    'Este registro já está marcado para exclusão offline. Descarte a exclusão antes de editá-lo.',
}

function reject(reason: QueueRejection): never {
  throw new QueueRejectedError(reason, REJECTION_MESSAGES[reason])
}

function serializedSize(value: unknown): number {
  return new TextEncoder().encode(JSON.stringify(value)).length
}

/** Entries compaction is allowed to touch: never one already in flight. */
function isCompactable(entry: OutboxEntry): boolean {
  return entry.status === 'PENDING' || entry.status === 'FAILED_RETRYABLE' || entry.status === 'BLOCKED'
}

/**
 * Folds a new change into the queue.
 *
 * Compaction is not an optimization here — it is what keeps the queue honest.
 * A user who creates a transaction offline, corrects it twice and then deletes
 * it has, in the end, done nothing; sending four mutations would make the
 * server briefly hold values the user already abandoned, and would produce four
 * chances to conflict where there should be none.
 *
 * The rules only ever fold operations on the *same* resource, and never touch
 * an entry the server may already have seen.
 */
export function queueMutation(
  vault: VaultPayload,
  request: QueueRequest,
  now = new Date().toISOString(),
): QueueResult {
  if (serializedSize(request.payload) > OUTBOX_LIMITS.maxPayloadBytes) {
    reject('PAYLOAD_TOO_LARGE')
  }

  const own = vault.outbox.filter(
    (entry) => entry.clientResourceId === request.clientResourceId && isCompactable(entry),
  )
  const pendingDelete = own.find((entry) => entry.operation === 'DELETE')
  if (pendingDelete && request.operation !== 'DELETE') {
    // Editing something already queued for deletion is contradictory. Silently
    // dropping either side would lose an instruction the user gave, so the
    // conflict is surfaced instead.
    reject('DELETE_PENDING')
  }

  const pendingCreate = own.find((entry) => entry.operation === 'CREATE')
  const removed: string[] = []
  let rest = vault.outbox.filter((entry) => !own.includes(entry))

  let entry: OutboxEntry | null
  if (request.operation === 'DELETE' && pendingCreate) {
    // Created and deleted while offline: the server never heard of it, so the
    // whole chain — including anything queued underneath it — simply disappears.
    const cancelled = collectDependents(vault.outbox, request.clientResourceId)
    rest = rest.filter((candidate) => !cancelled.has(candidate.clientMutationId))
    own.forEach((candidate) => removed.push(candidate.clientMutationId))
    vault.outbox
      .filter((candidate) => cancelled.has(candidate.clientMutationId))
      .forEach((candidate) => removed.push(candidate.clientMutationId))
    entry = null
  } else {
    own.forEach((candidate) => removed.push(candidate.clientMutationId))
    entry = foldInto(own, request, now)
    rest = [...rest, entry]
  }

  const active = rest.filter(isActive)
  if (active.length > OUTBOX_LIMITS.maxEntries) reject('QUEUE_FULL')
  if (serializedSize(rest) > OUTBOX_LIMITS.maxOutboxBytes) reject('QUEUE_TOO_LARGE')

  return {
    payload: { ...vault, outbox: rest },
    entry,
    removed: [...new Set(removed)],
  }
}

/**
 * Produces the single entry that replaces everything previously queued for this
 * resource.
 *
 * The base version is deliberately taken from the *earliest* superseded update,
 * not the latest one. It records the state the user actually started from; a
 * later local edit never observed a new server version, so pretending it did
 * would silently claim agreement with a server change nobody saw.
 */
function foldInto(
  superseded: OutboxEntry[],
  request: QueueRequest,
  now: string,
): OutboxEntry {
  const create = superseded.find((entry) => entry.operation === 'CREATE')
  const earliest = superseded.at(0)
  const operation: SyncOperation = create ? 'CREATE' : request.operation
  const baseVersion = create ? null : (earliest?.baseVersion ?? request.baseVersion)
  const target: ResourceRef = create
    ? { clientResourceId: request.clientResourceId }
    : request.serverId != null
      ? { serverId: request.serverId }
      : { clientResourceId: request.clientResourceId }

  return {
    // A folded entry is a different request than the ones it replaces, so it
    // gets a new idempotency key. Reusing a superseded key would look, to the
    // server, like the same mutation arriving with different content.
    clientMutationId: newId(),
    resourceType: request.resourceType,
    operation,
    target,
    clientResourceId: request.clientResourceId,
    baseVersion,
    payload: request.payload,
    dependencies: request.dependencies ?? earliest?.dependencies ?? [],
    status: 'PENDING',
    createdAt: earliest?.createdAt ?? now,
    updatedAt: now,
    attemptCount: 0,
    nextAttemptAt: null,
    lastError: null,
    conflict: null,
    label: request.label,
  }
}

/** Every queued entry that (transitively) depends on a client resource id. */
export function collectDependents(
  entries: OutboxEntry[],
  clientResourceId: string,
): Set<string> {
  const doomedResources = new Set([clientResourceId])
  const doomedMutations = new Set<string>()
  let changed = true
  while (changed) {
    changed = false
    for (const entry of entries) {
      if (doomedMutations.has(entry.clientMutationId)) continue
      const dependsOnDoomed = entry.dependencies.some((id) => doomedResources.has(id))
      if (!dependsOnDoomed) continue
      doomedMutations.add(entry.clientMutationId)
      if (!doomedResources.has(entry.clientResourceId)) {
        doomedResources.add(entry.clientResourceId)
      }
      changed = true
    }
  }
  return doomedMutations
}

export interface ReplayOrder {
  /** Entries that may be sent, parents before children. */
  ready: OutboxEntry[]
  /** Entries waiting on a parent that is itself still queued. */
  blocked: OutboxEntry[]
  /** Entries in a dependency cycle: unsendable forever without intervention. */
  cyclic: OutboxEntry[]
}

/**
 * Orders the queue so a parent is never sent after its child.
 *
 * A dependency is satisfied when the parent has a server id (it was applied and
 * mapped) or when the parent is itself in this batch ahead of the child — the
 * server processes a batch in order, committing each mutation separately, so a
 * child can resolve a parent applied moments earlier in the same request.
 *
 * A cycle cannot resolve by waiting, so it is separated out rather than retried:
 * a queue that keeps re-sending an unsatisfiable batch is a retry storm with
 * extra steps.
 */
export function orderForReplay(
  entries: OutboxEntry[],
  mappings: ResourceMapping[],
): ReplayOrder {
  const mapped = new Set(mappings.map((mapping) => mapping.clientResourceId))
  const candidates = entries.filter(isSendable)
  const queuedResources = new Set(candidates.map((entry) => entry.clientResourceId))

  const ready: OutboxEntry[] = []
  const satisfied = new Set(mapped)
  let remaining = [...candidates].sort((left, right) =>
    left.createdAt === right.createdAt
      ? left.clientMutationId.localeCompare(right.clientMutationId)
      : left.createdAt.localeCompare(right.createdAt),
  )

  let progressed = true
  while (progressed && remaining.length > 0) {
    progressed = false
    const next: OutboxEntry[] = []
    for (const entry of remaining) {
      const ok = entry.dependencies.every((id) => satisfied.has(id))
      if (ok) {
        ready.push(entry)
        satisfied.add(entry.clientResourceId)
        progressed = true
      } else {
        next.push(entry)
      }
    }
    remaining = next
  }

  // Whatever is left either waits on a parent that is not queued at all
  // (blocked) or on one that transitively waits on it (a cycle).
  const blocked: OutboxEntry[] = []
  const cyclic: OutboxEntry[] = []
  for (const entry of remaining) {
    const waitsOnQueued = entry.dependencies.some(
      (id) => !satisfied.has(id) && queuedResources.has(id),
    )
    if (waitsOnQueued) {
      cyclic.push(entry)
    } else {
      blocked.push(entry)
    }
  }
  return { ready, blocked, cyclic }
}

/**
 * Rewrites an entry's target using what replay has learned.
 *
 * A resource created offline is addressed by its client id until the server
 * assigns one; afterwards the server id is used directly, which keeps later
 * mutations independent of the mapping surviving.
 */
export function resolveTarget(entry: OutboxEntry, mappings: ResourceMapping[]): ResourceRef {
  if (entry.operation === 'CREATE') {
    return { clientResourceId: entry.clientResourceId }
  }
  if (entry.target.serverId != null) {
    return { serverId: entry.target.serverId }
  }
  const mapping = mappings.find(
    (candidate) => candidate.clientResourceId === entry.clientResourceId,
  )
  return mapping ? { serverId: mapping.serverId } : { clientResourceId: entry.clientResourceId }
}

/** Records a finished entry in the bounded local log and drops it from the queue. */
export function archive(
  vault: VaultPayload,
  entry: OutboxEntry,
  outcome: SyncHistoryEntry['outcome'],
  detail?: string,
  now = new Date().toISOString(),
): VaultPayload {
  const record: SyncHistoryEntry = {
    clientMutationId: entry.clientMutationId,
    resourceType: entry.resourceType,
    operation: entry.operation,
    label: entry.label,
    outcome,
    at: now,
    ...(detail ? { detail } : {}),
  }
  return {
    ...vault,
    outbox: vault.outbox.filter(
      (candidate) => candidate.clientMutationId !== entry.clientMutationId,
    ),
    // Newest first, hard-capped: the log is a courtesy, not an audit trail, and
    // the server holds the authoritative one.
    syncHistory: [record, ...vault.syncHistory].slice(0, OUTBOX_LIMITS.maxHistory),
  }
}

/** Adds or refreshes a mapping learned from an applied (or already applied) result. */
export function rememberMapping(
  vault: VaultPayload,
  mapping: ResourceMapping,
): VaultPayload {
  const others = vault.resourceMappings.filter(
    (candidate) => candidate.clientResourceId !== mapping.clientResourceId,
  )
  return { ...vault, resourceMappings: [...others, mapping] }
}

/**
 * Drops mappings no active entry and no history record still refers to.
 *
 * Mappings are small but unbounded otherwise, and a mapping is only useful
 * while something can still name the resource by its client id.
 */
export function pruneMappings(vault: VaultPayload): VaultPayload {
  const referenced = new Set<string>()
  for (const entry of vault.outbox) {
    referenced.add(entry.clientResourceId)
    entry.dependencies.forEach((id) => referenced.add(id))
  }
  return {
    ...vault,
    resourceMappings: vault.resourceMappings.filter((mapping) =>
      referenced.has(mapping.clientResourceId),
    ),
  }
}

export function activeEntries(vault: VaultPayload): OutboxEntry[] {
  return vault.outbox.filter(isActive)
}

export function countByStatus(vault: VaultPayload) {
  const entries = activeEntries(vault)
  return {
    total: entries.length,
    pending: entries.filter((entry) => entry.status === 'PENDING').length,
    blocked: entries.filter((entry) => entry.status === 'BLOCKED').length,
    syncing: entries.filter((entry) => entry.status === 'SYNCING').length,
    conflicts: entries.filter((entry) => entry.status === 'CONFLICT').length,
    retryable: entries.filter((entry) => entry.status === 'FAILED_RETRYABLE').length,
    permanent: entries.filter((entry) => entry.status === 'FAILED_PERMANENT').length,
  }
}

/** True when logging out or deleting the vault would destroy unsynced work. */
export function hasUnsyncedWork(vault: VaultPayload): boolean {
  return activeEntries(vault).length > 0
}
