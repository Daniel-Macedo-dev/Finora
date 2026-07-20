import Dialog from '../../components/Dialog'
import Money from '../../components/Money'
import { errorMessage } from '../../components/states'
import { formatDate } from '../../lib/format'
import { usePatchItem } from './api'
import { DUPLICATE_STATUS_LABELS, type StatementItem } from './types'

interface DuplicateReviewProps {
  batchId: number
  item: StatementItem
  accountName: string
  onClose: () => void
}

function signedAmount(amount: number | null, type: string | null): number | null {
  if (amount === null) {
    return null
  }
  return type === 'EXPENSE' ? -amount : amount
}

/**
 * Side-by-side review of a possible duplicate: the statement row against
 * the existing Finora transaction that matched it. The user decides
 * explicitly — skip, or import anyway — and the decision is stored.
 * Exact duplicates only explain why they cannot be imported again.
 */
export default function DuplicateReview({
  batchId,
  item,
  accountName,
  onClose,
}: DuplicateReviewProps) {
  const patchItem = usePatchItem()
  const exact =
    item.duplicateStatus === 'EXACT_DUPLICATE' || item.duplicateStatus === 'DUPLICATE_WITHIN_FILE'
  const match = item.matchedTransaction

  function decide(patch: { included?: boolean; duplicateOverride?: boolean }) {
    patchItem.mutate({ batchId, itemId: item.id, patch }, { onSuccess: onClose })
  }

  return (
    <Dialog open title="Revisar duplicidade" onClose={onClose} wide>
      <p className="si-duplicate-reason">
        {item.duplicateStatus === 'POSSIBLE_DUPLICATE' &&
          'Encontramos uma transação existente com a mesma data, valor e descrição equivalente. Compare os dois lados e decida.'}
        {item.duplicateStatus === 'EXACT_DUPLICATE' &&
          'Este lançamento tem a mesma identidade de um lançamento já importado nesta conta e não pode ser importado de novo.'}
        {item.duplicateStatus === 'DUPLICATE_WITHIN_FILE' &&
          'Este lançamento se repete dentro do próprio arquivo. Apenas a primeira ocorrência pode ser importada.'}
      </p>

      <div className="si-duplicate-compare">
        <section className="card si-duplicate-side" aria-label="Lançamento do extrato">
          <h3 className="si-subtitle">
            Lançamento do extrato{' '}
            <span className="badge badge-warning">
              {DUPLICATE_STATUS_LABELS[item.duplicateStatus]}
            </span>
          </h3>
          <dl className="si-duplicate-facts">
            <div>
              <dt>Data</dt>
              <dd>{formatDate(item.postedDate)}</dd>
            </div>
            <div>
              <dt>Descrição</dt>
              <dd>{item.description ?? '—'}</dd>
            </div>
            <div>
              <dt>Valor</dt>
              <dd>
                <Money value={signedAmount(item.amount, item.type)} signed />
              </dd>
            </div>
            <div>
              <dt>Conta de destino</dt>
              <dd>{accountName}</dd>
            </div>
            {item.externalId && (
              <div>
                <dt>Identificador do banco</dt>
                <dd>{item.externalId}</dd>
              </div>
            )}
          </dl>
        </section>

        {match && (
          <section className="card si-duplicate-side" aria-label="Transação existente no Finora">
            <h3 className="si-subtitle">Transação existente no Finora</h3>
            <dl className="si-duplicate-facts">
              <div>
                <dt>Data</dt>
                <dd>{formatDate(match.date)}</dd>
              </div>
              <div>
                <dt>Descrição</dt>
                <dd>{match.description}</dd>
              </div>
              <div>
                <dt>Valor</dt>
                <dd>
                  <Money value={signedAmount(match.amount, match.type)} signed />
                </dd>
              </div>
              {match.categoryName && (
                <div>
                  <dt>Categoria</dt>
                  <dd>{match.categoryName}</dd>
                </div>
              )}
            </dl>
          </section>
        )}
      </div>

      {patchItem.isError && (
        <p className="field-error" role="alert">
          {errorMessage(patchItem.error)}
        </p>
      )}

      <div className="form-footer">
        <button
          type="button"
          className="btn btn-secondary"
          onClick={onClose}
          disabled={patchItem.isPending}
        >
          Fechar
        </button>
        {!exact && (
          <>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => decide({ included: false })}
              disabled={patchItem.isPending}
            >
              Pular este lançamento
            </button>
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => decide({ duplicateOverride: true, included: true })}
              disabled={patchItem.isPending}
            >
              {patchItem.isPending ? 'Salvando…' : 'Importar mesmo assim'}
            </button>
          </>
        )}
      </div>
    </Dialog>
  )
}
