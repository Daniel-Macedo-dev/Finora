import { useState, type FormEvent } from 'react'
import { Plus, Pencil, Trash2, CalendarClock } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import Money from '../../components/Money'
import Dialog from '../../components/Dialog'
import ConfirmDialog from '../../components/ConfirmDialog'
import FormActions from '../../components/FormActions'
import FormField from '../../components/FormField'
import { EmptyState, ErrorState, LoadingCards, errorMessage } from '../../components/states'
import { formatBRL, formatDate, parseMoneyInput } from '../../lib/format'
import { todayIso } from '../../lib/month'
import { useCategories } from '../shared/api'
import { PAYMENT_METHOD_LABELS, type PaymentMethod } from '../shared/types'
import {
  useCommitments,
  useCreateCommitment,
  useDeleteCommitment,
  useUpcomingCommitments,
  useUpdateCommitment,
} from './api'
import type { Commitment, CommitmentCadence, CommitmentRequest } from './types'
import './commitments.css'

interface FormState {
  description: string
  amount: string
  categoryId: string
  cadence: CommitmentCadence
  dueDay: string
  startDate: string
  endDate: string
  active: boolean
  paymentMethod: string
}

const EMPTY_FORM: FormState = {
  description: '',
  amount: '',
  categoryId: '',
  cadence: 'MONTHLY',
  dueDay: '',
  startDate: todayIso(),
  endDate: '',
  active: true,
  paymentMethod: '',
}

export default function CommitmentsPage() {
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Commitment | null>(null)
  const [deleting, setDeleting] = useState<Commitment | null>(null)
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [formError, setFormError] = useState<string | null>(null)

  const commitments = useCommitments()
  const upcoming = useUpcomingCommitments(2)
  const categories = useCategories('EXPENSE')
  const createMutation = useCreateCommitment()
  const updateMutation = useUpdateCommitment()
  const deleteMutation = useDeleteCommitment()

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((state) => ({ ...state, [key]: value }))
  }

  function openCreate() {
    setEditing(null)
    setForm(EMPTY_FORM)
    setFormError(null)
    createMutation.reset()
    updateMutation.reset()
    setFormOpen(true)
  }

  function openEdit(commitment: Commitment) {
    setEditing(commitment)
    setForm({
      description: commitment.description,
      amount: commitment.amount.toFixed(2).replace('.', ','),
      categoryId: String(commitment.category.id),
      cadence: commitment.cadence,
      dueDay: commitment.dueDay !== null ? String(commitment.dueDay) : '',
      startDate: commitment.startDate,
      endDate: commitment.endDate ?? '',
      active: commitment.active,
      paymentMethod: commitment.paymentMethod ?? '',
    })
    setFormError(null)
    createMutation.reset()
    updateMutation.reset()
    setFormOpen(true)
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const amount = parseMoneyInput(form.amount)
    if (!form.description.trim()) {
      setFormError('Informe a descrição.')
      return
    }
    if (amount === null || amount <= 0) {
      setFormError('Informe um valor maior que zero.')
      return
    }
    if (!form.categoryId) {
      setFormError('Selecione a categoria.')
      return
    }
    if (form.cadence === 'MONTHLY' && !form.dueDay) {
      setFormError('Informe o dia de vencimento para compromissos mensais.')
      return
    }
    if (!form.startDate) {
      setFormError('Informe a data de início.')
      return
    }
    setFormError(null)
    const request: CommitmentRequest = {
      description: form.description.trim(),
      amount,
      categoryId: Number(form.categoryId),
      cadence: form.cadence,
      dueDay: form.cadence === 'MONTHLY' ? Number(form.dueDay) : null,
      startDate: form.startDate,
      endDate: form.endDate || null,
      active: form.active,
      paymentMethod: (form.paymentMethod || null) as PaymentMethod | null,
    }
    const onSuccess = () => setFormOpen(false)
    if (editing) {
      updateMutation.mutate({ id: editing.id, request }, { onSuccess })
    } else {
      createMutation.mutate(request, { onSuccess })
    }
  }

  const busy = createMutation.isPending || updateMutation.isPending
  const submitError = editing ? updateMutation.error : createMutation.error

  return (
    <>
      <PageHeader
        title="Compromissos recorrentes"
        description="Assinaturas, aluguel e contas fixas — com projeção dos próximos vencimentos."
        actions={
          <button type="button" className="btn btn-primary" onClick={openCreate}>
            <Plus size={16} aria-hidden="true" />
            Novo compromisso
          </button>
        }
      />

      {commitments.isPending ? (
        <LoadingCards count={3} height={72} />
      ) : commitments.isError ? (
        <ErrorState error={commitments.error} onRetry={() => commitments.refetch()} />
      ) : commitments.data && commitments.data.length === 0 ? (
        <EmptyState
          title="Nenhum compromisso recorrente"
          description="Cadastre assinaturas e contas fixas para projetar os próximos meses e alimentar a análise de compras."
          action={
            <button type="button" className="btn btn-primary" onClick={openCreate}>
              <Plus size={16} aria-hidden="true" />
              Novo compromisso
            </button>
          }
        />
      ) : commitments.data ? (
        <div className="commitments-layout">
          <section aria-label="Compromissos cadastrados" className="commitments-main">
            <ul className="commitment-list">
              {commitments.data.map((commitment) => (
                <li key={commitment.id} className={`card commitment-row ${commitment.active ? '' : 'commitment-inactive'}`}>
                  <div className="commitment-info">
                    <span className="commitment-description">{commitment.description}</span>
                    <span className="commitment-meta">
                      <span className="badge badge-neutral">{commitment.category.name}</span>
                      <span>
                        {commitment.cadence === 'MONTHLY'
                          ? `Mensal · dia ${commitment.dueDay}`
                          : 'Anual'}
                      </span>
                      {commitment.paymentMethod && (
                        <span>{PAYMENT_METHOD_LABELS[commitment.paymentMethod]}</span>
                      )}
                      {!commitment.active && <span className="badge badge-neutral">Inativo</span>}
                    </span>
                  </div>
                  <div className="commitment-side">
                    <Money value={commitment.amount} />
                    <span className="commitment-next">
                      {commitment.nextDueDate
                        ? `Próximo: ${formatDate(commitment.nextDueDate)}`
                        : 'Sem próxima ocorrência'}
                    </span>
                  </div>
                  <div className="commitment-actions">
                    <button
                      type="button"
                      className="btn btn-ghost btn-icon"
                      onClick={() => openEdit(commitment)}
                      aria-label={`Editar ${commitment.description}`}
                    >
                      <Pencil size={16} aria-hidden="true" />
                    </button>
                    <button
                      type="button"
                      className="btn btn-ghost btn-icon"
                      onClick={() => setDeleting(commitment)}
                      aria-label={`Excluir ${commitment.description}`}
                    >
                      <Trash2 size={16} aria-hidden="true" />
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          </section>

          <aside className="card commitments-upcoming" aria-label="Próximos vencimentos">
            <h2 className="panel-title">
              <CalendarClock size={16} aria-hidden="true" /> Próximos 2 meses
            </h2>
            {upcoming.isPending ? (
              <p className="panel-empty">Calculando…</p>
            ) : upcoming.isError ? (
              <p className="panel-empty">Não foi possível projetar os vencimentos.</p>
            ) : upcoming.data.items.length === 0 ? (
              <p className="panel-empty">Nenhum vencimento na janela projetada.</p>
            ) : (
              <>
                <ul className="mini-list">
                  {upcoming.data.items.map((item) => (
                    <li key={`${item.commitmentId}-${item.dueDate}`}>
                      <span className="mini-list-main">{item.description}</span>
                      <span className="mini-list-meta">{formatDate(item.dueDate)}</span>
                      <Money value={item.amount} />
                    </li>
                  ))}
                </ul>
                <p className="stat-footnote">
                  Total projetado: {formatBRL(upcoming.data.totalAmount)}
                </p>
              </>
            )}
          </aside>
        </div>
      ) : null}

      <Dialog
        open={formOpen}
        title={editing ? 'Editar compromisso' : 'Novo compromisso'}
        onClose={() => setFormOpen(false)}
        wide
      >
        <form onSubmit={handleSubmit} noValidate>
          <div className="form-grid">
            <FormField label="Descrição">
              <input
                className="input"
                maxLength={200}
                value={form.description}
                onChange={(event) => set('description', event.target.value)}
              />
            </FormField>
            <div className="commitment-form-grid">
              <FormField label="Valor (R$)">
                <input
                  className="input"
                  inputMode="decimal"
                  placeholder="0,00"
                  value={form.amount}
                  onChange={(event) => set('amount', event.target.value)}
                />
              </FormField>
              <FormField label="Categoria">
                <select
                  className="select"
                  value={form.categoryId}
                  onChange={(event) => set('categoryId', event.target.value)}
                >
                  <option value="">Selecione…</option>
                  {(categories.data ?? [])
                    .filter((category) => category.active)
                    .map((category) => (
                      <option key={category.id} value={category.id}>
                        {category.name}
                      </option>
                    ))}
                </select>
              </FormField>
              <FormField label="Recorrência">
                <select
                  className="select"
                  value={form.cadence}
                  onChange={(event) => set('cadence', event.target.value as CommitmentCadence)}
                >
                  <option value="MONTHLY">Mensal</option>
                  <option value="YEARLY">Anual</option>
                </select>
              </FormField>
              {form.cadence === 'MONTHLY' && (
                <FormField label="Dia de vencimento" hint="Ajustado em meses mais curtos.">
                  <input
                    className="input"
                    type="number"
                    min={1}
                    max={31}
                    value={form.dueDay}
                    onChange={(event) => set('dueDay', event.target.value)}
                  />
                </FormField>
              )}
              <FormField label="Início">
                <input
                  className="input"
                  type="date"
                  value={form.startDate}
                  onChange={(event) => set('startDate', event.target.value)}
                />
              </FormField>
              <FormField label="Fim (opcional)">
                <input
                  className="input"
                  type="date"
                  value={form.endDate}
                  onChange={(event) => set('endDate', event.target.value)}
                />
              </FormField>
              <FormField label="Forma de pagamento (opcional)">
                <select
                  className="select"
                  value={form.paymentMethod}
                  onChange={(event) => set('paymentMethod', event.target.value)}
                >
                  <option value="">Não informar</option>
                  {Object.entries(PAYMENT_METHOD_LABELS).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </FormField>
            </div>
            <label className="commitment-active-toggle">
              <input
                type="checkbox"
                checked={form.active}
                onChange={(event) => set('active', event.target.checked)}
              />
              Compromisso ativo (entra nas projeções)
            </label>
            {(formError || submitError) && (
              <div role="alert" className="field-error">
                {formError ?? errorMessage(submitError)}
              </div>
            )}
            <FormActions
              busy={busy}
              submitLabel={editing ? 'Salvar' : 'Criar compromisso'}
              onCancel={() => setFormOpen(false)}
            />
          </div>
        </form>
      </Dialog>

      <ConfirmDialog
        open={deleting !== null}
        title="Excluir compromisso"
        message={`Excluir "${deleting?.description}"? As projeções deixarão de considerá-lo.`}
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
