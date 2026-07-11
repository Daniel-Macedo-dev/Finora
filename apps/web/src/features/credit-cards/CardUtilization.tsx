import { formatBRL, formatPercent } from '../../lib/format'
import type { CardLimit } from './types'

/**
 * Limit usage meter. Uses the ARIA meter pattern so screen readers announce
 * the utilization; color shifts with pressure but the numbers carry the truth.
 */
export default function CardUtilization({ limit }: { limit: CardLimit }) {
  const percent = Math.max(0, Math.min(limit.utilizationPercent, 100))
  const tone =
    limit.utilizationPercent >= 90
      ? 'cc-meter-danger'
      : limit.utilizationPercent >= 70
        ? 'cc-meter-warning'
        : 'cc-meter-ok'
  return (
    <div className="cc-utilization">
      <div
        role="meter"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.min(limit.utilizationPercent, 100)}
        aria-valuetext={`${formatPercent(limit.utilizationPercent)} do limite em uso`}
        aria-label="Utilização do limite"
        className="cc-meter"
      >
        <span className={`cc-meter-fill ${tone}`} style={{ width: `${percent}%` }} />
      </div>
      <div className="cc-utilization-legend">
        <span>
          Usado <strong>{formatBRL(limit.usedLimit)}</strong>
        </span>
        <span>
          Disponível <strong>{formatBRL(limit.availableLimit)}</strong>
        </span>
        <span>{formatPercent(limit.utilizationPercent)}</span>
      </div>
    </div>
  )
}
