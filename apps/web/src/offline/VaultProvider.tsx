import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { AuthUser } from '../features/auth/types'
import {
  createVault,
  emptyPayload,
  needsMigration,
  reopenVault,
  resealVault,
  unlockVault,
  type VaultPayload,
  type VaultSession,
} from './vaultCrypto'
import { deleteVault, encryptedSize, loadVault, saveVault } from './vaultStorage'
import {
  fetchOfflineDataset,
  hydrateAllowedQueries,
  isAllowedOfflineKey,
  serializeAllowedQueries,
} from './queryPersistence'
import { setOfflineUnlocked } from './session'
import {
  archive,
  countByStatus,
  hasUnsyncedWork,
  pruneMappings,
  queueMutation,
  type QueueRequest,
} from './outbox/queue'
import { replayOnce, type ReplayOutcome, type SendBatch } from './outbox/replay'
import { broadcast, withReplayLock } from './outbox/coordination'
import {
  newId,
  type OutboxEntry,
  type ResolutionOption,
  type ResourceMapping,
} from './outbox/types'
import { sendMutations } from './outbox/transport'

export type VaultState =
  | 'LOADING'
  | 'ABSENT'
  | 'LOCKED'
  | 'UNLOCKING'
  | 'UNLOCKED_ONLINE'
  | 'UNLOCKED_OFFLINE'
  | 'CORRUPTED'

export interface VaultCounts {
  total: number
  pending: number
  blocked: number
  syncing: number
  conflicts: number
  retryable: number
  permanent: number
}

interface VaultContextValue {
  state: VaultState
  owner: VaultPayload['owner'] | null
  updatedAt: string | null
  size: number | null
  error: string | null
  /** Active queue entries; empty whenever the vault is locked. */
  entries: OutboxEntry[]
  /**
   * Client resource id → server id, for resources the server has now accepted.
   *
   * Screens addressing an offline-created resource by its local id need this to
   * follow it once replay assigns the real one, instead of leaving the user on
   * a page whose id stopped meaning anything.
   */
  mappings: ResourceMapping[]
  counts: VaultCounts
  lastSyncAt: string | null
  autoSync: boolean
  replaying: boolean
  hasPendingWork: boolean
  enable(user: AuthUser, password: string): Promise<void>
  unlock(password: string, online: boolean): Promise<void>
  refresh(user: AuthUser, password: string): Promise<void>
  lock(): void
  remove(): Promise<void>
  reconcileOnline(user: AuthUser | null): boolean
  /** Re-reads the stored vault after another tab changed it. No-op when locked. */
  resync(): Promise<void>
  /** Queues one supported mutation. Returns null when it cancelled out. */
  queue(request: QueueRequest): Promise<OutboxEntry | null>
  replay(): Promise<ReplayOutcome | null>
  retry(clientMutationId: string): Promise<void>
  discard(clientMutationId: string): Promise<void>
  resolve(clientMutationId: string, action: ResolutionOption): Promise<void>
  reviseAndRetry(clientMutationId: string, payload: Record<string, unknown>): Promise<void>
  setAutoSync(value: boolean): Promise<void>
}

const VaultContext = createContext<VaultContextValue | null>(null)
const genericError =
  'Não foi possível desbloquear os dados. Verifique a senha ou exclua a cópia local.'

const EMPTY_COUNTS: VaultCounts = {
  total: 0,
  pending: 0,
  blocked: 0,
  syncing: 0,
  conflicts: 0,
  retryable: 0,
  permanent: 0,
}

function currentMonth(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

export function VaultProvider({
  children,
  send = sendMutations,
}: {
  children: ReactNode
  /** Injectable transport; the default posts to the real sync endpoint. */
  send?: SendBatch
}) {
  const queryClient = useQueryClient()
  const [state, setState] = useState<VaultState>('LOADING')
  const [payload, setPayload] = useState<VaultPayload | null>(null)
  const [updatedAt, setUpdatedAt] = useState<string | null>(null)
  const [size, setSize] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [replaying, setReplaying] = useState(false)

  // The derived key lives here and only here: a ref, never state, never
  // storage, cleared by lock, logout and the inactivity timer.
  const session = useRef<VaultSession | null>(null)
  const payloadRef = useRef<VaultPayload | null>(null)

  const setDecrypted = useCallback((next: VaultPayload | null) => {
    payloadRef.current = next
    setPayload(next)
  }, [])

  useEffect(() => {
    void loadVault()
      .then((vault) => {
        setState(vault ? 'LOCKED' : 'ABSENT')
        setUpdatedAt(vault?.updatedAt ?? null)
        setSize(vault ? encryptedSize(vault) : null)
      })
      .catch(() => {
        setState('ABSENT')
        setError('O armazenamento offline não está disponível neste navegador.')
      })
  }, [])

  const lock = useCallback(() => {
    setOfflineUnlocked(false)
    session.current = null
    setDecrypted(null)
    queryClient.clear()
    setState((current) => (current === 'ABSENT' ? 'ABSENT' : 'LOCKED'))
    broadcast({ type: 'VAULT_LOCKED' })
  }, [queryClient, setDecrypted])

  /**
   * Re-seals and stores the vault.
   *
   * The old ciphertext is only replaced once the new one exists, so a failure
   * anywhere in encrypt-or-write leaves the previous record readable. Losing a
   * queued mutation to a half-finished write would mean losing the only copy.
   */
  const persist = useCallback(
    async (next: VaultPayload): Promise<void> => {
      const active = session.current
      if (!active) throw new Error('A cópia offline está bloqueada.')
      const encrypted = await resealVault(next, active)
      await saveVault(encrypted)
      setDecrypted(next)
      setUpdatedAt(encrypted.updatedAt)
      setSize(encryptedSize(encrypted))
    },
    [setDecrypted],
  )

  /**
   * Re-reads the stored vault with the key this tab already holds.
   *
   * Called when another tab reports that the queue moved. Silently does nothing
   * when this tab is locked or the record cannot be opened: a failure here is a
   * stale view, not a reason to drop the user out of an unlocked session.
   */
  const resync = useCallback(async () => {
    const active = session.current
    if (!active) return
    try {
      const stored = await loadVault()
      if (!stored) return
      setDecrypted(await reopenVault(stored, active))
      setUpdatedAt(stored.updatedAt)
      setSize(encryptedSize(stored))
    } catch {
      // Keep showing what we have.
    }
  }, [setDecrypted])

  const write = useCallback(
    async (user: AuthUser, password: string, createdAt?: string) => {
      await fetchOfflineDataset(queryClient, currentMonth())
      const preparedAt = new Date().toISOString()
      const next = emptyPayload(
        { id: user.id, displayName: user.displayName, email: user.email },
        preparedAt,
        serializeAllowedQueries(queryClient),
      )
      const { encrypted, session: created } = await createVault(next, password, createdAt)
      // Round-trips the record before trusting it: a vault that cannot be read
      // back is worse than no vault at all.
      const verified = await unlockVault(encrypted, password)
      if (verified.payload.owner.id !== user.id) throw new Error(genericError)
      await saveVault(encrypted)
      session.current = created
      setDecrypted(next)
      setUpdatedAt(preparedAt)
      setSize(encryptedSize(encrypted))
      setState('UNLOCKED_ONLINE')
      setError(null)
    },
    [queryClient, setDecrypted],
  )

  const enable = useCallback(
    async (user: AuthUser, password: string) => {
      if (await loadVault()) {
        throw new Error('Já existe uma cópia offline neste perfil. Exclua-a antes de criar outra.')
      }
      await write(user, password)
    },
    [write],
  )

  const unlock = useCallback(
    async (password: string, online: boolean) => {
      setState('UNLOCKING')
      setError(null)
      try {
        const encrypted = await loadVault()
        if (!encrypted) {
          setState('ABSENT')
          return
        }
        const { payload: decrypted, session: opened } = await unlockVault(encrypted, password)
        session.current = opened
        hydrateAllowedQueries(queryClient, decrypted.queries)
        setDecrypted(decrypted)
        setUpdatedAt(decrypted.preparedAt)
        setSize(encryptedSize(encrypted))
        setOfflineUnlocked(!online)
        setState(online ? 'UNLOCKED_ONLINE' : 'UNLOCKED_OFFLINE')
        if (needsMigration(encrypted)) {
          // Persist the upgraded shape now that it decrypted cleanly. If this
          // write fails the old record survives untouched and the migration is
          // simply retried on the next unlock.
          try {
            await persist(decrypted)
          } catch {
            setError(
              'Não foi possível atualizar o formato da cópia offline. Ela continua utilizável.',
            )
          }
        }
      } catch {
        session.current = null
        setDecrypted(null)
        setState('CORRUPTED')
        setError(genericError)
        throw new Error(genericError)
      }
    },
    [persist, queryClient, setDecrypted],
  )

  const refresh = useCallback(
    async (user: AuthUser, password: string) => {
      const existing = await loadVault()
      if (!existing) throw new Error('A cópia offline não existe mais.')
      const { payload: decrypted } = await unlockVault(existing, password)
      if (decrypted.owner.id !== user.id) {
        throw new Error('Esta cópia offline pertence a outra conta e não será mesclada.')
      }
      if (hasUnsyncedWork(decrypted)) {
        throw new Error(
          'Há alterações offline pendentes. Sincronize-as antes de recriar a cópia local.',
        )
      }
      await write(user, password, existing.createdAt)
    },
    [write],
  )

  const remove = useCallback(async () => {
    lock()
    await deleteVault()
    setUpdatedAt(null)
    setSize(null)
    setState('ABSENT')
  }, [lock])

  const reconcileOnline = useCallback(
    (user: AuthUser | null) => {
      if (state !== 'UNLOCKED_OFFLINE') return true
      const current = payloadRef.current
      if (!user || !current || user.id !== current.owner.id) {
        lock()
        setError(
          user
            ? 'A sessão online pertence a outra conta. A cópia offline permaneceu bloqueada e não foi mesclada.'
            : null,
        )
        return false
      }
      setOfflineUnlocked(false)
      setState('UNLOCKED_ONLINE')
      queryClient.invalidateQueries({ predicate: (query) => isAllowedOfflineKey(query.queryKey) })
      return true
    },
    [lock, queryClient, state],
  )

  const queue = useCallback(
    async (request: QueueRequest) => {
      const current = payloadRef.current
      if (!current) throw new Error('A cópia offline está bloqueada.')
      const result = queueMutation(current, request)
      await persist(result.payload)
      broadcast({ type: 'QUEUE_CHANGED' })
      return result.entry
    },
    [persist],
  )

  const replay = useCallback(async (): Promise<ReplayOutcome | null> => {
    const current = payloadRef.current
    if (!current || state !== 'UNLOCKED_ONLINE') return null
    setReplaying(true)
    try {
      return await withReplayLock(async () => {
        broadcast({ type: 'REPLAY_STARTED' })
        // A queue longer than one batch is drained in place rather than left
        // for the next trigger: making the user press "sync" once per twenty
        // operations would be absurd. The loop is bounded twice over — it stops
        // the moment a round applies nothing, and never exceeds MAX_ROUNDS — so
        // a queue that cannot progress can never spin.
        const MAX_ROUNDS = 10
        let outcome = await replayOnce(payloadRef.current ?? current, send)
        let applied = outcome.applied
        let conflicts = outcome.conflicts
        let rejected = outcome.rejected
        for (let round = 1; round < MAX_ROUNDS; round += 1) {
          if (outcome.transportError || outcome.applied === 0) break
          const next = await replayOnce(outcome.payload, send)
          if (!next.sent) break
          outcome = next
          applied += next.applied
          conflicts += next.conflicts
          rejected += next.rejected
        }
        const totals = { ...outcome, applied, conflicts, rejected }
        await persist(pruneMappings(totals.payload))
        broadcast({ type: 'REPLAY_FINISHED', applied, conflicts })
        if (applied > 0) {
          queryClient.invalidateQueries({
            predicate: (query) => isAllowedOfflineKey(query.queryKey),
          })
        }
        return totals
      })
    } finally {
      setReplaying(false)
    }
  }, [persist, queryClient, send, state])

  const mutateEntry = useCallback(
    async (clientMutationId: string, change: (entry: OutboxEntry) => OutboxEntry) => {
      const current = payloadRef.current
      if (!current) throw new Error('A cópia offline está bloqueada.')
      const entry = current.outbox.find(
        (candidate) => candidate.clientMutationId === clientMutationId,
      )
      if (!entry) return
      const replacement = change(entry)
      await persist({
        ...current,
        outbox: current.outbox.map((candidate) =>
          candidate.clientMutationId === clientMutationId ? replacement : candidate,
        ),
      })
      broadcast({ type: 'QUEUE_CHANGED' })
    },
    [persist],
  )

  const retry = useCallback(
    (clientMutationId: string) =>
      mutateEntry(clientMutationId, (entry) => ({
        ...entry,
        status: 'PENDING',
        // The mutation id is intentionally kept: the server must be able to
        // recognise this as the same request it may already have applied.
        nextAttemptAt: null,
        lastError: null,
        updatedAt: new Date().toISOString(),
      })),
    [mutateEntry],
  )

  const discard = useCallback(
    async (clientMutationId: string) => {
      const current = payloadRef.current
      if (!current) throw new Error('A cópia offline está bloqueada.')
      const entry = current.outbox.find(
        (candidate) => candidate.clientMutationId === clientMutationId,
      )
      if (!entry) return
      await persist(pruneMappings(archive(current, entry, 'DISCARDED')))
      queryClient.invalidateQueries({ predicate: (query) => isAllowedOfflineKey(query.queryKey) })
      broadcast({ type: 'QUEUE_CHANGED' })
    },
    [persist, queryClient],
  )

  /**
   * Applies the user's decision about a conflict.
   *
   * "Apply local" and "edit and retry" both mint a new mutation id and adopt
   * the version the server just reported. Reusing the conflicting id would look
   * to the server like the same request arriving with different content — which
   * it correctly refuses — and reusing the stale base version would simply
   * conflict again.
   */
  const resolve = useCallback(
    async (clientMutationId: string, action: ResolutionOption) => {
      const current = payloadRef.current
      if (!current) throw new Error('A cópia offline está bloqueada.')
      const entry = current.outbox.find(
        (candidate) => candidate.clientMutationId === clientMutationId,
      )
      if (!entry) return

      if (action === 'KEEP_SERVER' || action === 'DISCARD_LOCAL') {
        const outcome = action === 'KEEP_SERVER' ? 'KEPT_SERVER' : 'DISCARDED'
        await persist(pruneMappings(archive(current, entry, outcome)))
        queryClient.invalidateQueries({ predicate: (query) => isAllowedOfflineKey(query.queryKey) })
        broadcast({ type: 'QUEUE_CHANGED' })
        return
      }

      if (action === 'APPLY_LOCAL') {
        await mutateEntry(clientMutationId, (candidate) => ({
          ...candidate,
          clientMutationId: newId(),
          baseVersion: candidate.conflict?.serverVersion ?? candidate.baseVersion,
          status: 'PENDING',
          attemptCount: 0,
          nextAttemptAt: null,
          lastError: null,
          conflict: null,
          updatedAt: new Date().toISOString(),
        }))
      }
      // EDIT_AND_RETRY is completed by reviseAndRetry once the form is saved.
    },
    [mutateEntry, persist, queryClient],
  )

  const reviseAndRetry = useCallback(
    (clientMutationId: string, revised: Record<string, unknown>) =>
      mutateEntry(clientMutationId, (entry) => ({
        ...entry,
        clientMutationId: newId(),
        payload: revised,
        baseVersion: entry.conflict?.serverVersion ?? entry.baseVersion,
        status: 'PENDING',
        attemptCount: 0,
        nextAttemptAt: null,
        lastError: null,
        conflict: null,
        updatedAt: new Date().toISOString(),
      })),
    [mutateEntry],
  )

  const setAutoSync = useCallback(
    async (value: boolean) => {
      const current = payloadRef.current
      if (!current) return
      await persist({
        ...current,
        syncPreferences: { ...current.syncPreferences, autoSync: value },
      })
    },
    [persist],
  )

  useEffect(() => {
    if (!payload) return
    const timeoutMs = 15 * 60_000
    let timer = window.setTimeout(lock, timeoutMs)
    const reset = () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(lock, timeoutMs)
    }
    const hidden = () => {
      if (document.hidden) timer = window.setTimeout(lock, 5 * 60_000)
    }
    window.addEventListener('pointerdown', reset)
    window.addEventListener('keydown', reset)
    document.addEventListener('visibilitychange', hidden)
    return () => {
      window.clearTimeout(timer)
      window.removeEventListener('pointerdown', reset)
      window.removeEventListener('keydown', reset)
      document.removeEventListener('visibilitychange', hidden)
    }
  }, [lock, payload])

  const counts = useMemo(() => (payload ? countByStatus(payload) : EMPTY_COUNTS), [payload])
  const entries = useMemo(
    () => (payload ? payload.outbox.filter((entry) => entry.status !== 'APPLIED') : []),
    [payload],
  )

  const value = useMemo(
    () => ({
      state,
      owner: payload?.owner ?? null,
      updatedAt,
      size,
      error,
      entries,
      mappings: payload?.resourceMappings ?? [],
      counts,
      lastSyncAt: payload?.syncPreferences.lastSyncAt ?? null,
      autoSync: payload?.syncPreferences.autoSync ?? true,
      replaying,
      hasPendingWork: counts.total > 0,
      enable,
      unlock,
      refresh,
      lock,
      remove,
      reconcileOnline,
      resync,
      queue,
      replay,
      retry,
      discard,
      resolve,
      reviseAndRetry,
      setAutoSync,
    }),
    [
      counts,
      discard,
      enable,
      entries,
      error,
      lock,
      payload,
      queue,
      reconcileOnline,
      refresh,
      remove,
      replay,
      replaying,
      resolve,
      resync,
      retry,
      reviseAndRetry,
      setAutoSync,
      size,
      state,
      unlock,
      updatedAt,
    ],
  )
  return <VaultContext.Provider value={value}>{children}</VaultContext.Provider>
}

export function useVault(): VaultContextValue {
  const value = useContext(VaultContext)
  if (!value) throw new Error('useVault must be used within VaultProvider')
  return value
}

export function useOptionalVault(): VaultContextValue | null {
  return useContext(VaultContext)
}
