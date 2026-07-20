import { useState, type FormEvent } from 'react'
import Dialog from '../../components/Dialog'
import FormField from '../../components/FormField'
import { errorMessage } from '../../components/states'
import { formatBRL, formatDate, parseMoneyInput } from '../../lib/format'
import { useCategories } from '../shared/api'
import { useCreateCategoryRule, usePatchItem } from './api'
import type { StatementItem, TransactionType } from './types'

interface ImportItemEditorProps {
  batchId: number
  item: StatementItem
  onClose: () => void
}

/**
 * Pre-confirmation editing of one statement row. Original parsed values
 * stay visible for audit; every change is validated by the backend. A
 * category correction can optionally become a deterministic rule.
 */
export default function ImportItemEditor({ batchId, item, onClose }: ImportItemEditorProps) {
  const patchItem = usePatchItem()
  const createRule = useCreateCategoryRule()
  const [description, setDescription] = useState(item.description ?? '')
  const [postedDate, setPostedDate] = useState(item.postedDate ?? '')
  const [amount, setAmount] = useState(
    item.amount !== null ? String(item.amount).replace('.', ',') : '',
  )
  const [type, setType] = useState<TransactionType>(item.type ?? 'EXPENSE')
  const [categoryId, setCategoryId] = useState(
    item.selectedCategoryId !== null ? String(item.selectedCategoryId) : '',
  )
  const [saveAsRule, setSaveAsRule] = useState(false)
  const [rulePattern, setRulePattern] = useState(item.description ?? '')
  const [localError, setLocalError] = useState<string | null>(null)

  const categories = useCategories(type)

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const parsedAmount = parseMoneyInput(amount)
    if (!description.trim()) {
      setLocalError('A descrição não pode ficar vazia.')
      return
    }
    if (parsedAmount === null || parsedAmount <= 0) {
      setLocalError('Informe um valor maior que zero.')
      return
    }
    if (saveAsRule && (!rulePattern.trim() || !categoryId)) {
      setLocalError('Para salvar a regra, informe o texto e a categoria.')
      return
    }
    setLocalError(null)
    patchItem.mutate(
      {
        batchId,
        itemId: item.id,
        patch: {
          description: description.trim(),
          postedDate: postedDate || undefined,
          amount: parsedAmount,
          type,
          selectedCategoryId: categoryId ? Number(categoryId) : undefined,
        },
      },
      {
        onSuccess: () => {
          if (saveAsRule && categoryId) {
            createRule.mutate(
              {
                transactionType: type,
                matchField: 'DESCRIPTION',
                operation: 'CONTAINS',
                pattern: rulePattern.trim(),
                categoryId: Number(categoryId),
                priority: 100,
                active: true,
              },
              { onSettled: onClose },
            )
          } else {
            onClose()
          }
        },
      },
    )
  }

  const busy = patchItem.isPending || createRule.isPending

  return (
    <Dialog open title="Editar lançamento" onClose={onClose}>
      <form onSubmit={handleSubmit} noValidate>
        <p className="si-original-values">
          Valores lidos do arquivo: {formatDate(item.originalDate)} ·{' '}
          {item.originalDescription ?? '—'} · {formatBRL(item.originalAmount)}. Eles ficam
          preservados para auditoria.
        </p>

        <FormField label="Descrição">
          <input
            className="input"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            maxLength={200}
            required
          />
        </FormField>

        <div className="form-columns">
          <FormField label="Data">
            <input
              className="input"
              type="date"
              value={postedDate}
              onChange={(event) => setPostedDate(event.target.value)}
              required
            />
          </FormField>
          <FormField label="Valor (R$)">
            <input
              className="input"
              inputMode="decimal"
              value={amount}
              onChange={(event) => setAmount(event.target.value)}
              required
            />
          </FormField>
        </div>

        <div className="form-columns">
          <FormField label="Tipo">
            <select
              className="select"
              value={type}
              onChange={(event) => {
                setType(event.target.value as TransactionType)
                setCategoryId('')
              }}
            >
              <option value="EXPENSE">Despesa</option>
              <option value="INCOME">Receita</option>
            </select>
          </FormField>
          <FormField label="Categoria">
            <select
              className="select"
              value={categoryId}
              onChange={(event) => setCategoryId(event.target.value)}
            >
              <option value="">Escolha a categoria</option>
              {(categories.data ?? [])
                .filter((category) => category.active)
                .map((category) => (
                  <option key={category.id} value={category.id}>
                    {category.name}
                  </option>
                ))}
            </select>
          </FormField>
        </div>

        <div className="field">
          <label className="si-checkbox-row" htmlFor="si-save-rule">
            <input
              id="si-save-rule"
              type="checkbox"
              checked={saveAsRule}
              onChange={(event) => setSaveAsRule(event.target.checked)}
            />
            Criar uma regra para categorizar lançamentos parecidos automaticamente
          </label>
        </div>
        {saveAsRule && (
          <FormField
            label="Quando a descrição contiver"
            hint="A regra é determinística: compara o texto, sem inteligência artificial."
          >
            <input
              className="input"
              value={rulePattern}
              onChange={(event) => setRulePattern(event.target.value)}
              maxLength={200}
            />
          </FormField>
        )}

        {(localError || patchItem.isError || createRule.isError) && (
          <p className="field-error" role="alert">
            {localError ??
              (patchItem.isError
                ? errorMessage(patchItem.error)
                : errorMessage(createRule.error))}
          </p>
        )}

        <div className="form-footer">
          <button type="button" className="btn btn-secondary" onClick={onClose} disabled={busy}>
            Cancelar
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Salvando…' : 'Salvar alterações'}
          </button>
        </div>
      </form>
    </Dialog>
  )
}
