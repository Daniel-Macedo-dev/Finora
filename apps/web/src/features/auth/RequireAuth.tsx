import { useEffect, type ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { ErrorState, LoadingCards } from '../../components/states'
import { useCurrentUser } from './api'
import { useVault } from '../../offline/VaultProvider'
import OfflineUnlock from '../../offline/OfflineUnlock'

/**
 * Gate for the authenticated application. Distinguishes the three states
 * deliberately: hydrating (skeleton, no content flash), unauthenticated
 * (redirect to login with safe return path) and network failure (retry UI —
 * never treated as "logged out").
 */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const currentUser = useCurrentUser()
  const vault = useVault()
  const location = useLocation()
  const refetchCurrentUser = currentUser.refetch

  useEffect(() => {
    const retry = () => void refetchCurrentUser()
    window.addEventListener('finora:retry-connection', retry)
    return () => window.removeEventListener('finora:retry-connection', retry)
  }, [refetchCurrentUser])

  useEffect(() => {
    if (vault.state === 'UNLOCKED_OFFLINE' && currentUser.isSuccess) {
      vault.reconcileOnline(currentUser.data)
    }
  }, [currentUser.data, currentUser.isSuccess, vault])

  if (currentUser.isPending) {
    return (
      <main style={{ padding: 'var(--space-6)', maxWidth: 960, margin: '0 auto' }}>
        <LoadingCards count={3} height={120} />
      </main>
    )
  }

  if (currentUser.isError) {
    if (vault.state === 'LOCKED' || vault.state === 'CORRUPTED' || vault.state === 'UNLOCKING') {
      return <OfflineUnlock />
    }
    if (vault.state === 'UNLOCKED_OFFLINE') return children
    return (
      <main style={{ padding: 'var(--space-6)', maxWidth: 640, margin: '0 auto' }}>
        <ErrorState error={currentUser.error} onRetry={() => currentUser.refetch()} />
      </main>
    )
  }

  if (currentUser.data === null) {
    // Only internal paths are kept as return targets (no open redirects).
    const from = `${location.pathname}${location.search}`
    return (
      <Navigate
        to="/login"
        replace
        state={{ from: from.startsWith('/') ? from : '/dashboard' }}
      />
    )
  }

  return children
}
