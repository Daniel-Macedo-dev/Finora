import { useState, type FormEvent } from 'react'
import FormField from '../../components/FormField'
import { errorMessage } from '../../components/states'
import { parseMoneyInput } from '../../lib/format'
import { useCategories } from '../shared/api'
import {
  PRIORITY_LABELS,
  STATUS_LABELS,
  type WishlistItemDetail,
  type WishlistItemRequest,
  type WishlistPriority,
  type WishlistStatus,
} from './types'

interface WishlistItemFormProps {
  initial?: WishlistItemDetail
  busy: boolean
  submitError: unknown
  onSubmit: (request: WishlistItemRequest) => void
  onCancel: () => void
}

export default function WishlistItemForm({
  initial,
  busy,
  submitError,
  onSubmit,
  onCancel,
}: WishlistItemFormProps) {
  const [name, setName] = useState(initial?.name ?? '')
  const [priority, setPriority] = useState<WishlistPriority>(initial?.priority ?? 'MEDIUM')
  const [status, setStatus] = useState<WishlistStatus>(initial?.status ?? 'PLANNING')
  const [categoryId, setCategoryId] = useState(
    initial?.category ? String(initial.category.id) : '',
  )
  const [referencePrice, setReferencePrice] = useState(
    initial?.referencePrice != null ? initial.referencePrice.toFixed(2).replace('.', ',') : '',
  )
  const [targetPrice, setTargetPrice] = useState(
    initial?.targetPrice != null ? initial.targetPrice.toFixed(2).replace('.', ',') : '',
  )
  const [desiredDate, setDesiredDate] = useState(initial?.desiredDate ?? '')
  const [notes, setNotes] = useState(initial?.notes ?? '')
  const [formError, setFormError] = useState<string | null>(null)

  const categories = useCategories('EXPENSE')

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!name.trim()) {
      setFormError('Informe o nome do item.')
      return
    }
    const reference = referencePrice ? parseMoneyInput(referencePrice) : null
    const target = targetPrice ? parseMoneyInput(targetPrice) : null
    if (referencePrice && (reference === null || reference < 0)) {
      setFormError('Preço de referência inválido.')
      return
    }
    if (targetPrice && (target === null || target < 0)) {
      setFormError('Preço alvo inválido.')
      return
    }
    setFormError(null)
    onSubmit({
      name: name.trim(),
      priority,
      status,
      categoryId: categoryId ? Number(categoryId) : null,
      referencePrice: reference,
      targetPrice: target,
      desiredDate: desiredDate || null,
      notes: notes.trim() || null,
    })
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <div style={{ display: 'grid', gap: 'var(--space-3)' }}>
        <FormField label="Nome do item">
          <input
            className="input"
            maxLength={150}
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </FormField>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
            gap: 'var(--space-3)',
          }}
        >
          <FormField label="Prioridade">
            <select
              className="select"
              value={priority}
              onChange={(event) => setPriority(event.target.value as WishlistPriority)}
            >
              {Object.entries(PRIORITY_LABELS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField label="Status">
            <select
              className="select"
              value={status}
              onChange={(event) => setStatus(event.target.value as WishlistStatus)}
            >
              {Object.entries(STATUS_LABELS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField label="Categoria (opcional)">
            <select
              className="select"
              value={categoryId}
              onChange={(event) => setCategoryId(event.target.value)}
            >
              <option value="">Sem categoria</option>
              {(categories.data ?? [])
                .filter((category) => category.active)
                .map((category) => (
                  <option key={category.id} value={category.id}>
                    {category.name}
                  </option>
                ))}
            </select>
          </FormField>
          <FormField label="Data desejada (opcional)">
            <input
              className="input"
              type="date"
              value={desiredDate}
              onChange={(event) => setDesiredDate(event.target.value)}
            />
          </FormField>
          <FormField label="Preço de referência (R$)" hint="Preço típico observado.">
            <input
              className="input"
              inputMode="decimal"
              placeholder="0,00"
              value={referencePrice}
              onChange={(event) => setReferencePrice(event.target.value)}
            />
          </FormField>
          <FormField label="Preço alvo (R$)" hint="Quanto você quer pagar.">
            <input
              className="input"
              inputMode="decimal"
              placeholder="0,00"
              value={targetPrice}
              onChange={(event) => setTargetPrice(event.target.value)}
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
        <div style={{ display: 'flex', gap: 'var(--space-2)', justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-secondary" onClick={onCancel} disabled={busy}>
            Cancelar
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Salvando…' : initial ? 'Salvar' : 'Adicionar item'}
          </button>
        </div>
      </div>
    </form>
  )
}
