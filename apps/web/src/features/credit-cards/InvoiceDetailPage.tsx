import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, HandCoins, Undo2 } from 'lucide-react'
import ConfirmDialog from '../../components/ConfirmDialog'
import Dialog from '../../components/Dialog'
import Money from '../../components/Money'
import PageHeader from '../../components/PageHeader'
import { EmptyState, ErrorState, LoadingCards, errorMessage } from '../../components/states'
import { formatDate, formatMonth } from '../../lib/format'
import InvoicePaymentForm from './InvoicePaymentForm'
import InvoiceStatusBadge from './InvoiceStatusBadge'
import { useCreditCard, useInvoiceDetail, usePayInvoice, useReversePayment } from './api'
import { ADJUSTMENT_KIND_LABELS, type InvoicePaymentLine, type PaymentRequest } from './types'
import './credit-cards.css'

export default function InvoiceDetailPage() {
  const params = useParams()
  const cardId = Number(params.cardId)
  const invoiceId = Number(params.invoiceId)

  const card = useCreditCard(cardId)
  const detail = useInvoiceDetail(cardId, invoiceId)
  const payMutation = usePayInvoice(cardId, invoiceId)
  const reverseMutation = useReversePayment(cardId, invoiceId)

  const [paymentOpen, setPaymentOpen] = useState(false)
  const [reversing, setReversing] = useState<InvoicePaymentLine | null>(null)

  if (detail.isPending) {
    return <LoadingCards count={3} height={120} />
  }
  if (detail.isError) {
    return <ErrorState error={detail.error} onRetry={() => detail.refetch()} />
  }
  const data = detail.data
  if (!data) {
    return null
  }
  const invoice = data.invoice

  function handlePayment(request: PaymentRequest) {
    // The dialog only closes on success; a failure keeps the form (and its
    // error message) on screen.
    payMutation.mutate(request, { onSuccess: () => setPaymentOpen(false) })
  }

  const cardName = card.data?.name ?? 'Cartão'

  return (
    <>
      <PageHeader
        title={`Fatura de ${formatMonth(invoice.referenceMonth)}`}
        description={`${cardName} · fecha em ${formatDate(invoice.closingDate)} · vence em ${formatDate(invoice.dueDate)}`}
        actions={
          <div className="cc-detail-actions">
            <Link to={`/credit-cards/${cardId}`} className="btn btn-secondary">
              <ArrowLeft size={16} aria-hidden="true" />
              {cardName}
            </Link>
            {invoice.outstandingAmount > 0 && (
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => {
                  payMutation.reset()
                  setPaymentOpen(true)
                }}
              >
                <HandCoins size={16} aria-hidden="true" />
                Pagar fatura
              </button>
            )}
          </div>
        }
      />

      <section className="card cc-section cc-invoice-summary" aria-labelledby="cc-invoice-heading">
        <h2 id="cc-invoice-heading" className="visually-hidden">
          Resumo da fatura
        </h2>
        <div className="cc-invoice-figures">
          <div>
            <span className="cc-figure-label">Status</span>
            <InvoiceStatusBadge status={invoice.status} />
          </div>
          <div>
            <span className="cc-figure-label">Compras</span>
            <Money value={invoice.purchaseTotal} />
          </div>
          <div>
            <span className="cc-figure-label">Ajustes</span>
            <Money value={invoice.adjustmentsNet} signed />
          </div>
          <div>
            <span className="cc-figure-label">Total da fatura</span>
            <Money value={invoice.invoiceTotal} className="cc-figure-strong" />
          </div>
          <div>
            <span className="cc-figure-label">Pago</span>
            <Money value={invoice.amountPaid} />
          </div>
          <div>
            <span className="cc-figure-label">Em aberto</span>
            <Money value={invoice.outstandingAmount} className="cc-figure-strong" />
          </div>
        </div>
      </section>

      <section className="card cc-section" aria-labelledby="cc-lines-heading">
        <h2 id="cc-lines-heading" className="cc-section-title">
          Lançamentos
        </h2>
        {data.installments.length === 0 && data.adjustments.length === 0 ? (
          <EmptyState title="Fatura sem lançamentos" />
        ) : (
          <div className="table-wrap">
            <table className="data">
              <caption className="visually-hidden">
                Parcelas e ajustes da fatura de {formatMonth(invoice.referenceMonth)}
              </caption>
              <thead>
                <tr>
                  <th scope="col">Compra</th>
                  <th scope="col" className="cc-col-optional">
                    Data da compra
                  </th>
                  <th scope="col" className="cc-col-optional">
                    Categoria
                  </th>
                  <th scope="col">Parcela</th>
                  <th scope="col" style={{ textAlign: 'right' }}>
                    Valor
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.installments.map((line) => (
                  <tr key={`i-${line.id}`} className={line.status === 'CANCELLED' ? 'cc-row-cancelled' : ''}>
                    <td className="cc-purchase-desc">
                      {line.description}
                      {line.merchant ? ` · ${line.merchant}` : ''}
                      {line.status === 'CANCELLED' && (
                        <span className="badge badge-neutral">Cancelada</span>
                      )}
                    </td>
                    <td className="cc-col-optional">{formatDate(line.purchaseDate)}</td>
                    <td className="cc-col-optional">
                      <span className="badge badge-neutral">{line.categoryName}</span>
                    </td>
                    <td>
                      {line.sequenceNumber}/{line.totalInstallments}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <Money value={line.amount} />
                    </td>
                  </tr>
                ))}
                {data.adjustments.map((line) => (
                  <tr key={`a-${line.id}`} className={line.status === 'REVERSED' ? 'cc-row-cancelled' : ''}>
                    <td className="cc-purchase-desc">
                      {line.description}
                      <span className="badge badge-neutral">
                        {ADJUSTMENT_KIND_LABELS[line.kind]}
                      </span>
                      {line.status === 'REVERSED' && (
                        <span className="badge badge-neutral">Estornado</span>
                      )}
                    </td>
                    <td className="cc-col-optional">—</td>
                    <td className="cc-col-optional">
                      {line.categoryName ? (
                        <span className="badge badge-neutral">{line.categoryName}</span>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td>—</td>
                    <td style={{ textAlign: 'right' }}>
                      <Money
                        value={
                          line.kind === 'CREDIT' || line.kind === 'REFUND'
                            ? -line.amount
                            : line.amount
                        }
                        signed
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card cc-section" aria-labelledby="cc-payments-heading">
        <h2 id="cc-payments-heading" className="cc-section-title">
          Pagamentos
        </h2>
        {data.payments.length === 0 ? (
          <EmptyState
            title="Nenhum pagamento registrado"
            description="O pagamento reduz o saldo da conta escolhida, mas não cria uma nova despesa."
          />
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Data</th>
                  <th scope="col" className="cc-col-optional">
                    Conta
                  </th>
                  <th scope="col">Situação</th>
                  <th scope="col" style={{ textAlign: 'right' }}>
                    Valor
                  </th>
                  <th scope="col">
                    <span className="visually-hidden">Ações</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.payments.map((payment) => (
                  <tr
                    key={payment.id}
                    className={payment.status === 'REVERSED' ? 'cc-row-cancelled' : ''}
                  >
                    <td>{formatDate(payment.paidOn)}</td>
                    <td className="cc-col-optional">{payment.accountName}</td>
                    <td>
                      {payment.status === 'REVERSED' ? (
                        <span className="badge badge-neutral">Estornado</span>
                      ) : (
                        <span className="badge badge-positive">Efetuado</span>
                      )}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <Money value={payment.amount} />
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      {payment.status === 'COMPLETED' && (
                        <button
                          type="button"
                          className="btn btn-ghost btn-icon"
                          onClick={() => {
                            reverseMutation.reset()
                            setReversing(payment)
                          }}
                          aria-label={`Estornar pagamento de ${formatDate(payment.paidOn)}`}
                        >
                          <Undo2 size={16} aria-hidden="true" />
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <Dialog
        open={paymentOpen}
        title={`Pagar fatura de ${formatMonth(invoice.referenceMonth)}`}
        onClose={() => setPaymentOpen(false)}
        wide
      >
        <InvoicePaymentForm
          invoice={invoice}
          defaultAccountId={card.data?.defaultPaymentAccountId ?? null}
          busy={payMutation.isPending}
          submitError={payMutation.error}
          onSubmit={handlePayment}
          onCancel={() => setPaymentOpen(false)}
        />
      </Dialog>

      <ConfirmDialog
        open={reversing !== null}
        title="Estornar pagamento"
        message={
          reverseMutation.error
            ? errorMessage(reverseMutation.error)
            : `Estornar o pagamento de ${formatDate(reversing?.paidOn)}? O valor volta para a conta ${reversing?.accountName}, a fatura volta a ficar em aberto e o registro permanece no histórico.`
        }
        confirmLabel="Estornar"
        danger
        busy={reverseMutation.isPending}
        onConfirm={() => {
          if (reversing) {
            reverseMutation.mutate(reversing.id, {
              onSuccess: () => setReversing(null),
            })
          }
        }}
        onCancel={() => setReversing(null)}
      />
    </>
  )
}
