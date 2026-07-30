/**
 * Keeps two tabs from replaying the same queue at once.
 *
 * Duplicate replay is not a correctness hole on its own — server receipts make
 * a repeated mutation harmless. But two tabs racing would double every request,
 * double the conflict noise, and let one tab archive an entry the other is
 * still holding in memory. So the engine takes a lock, and treats the server as
 * the backstop rather than the mechanism.
 */

const LOCK_NAME = 'finora-offline-sync-replay'
const CHANNEL_NAME = 'finora-offline-sync'

/** In-process guard: covers the same tab even without the Web Locks API. */
let localBusy = false

/**
 * Runs `work` while holding the replay lock, or returns null if another holder
 * already has it.
 *
 * Never queues behind the current holder: a second replay attempt is only ever
 * useful if it happens *after* the first finished, and by then the caller will
 * have been asked again anyway.
 */
export async function withReplayLock<T>(work: () => Promise<T>): Promise<T | null> {
  if (localBusy) return null
  localBusy = true
  try {
    if (typeof navigator !== 'undefined' && navigator.locks?.request) {
      return await navigator.locks.request(
        LOCK_NAME,
        { ifAvailable: true },
        async (lock) => (lock ? work() : null),
      )
    }
    // No Web Locks: the in-process guard still prevents overlap within this
    // tab, and the server's receipts absorb anything two tabs manage to send.
    return await work()
  } finally {
    localBusy = false
  }
}

export type SyncEvent =
  | { type: 'REPLAY_STARTED' }
  | { type: 'REPLAY_FINISHED'; applied: number; conflicts: number }
  | { type: 'QUEUE_CHANGED' }
  | { type: 'VAULT_LOCKED' }

type Listener = (event: SyncEvent) => void

let channel: BroadcastChannel | null = null
const listeners = new Set<Listener>()

function ensureChannel(): BroadcastChannel | null {
  if (typeof BroadcastChannel === 'undefined') return null
  if (!channel) {
    channel = new BroadcastChannel(CHANNEL_NAME)
    channel.onmessage = (message) => {
      const event = message.data as SyncEvent
      listeners.forEach((listener) => listener(event))
    }
  }
  return channel
}

/**
 * Announces a synchronization event to other tabs.
 *
 * Only counts and state transitions travel here — never a mutation payload,
 * never a resource, never an owner id. BroadcastChannel messages are plaintext
 * and reach every tab on the origin, which is exactly the boundary the vault
 * exists to keep financial data inside of.
 */
export function broadcast(event: SyncEvent): void {
  ensureChannel()?.postMessage(event)
}

export function onSyncEvent(listener: Listener): () => void {
  ensureChannel()
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Test seam: releases the channel so a suite can start clean. */
export function resetCoordination(): void {
  channel?.close()
  channel = null
  listeners.clear()
  localBusy = false
}
