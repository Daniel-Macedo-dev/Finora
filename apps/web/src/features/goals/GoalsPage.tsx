import { useState, type FormEvent } from 'react'
import { Plus, Pencil, Trash2, PiggyBank, CheckCircle2 } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import Money from '../../components/Money'
import Dialog from '../../components/Dialog'
import ConfirmDialog from '../../components/ConfirmDialog'
import FormField from '../../components/FormField'
import { EmptyState, ErrorState, LoadingCards, errorMessage } from '../../components/states'
import { formatBRL, formatDate, formatPercent, parseMoneyInput } from '../../lib/format'
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
      updateMutation.mutate({ id: editing.id, request }, { onSuccess })
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
      ) : goals.data && goals.data.length === 0 ? (
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
          {goals.data.map((goal) => (
            <li key={goal.id} className={`card goal-card ${goal.status === 'ARCHIVED' ? 'goal-archived' : ''}`}>
              <div className="goal-header">
                <h2 className="goal-name">{goal.name}</h2>
                {goal.status === 'COMPLETED' && (
                  <span className="badge badge-positive">
                    <CheckCircle2 size={13} aria-hidden="true" /> Concluída
                  </span>
                )}
                {goal.status === 'ARCHIVED' && (
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
                <button
                  type="button"
                  className="btn btn-secondary"
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
          <div style={{ display: 'grid', gap: 'var(--space-3)' }}>
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
            <div style={{ display: 'flex', gap: 'var(--space-2)', justifyContent: 'flex-end' }}>
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => setFormOpen(false)}
                disabled={busy}
              >
                Cancelar
              </button>
              <button type="submit" className="btn btn-primary" disabled={busy}>
                {busy ? 'Salvando…' : editing ? 'Salvar' : 'Criar meta'}
              </button>
            </div>
          </div>
        </form>
      </Dialog>

      <Dialog
        open={contributing !== null}
        title={`Registrar aporte — ${contributing?.name ?? ''}`}
        onClose={() => setContributing(null)}
      >
        <form onSubmit={handleContribute} noValidate>
          <div style={{ display: 'grid', gap: 'var(--space-3)' }}>
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
            <div style={{ display: 'flex', gap: 'var(--space-2)', justifyContent: 'flex-end' }}>
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => setContributing(null)}
                disabled={contributeMutation.isPending}
              >
                Cancelar
              </button>
              <button
                type="submit"
                className="btn btn-primary"
                disabled={contributeMutation.isPending}
              >
                {contributeMutation.isPending ? 'Salvando…' : 'Registrar'}
              </button>
            </div>
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
          deleting && deleteMutation.mutate(deleting.id, { onSuccess: () => setDeleting(null) })
        }
        onCancel={() => setDeleting(null)}
      />
    </>
  )
}
