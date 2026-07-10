import { useState, type FormEvent } from 'react'
import FormActions from '../../components/FormActions'
import FormField from '../../components/FormField'
import { errorMessage } from '../../components/states'
import { parseMoneyInput } from '../../lib/format'
import type { PurchaseOption, PurchaseOptionKind, PurchaseOptionRequest } from './types'

interface PurchaseOptionFormProps {
  initial?: PurchaseOption
  busy: boolean
  submitError: unknown
  onSubmit: (request: PurchaseOptionRequest) => void
  onCancel: () => void
}

export default function PurchaseOptionForm({
  initial,
  busy,
  submitError,
  onSubmit,
  onCancel,
}: PurchaseOptionFormProps) {
  const [merchant, setMerchant] = useState(initial?.merchant ?? '')
  const [kind, setKind] = useState<PurchaseOptionKind>(initial?.kind ?? 'CASH')
  const [basePrice, setBasePrice] = useState(
    initial ? initial.basePrice.toFixed(2).replace('.', ',') : '',
  )
  const [shipping, setShipping] = useState(
    initial && initial.shipping > 0 ? initial.shipping.toFixed(2).replace('.', ',') : '',
  )
  const [fees, setFees] = useState(
    initial && initial.fees > 0 ? initial.fees.toFixed(2).replace('.', ',') : '',
  )
  const [installmentCount, setInstallmentCount] = useState(
    initial?.installmentCount != null ? String(initial.installmentCount) : '',
  )
  const [installmentAmount, setInstallmentAmount] = useState(
    initial?.installmentAmount != null
      ? initial.installmentAmount.toFixed(2).replace('.', ',')
      : '',
  )
  const [notes, setNotes] = useState(initial?.notes ?? '')
  const [formError, setFormError] = useState<string | null>(null)

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!merchant.trim()) {
      setFormError('Informe a loja ou vendedor.')
      return
    }
    const price = parseMoneyInput(basePrice)
    if (price === null || price <= 0) {
      setFormError(
        kind === 'CASH'
          ? 'Informe o preço à vista.'
          : 'Informe o preço total parcelado (soma de todas as parcelas).',
      )
      return
    }
    const shippingValue = shipping ? parseMoneyInput(shipping) : 0
    const feesValue = fees ? parseMoneyInput(fees) : 0
    if (shippingValue === null || shippingValue < 0 || feesValue === null || feesValue < 0) {
      setFormError('Frete e taxas não podem ser negativos.')
      return
    }
    let count: number | null = null
    let amount: number | null = null
    if (kind === 'INSTALLMENT') {
      count = installmentCount ? Number(installmentCount) : null
      amount = installmentAmount ? parseMoneyInput(installmentAmount) : null
      if (!count || count < 1 || !Number.isInteger(count)) {
        setFormError('Informe o número de parcelas.')
        return
      }
      if (amount === null || amount <= 0) {
        setFormError('Informe o valor de cada parcela.')
        return
      }
    }
    setFormError(null)
    onSubmit({
      merchant: merchant.trim(),
      kind,
      basePrice: price,
      shipping: shippingValue,
      fees: feesValue,
      installmentCount: count,
      installmentAmount: amount,
      notes: notes.trim() || null,
    })
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <div className="form-grid">
        <FormField label="Loja / vendedor">
          <input
            className="input"
            maxLength={150}
            value={merchant}
            onChange={(event) => setMerchant(event.target.value)}
          />
        </FormField>

        <div className="field">
          <span
            id="option-kind-label"
            style={{
              fontSize: 'var(--text-sm)',
              fontWeight: 550,
              color: 'var(--ink-secondary)',
            }}
          >
            Forma de pagamento
          </span>
          <div
            role="group"
            aria-labelledby="option-kind-label"
            style={{ display: 'flex', gap: 'var(--space-2)' }}
          >
            <button
              type="button"
              aria-pressed={kind === 'CASH'}
              className={`btn ${kind === 'CASH' ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => setKind('CASH')}
            >
              À vista
            </button>
            <button
              type="button"
              aria-pressed={kind === 'INSTALLMENT'}
              className={`btn ${kind === 'INSTALLMENT' ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => setKind('INSTALLMENT')}
            >
              Parcelado
            </button>
          </div>
        </div>

        <div className="form-columns">
          <FormField
            label={kind === 'CASH' ? 'Preço à vista (R$)' : 'Preço total parcelado (R$)'}
            hint={kind === 'INSTALLMENT' ? 'Soma de todas as parcelas.' : undefined}
          >
            <input
              className="input"
              inputMode="decimal"
              placeholder="0,00"
              value={basePrice}
              onChange={(event) => setBasePrice(event.target.value)}
            />
          </FormField>
          {kind === 'INSTALLMENT' && (
            <>
              <FormField label="Nº de parcelas">
                <input
                  className="input"
                  type="number"
                  min={1}
                  max={120}
                  value={installmentCount}
                  onChange={(event) => setInstallmentCount(event.target.value)}
                />
              </FormField>
              <FormField label="Valor da parcela (R$)">
                <input
                  className="input"
                  inputMode="decimal"
                  placeholder="0,00"
                  value={installmentAmount}
                  onChange={(event) => setInstallmentAmount(event.target.value)}
                />
              </FormField>
            </>
          )}
          <FormField label="Frete (R$)">
            <input
              className="input"
              inputMode="decimal"
              placeholder="0,00"
              value={shipping}
              onChange={(event) => setShipping(event.target.value)}
            />
          </FormField>
          <FormField label="Taxas (R$)">
            <input
              className="input"
              inputMode="decimal"
              placeholder="0,00"
              value={fees}
              onChange={(event) => setFees(event.target.value)}
            />
          </FormField>
        </div>

        <FormField label="Observações (opcional)">
          <textarea
            className="textarea"
            maxLength={2000}
            value={notes}
            onChange={(event) => setNotes(event.target.value)}
          />
        </FormField>

        {(formError || submitError != null) && (
          <div role="alert" className="field-error">
            {formError ?? errorMessage(submitError)}
          </div>
        )}

        <FormActions
          busy={busy}
          submitLabel={initial ? 'Salvar opção' : 'Adicionar opção'}
          onCancel={onCancel}
        />
      </div>
    </form>
  )
}
