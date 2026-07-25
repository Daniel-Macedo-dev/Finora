import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

export type ConnectionState = 'ONLINE' | 'OFFLINE' | 'RECONNECTING'

type Listener = (reachable: boolean) => void
const listeners = new Set<Listener>()

export function reportApiReachability(reachable: boolean): void {
  listeners.forEach((listener) => listener(reachable))
}

interface ConnectionContextValue {
  state: ConnectionState
  lastOnlineAt: string | null
  retry(): void
}

const ConnectionContext = createContext<ConnectionContextValue | null>(null)

export function ConnectionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<ConnectionState>(() => navigator.onLine ? 'ONLINE' : 'OFFLINE')
  const [lastOnlineAt, setLastOnlineAt] = useState<string | null>(null)

  const retry = useCallback(() => {
    if (!navigator.onLine) {
      setState('OFFLINE')
      return
    }
    setState('RECONNECTING')
    window.dispatchEvent(new Event('finora:retry-connection'))
  }, [])

  useEffect(() => {
    const onOffline = () => setState('OFFLINE')
    const onOnline = () => retry()
    const onReachability: Listener = (reachable) => {
      if (reachable) {
        setLastOnlineAt(new Date().toISOString())
        setState('ONLINE')
      } else {
        setState(navigator.onLine ? 'RECONNECTING' : 'OFFLINE')
      }
    }
    window.addEventListener('offline', onOffline)
    window.addEventListener('online', onOnline)
    listeners.add(onReachability)
    return () => {
      window.removeEventListener('offline', onOffline)
      window.removeEventListener('online', onOnline)
      listeners.delete(onReachability)
    }
  }, [retry])

  const value = useMemo(() => ({ state, lastOnlineAt, retry }), [lastOnlineAt, retry, state])
  return <ConnectionContext.Provider value={value}>{children}</ConnectionContext.Provider>
}

export function useConnection(): ConnectionContextValue {
  const value = useContext(ConnectionContext)
  if (!value) throw new Error('useConnection must be used within ConnectionProvider')
  return value
}
