import type { VaultPayload } from '../vaultCrypto'
import { archive, orderForReplay, rememberMapping, resolveTarget } from './queue'
import type {
  ConflictType,
  OutboxConflict,
  OutboxEntry,
  ResolutionOption,
  SyncOperation,
  SyncResourceType,
} from './types'

/** How many mutations one request carries. The server caps this at 25. */
export const REPLAY_BATCH_SIZE = 20

/**
 * Retry policy for genuinely temporary failures.
 *
 * Only the network and the server's own 5xx qualify. A rejected payload or a
 * conflict would fail identically forever, so retrying it would be a loop that
 * burns battery and hides a decision the user has to make.
 */
export const RETRY_POLICY = {
  maxAttempts: 8,
  baseDelayMs: 2_000,
  maxDelayMs: 5 * 60_000,
  /** Spreads reconnect storms across tabs and devices. */
  jitterRatio: 0.2,
} as const

export interface WireEnvelope {
  clientMutationId: string
  resourceType: SyncResourceType
  operation: SyncOperation
  target: { serverId?: number; clientResourceId?: string }
  baseVersion?: number
  payload: Record<string, unknown>
}

export interface WireResult {
  clientMutationId: string
  resourceType: SyncResourceType
  operation: SyncOperation
  status: 'APPLIED' | 'ALREADY_APPLIED' | 'CONFLICT' | 'REJECTED' | 'DEPENDENCY_MISSING'
  clientResourceId: string | null
  resourceId: number | null
  version: number | null
  result: Record<string, unknown> | null
  conflict: {
    conflictType: ConflictType
    localBaseVersion: number | null
    serverVersion: number | null
    serverSnapshot: Record<string, unknown> | null
    resolutionOptions: ResolutionOption[]
    detail: string
  } | null
  error: { code: string; detail: string; fieldErrors?: { field: string; message: string }[] } | null
}

/** Sends one batch. Rejecting means the transport failed, not the mutations. */
export type SendBatch = (envelopes: WireEnvelope[]) => Promise<WireResult[]>

export interface ReplayOutcome {
  payload: VaultPayload
  applied: number
  conflicts: number
  rejected: number
  /** Set when the transport itself failed and the batch must be retried. */
  transportError: string | null
  /** True when a batch was sent and there may be more waiting. */
  sent: boolean
}

export function backoffDelay(attemptCount: number, random = Math.random): number {
  const exponential = RETRY_POLICY.baseDelayMs * 2 ** Math.max(attemptCount - 1, 0)
  const capped = Math.min(exponential, RETRY_POLICY.maxDelayMs)
  const jitter = capped * RETRY_POLICY.jitterRatio * (random() * 2 - 1)
  return Math.max(RETRY_POLICY.baseDelayMs, Math.round(capped + jitter))
}

function toEnvelope(entry: OutboxEntry, vault: VaultPayload): WireEnvelope {
  const target = resolveTarget(entry, vault.resourceMappings)
  return {
    clientMutationId: entry.clientMutationId,
    resourceType: entry.resourceType,
    operation: entry.operation,
    target,
    ...(entry.baseVersion != null ? { baseVersion: entry.baseVersion } : {}),
    payload: entry.payload,
  }
}

function withEntry(
  vault: VaultPayload,
  clientMutationId: string,
  change: (entry: OutboxEntry) => OutboxEntry,
): VaultPayload {
  return {
    ...vault,
    outbox: vault.outbox.map((entry) =>
      entry.clientMutationId === clientMutationId ? change(entry) : entry,
    ),
  }
}

/**
 * Selects and sends one bounded batch, then folds the results back in.
 *
 * Nothing here decides *when* to run — that is the engine's job. This function
 * is deliberately a pure-ish step so the interesting behaviour (what a lost
 * response does, what a conflict does, what a dependency does) can be tested
 * without a browser, a timer or a network.
 */
export async function replayOnce(
  vault: VaultPayload,
  send: SendBatch,
  now = () => new Date().toISOString(),
): Promise<ReplayOutcome> {
  let next = vault
  const order = orderForReplay(next.outbox, next.resourceMappings)

  // A cycle cannot be waited out; it needs the user to discard something.
  for (const entry of order.cyclic) {
    next = withEntry(next, entry.clientMutationId, (current) => ({
      ...current,
      status: 'FAILED_PERMANENT',
      updatedAt: now(),
      lastError: {
        code: 'SYNC_DEPENDENCY_CYCLE',
        detail:
          'Estas alterações dependem umas das outras e não podem ser enviadas. '
          + 'Descarte uma delas para continuar.',
      },
    }))
  }
  for (const entry of order.blocked) {
    next = withEntry(next, entry.clientMutationId, (current) => ({
      ...current,
      status: 'BLOCKED',
      updatedAt: now(),
    }))
  }

  const batch = order.ready.slice(0, REPLAY_BATCH_SIZE)
  if (batch.length === 0) {
    return { payload: next, applied: 0, conflicts: 0, rejected: 0, transportError: null, sent: false }
  }

  const envelopes = batch.map((entry) => toEnvelope(entry, next))
  for (const entry of batch) {
    next = withEntry(next, entry.clientMutationId, (current) => ({
      ...current,
      status: 'SYNCING',
      updatedAt: now(),
    }))
  }

  let results: WireResult[]
  try {
    results = await send(envelopes)
  } catch (error) {
    // The batch may or may not have been applied — that is precisely the
    // ambiguity receipts exist for. Marking these failed would be a guess;
    // marking them retryable lets the same mutation ids ask the server.
    const detail = error instanceof Error ? error.message : 'Falha de conexão durante a sincronização.'
    for (const entry of batch) {
      next = withEntry(next, entry.clientMutationId, (current) =>
        scheduleRetry(current, detail, now()),
      )
    }
    return {
      payload: next,
      applied: 0,
      conflicts: 0,
      rejected: 0,
      transportError: detail,
      sent: true,
    }
  }

  let applied = 0
  let conflicts = 0
  let rejected = 0
  const byId = new Map(results.map((result) => [result.clientMutationId, result]))

  for (const entry of batch) {
    const result = byId.get(entry.clientMutationId)
    if (!result) {
      // The server answers every input; a gap is a protocol violation, not a
      // reason to assume success.
      next = withEntry(next, entry.clientMutationId, (current) =>
        scheduleRetry(current, 'O servidor não respondeu a esta operação.', now()),
      )
      continue
    }

    if (result.status === 'APPLIED' || result.status === 'ALREADY_APPLIED') {
      applied += 1
      if (result.resourceId != null) {
        next = rememberMapping(next, {
          resourceType: entry.resourceType,
          clientResourceId: entry.clientResourceId,
          serverId: result.resourceId,
          serverVersion: result.version,
          mappedAt: now(),
        })
      }
      const current = next.outbox.find(
        (candidate) => candidate.clientMutationId === entry.clientMutationId,
      )
      if (current) next = archive(next, current, 'APPLIED')
      continue
    }

    if (result.status === 'CONFLICT') {
      conflicts += 1
      next = withEntry(next, entry.clientMutationId, (current) => ({
        ...current,
        status: 'CONFLICT',
        updatedAt: now(),
        conflict: toConflict(result),
        lastError: null,
      }))
      continue
    }

    if (result.status === 'DEPENDENCY_MISSING') {
      next = withEntry(next, entry.clientMutationId, (current) => ({
        ...current,
        status: 'BLOCKED',
        updatedAt: now(),
        lastError: result.error
          ? { code: result.error.code, detail: result.error.detail }
          : null,
      }))
      continue
    }

    rejected += 1
    next = withEntry(next, entry.clientMutationId, (current) => ({
      ...current,
      status: 'FAILED_PERMANENT',
      updatedAt: now(),
      attemptCount: current.attemptCount + 1,
      nextAttemptAt: null,
      lastError: result.error
        ? {
            code: result.error.code,
            detail: result.error.detail,
            ...(result.error.fieldErrors ? { fieldErrors: result.error.fieldErrors } : {}),
          }
        : { code: 'SYNC_REJECTED', detail: 'O servidor recusou esta alteração.' },
    }))
  }

  next = {
    ...next,
    syncPreferences: { ...next.syncPreferences, lastSyncAt: now() },
  }
  return { payload: next, applied, conflicts, rejected, transportError: null, sent: true }
}

function toConflict(result: WireResult): OutboxConflict | null {
  if (!result.conflict) return null
  return {
    conflictType: result.conflict.conflictType,
    localBaseVersion: result.conflict.localBaseVersion,
    serverVersion: result.conflict.serverVersion,
    serverSnapshot: result.conflict.serverSnapshot,
    resolutionOptions: result.conflict.resolutionOptions,
    detail: result.conflict.detail,
  }
}

/**
 * Schedules the next automatic attempt, or stops asking.
 *
 * After the cap the entry stays retryable but stops scheduling itself: the user
 * can still press "sync now", but the app will not keep hammering a server or a
 * connection that has failed eight times.
 */
function scheduleRetry(entry: OutboxEntry, detail: string, at: string): OutboxEntry {
  const attemptCount = entry.attemptCount + 1
  const exhausted = attemptCount >= RETRY_POLICY.maxAttempts
  return {
    ...entry,
    status: 'FAILED_RETRYABLE',
    updatedAt: at,
    attemptCount,
    nextAttemptAt: exhausted
      ? null
      : new Date(Date.now() + backoffDelay(attemptCount)).toISOString(),
    lastError: { code: exhausted ? 'SYNC_RETRIES_EXHAUSTED' : 'SYNC_TRANSPORT_FAILED', detail },
  }
}

/** True when an entry is due for another automatic attempt. */
export function isDue(entry: OutboxEntry, at = Date.now()): boolean {
  if (entry.status === 'PENDING') return true
  if (entry.status !== 'FAILED_RETRYABLE') return false
  return entry.nextAttemptAt != null && Date.parse(entry.nextAttemptAt) <= at
}
