import { useState } from 'react'
import Dialog from '../../components/Dialog'
import FormField from '../../components/FormField'
import Money from '../../components/Money'
import { errorMessage } from '../../components/states'
import { api } from '../../lib/api'
import { formatBRL, formatDate, formatMonth } from '../../lib/format'
import { useCreditCards } from '../credit-cards/api'
import { useBatchConvert } from './api'
import {
  BATCH_ITEM_STATUS_LABELS,
  type BatchItemResult,
  type BatchItemStatus,
  type ConversionInventoryItem,
  type ConversionPreview,
} from './types'

const RESULT_BADGES: Record<BatchItemStatus, string> = {
  SUCCESS: 'badge-positive',
  ALREADY_CONVERTED: 'badge-info',
  FAILED: 'badge-negative',
  SKIPPED: 'badge-neutral',
}

interface ItemPlan {
  source: ConversionInventoryItem
  cardId: string
  effectiveDate: string
  installments: string
  /** Backend-computed schedule for this item; null until previewed. */
  preview: {
    firstInvoiceMonth: string
    convertible: boolean
    blockerMessage: string | null
  } | null
  previewError: string | null
  result: BatchItemResult | null
}

interface LegacyBatchConversionProps {
  sources: ConversionInventoryItem[]
  onClose: () => void
  onFinished: () => void
}

/**
 * Assisted batch conversion over the independent per-item API. Each selected
 * source keeps its own card, date and installment count; the backend preview
 * resolves each item's first invoice before confirmation, and one item's
 * failure never hides another's success. Retry re-submits only failed items
 * that are still eligible.
 */
export default function LegacyBatchConversion({
  sources,
  onClose,
  onFinished,
}: LegacyBatchConversionProps) {
  const [items, setItems] = useState<ItemPlan[]>(() =>
    sources.map((source) => ({
      source,
      cardId: '',
      effectiveDate: source.date,
      installments: '1',
      preview: null,
      previewError: null,
      result: null,
    })),
  )
  const [previewing, setPreviewing] = useState(false)
  const [finished, setFinished] = useState(false)

  const cards = useCreditCards()
  const batch = useBatchConvert()
  const activeCards = (cards.data ?? []).filter((card) => !card.archived)

  const allConfigured = items.every(
    (item) => item.cardId !== '' && item.effectiveDate !== '' && Number(item.installments) >= 1,
  )
  const previewed = items.every((item) => item.preview !== null || item.previewError !== null)
  const convertibleItems = items.filter((item) => item.preview?.convertible)

  function updateItem(transactionId: number, changes: Partial<ItemPlan>) {
    setItems((current) =>
      current.map((item) =>
        item.source.transactionId === transactionId
          ? // Any parameter change invalidates that item's computed schedule.
            { ...item, ...changes, preview: null, previewError: null }
          : item,
      ),
    )
  }

  /** Resolves every item's deterministic first invoice through the backend. */
  async function previewAll() {
    setPreviewing(true)
    const resolved = await Promise.all(
      items.map(async (item) => {
        if (item.preview) {
          return item
        }
        try {
          const preview = await api.post<ConversionPreview>('/legacy-conversions/preview', {
            transactionId: item.source.transactionId,
            cardId: Number(item.cardId),
            effectivePurchaseDate: item.effectiveDate,
            installmentCount: Number(item.installments),
          })
          return {
            ...item,
            preview: {
              firstInvoiceMonth: preview.firstInvoiceMonth,
              convertible: preview.convertible,
              blockerMessage: preview.blockers[0]?.message ?? null,
            },
            previewError: null,
          }
        } catch (error) {
          return { ...item, preview: null, previewError: errorMessage(error) }
        }
      }),
    )
    setItems(resolved)
    setPreviewing(false)
  }

  function submit(only?: Set<number>) {
    const payload = items.filter(
      (item) =>
        item.preview?.convertible &&
        (only ? only.has(item.source.transactionId) : true),
    )
    if (payload.length === 0) {
      return
    }
    batch.mutate(
      {
        items: payload.map((item) => ({
          transactionId: item.source.transactionId,
          cardId: Number(item.cardId),
          effectivePurchaseDate: item.effectiveDate,
          installmentCount: Number(item.installments),
          firstInvoiceMonth: item.preview!.firstInvoiceMonth,
        })),
      },
      {
        onSuccess: (response) => {
          setItems((current) =>
            current.map((item) => {
              const result = response.results.find(
                (entry) => entry.transactionId === item.source.transactionId,
              )
              return result ? { ...item, result } : item
            }),
          )
          setFinished(true)
          onFinished()
        },
      },
    )
  }

  function retryFailed() {
    const failed = new Set(
      items
        .filter((item) => item.result?.status === 'FAILED')
        .map((item) => item.source.transactionId),
    )
    submit(failed)
  }

  const failedCount = items.filter((item) => item.result?.status === 'FAILED').length

  return (
    <Dialog open title="Converter em lote" onClose={onClose} wide>
      {!finished ? (
        <>
          <p className="lc-note" style={{ marginTop: 0 }}>
            Configure cada transação individualmente: cartão, data efetiva e parcelas não
            precisam ser iguais. Depois calcule as faturas para confirmar — cada item é
            processado de forma independente.
          </p>
          <ul className="lc-batch-list">
            {items.map((item) => (
              <li key={item.source.transactionId} className="lc-batch-item">
                <div className="lc-batch-item-head">
                  <strong>{item.source.description}</strong>
                  <span>
                    {formatDate(item.source.date)} · <Money value={-item.source.amount} signed />
                  </span>
                </div>
                <div className="lc-batch-item-controls">
                  <FormField label="Cartão">
                    <select
                      className="select"
                      value={item.cardId}
                      onChange={(event) =>
                        updateItem(item.source.transactionId, { cardId: event.target.value })
                      }
                    >
                      <option value="">Escolha…</option>
                      {activeCards.map((card) => (
                        <option key={card.id} value={card.id}>
                          {card.name} — {formatBRL(card.limit.availableLimit)} disponível
                        </option>
                      ))}
                    </select>
                  </FormField>
                  <FormField label="Data efetiva">
                    <input
                      className="input"
                      type="date"
                      value={item.effectiveDate}
                      onChange={(event) =>
                        updateItem(item.source.transactionId, {
                          effectiveDate: event.target.value,
                        })
                      }
                    />
                  </FormField>
                  <FormField label="Parcelas">
                    <input
                      className="input"
                      type="number"
                      min="1"
                      max="120"
                      value={item.installments}
                      onChange={(event) =>
                        updateItem(item.source.transactionId, {
                          installments: event.target.value,
                        })
                      }
                    />
                  </FormField>
                </div>
                {item.preview && (
                  <p style={{ margin: 0, fontSize: 'var(--text-sm)' }} role="status">
                    {item.preview.convertible ? (
                      <>Primeira fatura: {formatMonth(item.preview.firstInvoiceMonth)}</>
                    ) : (
                      <span className="lc-message lc-message-blocker">
                        Bloqueada: {item.preview.blockerMessage ?? 'não pode ser convertida.'}
                      </span>
                    )}
                  </p>
                )}
                {item.previewError && (
                  <p className="lc-message lc-message-blocker" role="alert" style={{ margin: 0 }}>
                    {item.previewError}
                  </p>
                )}
              </li>
            ))}
          </ul>

          {batch.isError && (
            <div role="alert" className="field-error">
              {errorMessage(batch.error)}
            </div>
          )}

          <div className="lc-wizard-footer">
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Cancelar
            </button>
            <span className="lc-wizard-footer-advance">
              <button
                type="button"
                className="btn btn-secondary"
                onClick={previewAll}
                disabled={!allConfigured || previewing}
              >
                {previewing ? 'Calculando…' : 'Calcular faturas'}
              </button>
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => submit()}
                disabled={!previewed || convertibleItems.length === 0 || batch.isPending}
              >
                {batch.isPending
                  ? 'Convertendo…'
                  : `Converter ${convertibleItems.length} ${
                      convertibleItems.length === 1 ? 'transação' : 'transações'
                    }`}
              </button>
            </span>
          </div>
        </>
      ) : (
        <>
          <p role="status">
            Resultado do lote — cada item foi processado de forma independente; falhas não
            desfazem os sucessos.
          </p>
          <ul className="lc-batch-list">
            {items.map((item) => (
              <li key={item.source.transactionId} className="lc-batch-item">
                <div className="lc-batch-item-head">
                  <strong>{item.source.description}</strong>
                  {item.result ? (
                    <span className={`badge ${RESULT_BADGES[item.result.status]}`}>
                      {BATCH_ITEM_STATUS_LABELS[item.result.status]}
                    </span>
                  ) : (
                    <span className="badge badge-neutral">Não enviada</span>
                  )}
                </div>
                {item.result?.status === 'FAILED' && item.result.message && (
                  <p className="lc-message lc-message-blocker" style={{ margin: 0 }}>
                    {item.result.message}
                  </p>
                )}
              </li>
            ))}
          </ul>
          <div className="lc-wizard-footer">
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Fechar
            </button>
            {failedCount > 0 && (
              <button
                type="button"
                className="btn btn-primary"
                onClick={retryFailed}
                disabled={batch.isPending}
              >
                {batch.isPending
                  ? 'Reenviando…'
                  : `Tentar novamente ${failedCount} ${failedCount === 1 ? 'falha' : 'falhas'}`}
              </button>
            )}
          </div>
        </>
      )}
    </Dialog>
  )
}
