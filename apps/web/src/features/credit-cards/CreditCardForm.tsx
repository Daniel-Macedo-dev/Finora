import { useState, type FormEvent } from 'react'
import FormActions from '../../components/FormActions'
import FormField from '../../components/FormField'
import { errorMessage } from '../../components/states'
import { ApiError } from '../../lib/api'
import { parseMoneyInput } from '../../lib/format'
import { useAccounts } from '../shared/api'
import { CARD_BRAND_LABELS, type CreditCard, type CreditCardBrand, type CreditCardRequest } from './types'

interface CreditCardFormProps {
  initial?: CreditCard
  busy: boolean
  submitError: unknown
  onSubmit: (request: CreditCardRequest) => void
  onCancel: () => void
}

interface FieldErrors {
  name?: string
  creditLimit?: string
  closingDay?: string
  dueDay?: string
  lastFourDigits?: string
}

export default function CreditCardForm({
  initial,
  busy,
  submitError,
  onSubmit,
  onCancel,
}: CreditCardFormProps) {
  const [name, setName] = useState(initial?.name ?? '')
  const [issuer, setIssuer] = useState(initial?.issuer ?? '')
  const [brand, setBrand] = useState<CreditCardBrand>(initial?.brand ?? 'VISA')
  const [lastFourDigits, setLastFourDigits] = useState(initial?.lastFourDigits ?? '')
  const [creditLimit, setCreditLimit] = useState(
    initial ? initial.limit.creditLimit.toFixed(2).replace('.', ',') : '',
  )
  const [closingDay, setClosingDay] = useState(initial ? String(initial.closingDay) : '')
  const [dueDay, setDueDay] = useState(initial ? String(initial.dueDay) : '')
  const [accountId, setAccountId] = useState(
    initial?.defaultPaymentAccountId ? String(initial.defaultPaymentAccountId) : '',
  )
  const [errors, setErrors] = useState<FieldErrors>({})

  const accounts = useAccounts()
  const openAccounts = (accounts.data ?? []).filter(
    (account) => !account.archived || String(account.id) === accountId,
  )

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const parsedLimit = parseMoneyInput(creditLimit)
    const closing = Number(closingDay)
    const due = Number(dueDay)
    const nextErrors: FieldErrors = {}
    if (!name.trim()) {
      nextErrors.name = 'Informe o nome do cartão.'
    }
    if (parsedLimit === null || parsedLimit <= 0) {
      nextErrors.creditLimit = 'Informe um limite maior que zero.'
    }
    if (!Number.isInteger(closing) || closing < 1 || closing > 31) {
      nextErrors.closingDay = 'Dia de fechamento entre 1 e 31.'
    }
    if (!Number.isInteger(due) || due < 1 || due > 31) {
      nextErrors.dueDay = 'Dia de vencimento entre 1 e 31.'
    }
    if (lastFourDigits && !/^\d{4}$/.test(lastFourDigits)) {
      nextErrors.lastFourDigits = 'Use exatamente 4 dígitos.'
    }
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) {
      return
    }
    onSubmit({
      name: name.trim(),
      issuer: issuer.trim() || null,
      brand,
      lastFourDigits: lastFourDigits || null,
      creditLimit: parsedLimit!,
      closingDay: closing,
      dueDay: due,
      defaultPaymentAccountId: accountId ? Number(accountId) : null,
    })
  }

  const serverFieldErrors = submitError instanceof ApiError ? submitError.fieldErrors : []

  return (
    <form onSubmit={handleSubmit} noValidate>
      <div className="form-grid">
        <FormField label="Nome do cartão" error={errors.name}>
          <input
            className="input"
            maxLength={100}
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="Ex.: Nubank Roxinho"
          />
        </FormField>

        <div className="form-columns">
          <FormField label="Bandeira">
            <select
              className="select"
              value={brand}
              onChange={(event) => setBrand(event.target.value as CreditCardBrand)}
            >
              {Object.entries(CARD_BRAND_LABELS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </FormField>

          <FormField label="Emissor (opcional)">
            <input
              className="input"
              maxLength={100}
              value={issuer}
              onChange={(event) => setIssuer(event.target.value)}
              placeholder="Ex.: Nu Pagamentos"
            />
          </FormField>

          <FormField label="4 últimos dígitos (opcional)" error={errors.lastFourDigits}>
            <input
              className="input"
              inputMode="numeric"
              maxLength={4}
              value={lastFourDigits}
              onChange={(event) => setLastFourDigits(event.target.value.replace(/\D/g, ''))}
              placeholder="0000"
            />
          </FormField>

          <FormField label="Limite (R$)" error={errors.creditLimit}>
            <input
              className="input"
              inputMode="decimal"
              placeholder="0,00"
              value={creditLimit}
              onChange={(event) => setCreditLimit(event.target.value)}
            />
          </FormField>

          <FormField label="Dia de fechamento" error={errors.closingDay}>
            <input
              className="input"
              type="number"
              min={1}
              max={31}
              value={closingDay}
              onChange={(event) => setClosingDay(event.target.value)}
            />
          </FormField>

          <FormField label="Dia de vencimento" error={errors.dueDay}>
            <input
              className="input"
              type="number"
              min={1}
              max={31}
              value={dueDay}
              onChange={(event) => setDueDay(event.target.value)}
            />
          </FormField>
        </div>

        <FormField label="Conta padrão de pagamento (opcional)">
          <select
            className="select"
            value={accountId}
            onChange={(event) => setAccountId(event.target.value)}
          >
            <option value="">Sem conta padrão</option>
            {openAccounts.map((account) => (
              <option key={account.id} value={account.id}>
                {account.name}
              </option>
            ))}
          </select>
        </FormField>

        {initial && (
          <p className="cc-form-hint">
            Alterar os dias de fechamento e vencimento afeta apenas as próximas faturas; as
            faturas já criadas mantêm suas datas.
          </p>
        )}

        {submitError != null && (
          <div role="alert" className="field-error">
            {serverFieldErrors.length > 0
              ? serverFieldErrors.map((fieldError) => fieldError.message).join(' ')
              : errorMessage(submitError)}
          </div>
        )}

        <FormActions
          busy={busy}
          submitLabel={initial ? 'Salvar alterações' : 'Adicionar cartão'}
          onCancel={onCancel}
        />
      </div>
    </form>
  )
}
