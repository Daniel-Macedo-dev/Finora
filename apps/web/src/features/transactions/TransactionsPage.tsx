import { useState } from 'react'
import { Plus, Pencil, Trash2 } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import MonthPicker from '../../components/MonthPicker'
import Money from '../../components/Money'
import Dialog from '../../components/Dialog'
import ConfirmDialog from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, LoadingCards } from '../../components/states'
import { currentMonth } from '../../lib/month'
import { formatDate } from '../../lib/format'
import { useCategories } from '../shared/api'
import { PAYMENT_METHOD_LABELS, type TransactionType } from '../shared/types'
import TransactionForm from './TransactionForm'
import {
  useCreateTransaction,
  useDeleteTransaction,
  useTransactions,
  useUpdateTransaction,
} from './api'
import type { Transaction, TransactionRequest } from './types'
import './transactions.css'

export default function TransactionsPage() {
  const [month, setMonth] = useState(currentMonth())
  const [type, setType] = useState<TransactionType | ''>('')
  const [categoryId, setCategoryId] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)

  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Transaction | null>(null)
  const [deleting, setDeleting] = useState<Transaction | null>(null)

  const filters = {
    month,
    type: type || undefined,
    categoryId: categoryId ? Number(categoryId) : undefined,
    search: search || undefined,
    page,
  }
  const transactions = useTransactions(filters)
  const categories = useCategories()
  const createMutation = useCreateTransaction()
  const updateMutation = useUpdateTransaction()
  const deleteMutation = useDeleteTransaction()

  function resetToFirstPage() {
    setPage(0)
  }

  function openCreate() {
    setEditing(null)
    createMutation.reset()
    updateMutation.reset()
    setFormOpen(true)
  }

  function openEdit(transaction: Transaction) {
    setEditing(transaction)
    createMutation.reset()
    updateMutation.reset()
    setFormOpen(true)
  }

  function handleSubmit(request: TransactionRequest) {
    const onSuccess = () => {
      setFormOpen(false)
      setEditing(null)
    }
    if (editing) {
      updateMutation.mutate({ id: editing.id, request }, { onSuccess })
    } else {
      createMutation.mutate(request, { onSuccess })
    }
  }

  function handleDelete() {
    if (!deleting) {
      return
    }
    deleteMutation.mutate(deleting.id, {
      onSuccess: () => setDeleting(null),
    })
  }

  const data = transactions.data
  const busy = createMutation.isPending || updateMutation.isPending

  return (
    <>
      <PageHeader
        title="Transações"
        description="Registre e acompanhe suas receitas e despesas."
        actions={
          <button type="button" className="btn btn-primary" onClick={openCreate}>
            <Plus size={16} aria-hidden="true" />
            Nova transação
          </button>
        }
      />

      <div className="tx-filters">
        <MonthPicker
          month={month}
          onChange={(value) => {
            setMonth(value)
            resetToFirstPage()
          }}
        />
        <select
          className="select tx-filter"
          aria-label="Filtrar por tipo"
          value={type}
          onChange={(event) => {
            setType(event.target.value as TransactionType | '')
            resetToFirstPage()
          }}
        >
          <option value="">Todos os tipos</option>
          <option value="EXPENSE">Despesas</option>
          <option value="INCOME">Receitas</option>
        </select>
        <select
          className="select tx-filter"
          aria-label="Filtrar por categoria"
          value={categoryId}
          onChange={(event) => {
            setCategoryId(event.target.value)
            resetToFirstPage()
          }}
        >
          <option value="">Todas as categorias</option>
          {(categories.data ?? []).map((category) => (
            <option key={category.id} value={category.id}>
              {category.name} ({category.type === 'EXPENSE' ? 'despesa' : 'receita'})
            </option>
          ))}
        </select>
        <input
          className="input tx-filter tx-search"
          type="search"
          placeholder="Buscar por descrição…"
          aria-label="Buscar por descrição"
          value={search}
          onChange={(event) => {
            setSearch(event.target.value)
            resetToFirstPage()
          }}
        />
      </div>

      {transactions.isPending ? (
        <LoadingCards count={4} height={64} />
      ) : transactions.isError ? (
        <ErrorState error={transactions.error} onRetry={() => transactions.refetch()} />
      ) : data && data.content.length === 0 ? (
        <EmptyState
          title="Nenhuma transação encontrada"
          description={
            search || type || categoryId
              ? 'Nenhuma transação corresponde aos filtros selecionados.'
              : 'Comece registrando sua primeira receita ou despesa deste mês.'
          }
          action={
            <button type="button" className="btn btn-primary" onClick={openCreate}>
              <Plus size={16} aria-hidden="true" />
              Nova transação
            </button>
          }
        />
      ) : data ? (
        <>
          <div className="card table-wrap" style={{ padding: 0 }}>
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Data</th>
                  <th scope="col">Descrição</th>
                  <th scope="col">Categoria</th>
                  <th scope="col" className="tx-col-optional">
                    Pagamento
                  </th>
                  <th scope="col" style={{ textAlign: 'right' }}>
                    Valor
                  </th>
                  <th scope="col">
                    <span className="visually-hidden">Ações</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((transaction) => (
                  <tr key={transaction.id}>
                    <td>{formatDate(transaction.date)}</td>
                    <td className="tx-description">{transaction.description}</td>
                    <td>
                      <span className="badge badge-neutral">{transaction.category.name}</span>
                    </td>
                    <td className="tx-col-optional">
                      {transaction.paymentMethod
                        ? PAYMENT_METHOD_LABELS[transaction.paymentMethod]
                        : '—'}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <Money
                        value={
                          transaction.type === 'EXPENSE'
                            ? -transaction.amount
                            : transaction.amount
                        }
                        signed
                      />
                    </td>
                    <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                      <button
                        type="button"
                        className="btn btn-ghost btn-icon"
                        onClick={() => openEdit(transaction)}
                        aria-label={`Editar ${transaction.description}`}
                      >
                        <Pencil size={16} aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="btn btn-ghost btn-icon"
                        onClick={() => setDeleting(transaction)}
                        aria-label={`Excluir ${transaction.description}`}
                      >
                        <Trash2 size={16} aria-hidden="true" />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {data.totalPages > 1 && (
            <nav className="tx-pagination" aria-label="Paginação">
              <button
                type="button"
                className="btn btn-secondary"
                disabled={page === 0}
                onClick={() => setPage((value) => value - 1)}
              >
                Anterior
              </button>
              <span style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)' }}>
                Página {data.page + 1} de {data.totalPages}
              </span>
              <button
                type="button"
                className="btn btn-secondary"
                disabled={data.page + 1 >= data.totalPages}
                onClick={() => setPage((value) => value + 1)}
              >
                Próxima
              </button>
            </nav>
          )}
        </>
      ) : null}

      <Dialog
        open={formOpen}
        title={editing ? 'Editar transação' : 'Nova transação'}
        onClose={() => setFormOpen(false)}
        wide
      >
        <TransactionForm
          key={editing?.id ?? 'new'}
          initial={editing ?? undefined}
          busy={busy}
          submitError={editing ? updateMutation.error : createMutation.error}
          onSubmit={handleSubmit}
          onCancel={() => setFormOpen(false)}
        />
      </Dialog>

      <ConfirmDialog
        open={deleting !== null}
        title="Excluir transação"
        message={`Excluir "${deleting?.description}"? Essa ação não pode ser desfeita.`}
        confirmLabel="Excluir"
        danger
        busy={deleteMutation.isPending}
        onConfirm={handleDelete}
        onCancel={() => setDeleting(null)}
      />
    </>
  )
}
