import { useId, useMemo } from 'react'
import { errorMessage } from '../../components/states'
import { currencyLabel } from '../../lib/money'
import { ACCOUNT_TYPE_LABELS } from '../shared/types'
import { useAccounts } from '../shared/api'
import { useChangeAccount } from './api'
import type { BatchDetail } from './types'

interface DestinationAccountFieldProps {
  batch: BatchDetail
}

/**
 * The batch's destination account: read-only once the batch is locked, editable
 * while it is not.
 *
 * <p>Because the account decides the denomination, changing it changes what the
 * amounts mean — never their value. Nothing is converted; the same numbers are
 * simply read in the newly selected account's currency, which the copy below
 * says outright so the change never looks like a conversion.
 *
 * <p>A batch whose currency the file itself declared can only move between
 * accounts of that currency. The backend refuses the rest before writing
 * anything, and its message names both currencies.
 */
export default function DestinationAccountField({ batch }: DestinationAccountFieldProps) {
  const accounts = useAccounts()
  const changeAccount = useChangeAccount()
  // The select lives in a definition list rather than a FormField, so the hint
  // and the currency refusal are associated by hand — without this, a reader
  // reaching the control hears its label and nothing about the constraint.
  const fieldId = useId()
  const hintId = `${fieldId}-hint`
  const errorId = `${fieldId}-error`

  const editable = batch.status === 'NEEDS_MAPPING' || batch.status === 'PREVIEW_READY'
  const declared = batch.currency.currencySource === 'FILE'
      ? batch.currency.declaredCurrency
      : null

  const eligible = useMemo(
    () =>
      (accounts.data ?? []).filter(
        (account) =>
          !account.archived && (account.type === 'CHECKING' || account.type === 'SAVINGS'),
      ),
    [accounts.data],
  )

  if (!editable) {
    return (
      <div>
        <dt>Conta de destino</dt>
        <dd>
          {batch.accountName} • {currencyLabel(batch.currency.accountCurrency)}
        </dd>
      </div>
    )
  }

  return (
    <div className="si-destination-field">
      <dt>
        <label htmlFor="si-destination-account">Conta de destino</label>
      </dt>
      <dd>
        <select
          id="si-destination-account"
          className="select"
          value={String(batch.accountId)}
          aria-describedby={changeAccount.isError ? `${hintId} ${errorId}` : hintId}
          aria-invalid={changeAccount.isError ? true : undefined}
          disabled={changeAccount.isPending || eligible.length === 0}
          onChange={(event) =>
            changeAccount.mutate({ batchId: batch.id, accountId: Number(event.target.value) })
          }
        >
          {eligible.map((account) => (
            <option key={account.id} value={account.id}>
              {account.name} • {ACCOUNT_TYPE_LABELS[account.type]} •{' '}
              {currencyLabel(account.currency)}
            </option>
          ))}
        </select>
        <span id={hintId} className="si-destination-hint">
          {declared !== null
            ? `O arquivo declara ${declared}: só contas em ${declared} podem receber este extrato.`
            : 'Trocar a conta muda a moeda em que os mesmos valores serão lidos. Nenhuma conversão é realizada.'}
        </span>
        {changeAccount.isError && (
          <p id={errorId} className="field-error" role="alert">
            {errorMessage(changeAccount.error)}
          </p>
        )}
      </dd>
    </div>
  )
}
