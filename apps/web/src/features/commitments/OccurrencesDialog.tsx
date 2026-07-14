import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import Dialog from '../../components/Dialog'
import ConfirmDialog from '../../components/ConfirmDialog'
import FormActions from '../../components/FormActions'
import FormField from '../../components/FormField'
import Money from '../../components/Money'
import { ErrorState, errorMessage } from '../../components/states'
import { formatDate } from '../../lib/format'
import {
  useOccurrenceAction,
  useOccurrencePreview,
  useRescheduleOccurrence,
} from './api'
import {
  OCCURRENCE_STATUS_LABELS,
  type Commitment,
  type Occurrence,
  type OccurrenceStatus,
} from './types'

const STATUS_BADGE: Record<OccurrenceStatus, string> = {
  SCHEDULED: 'badge-info',
  MATERIALIZED: 'badge-positive',
  SKIPPED: 'badge-neutral',
  FAILED: 'badge-negative',
  REVERSED: 'badge-neutral',
}

interface OccurrencesDialogProps {
  commitment: Commitment | null
  onClose: () => void
}

function isoDaysFromToday(days: number): string {
  const date = new Date()
  date.setDate(date.getDate() + days)
  return date.toISOString().slice(0, 10)
}

/**
 * Occurrence timeline of one recurring definition: recent history plus the
 * next three months, with the lifecycle actions. Amounts and dates come from
 * the backend — this dialog never computes recurrence math.
 */
export default function OccurrencesDialog({ commitment, onClose }: OccurrencesDialogProps) {
  const from = useMemo(() => isoDaysFromToday(-92), [])
  const to = useMemo(() => isoDaysFromToday(92), [])
  const preview = useOccurrencePreview(commitment?.id ?? null, from, to)
  const action = useOccurrenceAction(commitment?.id ?? null)
  const reschedule = useRescheduleOccurrence(commitment?.id ?? null)

  const [confirming, setConfirming] = useState<{
    occurrence: Occurrence
    kind: 'materialize' | 'reverse' | 'skip'
  } | null>(null)
  const [rescheduling, setRescheduling] = useState<Occurrence | null>(null)
  const [newDate, setNewDate] = useState('')

  if (!commitment) {
    return null
  }

  function run(occurrence: Occurrence, kind: 'materialize' | 'retry' | 'skip' | 'unskip' | 'reverse') {
    action.mutate(
      { date: occurrence.scheduledDate, action: kind },
      // Close the confirmation either way: on failure the occurrence becomes
      // FAILED in the refreshed table and the error is announced below it.
      { onSettled: () => setConfirming(null) },
    )
  }

  const confirmCopy = confirming
    ? {
        materialize: {
          title: 'Executar ocorrência',
          message: `Executar "${commitment.description}" de ${formatDate(
            confirming.occurrence.effectiveDate,
          )}? Um registro financeiro real será criado.`,
          label: 'Executar',
          danger: false,
        },
        reverse: {
          title: 'Estornar ocorrência',
          message:
            confirming.occurrence.transactionId !== null
              ? 'O lançamento gerado será desfeito e o saldo da conta restaurado.'
              : 'A compra gerada será cancelada e o limite do cartão liberado.',
          label: 'Estornar',
          danger: true,
        },
        skip: {
          title: 'Pular ocorrência',
          message: `Pular "${commitment.description}" de ${formatDate(
            confirming.occurrence.effectiveDate,
          )}? Ela não será executada nem projetada.`,
          label: 'Pular',
          danger: false,
        },
      }[confirming.kind]
    : null

  return (
    <>
      <Dialog
        open
        title={`Ocorrências — ${commitment.description}`}
        onClose={onClose}
        wide
      >
        {preview.isPending ? (
          <p className="panel-empty">Carregando ocorrências…</p>
        ) : preview.isError ? (
          <ErrorState error={preview.error} onRetry={() => preview.refetch()} />
        ) : preview.data && preview.data.occurrences.length === 0 ? (
          <p className="panel-empty">Nenhuma ocorrência nesta janela de seis meses.</p>
        ) : preview.data ? (
          <>
            <p className="commitment-form-note">
              Janela: {formatDate(preview.data.from)} a {formatDate(preview.data.to)}. Pagamentos
              executados criam registros reais; estornos os desfazem exatamente uma vez.
            </p>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th scope="col">Data</th>
                    <th scope="col">Status</th>
                    <th scope="col">Registro</th>
                    <th scope="col">
                      <span className="visually-hidden">Ações</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {preview.data.occurrences.map((occurrence) => (
                    <tr key={occurrence.scheduledDate}>
                      <td>
                        {formatDate(occurrence.effectiveDate)}
                        {occurrence.effectiveDate !== occurrence.scheduledDate && (
                          <span className="occurrence-moved">
                            {' '}
                            (movida de {formatDate(occurrence.scheduledDate)})
                          </span>
                        )}
                      </td>
                      <td>
                        <span className={`badge ${STATUS_BADGE[occurrence.status]}`}>
                          {OCCURRENCE_STATUS_LABELS[occurrence.status]}
                        </span>
                        {occurrence.status === 'FAILED' && occurrence.failureMessage && (
                          <span className="occurrence-failure"> {occurrence.failureMessage}</span>
                        )}
                      </td>
                      <td>
                        {occurrence.transactionId !== null ? (
                          <Link to="/transactions" className="cc-invoice-link">
                            Transação gerada
                          </Link>
                        ) : occurrence.cardPurchaseId !== null ? (
                          commitment.creditCardId !== null ? (
                            <Link
                              to={`/credit-cards/${commitment.creditCardId}`}
                              className="cc-invoice-link"
                            >
                              Compra no cartão
                            </Link>
                          ) : (
                            'Compra no cartão'
                          )
                        ) : (
                          '—'
                        )}
                      </td>
                      <td className="occurrence-actions">
                        {(occurrence.status === 'SCHEDULED' || occurrence.status === 'FAILED') && (
                          <>
                            <button
                              type="button"
                              className="btn btn-secondary btn-small"
                              onClick={() =>
                                occurrence.status === 'FAILED'
                                  ? run(occurrence, 'retry')
                                  : setConfirming({ occurrence, kind: 'materialize' })
                              }
                            >
                              {occurrence.status === 'FAILED' ? 'Tentar de novo' : 'Executar'}
                            </button>
                            <button
                              type="button"
                              className="btn btn-ghost btn-small"
                              onClick={() => setConfirming({ occurrence, kind: 'skip' })}
                            >
                              Pular
                            </button>
                            <button
                              type="button"
                              className="btn btn-ghost btn-small"
                              onClick={() => {
                                setNewDate(occurrence.effectiveDate)
                                reschedule.reset()
                                setRescheduling(occurrence)
                              }}
                            >
                              Reagendar
                            </button>
                          </>
                        )}
                        {occurrence.status === 'SKIPPED' && (
                          <button
                            type="button"
                            className="btn btn-ghost btn-small"
                            onClick={() => run(occurrence, 'unskip')}
                          >
                            Reativar
                          </button>
                        )}
                        {occurrence.status === 'MATERIALIZED' && (
                          <button
                            type="button"
                            className="btn btn-ghost btn-small"
                            onClick={() => setConfirming({ occurrence, kind: 'reverse' })}
                          >
                            Estornar
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="stat-footnote">
              Valor por ocorrência: <Money value={commitment.amount} />
            </p>
            {action.isError && (
              <div role="alert" className="field-error">
                {errorMessage(action.error)}
              </div>
            )}
          </>
        ) : null}
      </Dialog>

      <ConfirmDialog
        open={confirming !== null}
        title={confirmCopy?.title ?? ''}
        message={confirmCopy?.message ?? ''}
        confirmLabel={confirmCopy?.label ?? ''}
        danger={confirmCopy?.danger ?? false}
        busy={action.isPending}
        onConfirm={() =>
          confirming &&
          run(confirming.occurrence, confirming.kind === 'materialize' ? 'materialize' : confirming.kind)
        }
        onCancel={() => setConfirming(null)}
      />

      <Dialog
        open={rescheduling !== null}
        title="Reagendar ocorrência"
        onClose={() => setRescheduling(null)}
      >
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (rescheduling && newDate) {
              reschedule.mutate(
                { date: rescheduling.scheduledDate, newDate },
                { onSuccess: () => setRescheduling(null) },
              )
            }
          }}
          noValidate
        >
          <div className="form-grid">
            <p className="commitment-form-note">
              A identidade da ocorrência não muda — apenas a data em que ela acontece.
            </p>
            <FormField label="Nova data">
              <input
                className="input"
                type="date"
                value={newDate}
                onChange={(event) => setNewDate(event.target.value)}
              />
            </FormField>
            {reschedule.isError && (
              <div role="alert" className="field-error">
                {errorMessage(reschedule.error)}
              </div>
            )}
            <FormActions
              busy={reschedule.isPending}
              submitLabel="Reagendar"
              onCancel={() => setRescheduling(null)}
            />
          </div>
        </form>
      </Dialog>
    </>
  )
}
