/**
 * The shape of the encrypted mutation outbox.
 *
 * Everything declared here lives inside the vault's authenticated ciphertext.
 * No part of it may reach localStorage, Cache Storage or the Service Worker:
 * a queued mutation is financial data, and it is data the server does not have
 * yet, which makes it the only copy.
 */

export type SyncResourceType =
  | 'TRANSACTION'
  | 'BUDGET'
  | 'GOAL'
  | 'WISHLIST_ITEM'
  | 'PURCHASE_OPTION'
  | 'PRICE_SNAPSHOT'

export type SyncOperation = 'CREATE' | 'UPDATE' | 'DELETE'

/**
 * Local lifecycle of one queued mutation.
 *
 * `FAILED_RETRYABLE` and `FAILED_PERMANENT` are deliberately separate: the
 * first is the replay engine's business, the second is the user's. Retrying a
 * permanently invalid mutation forever would be a retry storm that never
 * converges, and would hide a decision the person needs to make.
 */
export type OutboxStatus =
  | 'PENDING'
  | 'BLOCKED'
  | 'SYNCING'
  | 'CONFLICT'
  | 'FAILED_RETRYABLE'
  | 'FAILED_PERMANENT'
  | 'APPLIED'
  | 'DISCARDED'

export type ConflictType =
  | 'VERSION_MISMATCH'
  | 'REMOTE_DELETED'
  | 'RESOURCE_ALREADY_EXISTS'
  | 'IDEMPOTENCY_KEY_REUSED'
  | 'DEPENDENCY_CHANGED'

export type ResolutionOption =
  | 'KEEP_SERVER'
  | 'APPLY_LOCAL'
  | 'EDIT_AND_RETRY'
  | 'DISCARD_LOCAL'

/** Exactly one identity: a server id, or the id this device generated. */
export interface ResourceRef {
  serverId?: number
  clientResourceId?: string
}

export interface OutboxError {
  code: string
  detail: string
  fieldErrors?: { field: string; message: string }[]
}

export interface OutboxConflict {
  conflictType: ConflictType
  localBaseVersion: number | null
  serverVersion: number | null
  /** The server's public representation of the resource; never entity internals. */
  serverSnapshot: Record<string, unknown> | null
  resolutionOptions: ResolutionOption[]
  detail: string
}

export interface OutboxEntry {
  /** Stable idempotency key. Reused verbatim on every retry, never regenerated. */
  clientMutationId: string
  resourceType: SyncResourceType
  operation: SyncOperation
  target: ResourceRef
  /** Identity of the resource itself — the anchor dependents reference. */
  clientResourceId: string
  /** The version this edit was based on; null for a create. */
  baseVersion: number | null
  payload: Record<string, unknown>
  /** Client resource ids that must be applied before this entry can be sent. */
  dependencies: string[]
  status: OutboxStatus
  createdAt: string
  updatedAt: string
  attemptCount: number
  nextAttemptAt: string | null
  lastError: OutboxError | null
  conflict: OutboxConflict | null
  /** Short human label ("Mercado", "Alimentação · jul/2026") shown in the queue. */
  label: string
}

export interface ResourceMapping {
  resourceType: SyncResourceType
  clientResourceId: string
  serverId: number
  serverVersion: number | null
  mappedAt: string
}

export type SyncOutcome = 'APPLIED' | 'DISCARDED' | 'KEPT_SERVER' | 'FAILED'

export interface SyncHistoryEntry {
  clientMutationId: string
  resourceType: SyncResourceType
  operation: SyncOperation
  label: string
  outcome: SyncOutcome
  at: string
  detail?: string
}

export interface SyncPreferences {
  /** Whether reconnecting may trigger one replay attempt without being asked. */
  autoSync: boolean
  lastSyncAt: string | null
}

/**
 * Hard bounds. The vault is a browser-owned store that the browser may evict,
 * so it has to stay small and predictable — and an unbounded queue would turn
 * one forgotten offline session into an unopenable vault.
 */
export const OUTBOX_LIMITS = {
  /** Active (unfinished) entries. Reaching this blocks new offline mutations. */
  maxEntries: 200,
  /** Completed entries kept for the local sync log. */
  maxHistory: 100,
  /** Serialized size of one payload; the server rejects anything larger too. */
  maxPayloadBytes: 64 * 1024,
  /** Serialized size of the whole active queue. */
  maxOutboxBytes: 2 * 1024 * 1024,
} as const

/** Entries the replay engine still owns, as opposed to finished history. */
export const ACTIVE_STATUSES: readonly OutboxStatus[] = [
  'PENDING',
  'BLOCKED',
  'SYNCING',
  'CONFLICT',
  'FAILED_RETRYABLE',
  'FAILED_PERMANENT',
]

export function isActive(entry: OutboxEntry): boolean {
  return ACTIVE_STATUSES.includes(entry.status)
}

/** Entries the engine may send right now, ignoring dependency order. */
export function isSendable(entry: OutboxEntry): boolean {
  return entry.status === 'PENDING' || entry.status === 'FAILED_RETRYABLE'
}

export const DEFAULT_SYNC_PREFERENCES: SyncPreferences = {
  autoSync: true,
  lastSyncAt: null,
}

/**
 * Cryptographically random identity. `Math.random`, timestamps and counters are
 * all unusable here: a mutation id is an idempotency key, and a predictable or
 * colliding one would either duplicate a financial write or suppress a real one.
 */
export function newId(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
