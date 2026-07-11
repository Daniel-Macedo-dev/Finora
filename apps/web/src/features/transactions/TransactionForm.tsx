import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import FormActions from '../../components/FormActions'
import FormField from '../../components/FormField'
import { errorMessage } from '../../components/states'
import { ApiError } from '../../lib/api'
import { parseMoneyInput } from '../../lib/format'
import { todayIso } from '../../lib/month'
import { useCategories } from '../shared/api'
import { useAccounts } from '../shared/api'
import { PAYMENT_METHOD_LABELS, type PaymentMethod, type TransactionType } from '../shared/types'
import type { Transaction, TransactionRequest } from './types'

interface TransactionFormProps {
  initial?: Transaction
  busy: boolean
  submitError: unknown
  onSubmit: (request: TransactionRequest) => void
  onCancel: () => void
}

interface FieldErrors {
  amount?: string
  description?: string
  date?: string
  categoryId?: string
}

export default function TransactionForm({
  initial,
  busy,
  submitError,
  onSubmit,
  onCancel,
}: TransactionFormProps) {
  const [type, setType] = useState<TransactionType>(initial?.type ?? 'EXPENSE')
  const [amount, setAmount] = useState(
    initial ? initial.amount.toFixed(2).replace('.', ',') : '',
  )
  const [description, setDescription] = useState(initial?.description ?? '')
  const [date, setDate] = useState(initial?.date ?? todayIso())
  const [categoryId, setCategoryId] = useState<string>(
    initial ? String(initial.category.id) : '',
  )
  const [accountId, setAccountId] = useState<string>(
    initial?.account ? String(initial.account.id) : '',
  )
  const [paymentMethod, setPaymentMethod] = useState<string>(initial?.paymentMethod ?? '')
  const [notes, setNotes] = useState(initial?.notes ?? '')
  const [errors, setErrors] = useState<FieldErrors>({})

  const categories = useCategories(type)
  const accounts = useAccounts()

  // New credit purchases belong to the card flow (invoices, installments,
  // limit); the generic CREDIT method survives only on legacy entries.
  const paymentMethodOptions = Object.entries(PAYMENT_METHOD_LABELS).filter(
    ([value]) => value !== 'CREDIT' || (initial?.legacyCredit && initial.paymentMethod === 'CREDIT'),
  )

  const activeCategories = (categories.data ?? []).filter(
    (category) => category.active || String(category.id) === categoryId,
  )
  const openAccounts = (accounts.data ?? []).filter(
    (account) => !account.archived || String(account.id) === accountId,
  )

  function handleTypeChange(next: TransactionType) {
    setType(next)
    setCategoryId('')
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const parsedAmount = parseMoneyInput(amount)
    const nextErrors: FieldErrors = {}
    if (parsedAmount === null || parsedAmount <= 0) {
      nextErrors.amount = 'Informe um valor maior que zero.'
    }
    if (!description.trim()) {
      nextErrors.description = 'Informe a descrição.'
    }
    if (!date) {
      nextErrors.date = 'Informe a data.'
    }
    if (!categoryId) {
      nextErrors.categoryId = 'Selecione a categoria.'
    }
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) {
      return
    }
    onSubmit({
      type,
      amount: parsedAmount!,
      description: description.trim(),
      date,
      categoryId: Number(categoryId),
      accountId: accountId ? Number(accountId) : null,
      paymentMethod: (paymentMethod || null) as PaymentMethod | null,
      notes: notes.trim() || null,
    })
  }

  const serverFieldErrors =
    submitError instanceof ApiError ? submitError.fieldErrors : []

  return (
    <form onSubmit={handleSubmit} noValidate>
      <div className="form-grid">
        <div className="field">
          <span
            style={{
              fontSize: 'var(--text-sm)',
              fontWeight: 550,
              color: 'var(--ink-secondary)',
            }}
            id="tx-type-label"
          >
            Tipo
          </span>
          <div
            role="group"
            aria-labelledby="tx-type-label"
            style={{ display: 'flex', gap: 'var(--space-2)' }}
          >
            <button
              type="button"
              aria-pressed={type === 'EXPENSE'}
              className={`btn ${type === 'EXPENSE' ? 'btn-danger' : 'btn-secondary'}`}
              onClick={() => handleTypeChange('EXPENSE')}
            >
              Despesa
            </button>
            <button
              type="button"
              aria-pressed={type === 'INCOME'}
              className={`btn ${type === 'INCOME' ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => handleTypeChange('INCOME')}
            >
              Receita
            </button>
          </div>
        </div>

        <FormField label="Valor (R$)" error={errors.amount}>
          <input
            className="input"
            inputMode="decimal"
            placeholder="0,00"
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
          />
        </FormField>

        <FormField label="Descrição" error={errors.description}>
          <input
            className="input"
            maxLength={200}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
        </FormField>

        <div className="form-columns">
          <FormField label="Data" error={errors.date}>
            <input
              className="input"
              type="date"
              value={date}
              onChange={(event) => setDate(event.target.value)}
            />
          </FormField>

          <FormField label="Categoria" error={errors.categoryId}>
            <select
              className="select"
              value={categoryId}
              onChange={(event) => setCategoryId(event.target.value)}
            >
              <option value="">Selecione…</option>
              {activeCategories.map((category) => (
                <option key={category.id} value={category.id}>
                  {category.name}
                </option>
              ))}
            </select>
          </FormField>

          <FormField label="Conta (opcional)">
            <select
              className="select"
              value={accountId}
              onChange={(event) => setAccountId(event.target.value)}
            >
              <option value="">Sem conta</option>
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
              value={paymentMethod}
              onChange={(event) => setPaymentMethod(event.target.value)}
            >
              <option value="">Não informar</option>
              {paymentMethodOptions.map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </FormField>
        </div>

        {type === 'EXPENSE' && !initial?.legacyCredit && (
          <p className="tx-credit-hint">
            Compra no crédito? <Link to="/credit-cards">Registre na área de Cartões</Link> para
            acompanhar fatura, parcelas e limite.
          </p>
        )}

        <FormField label="Observações (opcional)">
          <textarea
            className="textarea"
            maxLength={2000}
            value={notes}
            onChange={(event) => setNotes(event.target.value)}
          />
        </FormField>

        {submitError != null && (
          <div role="alert" className="field-error">
            {serverFieldErrors.length > 0
              ? serverFieldErrors.map((fieldError) => fieldError.message).join(' ')
              : errorMessage(submitError)}
          </div>
        )}

        <FormActions
          busy={busy}
          submitLabel={initial ? 'Salvar alterações' : 'Adicionar transação'}
          onCancel={onCancel}
        />
      </div>
    </form>
  )
}
