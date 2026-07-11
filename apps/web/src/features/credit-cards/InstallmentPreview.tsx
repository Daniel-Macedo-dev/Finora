import { formatBRL } from '../../lib/format'
import { previewInstallments } from './allocation'

/**
 * Live preview of the installment split. Mirrors the backend's deterministic
 * cent allocation; the server-generated schedule shown after submission is
 * always the source of truth.
 */
export default function InstallmentPreview({
  total,
  count,
}: {
  total: number | null
  count: number
}) {
  if (total === null || total <= 0 || count < 1) {
    return null
  }
  const parts = previewInstallments(total, count)
  if (!parts) {
    return null
  }
  const uniform = parts.every((part) => part === parts[0])
  return (
    <div className="cc-preview" aria-live="polite">
      <span className="cc-preview-title">Parcelas previstas</span>
      {uniform ? (
        <span className="cc-preview-line">
          {count}× de <strong>{formatBRL(parts[0])}</strong>
        </span>
      ) : (
        <ul className="cc-preview-list">
          {parts.map((part, index) => (
            <li key={index}>
              <span>
                {index + 1}/{count}
              </span>
              <strong>{formatBRL(part)}</strong>
            </li>
          ))}
        </ul>
      )}
      <span className="cc-preview-note">
        Os centavos restantes vão para as últimas parcelas; a soma é exatamente{' '}
        {formatBRL(Math.round(total * 100) / 100)}.
      </span>
    </div>
  )
}
