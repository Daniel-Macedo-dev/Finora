import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Archive, ArchiveRestore, ArrowLeft, Pencil, Plus, XCircle } from 'lucide-react'
import ConfirmDialog from '../../components/ConfirmDialog'
import Dialog from '../../components/Dialog'
import Money from '../../components/Money'
import PageHeader from '../../components/PageHeader'
import { EmptyState, ErrorState, LoadingCards, errorMessage } from '../../components/states'
import { formatDate, formatMonth } from '../../lib/format'
import CardUtilization from './CardUtilization'
import CreditCardForm from './CreditCardForm'
import CreditPurchaseForm from './CreditPurchaseForm'
import InvoiceStatusBadge from './InvoiceStatusBadge'
import {
  useArchiveCreditCard,
  useCancelPurchase,
  useCardInvoices,
  useCardPurchases,
  useCreatePurchase,
  useCreditCard,
  useUpdateCreditCard,
  useUpdatePurchase,
} from './api'
import {
  CARD_BRAND_LABELS,
  type CardPurchase,
  type CreditCardRequest,
  type PurchaseRequest,
} from './types'
import './credit-cards.css'

export default function CreditCardDetailPage() {
  const params = useParams()
  const cardId = Number(params.cardId)

  const card = useCreditCard(cardId)
  const invoices = useCardInvoices(cardId)
  const [purchasePage, setPurchasePage] = useState(0)
  const purchases = useCardPurchases(cardId, purchasePage)

  const [editOpen, setEditOpen] = useState(false)
  const [purchaseFormOpen, setPurchaseFormOpen] = useState(false)
  const [editingPurchase, setEditingPurchase] = useState<CardPurchase | null>(null)
  const [cancelling, setCancelling] = useState<CardPurchase | null>(null)
  const [archiveConfirm, setArchiveConfirm] = useState(false)

  const updateCard = useUpdateCreditCard()
  const archiveCard = useArchiveCreditCard()
  const createPurchase = useCreatePurchase(cardId)
  const updatePurchase = useUpdatePurchase(cardId)
  const cancelPurchase = useCancelPurchase(cardId)

  if (card.isPending) {
    return <LoadingCards count={3} height={140} />
  }
  if (card.isError) {
    return <ErrorState error={card.error} onRetry={() => card.refetch()} />
  }
  const data = card.data
  if (!data) {
    return null
  }

  function openNewPurchase() {
    setEditingPurchase(null)
    createPurchase.reset()
    updatePurchase.reset()
    setPurchaseFormOpen(true)
  }

  function openEditPurchase(purchase: CardPurchase) {
    setEditingPurchase(purchase)
    createPurchase.reset()
    updatePurchase.reset()
    setPurchaseFormOpen(true)
  }

  function handlePurchaseSubmit(request: PurchaseRequest) {
    const onSuccess = () => {
      setPurchaseFormOpen(false)
      setEditingPurchase(null)
    }
    if (editingPurchase) {
      updatePurchase.mutate({ purchaseId: editingPurchase.id, request }, { onSuccess })
    } else {
      createPurchase.mutate(request, { onSuccess })
    }
  }

  function handleCardSubmit(request: CreditCardRequest) {
    updateCard.mutate({ id: cardId, request }, { onSuccess: () => setEditOpen(false) })
  }

  function handleArchive() {
    archiveCard.mutate(
      { id: cardId, archive: !data!.archived },
      {
        onSuccess: () => setArchiveConfirm(false),
        onError: () => undefined,
      },
    )
  }

  const invoiceList = invoices.data ?? []
  const purchaseData = purchases.data

  return (
    <>
      <PageHeader
        title={data.name}
        description={`${CARD_BRAND_LABELS[data.brand]}${
          data.lastFourDigits ? ` •••• ${data.lastFourDigits}` : ''
        } · fecha dia ${data.closingDay} · vence dia ${data.dueDay}`}
        actions={
          <div className="cc-detail-actions">
            <Link to="/credit-cards" className="btn btn-secondary">
              <ArrowLeft size={16} aria-hidden="true" />
              Cartões
            </Link>
            <button type="button" className="btn btn-secondary" onClick={() => setEditOpen(true)}>
              <Pencil size={16} aria-hidden="true" />
              Editar
            </button>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => setArchiveConfirm(true)}
            >
              {data.archived ? (
                <ArchiveRestore size={16} aria-hidden="true" />
              ) : (
                <Archive size={16} aria-hidden="true" />
              )}
              {data.archived ? 'Desarquivar' : 'Arquivar'}
            </button>
            {!data.archived && (
              <button type="button" className="btn btn-primary" onClick={openNewPurchase}>
                <Plus size={16} aria-hidden="true" />
                Nova compra
              </button>
            )}
          </div>
        }
      />

      {data.archived && (
        <p className="cc-archived-note" role="status">
          Este cartão está arquivado: o histórico permanece, mas ele não aceita novas compras.
        </p>
      )}

      <section className="card cc-section" aria-labelledby="cc-limit-heading">
        <h2 id="cc-limit-heading" className="cc-section-title">
          Limite
        </h2>
        <CardUtilization limit={data.limit} />
        <p className="cc-cycle-note">
          Compras feitas hoje entram na fatura de {formatMonth(data.currentCycle.referenceMonth)}{' '}
          (fecha em {formatDate(data.currentCycle.closingDate)}, vence em{' '}
          {formatDate(data.currentCycle.dueDate)}).
        </p>
      </section>

      <section className="card cc-section" aria-labelledby="cc-invoices-heading">
        <h2 id="cc-invoices-heading" className="cc-section-title">
          Faturas
        </h2>
        {invoices.isPending ? (
          <LoadingCards count={2} height={48} />
        ) : invoices.isError ? (
          <ErrorState error={invoices.error} onRetry={() => invoices.refetch()} />
        ) : invoiceList.length === 0 ? (
          <EmptyState
            title="Nenhuma fatura ainda"
            description="A primeira compra cria a fatura do ciclo correspondente."
          />
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Mês</th>
                  <th scope="col" className="cc-col-optional">
                    Fechamento
                  </th>
                  <th scope="col">Vencimento</th>
                  <th scope="col">Status</th>
                  <th scope="col" className="cc-col-optional" style={{ textAlign: 'right' }}>
                    Total
                  </th>
                  <th scope="col" style={{ textAlign: 'right' }}>
                    Em aberto
                  </th>
                </tr>
              </thead>
              <tbody>
                {invoiceList.map((invoice) => (
                  <tr key={invoice.id}>
                    <td>
                      <Link
                        to={`/credit-cards/${cardId}/invoices/${invoice.id}`}
                        className="cc-invoice-link"
                      >
                        {formatMonth(invoice.referenceMonth)}
                      </Link>
                    </td>
                    <td className="cc-col-optional">{formatDate(invoice.closingDate)}</td>
                    <td>{formatDate(invoice.dueDate)}</td>
                    <td>
                      <InvoiceStatusBadge status={invoice.status} />
                    </td>
                    <td className="cc-col-optional" style={{ textAlign: 'right' }}>
                      <Money value={invoice.invoiceTotal} />
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <Money value={invoice.outstandingAmount} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card cc-section" aria-labelledby="cc-purchases-heading">
        <h2 id="cc-purchases-heading" className="cc-section-title">
          Compras
        </h2>
        {purchases.isPending ? (
          <LoadingCards count={2} height={48} />
        ) : purchases.isError ? (
          <ErrorState error={purchases.error} onRetry={() => purchases.refetch()} />
        ) : purchaseData && purchaseData.content.length === 0 ? (
          <EmptyState
            title="Nenhuma compra registrada"
            description="Registre a primeira compra deste cartão."
            action={
              !data.archived ? (
                <button type="button" className="btn btn-primary" onClick={openNewPurchase}>
                  <Plus size={16} aria-hidden="true" />
                  Nova compra
                </button>
              ) : undefined
            }
          />
        ) : purchaseData ? (
          <>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th scope="col" className="cc-col-optional">
                      Data
                    </th>
                    <th scope="col">Descrição</th>
                    <th scope="col" className="cc-col-optional">
                      Categoria
                    </th>
                    <th scope="col">Parcelas</th>
                    <th scope="col" style={{ textAlign: 'right' }}>
                      Total
                    </th>
                    <th scope="col">
                      <span className="visually-hidden">Ações</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {purchaseData.content.map((purchase) => (
                    <tr
                      key={purchase.id}
                      className={purchase.status === 'CANCELLED' ? 'cc-row-cancelled' : ''}
                    >
                      <td className="cc-col-optional">{formatDate(purchase.purchaseDate)}</td>
                      <td className="cc-purchase-desc">
                        {purchase.description}
                        {purchase.status === 'CANCELLED' && (
                          <span className="badge badge-neutral">Cancelada</span>
                        )}
                      </td>
                      <td className="cc-col-optional">
                        <span className="badge badge-neutral">{purchase.category.name}</span>
                      </td>
                      <td>{purchase.installmentCount}×</td>
                      <td style={{ textAlign: 'right' }}>
                        <Money value={purchase.totalAmount} />
                      </td>
                      <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                        {purchase.status === 'ACTIVE' && (
                          <>
                            <button
                              type="button"
                              className="btn btn-ghost btn-icon"
                              onClick={() => openEditPurchase(purchase)}
                              aria-label={`Editar ${purchase.description}`}
                            >
                              <Pencil size={16} aria-hidden="true" />
                            </button>
                            <button
                              type="button"
                              className="btn btn-ghost btn-icon"
                              onClick={() => setCancelling(purchase)}
                              aria-label={`Cancelar ${purchase.description}`}
                            >
                              <XCircle size={16} aria-hidden="true" />
                            </button>
                          </>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {purchaseData.totalPages > 1 && (
              <nav className="cc-pagination" aria-label="Paginação de compras">
                <button
                  type="button"
                  className="btn btn-secondary"
                  disabled={purchasePage === 0}
                  onClick={() => setPurchasePage((value) => value - 1)}
                >
                  Anterior
                </button>
                <span className="cc-pagination-info">
                  Página {purchaseData.page + 1} de {purchaseData.totalPages}
                </span>
                <button
                  type="button"
                  className="btn btn-secondary"
                  disabled={purchaseData.page + 1 >= purchaseData.totalPages}
                  onClick={() => setPurchasePage((value) => value + 1)}
                >
                  Próxima
                </button>
              </nav>
            )}
          </>
        ) : null}
      </section>

      <Dialog open={editOpen} title="Editar cartão" onClose={() => setEditOpen(false)} wide>
        <CreditCardForm
          key={data.id}
          initial={data}
          busy={updateCard.isPending}
          submitError={updateCard.error}
          onSubmit={handleCardSubmit}
          onCancel={() => setEditOpen(false)}
        />
      </Dialog>

      <Dialog
        open={purchaseFormOpen}
        title={editingPurchase ? 'Editar compra' : 'Nova compra no cartão'}
        onClose={() => setPurchaseFormOpen(false)}
        wide
      >
        <CreditPurchaseForm
          key={editingPurchase?.id ?? 'new'}
          card={data}
          initial={editingPurchase ?? undefined}
          busy={createPurchase.isPending || updatePurchase.isPending}
          submitError={editingPurchase ? updatePurchase.error : createPurchase.error}
          onSubmit={handlePurchaseSubmit}
          onCancel={() => setPurchaseFormOpen(false)}
        />
      </Dialog>

      <ConfirmDialog
        open={cancelling !== null}
        title="Cancelar compra"
        message={
          cancelPurchase.error
            ? errorMessage(cancelPurchase.error)
            : `Cancelar "${cancelling?.description}"? As parcelas deixam de contar nas faturas e o limite é liberado. O registro permanece no histórico.`
        }
        confirmLabel="Cancelar compra"
        danger
        busy={cancelPurchase.isPending}
        onConfirm={() => {
          if (cancelling) {
            cancelPurchase.mutate(cancelling.id, {
              onSuccess: () => {
                setCancelling(null)
                cancelPurchase.reset()
              },
            })
          }
        }}
        onCancel={() => {
          setCancelling(null)
          cancelPurchase.reset()
        }}
      />

      <ConfirmDialog
        open={archiveConfirm}
        title={data.archived ? 'Desarquivar cartão' : 'Arquivar cartão'}
        message={
          archiveCard.error
            ? errorMessage(archiveCard.error)
            : data.archived
              ? 'O cartão voltará a aceitar novas compras.'
              : 'O cartão deixa de aceitar novas compras; faturas e histórico permanecem. Cartões com saldo em aberto não podem ser arquivados.'
        }
        confirmLabel={data.archived ? 'Desarquivar' : 'Arquivar'}
        busy={archiveCard.isPending}
        onConfirm={handleArchive}
        onCancel={() => {
          setArchiveConfirm(false)
          archiveCard.reset()
        }}
      />
    </>
  )
}
