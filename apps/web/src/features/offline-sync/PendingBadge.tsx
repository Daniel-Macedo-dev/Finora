import { PENDING_LABELS, type PendingState } from '../../offline/outbox/projection'

/**
 * The label a row carries while it differs from the server.
 *
 * Text, always — a coloured row alone would be invisible to anyone who cannot
 * distinguish the hues, and indistinguishable from a highlight to everyone else.
 */
export default function PendingBadge({ state }: { state: PendingState }) {
  const tone =
    state === 'CONFLICT' || state === 'FAILED'
      ? 'badge-warning'
      : state === 'DELETED'
        ? 'badge-neutral'
        : 'badge-positive'
  return <span className={`badge ${tone}`}>{PENDING_LABELS[state]}</span>
}

/**
 * Shown on any screen whose figures come from the last server snapshot while
 * local changes are still queued.
 *
 * Finora deliberately does not recompute dashboards, forecasts and balances in
 * the browser: a second financial engine that disagrees subtly with the real
 * one is worse than an honestly stale number. So the screens say which it is.
 */
export function StaleTotalsWarning({ pendingCount }: { pendingCount: number }) {
  if (pendingCount === 0) return null
  return (
    <p className="stale-totals-warning" role="note">
      Alguns totais ainda não incluem alterações offline pendentes.
    </p>
  )
}
