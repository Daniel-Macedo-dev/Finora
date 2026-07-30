import { RotateCcw, Trash2 } from 'lucide-react'
import { useVault } from '../../offline/VaultProvider'
import type { OutboxEntry, OutboxStatus, ResolutionOption } from '../../offline/outbox/types'
import ConflictComparison from './ConflictComparison'

const RESOURCE_LABELS: Record<OutboxEntry['resourceType'], string> = {
  TRANSACTION: 'Transação',
  BUDGET: 'Orçamento',
  GOAL: 'Meta',
  WISHLIST_ITEM: 'Item da lista de desejos',
  PURCHASE_OPTION: 'Opção de compra',
  PRICE_SNAPSHOT: 'Observação de preço',
}

const OPERATION_LABELS: Record<OutboxEntry['operation'], string> = {
  CREATE: 'Criação',
  UPDATE: 'Edição',
  DELETE: 'Exclusão',
}

const STATUS_LABELS: Record<OutboxStatus, string> = {
  PENDING: 'Pendente',
  BLOCKED: 'Aguardando outro item',
  SYNCING: 'Sincronizando',
  CONFLICT: 'Conflito',
  FAILED_RETRYABLE: 'Falha temporária',
  FAILED_PERMANENT: 'Falha',
  APPLIED: 'Sincronizado',
  DISCARDED: 'Descartado',
}

const RESOLUTION_LABELS: Record<ResolutionOption, string> = {
  KEEP_SERVER: 'Manter o do servidor',
  APPLY_LOCAL: 'Aplicar minha alteração',
  EDIT_AND_RETRY: 'Editar e tentar de novo',
  DISCARD_LOCAL: 'Descartar minha alteração',
}

function timestamp(value: string): string {
  return new Date(value).toLocaleString('pt-BR')
}

interface SyncEntryRowProps {
  entry: OutboxEntry
  expanded: boolean
  onToggle(): void
  /** Destructive actions raise a confirmation the page owns. */
  onConfirmDiscard(entry: OutboxEntry): void
  onConfirmApplyLocal(entry: OutboxEntry): void
}

/**
 * One queued operation.
 *
 * Non-destructive actions (retry, keep server, discard the local change on a
 * conflict) are applied here; the two that overwrite or destroy something the
 * user cannot get back are raised to the page, which owns the confirmations.
 */
export default function SyncEntryRow({
  entry,
  expanded,
  onToggle,
  onConfirmDiscard,
  onConfirmApplyLocal,
}: SyncEntryRowProps) {
  const vault = useVault()

  return (
    <li className="card sync-entry">
      <div className="sync-entry-head">
        <div>
          <p className="sync-entry-title">{entry.label}</p>
          <p className="sync-entry-meta">
            {RESOURCE_LABELS[entry.resourceType]} · {OPERATION_LABELS[entry.operation]} ·{' '}
            {timestamp(entry.createdAt)}
          </p>
        </div>
        <span className={`badge sync-status sync-status-${entry.status.toLowerCase()}`}>
          {STATUS_LABELS[entry.status]}
        </span>
      </div>

      <dl className="sync-entry-facts">
        <div>
          <dt>Tentativas</dt>
          <dd>{entry.attemptCount}</dd>
        </div>
        {entry.nextAttemptAt && (
          <div>
            <dt>Próxima tentativa</dt>
            <dd>{timestamp(entry.nextAttemptAt)}</dd>
          </div>
        )}
        {entry.dependencies.length > 0 && (
          <div>
            <dt>Depende de</dt>
            <dd>{entry.dependencies.length} alteração(ões) anterior(es)</dd>
          </div>
        )}
      </dl>

      {entry.lastError && <p className="sync-error">{entry.lastError.detail}</p>}

      <div className="sync-entry-actions">
        {entry.status === 'CONFLICT' && (
          <button
            type="button"
            className="btn btn-secondary"
            aria-expanded={expanded}
            onClick={onToggle}
          >
            {expanded ? 'Ocultar comparação' : `Resolver conflito: ${entry.label}`}
          </button>
        )}
        {(entry.status === 'FAILED_RETRYABLE' || entry.status === 'BLOCKED') && (
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => void vault.retry(entry.clientMutationId)}
          >
            <RotateCcw size={16} aria-hidden="true" />
            Tentar novamente: {entry.label}
          </button>
        )}
        <button
          type="button"
          className="btn btn-danger"
          onClick={() => onConfirmDiscard(entry)}
        >
          <Trash2 size={16} aria-hidden="true" />
          Descartar: {entry.label}
        </button>
      </div>

      {/* Rendered only when opened: a long queue must not become hundreds of
          expanded comparison tables nobody is looking at. */}
      {expanded && entry.status === 'CONFLICT' && (
        <div className="sync-conflict">
          <ConflictComparison entry={entry} />
          <div className="sync-entry-actions">
            {(entry.conflict?.resolutionOptions ?? []).map((option) => (
              <button
                key={option}
                type="button"
                className={option === 'DISCARD_LOCAL' ? 'btn btn-danger' : 'btn btn-secondary'}
                onClick={() => {
                  if (option === 'APPLY_LOCAL') {
                    onConfirmApplyLocal(entry)
                    return
                  }
                  void vault.resolve(entry.clientMutationId, option)
                  onToggle()
                }}
              >
                {RESOLUTION_LABELS[option]}
              </button>
            ))}
          </div>
        </div>
      )}
    </li>
  )
}
