import { useState } from 'react'
import { Link } from 'react-router-dom'
import Dialog from '../../components/Dialog'
import FormField from '../../components/FormField'
import { ErrorState, errorMessage, LoadingCards } from '../../components/states'
import { formatBRL, formatDate, formatMonth } from '../../lib/format'
import { useConversion, useReverseConversion } from './api'
import { CONVERSION_STATUS_LABELS } from './types'

interface LegacyConversionDetailProps {
  conversionId: number
  onClose: () => void
}

function formatInstant(instant: string | null): string {
  if (!instant) {
    return '—'
  }
  const parsed = new Date(instant)
  if (Number.isNaN(parsed.getTime())) {
    return '—'
  }
  return parsed.toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
}

/**
 * Full audit view of one conversion, with the reversal flow. Whether a
 * reversal is possible comes exclusively from the backend
 * ({@code reversible} / {@code reversalBlocked*}) — settlement rules are
 * never re-derived here.
 */
export default function LegacyConversionDetail({
  conversionId,
  onClose,
}: LegacyConversionDetailProps) {
  const [confirmingReversal, setConfirmingReversal] = useState(false)
  const [reason, setReason] = useState('')

  const conversion = useConversion(conversionId)
  const reverse = useReverseConversion()

  const data = conversion.data

  function handleReverse() {
    reverse.mutate(
      { id: conversionId, reason: reason.trim() || undefined },
      {
        onSuccess: () => {
          setConfirmingReversal(false)
          setReason('')
        },
      },
    )
  }

  return (
    <Dialog open title="Detalhe da conversão" onClose={onClose} wide>
      {conversion.isPending ? (
        <LoadingCards count={2} height={80} />
      ) : conversion.isError ? (
        <ErrorState error={conversion.error} onRetry={() => conversion.refetch()} />
      ) : data ? (
        <>
          <dl className="lc-detail-grid">
            <div>
              <dt>Situação</dt>
              <dd>
                <span
                  className={`badge ${
                    data.status === 'ACTIVE' ? 'badge-positive' : 'badge-warning'
                  }`}
                >
                  {CONVERSION_STATUS_LABELS[data.status]}
                </span>
              </dd>
            </div>
            <div>
              <dt>Transação original</dt>
              <dd>
                {data.sourceDescription} ({formatDate(data.originalTransactionDate)})
              </dd>
            </div>
            <div>
              <dt>Valor</dt>
              <dd>{formatBRL(data.amount)}</dd>
            </div>
            <div>
              <dt>Cartão</dt>
              <dd>
                <Link to={`/credit-cards/${data.cardId}`}>{data.cardName}</Link>
              </dd>
            </div>
            <div>
              <dt>Data efetiva da compra</dt>
              <dd>{formatDate(data.effectivePurchaseDate)}</dd>
            </div>
            <div>
              <dt>Parcelas</dt>
              <dd>{data.installmentCount}×</dd>
            </div>
            <div>
              <dt>Primeira fatura</dt>
              <dd>{formatMonth(data.firstInvoiceMonth)}</dd>
            </div>
            <div>
              <dt>Convertida em</dt>
              <dd>{formatInstant(data.convertedAt)}</dd>
            </div>
            {data.status === 'REVERSED' && (
              <>
                <div>
                  <dt>Estornada em</dt>
                  <dd>{formatInstant(data.reversedAt)}</dd>
                </div>
                <div>
                  <dt>Motivo do estorno</dt>
                  <dd>{data.reversalReason ?? '—'}</dd>
                </div>
              </>
            )}
          </dl>

          <p className="lc-note">
            {data.status === 'ACTIVE'
              ? 'A compra gerada no cartão é a fonte da despesa; a transação original permanece no histórico como registro de auditoria, sem contar nas somas.'
              : 'A conversão foi estornada: a compra gerada foi cancelada e a transação original voltou a ser a despesa histórica — exatamente uma vez. O registro da conversão permanece para auditoria.'}
            {' '}
            <Link to={`/credit-cards/${data.cardId}`}>Ver compra e faturas no cartão.</Link>
          </p>

          {data.status === 'ACTIVE' && (
            <>
              {!data.reversible && data.reversalBlockedMessage && (
                <p className="lc-message lc-message-blocker" role="status">
                  Estorno indisponível: {data.reversalBlockedMessage}
                </p>
              )}
              {reverse.isError && (
                <div role="alert" className="field-error">
                  {errorMessage(reverse.error)}
                </div>
              )}
              {confirmingReversal ? (
                <div className="lc-note" role="group" aria-label="Confirmar estorno">
                  <p style={{ marginTop: 0 }}>
                    Estornar esta conversão cancela a compra gerada no cartão (e suas parcelas)
                    e faz a transação original voltar a contar como despesa histórica. Essa ação
                    fica registrada na auditoria.
                  </p>
                  <FormField label="Motivo (opcional)">
                    <input
                      className="input"
                      maxLength={300}
                      value={reason}
                      onChange={(event) => setReason(event.target.value)}
                    />
                  </FormField>
                  <div className="form-footer">
                    <button
                      type="button"
                      className="btn btn-secondary"
                      onClick={() => setConfirmingReversal(false)}
                      disabled={reverse.isPending}
                    >
                      Manter conversão
                    </button>
                    <button
                      type="button"
                      className="btn btn-danger"
                      onClick={handleReverse}
                      disabled={reverse.isPending}
                    >
                      {reverse.isPending ? 'Estornando…' : 'Estornar conversão'}
                    </button>
                  </div>
                </div>
              ) : (
                <div className="form-footer">
                  <button type="button" className="btn btn-secondary" onClick={onClose}>
                    Fechar
                  </button>
                  <button
                    type="button"
                    className="btn btn-danger"
                    onClick={() => setConfirmingReversal(true)}
                    disabled={!data.reversible}
                    title={
                      data.reversible ? undefined : (data.reversalBlockedMessage ?? undefined)
                    }
                  >
                    Estornar conversão
                  </button>
                </div>
              )}
            </>
          )}
        </>
      ) : null}
    </Dialog>
  )
}
