import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, Plus, Pencil, ShoppingCart, Trash2 } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import Money from '../../components/Money'
import Dialog from '../../components/Dialog'
import ConfirmDialog from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, LoadingCards } from '../../components/states'
import { formatBRL, formatDate } from '../../lib/format'
import WishlistItemForm from './WishlistItemForm'
import PurchaseOptionForm from './PurchaseOptionForm'
import ExecutePurchaseForm from './ExecutePurchaseDialog'
import AnalysisPanel from './AnalysisPanel'
import {
  useAddOption,
  useDeleteOption,
  useDeleteWishlistItem,
  useExecutePurchase,
  usePurchaseAnalysis,
  useUpdateOption,
  useUpdateWishlistItem,
  useWishlistItem,
} from './api'
import {
  PRIORITY_LABELS,
  STATUS_LABELS,
  type PurchaseOption,
  type PurchaseOptionRequest,
  type WishlistItemRequest,
} from './types'
import './wishlist.css'

export default function WishlistItemPage() {
  const params = useParams<{ id: string }>()
  const navigate = useNavigate()
  const itemId = Number(params.id)

  const item = useWishlistItem(itemId)
  const hasOptions = (item.data?.options.length ?? 0) > 0
  const analysis = usePurchaseAnalysis(itemId, hasOptions)

  const updateItem = useUpdateWishlistItem()
  const deleteItem = useDeleteWishlistItem()
  const addOption = useAddOption(itemId)
  const updateOption = useUpdateOption(itemId)
  const deleteOption = useDeleteOption(itemId)

  const executePurchase = useExecutePurchase(itemId)

  const [editOpen, setEditOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [optionFormOpen, setOptionFormOpen] = useState(false)
  const [editingOption, setEditingOption] = useState<PurchaseOption | null>(null)
  const [deletingOption, setDeletingOption] = useState<PurchaseOption | null>(null)
  const [executingOption, setExecutingOption] = useState<PurchaseOption | null>(null)

  if (!Number.isInteger(itemId)) {
    return (
      <EmptyState
        title="Item não encontrado"
        action={
          <Link className="btn btn-secondary" to="/wishlist">
            Voltar para a lista
          </Link>
        }
      />
    )
  }

  if (item.isPending) {
    return <LoadingCards count={3} height={120} />
  }

  if (item.isError) {
    return <ErrorState error={item.error} onRetry={() => item.refetch()} />
  }

  const data = item.data

  function handleUpdate(request: WishlistItemRequest) {
    updateItem.mutate({ id: itemId, request }, { onSuccess: () => setEditOpen(false) })
  }

  function handleOptionSubmit(request: PurchaseOptionRequest) {
    const onSuccess = () => {
      setOptionFormOpen(false)
      setEditingOption(null)
    }
    if (editingOption) {
      updateOption.mutate({ optionId: editingOption.id, request }, { onSuccess })
    } else {
      addOption.mutate(request, { onSuccess })
    }
  }

  const optionBusy = addOption.isPending || updateOption.isPending

  return (
    <>
      <div style={{ marginBottom: 'var(--space-3)' }}>
        <Link to="/wishlist" className="btn btn-ghost">
          <ArrowLeft size={16} aria-hidden="true" />
          Lista de desejos
        </Link>
      </div>

      <PageHeader
        title={data.name}
        description={data.notes ?? undefined}
        actions={
          <>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => {
                updateItem.reset()
                setEditOpen(true)
              }}
            >
              <Pencil size={15} aria-hidden="true" />
              Editar item
            </button>
            <button
              type="button"
              className="btn btn-danger"
              onClick={() => setDeleteOpen(true)}
            >
              <Trash2 size={15} aria-hidden="true" />
              Excluir
            </button>
          </>
        }
      />

      <div className="wishlist-detail-facts card">
        <div>
          <span className="stat-footnote">Status</span>
          <p>{STATUS_LABELS[data.status]}</p>
        </div>
        <div>
          <span className="stat-footnote">Prioridade</span>
          <p>{PRIORITY_LABELS[data.priority]}</p>
        </div>
        {data.category && (
          <div>
            <span className="stat-footnote">Categoria</span>
            <p>{data.category.name}</p>
          </div>
        )}
        {data.referencePrice !== null && (
          <div>
            <span className="stat-footnote">Preço de referência</span>
            <p>{formatBRL(data.referencePrice)}</p>
          </div>
        )}
        {data.targetPrice !== null && (
          <div>
            <span className="stat-footnote">Preço alvo</span>
            <p>{formatBRL(data.targetPrice)}</p>
          </div>
        )}
        {data.desiredDate && (
          <div>
            <span className="stat-footnote">Data desejada</span>
            <p>{formatDate(data.desiredDate)}</p>
          </div>
        )}
      </div>

      <section className="wishlist-detail-section" aria-label="Opções de compra">
        <div className="wishlist-detail-section-header">
          <h2>Opções de compra</h2>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => {
              setEditingOption(null)
              addOption.reset()
              updateOption.reset()
              setOptionFormOpen(true)
            }}
          >
            <Plus size={16} aria-hidden="true" />
            Nova opção
          </button>
        </div>

        {data.options.length === 0 ? (
          <EmptyState
            title="Nenhuma opção de compra cadastrada"
            description="Cadastre as ofertas que você encontrou (à vista e parcelado) para o Finora comparar custos, valor presente e impacto no seu caixa."
          />
        ) : (
          <ul className="option-list">
            {data.options.map((option) => (
              <li key={option.id} className="card option-row">
                <div className="option-info">
                  <span className="option-merchant">{option.merchant}</span>
                  <span className="stat-footnote">
                    {option.kind === 'CASH'
                      ? `À vista · ${formatBRL(option.basePrice)}`
                      : `${option.installmentCount}× de ${formatBRL(option.installmentAmount)} · total ${formatBRL(option.basePrice)}`}
                    {option.shipping > 0 && ` · frete ${formatBRL(option.shipping)}`}
                    {option.fees > 0 && ` · taxas ${formatBRL(option.fees)}`}
                    {option.creditCardName && ` · cartão ${option.creditCardName}`}
                  </span>
                  {option.notes && <span className="option-notes">{option.notes}</span>}
                </div>
                <div className="option-cost">
                  <span className="stat-footnote">Custo total</span>
                  <Money value={option.nominalCost} />
                </div>
                <div className="option-actions">
                  {data.status !== 'PURCHASED' && (
                    <button
                      type="button"
                      className="btn btn-secondary"
                      onClick={() => {
                        executePurchase.reset()
                        setExecutingOption(option)
                      }}
                    >
                      <ShoppingCart size={15} aria-hidden="true" />
                      Comprar
                    </button>
                  )}
                  <button
                    type="button"
                    className="btn btn-ghost btn-icon"
                    onClick={() => {
                      setEditingOption(option)
                      addOption.reset()
                      updateOption.reset()
                      setOptionFormOpen(true)
                    }}
                    aria-label={`Editar opção de ${option.merchant}`}
                  >
                    <Pencil size={16} aria-hidden="true" />
                  </button>
                  <button
                    type="button"
                    className="btn btn-ghost btn-icon"
                    onClick={() => setDeletingOption(option)}
                    aria-label={`Excluir opção de ${option.merchant}`}
                  >
                    <Trash2 size={16} aria-hidden="true" />
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="wishlist-detail-section" aria-label="Análise de compra">
        <div className="wishlist-detail-section-header">
          <h2>Análise de compra</h2>
        </div>
        {!hasOptions ? (
          <p className="panel-empty card" style={{ padding: 'var(--space-4)' }}>
            A análise aparece assim que houver pelo menos uma opção de compra cadastrada.
          </p>
        ) : analysis.isPending ? (
          <LoadingCards count={2} height={120} />
        ) : analysis.isError ? (
          <ErrorState error={analysis.error} onRetry={() => analysis.refetch()} />
        ) : (
          <div className="card">
            <AnalysisPanel analysis={analysis.data} />
          </div>
        )}
      </section>

      <Dialog open={editOpen} title="Editar item" onClose={() => setEditOpen(false)} wide>
        <WishlistItemForm
          key={data.id}
          initial={data}
          busy={updateItem.isPending}
          submitError={updateItem.error}
          onSubmit={handleUpdate}
          onCancel={() => setEditOpen(false)}
        />
      </Dialog>

      <Dialog
        open={optionFormOpen}
        title={editingOption ? `Editar opção — ${editingOption.merchant}` : 'Nova opção de compra'}
        onClose={() => setOptionFormOpen(false)}
        wide
      >
        <PurchaseOptionForm
          key={editingOption?.id ?? 'new'}
          initial={editingOption ?? undefined}
          busy={optionBusy}
          submitError={editingOption ? updateOption.error : addOption.error}
          onSubmit={handleOptionSubmit}
          onCancel={() => setOptionFormOpen(false)}
        />
      </Dialog>

      <ConfirmDialog
        open={deleteOpen}
        title="Excluir item"
        message={`Excluir "${data.name}" e todas as suas opções de compra?`}
        confirmLabel="Excluir"
        danger
        busy={deleteItem.isPending}
        onConfirm={() =>
          deleteItem.mutate(itemId, {
            onSuccess: () => navigate('/wishlist'),
          })
        }
        onCancel={() => setDeleteOpen(false)}
      />

      <Dialog
        open={executingOption !== null}
        title={executingOption ? `Comprar — ${executingOption.merchant}` : 'Comprar'}
        onClose={() => setExecutingOption(null)}
        wide
      >
        {executingOption && (
          <ExecutePurchaseForm
            key={executingOption.id}
            option={executingOption}
            busy={executePurchase.isPending}
            submitError={executePurchase.error}
            onSubmit={(request) =>
              executePurchase.mutate(request, {
                onSuccess: () => setExecutingOption(null),
              })
            }
            onCancel={() => setExecutingOption(null)}
          />
        )}
      </Dialog>

      <ConfirmDialog
        open={deletingOption !== null}
        title="Excluir opção"
        message={`Excluir a opção de ${deletingOption?.merchant}?`}
        confirmLabel="Excluir"
        danger
        busy={deleteOption.isPending}
        onConfirm={() =>
          deletingOption &&
          deleteOption.mutate(deletingOption.id, {
            onSuccess: () => setDeletingOption(null),
          })
        }
        onCancel={() => setDeletingOption(null)}
      />
    </>
  )
}
