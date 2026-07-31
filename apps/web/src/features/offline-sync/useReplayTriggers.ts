import { useEffect, useRef } from 'react'
import { useConnection } from '../../offline/connection'
import { useOptionalVault } from '../../offline/VaultProvider'
import { isDue } from '../../offline/outbox/replay'
import { onSyncEvent } from '../../offline/outbox/coordination'

/**
 * Decides when replay runs.
 *
 * The rule is one controlled attempt per meaningful change of circumstance —
 * connectivity returning, the app regaining focus — and never a poll. A timer
 * that fires every few seconds would drain battery on mobile, and an effect
 * that reacts to render would fire a request per keystroke. Both would also
 * multiply across tabs.
 *
 * Replay is only ever attempted with the app open, online, and the vault
 * unlocked, because the encryption key exists only in memory. There is no
 * Service Worker replay and no Background Sync: neither can decrypt the queue.
 */
export function useReplayTriggers(): void {
  const vault = useOptionalVault()
  const connection = useConnection()
  const lastAttempt = useRef(0)

  const ready = vault?.state === 'UNLOCKED_ONLINE' && connection.state === 'ONLINE'
  const autoSync = vault?.autoSync ?? false
  const dueCount = (vault?.entries ?? []).filter((entry) => isDue(entry)).length

  useEffect(() => {
    if (!vault || !ready || !autoSync || dueCount === 0) return
    // A short floor keeps a burst of state changes from becoming a burst of
    // requests; it is not a polling interval.
    const now = Date.now()
    if (now - lastAttempt.current < 5_000) return
    lastAttempt.current = now
    void vault.replay()
  }, [autoSync, dueCount, ready, vault])

  // Coming back to the tab is a good moment to try again; nothing else here
  // listens on a timer.
  useEffect(() => {
    if (!vault) return
    const onFocus = () => {
      if (
        vault.state === 'UNLOCKED_ONLINE'
        && vault.autoSync
        && vault.entries.some((entry) => isDue(entry))
        && Date.now() - lastAttempt.current > 30_000
      ) {
        lastAttempt.current = Date.now()
        void vault.replay()
      }
    }
    window.addEventListener('focus', onFocus)
    return () => window.removeEventListener('focus', onFocus)
  }, [vault])

  // Another tab finishing a run means this one's view is stale, not that it
  // should start its own: the queue is shared, so the right response is to
  // re-read it, never to send the same mutations a second time.
  useEffect(() => {
    if (!vault) return
    return onSyncEvent((event) => {
      if (event.type === 'REPLAY_FINISHED' || event.type === 'QUEUE_CHANGED') {
        void vault.resync()
      }
    })
  }, [vault])
}
