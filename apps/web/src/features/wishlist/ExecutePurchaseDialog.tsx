import { useState, type FormEvent } from 'react'
import FormActions from '../../components/FormActions'
import FormField from '../../components/FormField'
import { errorMessage } from '../../components/states'
import { formatBRL } from '../../lib/format'
import { todayIso } from '../../lib/month'
import { useCreditCards } from '../credit-cards/api'
import { useAccounts } from '../shared/api'
import type { ExecutePurchaseRequest, PurchaseOption } from './types'

interface ExecutePurchaseFormProps {
  option: PurchaseOption
  busy: boolean
  submitError: unknown
  onSubmit: (request: ExecutePurchaseRequest) => void
  onCancel: () => void
}

/**
 * Converts the selected wishlist option into a real financial event: a CASH
 * option becomes an expense transaction, an INSTALLMENT option becomes a card
 * purchase with its full invoice schedule. The backend guarantees this can
 * only happen once per item.
 */
export default function ExecutePurchaseForm({
  option,
  busy,
  submitError,
  onSubmit,
  onCancel,
}: ExecutePurchaseFormProps) {
  const isCash = option.kind === 'CASH'
  const [accountId, setAccountId] = useState('')
  const [cardId, setCardId] = useState(option.creditCardId ? String(option.creditCardId) : '')
  const [purchasedOn, setPurchasedOn] = useState(todayIso())
  const [formError, setFormError] = useState<string | null>(null)

  const accounts = useAccounts()
  const cards = useCreditCards()
  const openAccounts = (accounts.data ?? []).filter((account) => !account.archived)
  const activeCards = (cards.data ?? []).filter((card) => !card.archived)

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!isCash && !cardId) {
      setFormError('Escolha o cartão que receberá a compra parcelada.')
      return
    }
    setFormError(null)
    onSubmit({
      optionId: option.id,
      accountId: isCash && accountId ? Number(accountId) : null,
      creditCardId: !isCash && cardId ? Number(cardId) : null,
      purchasedOn,
    })
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <div className="form-grid">
        <p className="option-execute-summary">
          {isCash
            ? `Compra à vista em ${option.merchant} por ${formatBRL(option.nominalCost)}. Será registrada como despesa na data escolhida.`
            : `Compra parcelada em ${option.merchant}: ${option.installmentCount}× · total ${formatBRL(option.nominalCost)}. As parcelas entram nas próximas faturas do cartão.`}
        </p>

        <div className="form-columns">
          {isCash ? (
            <FormField label="Conta que paga (opcional)">
              <select
                className="select"
                value={accountId}
                onChange={(event) => setAccountId(event.target.value)}
              >
                <option value="">Sem conta</option>
                {openAccounts.map((account) => (
                  <option key={account.id} value={account.id}>
                    {account.name} ({formatBRL(account.currentBalance)})
                  </option>
                ))}
              </select>
            </FormField>
          ) : (
            <FormField label="Cartão de crédito">
              <select
                className="select"
                value={cardId}
                onChange={(event) => setCardId(event.target.value)}
              >
                <option value="">Selecione…</option>
                {activeCards.map((card) => (
                  <option key={card.id} value={card.id}>
                    {card.name} (disponível {formatBRL(card.limit.availableLimit)})
                  </option>
                ))}
              </select>
            </FormField>
          )}

          <FormField label="Data da compra">
            <input
              className="input"
              type="date"
              value={purchasedOn}
              onChange={(event) => setPurchasedOn(event.target.value)}
            />
          </FormField>
        </div>

        {(formError || submitError != null) && (
          <div role="alert" className="field-error">
            {formError ?? errorMessage(submitError)}
          </div>
        )}

        <FormActions busy={busy} submitLabel="Confirmar compra" onCancel={onCancel} />
      </div>
    </form>
  )
}
