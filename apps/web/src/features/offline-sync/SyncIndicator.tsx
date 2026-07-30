import { NavLink } from 'react-router-dom'
import { CloudUpload, RefreshCw, TriangleAlert } from 'lucide-react'
import { useOptionalVault } from '../../offline/VaultProvider'

/**
 * The compact shell entry point.
 *
 * Shows nothing at all when there is nothing to say — an always-present badge
 * would train people to ignore it. When it does appear, the count and the
 * reason are both in the accessible name, so it is never colour alone.
 */
export default function SyncIndicator() {
  const vault = useOptionalVault()
  if (!vault) return null

  const { counts, replaying } = vault
  const needsAttention = counts.conflicts + counts.permanent
  if (counts.total === 0 && !replaying) return null

  const bounded = counts.total > 99 ? '99+' : String(counts.total)
  const label = replaying
    ? 'Sincronizando alterações offline'
    : needsAttention > 0
      ? `${counts.total} alteração(ões) offline, ${needsAttention} precisando de atenção`
      : `${counts.total} alteração(ões) offline pendentes`

  return (
    <NavLink
      to="/offline-sync"
      className="btn btn-ghost sync-indicator"
      aria-label={label}
      title={label}
    >
      {replaying ? (
        <RefreshCw size={16} aria-hidden="true" />
      ) : needsAttention > 0 ? (
        <TriangleAlert size={16} aria-hidden="true" />
      ) : (
        <CloudUpload size={16} aria-hidden="true" />
      )}
      <span className="sync-indicator-count" aria-hidden="true">
        {bounded}
      </span>
    </NavLink>
  )
}
