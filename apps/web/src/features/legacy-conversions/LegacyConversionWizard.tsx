import { useState } from 'react'
import Dialog from '../../components/Dialog'
import FormField from '../../components/FormField'
import Money from '../../components/Money'
import { errorMessage } from '../../components/states'
import { formatBRL, formatDate, formatMonth } from '../../lib/format'
import { INVOICE_STATUS_LABELS } from '../credit-cards/types'
import { useCreditCards } from '../credit-cards/api'
import { useConversionPreview, useConvertLegacy } from './api'
import type { ConversionInventoryItem, ConversionPreview } from './types'

const STEPS = ['Origem', 'Cartão e compra', 'Parcelas e faturas', 'Impacto financeiro', 'Confirmação']

interface LegacyConversionWizardProps {
  source: ConversionInventoryItem
  onClose: () => void
  onConverted: (conversionId: number) => void
}

/**
 * Assisted single conversion. Steps 3-5 render the backend's deterministic
 * preview verbatim — installment schedule, invoice cycles, limit effect and
 * monthly redistribution are never recomputed in the client — and
 * confirmation stays disabled while the preview reports blockers.
 */
export default function LegacyConversionWizard({
  source,
  onClose,
  onConverted,
}: LegacyConversionWizardProps) {
  const [step, setStep] = useState(0)
  const [cardId, setCardId] = useState('')
  const [effectiveDate, setEffectiveDate] = useState(source.date)
  const [installments, setInstallments] = useState('1')

  const cards = useCreditCards()
  const preview = useConversionPreview()
  const convert = useConvertLegacy()

  const activeCards = (cards.data ?? []).filter((card) => !card.archived)
  const result = preview.data ?? null
  const parametersReady = cardId !== '' && effectiveDate !== '' && Number(installments) >= 1

  function fetchPreview() {
    preview.mutate({
      transactionId: source.transactionId,
      cardId: Number(cardId),
      effectivePurchaseDate: effectiveDate,
      installmentCount: Number(installments),
    })
  }

  function goToStep(next: number) {
    // Entering the preview steps always refetches: the parameters may have
    // changed and stale numbers must never be confirmed.
    if (next === 2 && step < 2) {
      fetchPreview()
    }
    setStep(next)
  }

  function handleConfirm() {
    if (!result || !result.convertible) {
      return
    }
    convert.mutate(
      {
        transactionId: source.transactionId,
        cardId: result.card.cardId,
        effectivePurchaseDate: effectiveDate,
        installmentCount: result.installmentCount,
        firstInvoiceMonth: result.firstInvoiceMonth,
      },
      { onSuccess: (conversion) => onConverted(conversion.id) },
    )
  }

  const previewBody = preview.isPending ? (
    <p role="status">Calculando pré-visualização…</p>
  ) : preview.isError ? (
    <div role="alert" className="field-error">
      {errorMessage(preview.error)}
    </div>
  ) : null

  function messages(items: ConversionPreview['warnings'], tone: 'warning' | 'blocker') {
    if (items.length === 0) {
      return null
    }
    return (
      <ul className="lc-messages">
        {items.map((message) => (
          <li key={message.code} className={`lc-message lc-message-${tone}`}>
            {message.message}
          </li>
        ))}
      </ul>
    )
  }

  return (
    <Dialog open title={`Converter crédito legado`} onClose={onClose} wide>
      <ol className="lc-stepper" aria-label="Etapas da conversão">
        {STEPS.map((label, index) => (
          <li
            key={label}
            className={`lc-step ${index === step ? 'lc-step-current' : ''} ${
              index < step ? 'lc-step-done' : ''
            }`}
            aria-current={index === step ? 'step' : undefined}
          >
            {label}
          </li>
        ))}
      </ol>

      {step === 0 && (
        <>
          <dl className="lc-source-facts">
            <div>
              <dt>Descrição</dt>
              <dd>{source.description}</dd>
            </div>
            <div>
              <dt>Valor histórico</dt>
              <dd>
                <Money value={-source.amount} signed />
              </dd>
            </div>
            <div>
              <dt>Data original</dt>
              <dd>{formatDate(source.date)}</dd>
            </div>
            <div>
              <dt>Categoria</dt>
              <dd>{source.category.name}</dd>
            </div>
            {source.accountName && (
              <div>
                <dt>Conta vinculada</dt>
                <dd>{source.accountName}</dd>
              </div>
            )}
          </dl>
          <p className="lc-note">
            Estes dados são imutáveis: a transação original permanece no histórico como registro
            de auditoria e deixa de contar nas somas enquanto a conversão estiver ativa.
          </p>
        </>
      )}

      {step === 1 && (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            goToStep(2)
          }}
        >
          <FormField label="Cartão que receberá a compra">
            <select
              className="select"
              required
              value={cardId}
              onChange={(event) => setCardId(event.target.value)}
            >
              <option value="">Escolha um cartão…</option>
              {activeCards.map((card) => (
                <option key={card.id} value={card.id}>
                  {card.name} — limite disponível {formatBRL(card.limit.availableLimit)}
                </option>
              ))}
            </select>
          </FormField>
          <FormField
            label="Data efetiva da compra"
            hint="Normalmente a data original; define em qual fatura a compra entra."
          >
            <input
              className="input"
              type="date"
              required
              value={effectiveDate}
              onChange={(event) => setEffectiveDate(event.target.value)}
            />
          </FormField>
          <FormField label="Número de parcelas">
            <input
              className="input"
              type="number"
              min="1"
              max="120"
              required
              value={installments}
              onChange={(event) => setInstallments(event.target.value)}
            />
          </FormField>
          {activeCards.length === 0 && !cards.isPending && (
            <p className="lc-note" role="alert">
              Você ainda não tem um cartão ativo. Cadastre um cartão em Cartões antes de
              converter.
            </p>
          )}
        </form>
      )}

      {step === 2 && (
        <>
          {previewBody}
          {result && (
            <>
              <p>
                {result.installmentCount === 1
                  ? 'A compra entra em 1 parcela na fatura abaixo.'
                  : `A compra entra em ${result.installmentCount} parcelas, começando na fatura de ${formatMonth(result.firstInvoiceMonth)}.`}
              </p>
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th scope="col">Parcela</th>
                      <th scope="col" style={{ textAlign: 'right' }}>
                        Valor
                      </th>
                      <th scope="col">Fatura</th>
                      <th scope="col" className="lc-col-optional">
                        Fechamento
                      </th>
                      <th scope="col">Vencimento</th>
                      <th scope="col" className="lc-col-optional">
                        Situação da fatura
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.installments.map((installment) => (
                      <tr key={installment.sequenceNumber}>
                        <td>
                          {installment.sequenceNumber}/{installment.totalInstallments}
                        </td>
                        <td style={{ textAlign: 'right' }}>
                          <Money value={installment.amount} />
                        </td>
                        <td>{formatMonth(installment.invoiceMonth)}</td>
                        <td className="lc-col-optional">{formatDate(installment.closingDate)}</td>
                        <td>{formatDate(installment.dueDate)}</td>
                        <td className="lc-col-optional">
                          {installment.invoiceExists && installment.invoiceStatus
                            ? INVOICE_STATUS_LABELS[installment.invoiceStatus]
                            : 'Será criada'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {messages(result.warnings, 'warning')}
              {messages(result.blockers, 'blocker')}
            </>
          )}
        </>
      )}

      {step === 3 && (
        <>
          {previewBody}
          {result && (
            <>
              <div className="lc-impact-grid">
                <div className="card stat-card">
                  <span className="stat-label">Limite disponível antes</span>
                  <span className="stat-value">{formatBRL(result.limit.availableBefore)}</span>
                </div>
                <div className="card stat-card">
                  <span className="stat-label">Limite disponível depois</span>
                  <span className="stat-value">{formatBRL(result.limit.availableAfter)}</span>
                </div>
              </div>

              <h3 style={{ marginTop: 'var(--space-4)' }}>Redistribuição mensal da despesa</h3>
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th scope="col">Mês</th>
                      <th scope="col" style={{ textAlign: 'right' }}>
                        Variação da despesa
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.monthlyExpenseShift.map((shift) => (
                      <tr key={shift.month}>
                        <td>{formatMonth(shift.month)}</td>
                        <td style={{ textAlign: 'right' }}>
                          <Money value={shift.delta} signed />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="lc-note">
                Orçamentos da categoria {result.source.category.name} passam a considerar as
                parcelas nos meses das faturas.
              </p>

              <h3 style={{ marginTop: 'var(--space-4)' }}>Caixa e previsão</h3>
              <p>{result.cashFlow.explanation}</p>
              <p>{result.forecastExplanation}</p>
              {messages(result.warnings, 'warning')}
              {messages(result.blockers, 'blocker')}
            </>
          )}
        </>
      )}

      {step === 4 && result && (
        <>
          <dl className="lc-source-facts">
            <div>
              <dt>Origem</dt>
              <dd>{result.source.description}</dd>
            </div>
            <div>
              <dt>Valor total</dt>
              <dd>{formatBRL(result.totalAmount)}</dd>
            </div>
            <div>
              <dt>Cartão</dt>
              <dd>{result.card.name}</dd>
            </div>
            <div>
              <dt>Data efetiva</dt>
              <dd>{formatDate(effectiveDate)}</dd>
            </div>
            <div>
              <dt>Parcelas</dt>
              <dd>{result.installmentCount}×</dd>
            </div>
            <div>
              <dt>Primeira fatura</dt>
              <dd>{formatMonth(result.firstInvoiceMonth)}</dd>
            </div>
          </dl>
          <p className="lc-note">
            Ao confirmar, uma compra real será criada no cartão {result.card.name} com as
            parcelas acima, e a transação original deixará de contar nas somas — sem nunca
            contar duas vezes. Você pode estornar a conversão enquanto nenhuma fatura da compra
            tiver pagamento concluído.
          </p>
          {messages(result.blockers, 'blocker')}
          {convert.isError && (
            <div role="alert" className="field-error">
              {errorMessage(convert.error)}
            </div>
          )}
        </>
      )}

      <div className="lc-wizard-footer">
        <button
          type="button"
          className="btn btn-secondary"
          onClick={step === 0 ? onClose : () => goToStep(step - 1)}
          disabled={convert.isPending}
        >
          {step === 0 ? 'Cancelar' : 'Voltar'}
        </button>
        <span className="lc-wizard-footer-advance">
          {step < 4 ? (
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => goToStep(step + 1)}
              disabled={
                (step >= 1 && !parametersReady) ||
                (step >= 2 && (preview.isPending || !result))
              }
            >
              Avançar
            </button>
          ) : (
            <button
              type="button"
              className="btn btn-primary"
              onClick={handleConfirm}
              disabled={!result || !result.convertible || convert.isPending || preview.isPending}
            >
              {convert.isPending
                ? 'Convertendo…'
                : 'Confirmar e criar compra real no cartão'}
            </button>
          )}
        </span>
      </div>
    </Dialog>
  )
}
