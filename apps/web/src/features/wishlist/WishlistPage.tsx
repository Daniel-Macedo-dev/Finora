import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Plus, Heart } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import Money from '../../components/Money'
import Dialog from '../../components/Dialog'
import { EmptyState, ErrorState, LoadingCards } from '../../components/states'
import { formatDate } from '../../lib/format'
import WishlistItemForm from './WishlistItemForm'
import { useCreateWishlistItem, useWishlist } from './api'
import {
  PRIORITY_LABELS,
  STATUS_LABELS,
  type WishlistItemRequest,
  type WishlistPriority,
  type WishlistStatus,
} from './types'
import './wishlist.css'

const STATUS_BADGES: Record<WishlistStatus, string> = {
  PLANNING: 'badge-neutral',
  MONITORING: 'badge-info',
  READY_TO_BUY: 'badge-positive',
  PURCHASED: 'badge-positive',
  ARCHIVED: 'badge-neutral',
}

const PRIORITY_BADGES: Record<WishlistPriority, string> = {
  LOW: 'badge-neutral',
  MEDIUM: 'badge-info',
  HIGH: 'badge-warning',
  ESSENTIAL: 'badge-negative',
}

export default function WishlistPage() {
  const [formOpen, setFormOpen] = useState(false)
  const wishlist = useWishlist()
  const createMutation = useCreateWishlistItem()

  function handleCreate(request: WishlistItemRequest) {
    createMutation.mutate(request, { onSuccess: () => setFormOpen(false) })
  }

  return (
    <>
      <PageHeader
        title="Lista de desejos"
        description="Planeje compras, compare opções de pagamento e saiba a hora certa de comprar."
        actions={
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => {
              createMutation.reset()
              setFormOpen(true)
            }}
          >
            <Plus size={16} aria-hidden="true" />
            Novo item
          </button>
        }
      />

      {wishlist.isPending ? (
        <LoadingCards count={3} height={96} />
      ) : wishlist.isError ? (
        <ErrorState error={wishlist.error} onRetry={() => wishlist.refetch()} />
      ) : wishlist.data && wishlist.data.length === 0 ? (
        <EmptyState
          title="Sua lista de desejos está vazia"
          description="Adicione um item que você pretende comprar, cadastre as opções de pagamento e deixe o Finora analisar qual vale mais a pena."
          action={
            <button type="button" className="btn btn-primary" onClick={() => setFormOpen(true)}>
              <Plus size={16} aria-hidden="true" />
              Novo item
            </button>
          }
        />
      ) : wishlist.data ? (
        <ul className="wishlist-grid">
          {wishlist.data.map((item) => (
            <li key={item.id} className="card wishlist-card">
              <div className="wishlist-card-header">
                <Heart size={16} aria-hidden="true" className="wishlist-heart" />
                <Link to={`/wishlist/${item.id}`} className="wishlist-name">
                  {item.name}
                </Link>
              </div>
              <div className="wishlist-badges">
                <span className={`badge ${STATUS_BADGES[item.status]}`}>
                  {STATUS_LABELS[item.status]}
                </span>
                <span className={`badge ${PRIORITY_BADGES[item.priority]}`}>
                  Prioridade {PRIORITY_LABELS[item.priority].toLowerCase()}
                </span>
              </div>
              <dl className="wishlist-facts">
                <div>
                  <dt>Melhor preço cadastrado</dt>
                  <dd>
                    {item.bestNominalCost !== null ? (
                      <Money value={item.bestNominalCost} />
                    ) : (
                      <span className="stat-footnote">
                        {item.optionCount === 0 ? 'Sem opções ainda' : '—'}
                      </span>
                    )}
                  </dd>
                </div>
                {item.targetPrice !== null && (
                  <div>
                    <dt>Preço alvo</dt>
                    <dd>
                      <Money value={item.targetPrice} />
                    </dd>
                  </div>
                )}
                {item.desiredDate && (
                  <div>
                    <dt>Data desejada</dt>
                    <dd>{formatDate(item.desiredDate)}</dd>
                  </div>
                )}
              </dl>
              <div className="wishlist-card-footer">
                <span className="stat-footnote">
                  {item.optionCount === 0
                    ? 'Nenhuma opção de compra'
                    : item.optionCount === 1
                      ? '1 opção de compra'
                      : `${item.optionCount} opções de compra`}
                </span>
                <Link to={`/wishlist/${item.id}`} className="btn btn-secondary">
                  Ver análise
                </Link>
              </div>
            </li>
          ))}
        </ul>
      ) : null}

      <Dialog open={formOpen} title="Novo item" onClose={() => setFormOpen(false)} wide>
        <WishlistItemForm
          busy={createMutation.isPending}
          submitError={createMutation.error}
          onSubmit={handleCreate}
          onCancel={() => setFormOpen(false)}
        />
      </Dialog>
    </>
  )
}
