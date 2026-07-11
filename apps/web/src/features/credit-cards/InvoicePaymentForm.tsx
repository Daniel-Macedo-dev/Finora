import { useState, type FormEvent } from 'react'
import FormActions from '../../components/FormActions'
import FormField from '../../components/FormField'
import { errorMessage } from '../../components/states'
import { ApiError } from '../../lib/api'
import { formatBRL, parseMoneyInput } from '../../lib/format'
import { todayIso } from '../../lib/month'
import { useAccounts } from '../shared/api'
import type { InvoiceSummary, PaymentRequest } from './types'

interface InvoicePaymentFormProps {
  invoice: InvoiceSummary
  defaultAccountId: number | null
  busy: boolean
  submitError: unknown
  onSubmit: (request: PaymentRequest) => void
  onCancel: () => void
}

interface FieldErrors {
  accountId?: string
  amount?: string
  paidOn?: string
}

export default function InvoicePaymentForm({
  invoice,
  defaultAccountId,
  busy,
  submitError,
  onSubmit,
  onCancel,
}: InvoicePaymentFormProps) {
  const [accountId, setAccountId] = useState(defaultAccountId ? String(defaultAccountId) : '')
  const [mode, setMode] = useState<'full' | 'partial'>('full')
  const [amount, setAmount] = useState('')
  const [paidOn, setPaidOn] = useState(todayIso())
  const [notes, setNotes] = useState('')
  const [errors, setErrors] = useState<FieldErrors>({})

  const accounts = useAccounts()
  const openAccounts = (accounts.data ?? []).filter((account) => !account.archived)

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const value = mode === 'full' ? invoice.outstandingAmount : parseMoneyInput(amount)
    const nextErrors: FieldErrors = {}
    if (!accountId) {
      nextErrors.accountId = 'Selecione a conta que pagará a fatura.'
    }
    if (value === null || value <= 0) {
      nextErrors.amount = 'Informe um valor maior que zero.'
    } else if (value > invoice.outstandingAmount) {
      nextErrors.amount = `O pagamento não pode exceder o valor em aberto (${formatBRL(invoice.outstandingAmount)}).`
    }
    if (!paidOn) {
      nextErrors.paidOn = 'Informe a data do pagamento.'
    }
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) {
      return
    }
    onSubmit({
      accountId: Number(accountId),
      amount: value!,
      paidOn,
      notes: notes.trim() || null,
    })
  }

  const serverFieldErrors = submitError instanceof ApiError ? submitError.fieldErrors : []

  return (
    <form onSubmit={handleSubmit} noValidate>
      <div className="form-grid">
        <p className="cc-payment-note">
          O pagamento reduz o saldo da conta, mas não registra uma nova despesa — as parcelas
          da fatura já contam como despesa nos seus meses.
        </p>

        <div className="form-columns">
          <FormField label="Conta" error={errors.accountId}>
            <select
              className="select"
              value={accountId}
              onChange={(event) => setAccountId(event.target.value)}
            >
              <option value="">Selecione…</option>
              {openAccounts.map((account) => (
                <option key={account.id} value={account.id}>
                  {account.name} ({formatBRL(account.currentBalance)})
                </option>
              ))}
            </select>
          </FormField>

          <FormField label="Data do pagamento" error={errors.paidOn}>
            <input
              className="input"
              type="date"
              value={paidOn}
              onChange={(event) => setPaidOn(event.target.value)}
            />
          </FormField>
        </div>

        <div className="field">
          <span className="cc-mode-label" id="payment-mode-label">
            Valor do pagamento
          </span>
          <div
            role="group"
            aria-labelledby="payment-mode-label"
            style={{ display: 'flex', gap: 'var(--space-2)' }}
          >
            <button
              type="button"
              aria-pressed={mode === 'full'}
              className={`btn ${mode === 'full' ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => setMode('full')}
            >
              Total em aberto ({formatBRL(invoice.outstandingAmount)})
            </button>
            <button
              type="button"
              aria-pressed={mode === 'partial'}
              className={`btn ${mode === 'partial' ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => setMode('partial')}
            >
              Valor parcial
            </button>
          </div>
        </div>

        {mode === 'partial' && (
          <FormField label="Valor parcial (R$)" error={errors.amount}>
            <input
              className="input"
              inputMode="decimal"
              placeholder="0,00"
              value={amount}
              onChange={(event) => setAmount(event.target.value)}
            />
          </FormField>
        )}
        {mode === 'full' && errors.amount && (
          <div role="alert" className="field-error">
            {errors.amount}
          </div>
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

        <FormActions busy={busy} submitLabel="Confirmar pagamento" onCancel={onCancel} />
      </div>
    </form>
  )
}
