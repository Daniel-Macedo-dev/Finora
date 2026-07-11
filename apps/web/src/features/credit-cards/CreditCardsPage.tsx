import { useState } from 'react'
import { Link } from 'react-router-dom'
import { CreditCard as CreditCardIcon, Plus } from 'lucide-react'
import Dialog from '../../components/Dialog'
import Money from '../../components/Money'
import PageHeader from '../../components/PageHeader'
import { EmptyState, ErrorState, LoadingCards } from '../../components/states'
import { formatDate, formatMonth } from '../../lib/format'
import CardUtilization from './CardUtilization'
import CreditCardForm from './CreditCardForm'
import InvoiceStatusBadge from './InvoiceStatusBadge'
import { useCreateCreditCard, useCreditCards } from './api'
import { CARD_BRAND_LABELS, type CreditCard, type CreditCardRequest } from './types'
import './credit-cards.css'

function CardSummaryTile({ card }: { card: CreditCard }) {
  const nextDue = card.nextDueInvoice
  return (
    <Link to={`/credit-cards/${card.id}`} className={`card cc-card ${card.archived ? 'cc-card-archived' : ''}`}>
      <div className="cc-card-head">
        <span className="cc-card-name">
          <CreditCardIcon size={18} aria-hidden="true" />
          {card.name}
        </span>
        <span className="cc-card-meta">
          {CARD_BRAND_LABELS[card.brand]}
          {card.lastFourDigits ? ` •••• ${card.lastFourDigits}` : ''}
        </span>
      </div>
      {card.archived && <span className="badge badge-neutral">Arquivado</span>}
      <CardUtilization limit={card.limit} />
      <dl className="cc-card-facts">
        <div>
          <dt>Limite</dt>
          <dd>
            <Money value={card.limit.creditLimit} />
          </dd>
        </div>
        <div>
          <dt>Fatura atual</dt>
          <dd>{formatMonth(card.currentCycle.referenceMonth)}</dd>
        </div>
        <div>
          <dt>Fechamento</dt>
          <dd>{formatDate(card.currentCycle.closingDate)}</dd>
        </div>
      </dl>
      {nextDue ? (
        <div className="cc-card-next-due">
          <span>
            Próximo vencimento {formatDate(nextDue.dueDate)} · <Money value={nextDue.outstandingAmount} />
          </span>
          <InvoiceStatusBadge status={nextDue.status} />
        </div>
      ) : (
        <div className="cc-card-next-due cc-card-next-due-empty">Nenhuma fatura em aberto</div>
      )}
    </Link>
  )
}

export default function CreditCardsPage() {
  const [formOpen, setFormOpen] = useState(false)
  const cards = useCreditCards()
  const createMutation = useCreateCreditCard()

  function openCreate() {
    createMutation.reset()
    setFormOpen(true)
  }

  function handleSubmit(request: CreditCardRequest) {
    createMutation.mutate(request, { onSuccess: () => setFormOpen(false) })
  }

  return (
    <>
      <PageHeader
        title="Cartões"
        description="Cartões de crédito, faturas e parcelamentos."
        actions={
          <button type="button" className="btn btn-primary" onClick={openCreate}>
            <Plus size={16} aria-hidden="true" />
            Adicionar cartão
          </button>
        }
      />

      {cards.isPending ? (
        <LoadingCards count={2} height={180} />
      ) : cards.isError ? (
        <ErrorState error={cards.error} onRetry={() => cards.refetch()} />
      ) : cards.data && cards.data.length === 0 ? (
        <EmptyState
          title="Nenhum cartão cadastrado"
          description="Cadastre um cartão para acompanhar faturas, parcelas e limite disponível."
          action={
            <button type="button" className="btn btn-primary" onClick={openCreate}>
              <Plus size={16} aria-hidden="true" />
              Adicionar cartão
            </button>
          }
        />
      ) : cards.data ? (
        <div className="cc-grid">
          {cards.data.map((card) => (
            <CardSummaryTile key={card.id} card={card} />
          ))}
        </div>
      ) : null}

      <Dialog open={formOpen} title="Novo cartão" onClose={() => setFormOpen(false)} wide>
        <CreditCardForm
          busy={createMutation.isPending}
          submitError={createMutation.error}
          onSubmit={handleSubmit}
          onCancel={() => setFormOpen(false)}
        />
      </Dialog>
    </>
  )
}
