import { useState, type FormEvent } from 'react'
import { Plus, Pencil, Trash2, PiggyBank, CheckCircle2 } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import Money from '../../components/Money'
import Dialog from '../../components/Dialog'
import ConfirmDialog from '../../components/ConfirmDialog'
import FormActions from '../../components/FormActions'
import FormField from '../../components/FormField'
import { EmptyState, ErrorState, LoadingCards, errorMessage } from '../../components/states'
import { formatBRL, formatDate, formatPercent, parseMoneyInput } from '../../lib/format'
import { useOptionalVault } from '../../offline/VaultProvider'
import { localId, projectList } from '../../offline/outbox/projection'
import { UNSUPPORTED_OFFLINE_MESSAGE } from '../../offline/outbox/useOutbox'
import PendingBadge from '../offline-sync/PendingBadge'
import { useContributeToGoal, useCreateGoal, useDeleteGoal, useGoals, useUpdateGoal } from './api'
import type { Goal, GoalRequest } from './types'
import './goals.css'

interface FormState {
  name: string
  target: string
  current: string
  targetDate: string
  archived: boolean
}

const EMPTY_FORM: FormState = {
  name: '',
  target: '',
  current: '',
  targetDate: '',
  archived: false,
}

export default function GoalsPage() {
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Goal | null>(null)
  const [deleting, setDeleting] = useState<Goal | null>(null)
  const [contributing, setContributing] = useState<Goal | null>(null)
  const [contribution, setContribution] = useState('')
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [formError, setFormError] = useState<string | null>(null)
  const [contributionError, setContributionError] = useState<string | null>(null)

  const goals = useGoals()
  const createMutation = useCreateGoal()
  const updateMutation = useUpdateGoal()
  const contributeMutation = useContributeToGoal()
  const deleteMutation = useDeleteGoal()

  function openCreate() {
    setEditing(null)
    setForm(EMPTY_FORM)
    setFormError(null)
    createMutation.reset()
    updateMutation.reset()
    setFormOpen(true)
  }

  function openEdit(goal: Goal) {
    setEditing(goal)
    setForm({
      name: goal.name,
      target: goal.targetAmount.toFixed(2).replace('.', ','),
      current: goal.currentAmount.toFixed(2).replace('.', ','),
      targetDate: goal.targetDate ?? '',
      archived: goal.status === 'ARCHIVED',
    })
    setFormError(null)
    createMutation.reset()
    updateMutation.reset()
    setFormOpen(true)
  }

  function openContribute(goal: Goal) {
    setContributing(goal)
    setContribution('')
    setContributionError(null)
    contributeMutation.reset()
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const target = parseMoneyInput(form.target)
    const current = form.current ? parseMoneyInput(form.current) : 0
    if (!form.name.trim()) {
      setFormError('Informe o nome da meta.')
      return
    }
    if (target === null || target <= 0) {
      setFormError('Informe um valor alvo maior que zero.')
      return
    }
    if (current === null || current < 0) {
      setFormError('O valor atual não pode ser negativo.')
      return
    }
    setFormError(null)
    const request: GoalRequest = {
      name: form.name.trim(),
      targetAmount: target,
      currentAmount: current,
      targetDate: form.targetDate || null,
      archived: form.archived,
    }
    const onSuccess = () => setFormOpen(false)
    if (editing) {
      updateMutation.mutate({ id: editing.id, request, version: editing.version }, { onSuccess })
    } else {
      createMutation.mutate(request, { onSuccess })
    }
  }

  function handleContribute(event: FormEvent) {
    event.preventDefault()
    if (!contributing) {
      return
    }
    const amount = parseMoneyInput(contribution)
    if (amount === null || amount === 0) {
      setContributionError('Informe um valor diferente de zero (negativo para retirar).')
      return
    }
    setContributionError(null)
    contributeMutation.mutate(
      { id: contributing.id, amount },
      { onSuccess: () => setContributing(null) },
    )
  }

  const busy = createMutation.isPending || updateMutation.isPending
  const submitError = editing ? updateMutation.error : createMutation.error
  const vault = useOptionalVault()

  /**
   * The server's goals with the queue laid over them.
   *
   * Remaining and percentage are recomputed here, which is safe because both
   * come from the two numbers the user just typed into the form — not from any
   * other financial record. The suggested monthly contribution is dropped
   * instead: that one depends on server-side date rules, and inventing it
   * locally would put a number on screen the server never agreed to.
   */
  const rows = projectList(
    goals.data ?? [],
    vault?.entries ?? [],
    'GOAL',
    (base, entry) => {
      if (entry.operation === 'DELETE') return base
      const payload = entry.payload as Partial<GoalRequest>
      const targetAmount = Number(payload.targetAmount ?? base?.targetAmount ?? 0)
      const currentAmount = Number(payload.currentAmount ?? base?.currentAmount ?? 0)
      const remainingAmount = Math.max(targetAmount - currentAmount, 0)
      const percentAchieved = targetAmount > 0 ? (currentAmount / targetAmount) * 100 : 0
      const archived = payload.archived ?? base?.status === 'ARCHIVED'
      return {
        id: base?.id ?? localId(entry.clientResourceId),
        name: String(payload.name ?? base?.name ?? entry.label),
        targetAmount,
        currentAmount,
        remainingAmount,
        percentAchieved,
        targetDate: payload.targetDate ?? base?.targetDate ?? null,
        status: archived ? ('ARCHIVED' as const) : ('IN_PROGRESS' as const),
        suggestedMonthlyContribution: null,
        version: base?.version ?? 0,
      }
    },
  )

  return (
    <>
      <PageHeader
        title="Metas de poupança"
        description="Acompanhe reservas e objetivos de compra com progresso real."
        actions={
          <button type="button" className="btn btn-primary" onClick={openCreate}>
            <Plus size={16} aria-hidden="true" />
            Nova meta
          </button>
        }
      />

      {goals.isPending ? (
        <LoadingCards count={3} height={120} />
      ) : goals.isError ? (
        <ErrorState error={goals.error} onRetry={() => goals.refetch()} />
      ) : goals.data && rows.length === 0 ? (
        <EmptyState
          title="Nenhuma meta criada"
          description="Crie metas como reserva de emergência ou uma viagem e registre aportes ao longo do tempo."
          action={
            <button type="button" className="btn btn-primary" onClick={openCreate}>
              <Plus size={16} aria-hidden="true" />
              Nova meta
            </button>
          }
        />
      ) : goals.data ? (
        <ul className="goal-grid">
          {rows.map(({ item: goal, pending }) => (
            <li key={goal.id} className={`card goal-card ${goal.status === 'ARCHIVED' ? 'goal-archived' : ''}`}>
              <div className="goal-header">
                <h2 className="goal-name">{goal.name}</h2>
                {pending && <PendingBadge state={pending} />}
                {!pending && goal.status === 'COMPLETED' && (
                  <span className="badge badge-positive">
                    <CheckCircle2 size={13} aria-hidden="true" /> Concluída
                  </span>
                )}
                {!pending && goal.status === 'ARCHIVED' && (
                  <span className="badge badge-neutral">Arquivada</span>
                )}
              </div>
              <p className="goal-amounts">
                <Money value={goal.currentAmount} />{' '}
                <span className="stat-footnote">de {formatBRL(goal.targetAmount)}</span>
              </p>
              <div
                className="goal-track"
                role="img"
                aria-label={`${goal.name}: ${formatPercent(goal.percentAchieved)} alcançado`}
              >
                <div
                  className="goal-fill"
                  style={{ width: `${Math.min(goal.percentAchieved, 100)}%` }}
                />
              </div>
              <p className="goal-details">
                {goal.status !== 'COMPLETED' && (
                  <>Faltam {formatBRL(goal.remainingAmount)}</>
                )}
                {goal.targetDate && <> · Data alvo: {formatDate(goal.targetDate)}</>}
                {goal.suggestedMonthlyContribution !== null && (
                  <> · Sugestão: {formatBRL(goal.suggestedMonthlyContribution)}/mês</>
                )}
              </p>
              <div className="goal-actions">
                {/* A contribution is a delta against the balance at the
                    moment it runs, so it cannot be replayed from a queue. */}
                <button
                  type="button"
                  className="btn btn-secondary"
                  data-offline-blocked="true"
                  // A goal that only exists in the queue has no server id to
                  // contribute against, so the control stays out of reach until
                  // the creation has actually been applied.
                  disabled={goal.id < 0}
                  title={goal.id < 0 ? UNSUPPORTED_OFFLINE_MESSAGE : undefined}
                  onClick={() => openContribute(goal)}
                >
                  <PiggyBank size={15} aria-hidden="true" />
                  Registrar aporte
                </button>
                <span>
                  <button
                    type="button"
                    className="btn btn-ghost btn-icon"
                    onClick={() => openEdit(goal)}
                    aria-label={`Editar ${goal.name}`}
                  >
                    <Pencil size={16} aria-hidden="true" />
                  </button>
                  <button
                    type="button"
                    className="btn btn-ghost btn-icon"
                    onClick={() => setDeleting(goal)}
                    aria-label={`Excluir ${goal.name}`}
                  >
                    <Trash2 size={16} aria-hidden="true" />
                  </button>
                </span>
              </div>
            </li>
          ))}
        </ul>
      ) : null}

      <Dialog
        open={formOpen}
        title={editing ? 'Editar meta' : 'Nova meta'}
        onClose={() => setFormOpen(false)}
      >
        <form onSubmit={handleSubmit} noValidate>
          <div className="form-grid">
            <FormField label="Nome da meta">
              <input
                className="input"
                maxLength={100}
                value={form.name}
                onChange={(event) => setForm((state) => ({ ...state, name: event.target.value }))}
              />
            </FormField>
            <FormField label="Valor alvo (R$)">
              <input
                className="input"
                inputMode="decimal"
                placeholder="0,00"
                value={form.target}
                onChange={(event) => setForm((state) => ({ ...state, target: event.target.value }))}
              />
            </FormField>
            <FormField label="Valor atual (R$)" hint="Quanto já foi reservado até agora.">
              <input
                className="input"
                inputMode="decimal"
                placeholder="0,00"
                value={form.current}
                onChange={(event) =>
                  setForm((state) => ({ ...state, current: event.target.value }))
                }
              />
            </FormField>
            <FormField label="Data alvo (opcional)">
              <input
                className="input"
                type="date"
                value={form.targetDate}
                onChange={(event) =>
                  setForm((state) => ({ ...state, targetDate: event.target.value }))
                }
              />
            </FormField>
            {editing && (
              <label className="goal-archive-toggle">
                <input
                  type="checkbox"
                  checked={form.archived}
                  onChange={(event) =>
                    setForm((state) => ({ ...state, archived: event.target.checked }))
                  }
                />
                Arquivar meta
              </label>
            )}
            {(formError || submitError) && (
              <div role="alert" className="field-error">
                {formError ?? errorMessage(submitError)}
              </div>
            )}
            <FormActions
              busy={busy}
              submitLabel={editing ? 'Salvar' : 'Criar meta'}
              onCancel={() => setFormOpen(false)}
            />
          </div>
        </form>
      </Dialog>

      <Dialog
        open={contributing !== null}
        title={`Registrar aporte — ${contributing?.name ?? ''}`}
        onClose={() => setContributing(null)}
      >
        <form onSubmit={handleContribute} noValidate>
          <div className="form-grid">
            <FormField
              label="Valor do aporte (R$)"
              hint="Use um valor negativo para registrar uma retirada."
            >
              <input
                className="input"
                inputMode="decimal"
                placeholder="0,00"
                value={contribution}
                onChange={(event) => setContribution(event.target.value)}
              />
            </FormField>
            {(contributionError || contributeMutation.error) && (
              <div role="alert" className="field-error">
                {contributionError ?? errorMessage(contributeMutation.error)}
              </div>
            )}
            <FormActions
              busy={contributeMutation.isPending}
              submitLabel="Registrar"
              onCancel={() => setContributing(null)}
            />
          </div>
        </form>
      </Dialog>

      <ConfirmDialog
        open={deleting !== null}
        title="Excluir meta"
        message={`Excluir a meta "${deleting?.name}"? O histórico de progresso será perdido.`}
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
