import { useState, type FormEvent } from 'react'
import {
  Plus,
  Pencil,
  Trash2,
  AlertTriangle,
  AlertOctagon,
  CheckCircle2,
  HelpCircle,
} from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import MonthPicker from '../../components/MonthPicker'
import Money from '../../components/Money'
import Dialog from '../../components/Dialog'
import ConfirmDialog from '../../components/ConfirmDialog'
import FormActions from '../../components/FormActions'
import FormField from '../../components/FormField'
import { EmptyState, ErrorState, LoadingCards, errorMessage } from '../../components/states'
import { currentMonth } from '../../lib/month'
import { formatPercent } from '../../lib/format'
import { emptyTotals, formatMoney } from '../../lib/money'
import { parseMoneyInput } from '../../lib/format'
import { useCategories } from '../shared/api'
import { useOptionalVault } from '../../offline/VaultProvider'
import { localId, projectList } from '../../offline/outbox/projection'
import PendingBadge, { StaleTotalsWarning } from '../offline-sync/PendingBadge'
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
  INCOMPLETE: { label: 'Consumo incompleto', badge: 'badge-warning', icon: HelpCircle },
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
  const vault = useOptionalVault()

  // A category with a budget already queued offline counts as used: offering it
  // again would queue a second budget for the same month and turn a form the
  // user could have got right into a uniqueness conflict at replay time.
  const usedCategoryIds = new Set([
    ...(budgets.data?.budgets ?? []).map((budget) => budget.category.id),
    ...(vault?.entries ?? [])
      .filter((entry) => entry.resourceType === 'BUDGET' && entry.operation === 'CREATE')
      .map((entry) => (entry.payload as { categoryId?: number }).categoryId)
      .filter((id): id is number => id != null),
  ])
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
    const categoryName = (categories.data ?? []).find(
      (category) => category.id === request.categoryId,
    )?.name
    const onSuccess = () => setFormOpen(false)
    if (editing) {
      updateMutation.mutate(
        { id: editing.id, request, version: editing.version, categoryName },
        { onSuccess },
      )
    } else {
      createMutation.mutate({ request, categoryName }, { onSuccess })
    }
  }

  const busy = createMutation.isPending || updateMutation.isPending
  const submitError = editing ? updateMutation.error : createMutation.error
  const data = budgets.data
  // The denomination comes from the server summary. Until it is known there is
  // nothing to label a queued row with, and guessing BRL would mislabel it.
  const baseCurrency = data?.baseCurrency

  /**
   * The server's budgets with the queue laid over them.
   *
   * Consumption and status are deliberately *not* recomputed for a pending row:
   * they depend on every transaction of the month, which may itself be queued.
   * The row shows the limit the user chose and says the rest is still to come,
   * rather than showing a healthy bar for a category that is already overspent.
   */
  const rows = projectList(
    (data?.budgets ?? []).filter((budget) => budget.month === month),
    vault?.entries ?? [],
    'BUDGET',
    (base, entry) => {
      if (entry.operation === 'DELETE') return base
      const payload = entry.payload as { month?: string; categoryId?: number; limitAmount?: number }
      if (payload.month && payload.month !== month) return null
      const category =
        base?.category
        ?? (categories.data ?? []).find((candidate) => candidate.id === payload.categoryId)
        ?? { id: payload.categoryId ?? 0, name: 'Categoria', type: 'EXPENSE' as const }
      if (!base) {
        if (!baseCurrency) return null
        return {
          id: localId(entry.clientResourceId),
          month: payload.month ?? month,
          category,
          limitAmount: Number(payload.limitAmount ?? 0),
          currency: baseCurrency,
          consumedAmount: 0,
          consumedTotals: emptyTotals(baseCurrency),
          remainingAmount: Number(payload.limitAmount ?? 0),
          percentUsed: 0,
          status: 'HEALTHY' as const,
          version: 0,
        }
      }
      return { ...base, limitAmount: Number(payload.limitAmount ?? base.limitAmount), category }
    },
  )

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
      ) : data && rows.length === 0 ? (
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
          <StaleTotalsWarning pendingCount={vault?.counts.total ?? 0} />
          <div className="card budget-summary">
            <div>
              <span className="stat-footnote">Total consumido</span>
              <p className="budget-summary-value">
                {formatMoney(data.totalConsumed, data.baseCurrency)}{' '}
                <span className="stat-footnote">
                  de {formatMoney(data.totalLimit, data.baseCurrency)}
                  {data.percentUsed !== null ? ` (${formatPercent(data.percentUsed)})` : ''}
                </span>
              </p>
            </div>
            <div>
              <span className="stat-footnote">Restante</span>
              <p className="budget-summary-value">
                {data.totalRemaining !== null ? (
                  <Money value={data.totalRemaining} currency={data.baseCurrency} signed />
                ) : (
                  <span className="stat-footnote" role="note">
                    Indisponível: {data.incompleteCount} orçamento(s) têm gastos em outra moeda.
                  </span>
                )}
              </p>
            </div>
          </div>

          <ul className="budget-list">
            {rows.map(({ item: budget, pending }) => {
              const meta = STATUS_META[budget.status]
              const StatusIcon = meta.icon
              return (
                <li key={budget.id} className="card budget-row">
                  <div className="budget-row-header">
                    <span className="budget-category">{budget.category.name}</span>
                    {pending ? (
                      <PendingBadge state={pending} />
                    ) : (
                      <span className={`badge ${meta.badge}`}>
                        <StatusIcon size={13} aria-hidden="true" />
                        {meta.label}
                      </span>
                    )}
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
                  {pending ? (
                    <p className="budget-pending-note">
                      Limite de {formatMoney(budget.limitAmount, budget.currency)}. O consumo e o status serão
                      calculados pelo servidor depois da sincronização.
                    </p>
                  ) : (
                    <>
                      {budget.percentUsed !== null ? (
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
                      ) : null}
                      <div className="budget-row-footer">
                        <span>
                          {formatMoney(budget.consumedAmount, budget.currency)} de{' '}
                          {formatMoney(budget.limitAmount, budget.currency)}
                        </span>
                        <span>
                          {budget.remainingAmount === null
                            ? 'Restante indisponível'
                            : budget.remainingAmount >= 0
                              ? `Restam ${formatMoney(budget.remainingAmount, budget.currency)}`
                              : `${formatMoney(Math.abs(budget.remainingAmount), budget.currency)} acima do limite`}
                        </span>
                      </div>
                      {budget.status === 'INCOMPLETE' && (
                        <p className="budget-incomplete-note" role="note">
                          Há gastos nesta categoria em{' '}
                          {budget.consumedTotals.unconvertedCurrencies.join(', ')}:{' '}
                          {budget.consumedTotals.byCurrency
                            .filter((entry) => entry.currency !== budget.currency)
                            .map((entry) => formatMoney(entry.amount, entry.currency))
                            .join(', ')}
                          . Sem cotações, o consumo total e a porcentagem não podem ser calculados —
                          tratá-los como zero poderia mostrar como saudável um orçamento já
                          estourado.
                        </p>
                      )}
                    </>
                  )}
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
          <div className="form-grid">
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
            <FormActions
              busy={busy}
              submitLabel={editing ? 'Salvar' : 'Criar orçamento'}
              onCancel={() => setFormOpen(false)}
            />
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
          deleting && deleteMutation.mutate(deleting, { onSuccess: () => setDeleting(null) })
        }
        onCancel={() => setDeleting(null)}
      />
    </>
  )
}
