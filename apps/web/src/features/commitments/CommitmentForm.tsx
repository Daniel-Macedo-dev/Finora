import { useState, type FormEvent } from 'react'
import FormActions from '../../components/FormActions'
import FormField from '../../components/FormField'
import { errorMessage } from '../../components/states'
import { formatDate, parseMoneyInput } from '../../lib/format'
import { todayIso } from '../../lib/month'
import { useAccounts, useCategories } from '../shared/api'
import { PAYMENT_METHOD_LABELS, type PaymentMethod } from '../shared/types'
import { useCreditCards } from '../credit-cards/api'
import type {
  Commitment,
  CommitmentCadence,
  CommitmentRequest,
  ExecutionMode,
  RecurrenceTarget,
} from './types'

interface CommitmentFormProps {
  editing: Commitment | null
  busy: boolean
  submitError: unknown
  onSubmit: (request: CommitmentRequest) => void
  onCancel: () => void
}

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
  executionMode: ExecutionMode
  targetKind: RecurrenceTarget
  accountId: string
  creditCardId: string
  installmentCount: string
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
  executionMode: 'MANUAL',
  targetKind: 'PROJECTION_ONLY',
  accountId: '',
  creditCardId: '',
  installmentCount: '1',
}

function fromCommitment(commitment: Commitment): FormState {
  return {
    description: commitment.description,
    amount: commitment.amount.toFixed(2).replace('.', ','),
    categoryId: String(commitment.category.id),
    cadence: commitment.cadence,
    dueDay: commitment.dueDay !== null ? String(commitment.dueDay) : '',
    startDate: commitment.startDate,
    endDate: commitment.endDate ?? '',
    active: commitment.active,
    paymentMethod: commitment.paymentMethod ?? '',
    executionMode: commitment.executionMode,
    targetKind: commitment.targetKind,
    accountId: commitment.accountId !== null ? String(commitment.accountId) : '',
    creditCardId: commitment.creditCardId !== null ? String(commitment.creditCardId) : '',
    installmentCount: String(commitment.installmentCount),
  }
}

export default function CommitmentForm({
  editing,
  busy,
  submitError,
  onSubmit,
  onCancel,
}: CommitmentFormProps) {
  const [form, setForm] = useState<FormState>(editing ? fromCommitment(editing) : EMPTY_FORM)
  const [formError, setFormError] = useState<string | null>(null)

  const categories = useCategories()
  const accounts = useAccounts()
  const cards = useCreditCards()

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((state) => ({ ...state, [key]: value }))
  }

  const selectedCategory = (categories.data ?? []).find(
    (category) => String(category.id) === form.categoryId,
  )
  const cardTargetAllowed = selectedCategory?.type !== 'INCOME'

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
      setFormError('Informe o dia de vencimento para recorrentes mensais.')
      return
    }
    if (!form.startDate) {
      setFormError('Informe a data de início.')
      return
    }
    if (form.targetKind === 'ACCOUNT_TRANSACTION' && !form.accountId) {
      setFormError('Escolha a conta de destino.')
      return
    }
    if (form.targetKind === 'CREDIT_CARD_PURCHASE' && !form.creditCardId) {
      setFormError('Escolha o cartão de destino.')
      return
    }
    if (form.executionMode === 'AUTOMATIC' && form.targetKind === 'PROJECTION_ONLY') {
      setFormError('A execução automática exige uma conta ou um cartão de destino.')
      return
    }
    setFormError(null)
    onSubmit({
      description: form.description.trim(),
      amount,
      categoryId: Number(form.categoryId),
      cadence: form.cadence,
      dueDay: form.cadence === 'MONTHLY' ? Number(form.dueDay) : null,
      startDate: form.startDate,
      endDate: form.endDate || null,
      active: form.active,
      paymentMethod:
        form.targetKind === 'ACCOUNT_TRANSACTION' && form.paymentMethod
          ? (form.paymentMethod as PaymentMethod)
          : form.targetKind === 'PROJECTION_ONLY'
            ? ((form.paymentMethod || null) as PaymentMethod | null)
            : null,
      executionMode: form.executionMode,
      targetKind: form.targetKind,
      accountId: form.targetKind === 'ACCOUNT_TRANSACTION' ? Number(form.accountId) : null,
      creditCardId: form.targetKind === 'CREDIT_CARD_PURCHASE' ? Number(form.creditCardId) : null,
      installmentCount:
        form.targetKind === 'CREDIT_CARD_PURCHASE' ? Number(form.installmentCount) || 1 : 1,
    })
  }

  const openAccounts = (accounts.data ?? []).filter((account) => !account.archived)
  const activeCards = (cards.data ?? []).filter((card) => !card.archived)

  return (
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
          <FormField label="Categoria" hint="Receitas geram entradas; despesas, saídas.">
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
                    {category.name} ({category.type === 'INCOME' ? 'receita' : 'despesa'})
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
              <option value="WEEKLY">Semanal</option>
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
          <FormField
            label="Início"
            hint={
              form.cadence === 'WEEKLY'
                ? 'A recorrência repete a cada 7 dias a partir desta data.'
                : form.cadence === 'YEARLY'
                  ? 'Repete todo ano no mesmo mês e dia.'
                  : undefined
            }
          >
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
        </div>

        <fieldset className="commitment-target">
          <legend className="commitment-target-legend">Destino da execução</legend>
          <FormField
            label="O que cada ocorrência vira"
            hint={
              form.targetKind === 'PROJECTION_ONLY'
                ? 'Apenas projeções e lembretes — nada é lançado automaticamente.'
                : undefined
            }
          >
            <select
              className="select"
              value={form.targetKind}
              onChange={(event) => {
                const target = event.target.value as RecurrenceTarget
                set('targetKind', target)
                if (target === 'PROJECTION_ONLY') {
                  set('executionMode', 'MANUAL')
                }
              }}
            >
              <option value="PROJECTION_ONLY">Somente planejamento</option>
              <option value="ACCOUNT_TRANSACTION">Lançamento em conta</option>
              {cardTargetAllowed && (
                <option value="CREDIT_CARD_PURCHASE">Compra no cartão</option>
              )}
            </select>
          </FormField>

          {form.targetKind === 'ACCOUNT_TRANSACTION' && (
            <div className="commitment-form-grid">
              <FormField label="Conta">
                <select
                  className="select"
                  value={form.accountId}
                  onChange={(event) => set('accountId', event.target.value)}
                >
                  <option value="">Selecione…</option>
                  {openAccounts.map((account) => (
                    <option key={account.id} value={account.id}>
                      {account.name}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField label="Forma de pagamento (opcional)">
                <select
                  className="select"
                  value={form.paymentMethod}
                  onChange={(event) => set('paymentMethod', event.target.value)}
                >
                  <option value="">Não informar</option>
                  {Object.entries(PAYMENT_METHOD_LABELS)
                    .filter(([value]) => value !== 'CREDIT')
                    .map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                </select>
              </FormField>
            </div>
          )}

          {form.targetKind === 'CREDIT_CARD_PURCHASE' && (
            <div className="commitment-form-grid">
              <FormField label="Cartão">
                <select
                  className="select"
                  value={form.creditCardId}
                  onChange={(event) => set('creditCardId', event.target.value)}
                >
                  <option value="">Selecione…</option>
                  {activeCards.map((card) => (
                    <option key={card.id} value={card.id}>
                      {card.name}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField label="Parcelas" hint="Cada ocorrência gera uma compra parcelada.">
                <input
                  className="input"
                  type="number"
                  min={1}
                  max={120}
                  value={form.installmentCount}
                  onChange={(event) => set('installmentCount', event.target.value)}
                />
              </FormField>
            </div>
          )}

          {form.targetKind !== 'PROJECTION_ONLY' && (
            <FormField
              label="Execução"
              hint={
                form.executionMode === 'AUTOMATIC'
                  ? 'Ocorrências vencidas viram registros financeiros reais sem ação manual.'
                  : 'Você executa cada ocorrência manualmente quando quiser.'
              }
            >
              <select
                className="select"
                value={form.executionMode}
                onChange={(event) => set('executionMode', event.target.value as ExecutionMode)}
              >
                <option value="MANUAL">Manual</option>
                <option value="AUTOMATIC">Automática</option>
              </select>
            </FormField>
          )}
        </fieldset>

        <label className="commitment-active-toggle">
          <input
            type="checkbox"
            checked={form.active}
            onChange={(event) => set('active', event.target.checked)}
          />
          Recorrente ativo (entra nas projeções e no processamento)
        </label>

        {editing && (
          <p className="commitment-form-note">
            Alterações valem apenas para ocorrências futuras — o histórico já executado em{' '}
            {formatDate(editing.startDate)} em diante permanece como está.
          </p>
        )}

        {(formError || submitError != null) && (
          <div role="alert" className="field-error">
            {formError ?? errorMessage(submitError)}
          </div>
        )}
        <FormActions
          busy={busy}
          submitLabel={editing ? 'Salvar' : 'Criar recorrente'}
          onCancel={onCancel}
        />
      </div>
    </form>
  )
}
