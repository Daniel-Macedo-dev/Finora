import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { AuthUser } from '../features/auth/types'
import { DATA_SCHEMA_VERSION, decryptVault, encryptVault, type VaultPayload } from './vaultCrypto'
import { deleteVault, encryptedSize, loadVault, saveVault } from './vaultStorage'
import { fetchOfflineDataset, hydrateAllowedQueries, isAllowedOfflineKey, serializeAllowedQueries } from './queryPersistence'
import { setOfflineUnlocked } from './session'

export type VaultState = 'LOADING' | 'ABSENT' | 'LOCKED' | 'UNLOCKING' | 'UNLOCKED_ONLINE' | 'UNLOCKED_OFFLINE' | 'CORRUPTED'

interface VaultContextValue {
  state: VaultState
  owner: VaultPayload['owner'] | null
  updatedAt: string | null
  size: number | null
  error: string | null
  enable(user: AuthUser, password: string): Promise<void>
  unlock(password: string, online: boolean): Promise<void>
  refresh(user: AuthUser, password: string): Promise<void>
  lock(): void
  remove(): Promise<void>
  reconcileOnline(user: AuthUser | null): boolean
}

const VaultContext = createContext<VaultContextValue | null>(null)
const genericError = 'Não foi possível desbloquear os dados. Verifique a senha ou exclua a cópia local.'

function currentMonth(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

export function VaultProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [state, setState] = useState<VaultState>('LOADING')
  const [owner, setOwner] = useState<VaultPayload['owner'] | null>(null)
  const [updatedAt, setUpdatedAt] = useState<string | null>(null)
  const [size, setSize] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void loadVault().then((vault) => {
      setState(vault ? 'LOCKED' : 'ABSENT')
      setUpdatedAt(vault?.updatedAt ?? null)
      setSize(vault ? encryptedSize(vault) : null)
    }).catch(() => { setState('ABSENT'); setError('O armazenamento offline não está disponível neste navegador.') })
  }, [])

  const lock = useCallback(() => {
    setOfflineUnlocked(false)
    queryClient.clear()
    setOwner(null)
    setState((current) => current === 'ABSENT' ? 'ABSENT' : 'LOCKED')
  }, [queryClient])

  const write = useCallback(async (user: AuthUser, password: string, createdAt?: string) => {
    await fetchOfflineDataset(queryClient, currentMonth())
    const preparedAt = new Date().toISOString()
    const payload: VaultPayload = {
      dataSchemaVersion: DATA_SCHEMA_VERSION,
      owner: { id: user.id, displayName: user.displayName, email: user.email },
      preparedAt,
      queries: serializeAllowedQueries(queryClient),
    }
    const encrypted = await encryptVault(payload, password, createdAt)
    const verified = await decryptVault(encrypted, password)
    if (verified.owner.id !== user.id) throw new Error(genericError)
    await saveVault(encrypted)
    setOwner(payload.owner)
    setUpdatedAt(preparedAt)
    setSize(encryptedSize(encrypted))
    setState('UNLOCKED_ONLINE')
    setError(null)
  }, [queryClient])

  const enable = useCallback(async (user: AuthUser, password: string) => {
    if (await loadVault()) throw new Error('Já existe uma cópia offline neste perfil. Exclua-a antes de criar outra.')
    await write(user, password)
  }, [write])

  const unlock = useCallback(async (password: string, online: boolean) => {
    setState('UNLOCKING')
    setError(null)
    try {
      const encrypted = await loadVault()
      if (!encrypted) { setState('ABSENT'); return }
      const payload = await decryptVault(encrypted, password)
      hydrateAllowedQueries(queryClient, payload.queries)
      setOwner(payload.owner)
      setUpdatedAt(payload.preparedAt)
      setSize(encryptedSize(encrypted))
      setOfflineUnlocked(!online)
      setState(online ? 'UNLOCKED_ONLINE' : 'UNLOCKED_OFFLINE')
    } catch {
      setOwner(null)
      setState('CORRUPTED')
      setError(genericError)
      throw new Error(genericError)
    }
  }, [queryClient])

  const refresh = useCallback(async (user: AuthUser, password: string) => {
    const existing = await loadVault()
    if (!existing) throw new Error('A cópia offline não existe mais.')
    const payload = await decryptVault(existing, password)
    if (payload.owner.id !== user.id) throw new Error('Esta cópia offline pertence a outra conta e não será mesclada.')
    await write(user, password, existing.createdAt)
  }, [write])

  const remove = useCallback(async () => {
    lock()
    await deleteVault()
    setUpdatedAt(null)
    setSize(null)
    setState('ABSENT')
  }, [lock])

  const reconcileOnline = useCallback((user: AuthUser | null) => {
    if (state !== 'UNLOCKED_OFFLINE') return true
    if (!user || !owner || user.id !== owner.id) {
      lock()
      setError(user ? 'A sessão online pertence a outra conta. A cópia offline permaneceu bloqueada e não foi mesclada.' : null)
      return false
    }
    setOfflineUnlocked(false)
    setState('UNLOCKED_ONLINE')
    queryClient.invalidateQueries({ predicate: (query) => isAllowedOfflineKey(query.queryKey) })
    return true
  }, [lock, owner, queryClient, state])

  useEffect(() => {
    if (!owner) return
    let timer = window.setTimeout(lock, 15 * 60_000)
    const reset = () => { window.clearTimeout(timer); timer = window.setTimeout(lock, 15 * 60_000) }
    const hidden = () => { if (document.hidden) timer = window.setTimeout(lock, 5 * 60_000) }
    window.addEventListener('pointerdown', reset)
    window.addEventListener('keydown', reset)
    document.addEventListener('visibilitychange', hidden)
    return () => { window.clearTimeout(timer); window.removeEventListener('pointerdown', reset); window.removeEventListener('keydown', reset); document.removeEventListener('visibilitychange', hidden) }
  }, [lock, owner])

  const value = useMemo(() => ({ state, owner, updatedAt, size, error, enable, unlock, refresh, lock, remove, reconcileOnline }), [enable, error, lock, owner, reconcileOnline, refresh, remove, size, state, unlock, updatedAt])
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
