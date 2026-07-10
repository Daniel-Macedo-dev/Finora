import { useState, type FormEvent } from 'react'
import { Plus, Pencil, Trash2, AlertTriangle, AlertOctagon, CheckCircle2 } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import MonthPicker from '../../components/MonthPicker'
import Money from '../../components/Money'
import Dialog from '../../components/Dialog'
import ConfirmDialog from '../../components/ConfirmDialog'
import FormField from '../../components/FormField'
import { EmptyState, ErrorState, LoadingCards, errorMessage } from '../../components/states'
import { currentMonth } from '../../lib/month'
import { formatBRL, formatPercent } from '../../lib/format'
import { parseMoneyInput } from '../../lib/format'
import { useCategories } from '../shared/api'
import { useBudgets, useCreateBudget, useDeleteBudget, useUpdateBudget } from './api'
import type { Budget, BudgetStatus } from './types'
import './budgets.css'

const STATUS_META: Record<
  BudgetStatus,
  { label: string; badge: string; icon: typeof CheckCircle2 }
> = {
  HEALTHY: { label: 'Saudável', badge: 'badge-positive', icon: CheckCircle2 },
  WARNING: { label: 'Perto do limite', badge: 'badge-warning', icon: AlertTriangle },
  EXCEEDED: { label: 'Estourado', badge: 'badge-negative', icon: AlertOctagon },
}

interface BudgetFormState {
  categoryId: string
  limit: string
}

export default function BudgetsPage() {
  const [month, setMonth] = useState(currentMonth())
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Budget | null>(null)
  const [deleting, setDeleting] = useState<Budget | null>(null)
  const [form, setForm] = useState<BudgetFormState>({ categoryId: '', limit: '' })
  const [formError, setFormError] = useState<string | null>(null)

  const budgets = useBudgets(month)
  const categories = useCategories('EXPENSE')
  const createMutation = useCreateBudget()
  const updateMutation = useUpdateBudget()
  const deleteMutation = useDeleteBudget()

  const usedCategoryIds = new Set(
    (budgets.data?.budgets ?? []).map((budget) => budget.category.id),
  )
  const availableCategories = (categories.data ?? []).filter(
    (category) =>
      category.active &&
      (!usedCategoryIds.has(category.id) || String(category.id) === form.categoryId),
  )

  function openCreate() {
    setEditing(null)
    setForm({ categoryId: '', limit: '' })
    setFormError(null)
    createMutation.reset()
    updateMutation.reset()
    setFormOpen(true)
  }

  function openEdit(budget: Budget) {
    setEditing(budget)
    setForm({
      categoryId: String(budget.category.id),
      limit: budget.limitAmount.toFixed(2).replace('.', ','),
    })
    setFormError(null)
    createMutation.reset()
    updateMutation.reset()
    setFormOpen(true)
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const limit = parseMoneyInput(form.limit)
    if (!form.categoryId) {
      setFormError('Selecione a categoria.')
      return
    }
    if (limit === null || limit <= 0) {
      setFormError('Informe um limite maior que zero.')
      return
    }
    setFormError(null)
    const request = { month, categoryId: Number(form.categoryId), limitAmount: limit }
    const onSuccess = () => setFormOpen(false)
    if (editing) {
      updateMutation.mutate({ id: editing.id, request }, { onSuccess })
    } else {
      createMutation.mutate(request, { onSuccess })
    }
  }

  const busy = createMutation.isPending || updateMutation.isPending
  const submitError = editing ? updateMutation.error : createMutation.error
  const data = budgets.data

  return (
    <>
      <PageHeader
        title="Orçamentos"
        description="Defina limites mensais por categoria e acompanhe o consumo real."
        actions={
          <>
            <MonthPicker month={month} onChange={setMonth} />
            <button type="button" className="btn btn-primary" onClick={openCreate}>
              <Plus size={16} aria-hidden="true" />
              Novo orçamento
            </button>
          </>
        }
      />

      {budgets.isPending ? (
        <LoadingCards count={3} height={88} />
      ) : budgets.isError ? (
        <ErrorState error={budgets.error} onRetry={() => budgets.refetch()} />
      ) : data && data.budgets.length === 0 ? (
        <EmptyState
          title="Nenhum orçamento para este mês"
          description="Crie limites por categoria para acompanhar quanto do planejado já foi consumido."
          action={
            <button type="button" className="btn btn-primary" onClick={openCreate}>
              <Plus size={16} aria-hidden="true" />
              Novo orçamento
            </button>
          }
        />
      ) : data ? (
        <>
          <div className="card budget-summary">
            <div>
              <span className="stat-footnote">Total consumido</span>
              <p className="budget-summary-value">
                {formatBRL(data.totalConsumed)}{' '}
                <span className="stat-footnote">
                  de {formatBRL(data.totalLimit)} ({formatPercent(data.percentUsed)})
                </span>
              </p>
            </div>
            <div>
              <span className="stat-footnote">Restante</span>
              <p className="budget-summary-value">
                <Money value={data.totalRemaining} signed />
              </p>
            </div>
          </div>

          <ul className="budget-list">
            {data.budgets.map((budget) => {
              const meta = STATUS_META[budget.status]
              const StatusIcon = meta.icon
              return (
                <li key={budget.id} className="card budget-row">
                  <div className="budget-row-header">
                    <span className="budget-category">{budget.category.name}</span>
                    <span className={`badge ${meta.badge}`}>
                      <StatusIcon size={13} aria-hidden="true" />
                      {meta.label}
                    </span>
                    <span className="budget-actions">
                      <button
                        type="button"
                        className="btn btn-ghost btn-icon"
                        onClick={() => openEdit(budget)}
                        aria-label={`Editar orçamento de ${budget.category.name}`}
                      >
                        <Pencil size={16} aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="btn btn-ghost btn-icon"
                        onClick={() => setDeleting(budget)}
                        aria-label={`Excluir orçamento de ${budget.category.name}`}
                      >
                        <Trash2 size={16} aria-hidden="true" />
                      </button>
                    </span>
                  </div>
                  <div
                    className="budget-track"
                    role="img"
                    aria-label={`${budget.category.name}: ${formatPercent(budget.percentUsed)} do limite consumido`}
                  >
                    <div
                      className={`budget-fill budget-fill-${budget.status.toLowerCase()}`}
                      style={{ width: `${Math.min(budget.percentUsed, 100)}%` }}
                    />
                  </div>
                  <div className="budget-row-footer">
                    <span>
                      {formatBRL(budget.consumedAmount)} de {formatBRL(budget.limitAmount)}
                    </span>
                    <span>
                      {budget.remainingAmount >= 0
                        ? `Restam ${formatBRL(budget.remainingAmount)}`
                        : `${formatBRL(Math.abs(budget.remainingAmount))} acima do limite`}
                    </span>
                  </div>
                </li>
              )
            })}
          </ul>
        </>
      ) : null}

      <Dialog
        open={formOpen}
        title={editing ? `Editar orçamento — ${editing.category.name}` : 'Novo orçamento'}
        onClose={() => setFormOpen(false)}
      >
        <form onSubmit={handleSubmit} noValidate>
          <div style={{ display: 'grid', gap: 'var(--space-3)' }}>
            {!editing && (
              <FormField label="Categoria" hint="Apenas categorias de despesa.">
                <select
                  className="select"
                  value={form.categoryId}
                  onChange={(event) =>
                    setForm((state) => ({ ...state, categoryId: event.target.value }))
                  }
                >
                  <option value="">Selecione…</option>
                  {availableCategories.map((category) => (
                    <option key={category.id} value={category.id}>
                      {category.name}
                    </option>
                  ))}
                </select>
              </FormField>
            )}
            <FormField label="Limite mensal (R$)">
              <input
                className="input"
                inputMode="decimal"
                placeholder="0,00"
                value={form.limit}
                onChange={(event) =>
                  setForm((state) => ({ ...state, limit: event.target.value }))
                }
              />
            </FormField>
            {(formError || submitError) && (
              <div role="alert" className="field-error">
                {formError ?? errorMessage(submitError)}
              </div>
            )}
            <div
              style={{ display: 'flex', gap: 'var(--space-2)', justifyContent: 'flex-end' }}
            >
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => setFormOpen(false)}
                disabled={busy}
              >
                Cancelar
              </button>
              <button type="submit" className="btn btn-primary" disabled={busy}>
                {busy ? 'Salvando…' : editing ? 'Salvar' : 'Criar orçamento'}
              </button>
            </div>
          </div>
        </form>
      </Dialog>

      <ConfirmDialog
        open={deleting !== null}
        title="Excluir orçamento"
        message={`Excluir o orçamento de ${deleting?.category.name} deste mês?`}
        confirmLabel="Excluir"
        danger
        busy={deleteMutation.isPending}
        onConfirm={() =>
          deleting && deleteMutation.mutate(deleting.id, { onSuccess: () => setDeleting(null) })
        }
        onCancel={() => setDeleting(null)}
      />
    </>
  )
}
