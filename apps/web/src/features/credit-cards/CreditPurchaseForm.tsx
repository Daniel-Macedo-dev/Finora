import { useState, type FormEvent } from 'react'
import FormActions from '../../components/FormActions'
import FormField from '../../components/FormField'
import { errorMessage } from '../../components/states'
import { ApiError } from '../../lib/api'
import { formatBRL, parseMoneyInput } from '../../lib/format'
import { todayIso } from '../../lib/month'
import { useCategories } from '../shared/api'
import InstallmentPreview from './InstallmentPreview'
import type { CardPurchase, CreditCard, PurchaseRequest } from './types'

interface CreditPurchaseFormProps {
  card: CreditCard
  initial?: CardPurchase
  busy: boolean
  submitError: unknown
  onSubmit: (request: PurchaseRequest) => void
  onCancel: () => void
}

interface FieldErrors {
  description?: string
  totalAmount?: string
  purchaseDate?: string
  categoryId?: string
  installmentCount?: string
}

export default function CreditPurchaseForm({
  card,
  initial,
  busy,
  submitError,
  onSubmit,
  onCancel,
}: CreditPurchaseFormProps) {
  const [description, setDescription] = useState(initial?.description ?? '')
  const [merchant, setMerchant] = useState(initial?.merchant ?? '')
  const [categoryId, setCategoryId] = useState(initial ? String(initial.category.id) : '')
  const [purchaseDate, setPurchaseDate] = useState(initial?.purchaseDate ?? todayIso())
  const [totalAmount, setTotalAmount] = useState(
    initial ? initial.totalAmount.toFixed(2).replace('.', ',') : '',
  )
  const [installmentCount, setInstallmentCount] = useState(
    initial ? String(initial.installmentCount) : '1',
  )
  const [notes, setNotes] = useState(initial?.notes ?? '')
  const [errors, setErrors] = useState<FieldErrors>({})

  const categories = useCategories('EXPENSE')
  const activeCategories = (categories.data ?? []).filter(
    (category) => category.active || String(category.id) === categoryId,
  )

  const parsedAmount = parseMoneyInput(totalAmount)
  const parsedCount = Number(installmentCount)

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const nextErrors: FieldErrors = {}
    if (!description.trim()) {
      nextErrors.description = 'Informe a descrição.'
    }
    if (parsedAmount === null || parsedAmount <= 0) {
      nextErrors.totalAmount = 'Informe um valor maior que zero.'
    }
    if (!purchaseDate) {
      nextErrors.purchaseDate = 'Informe a data da compra.'
    }
    if (!categoryId) {
      nextErrors.categoryId = 'Selecione a categoria.'
    }
    if (!Number.isInteger(parsedCount) || parsedCount < 1 || parsedCount > 120) {
      nextErrors.installmentCount = 'Entre 1 e 120 parcelas.'
    }
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) {
      return
    }
    onSubmit({
      description: description.trim(),
      merchant: merchant.trim() || null,
      categoryId: Number(categoryId),
      purchaseDate,
      totalAmount: parsedAmount!,
      installmentCount: parsedCount,
      notes: notes.trim() || null,
    })
  }

  const serverFieldErrors = submitError instanceof ApiError ? submitError.fieldErrors : []
  const exceedsLimit =
    !initial && parsedAmount !== null && parsedAmount > card.limit.availableLimit

  return (
    <form onSubmit={handleSubmit} noValidate>
      <div className="form-grid">
        <FormField label="Descrição" error={errors.description}>
          <input
            className="input"
            maxLength={200}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="Ex.: Notebook"
          />
        </FormField>

        <div className="form-columns">
          <FormField label="Loja (opcional)">
            <input
              className="input"
              maxLength={150}
              value={merchant}
              onChange={(event) => setMerchant(event.target.value)}
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

          <FormField label="Data da compra" error={errors.purchaseDate}>
            <input
              className="input"
              type="date"
              value={purchaseDate}
              onChange={(event) => setPurchaseDate(event.target.value)}
            />
          </FormField>

          <FormField label="Valor total (R$)" error={errors.totalAmount}>
            <input
              className="input"
              inputMode="decimal"
              placeholder="0,00"
              value={totalAmount}
              onChange={(event) => setTotalAmount(event.target.value)}
            />
          </FormField>

          <FormField label="Parcelas" error={errors.installmentCount}>
            <input
              className="input"
              type="number"
              min={1}
              max={120}
              value={installmentCount}
              onChange={(event) => setInstallmentCount(event.target.value)}
            />
          </FormField>
        </div>

        {exceedsLimit && (
          <div role="alert" className="field-error">
            O valor excede o limite disponível de {formatBRL(card.limit.availableLimit)}. O
            servidor rejeitará a compra.
          </div>
        )}

        <InstallmentPreview
          total={parsedAmount}
          count={Number.isInteger(parsedCount) ? parsedCount : 0}
        />

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
          submitLabel={initial ? 'Salvar alterações' : 'Registrar compra'}
          onCancel={onCancel}
        />
      </div>
    </form>
  )
}
