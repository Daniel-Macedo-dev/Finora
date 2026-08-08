import { currencyLabel } from '../../lib/money'
import type { StatementCurrencyContext, StatementImportFormat } from './types'

interface ImportCurrencyNoticeProps {
  currency: StatementCurrencyContext
  format: StatementImportFormat
  accountName: string
  /**
   * Present only where the acknowledgement can still change an outcome. When
   * given, the control is rendered and starts from `acknowledged`.
   */
  acknowledged?: boolean
  onAcknowledgedChange?: (acknowledged: boolean) => void
}

/**
 * Stable preview metadata about the batch's denomination — not an alert and not
 * a toast. It states which currency the amounts are read in, why, and that
 * nothing was converted, because Finora holds no exchange rates.
 *
 * <p>The four sources get genuinely different wording. Saying "the file did not
 * declare a currency" about a pre-V16 import would be a claim nobody can make:
 * the parser of the day never read CURDEF, so the evidence is missing rather
 * than negative.
 */
export default function ImportCurrencyNotice({
  currency,
  format,
  accountName,
  acknowledged,
  onAcknowledgedChange,
}: ImportCurrencyNoticeProps) {
  const label = currencyLabel(currency.effectiveCurrency)
  const needsAck =
    currency.currencyAcknowledgementRequired && onAcknowledgedChange !== undefined

  return (
    <section
      className={`si-currency-notice si-currency-${currency.currencySource.toLowerCase()}`}
      aria-label="Moeda da importação"
    >
      <p className="si-currency-headline">
        {currency.currencySource === 'FILE' && currency.declaredCurrency !== null ? (
          <>
            O arquivo declara {currency.declaredCurrency} e a conta selecionada também usa{' '}
            {label}. Os valores serão importados sem conversão.
          </>
        ) : currency.currencySource === 'ACCOUNT' ? (
          <>
            Os valores deste {format} serão importados em {label} porque essa é a moeda da conta
            selecionada ({accountName}). Nenhuma conversão será realizada.
          </>
        ) : currency.currencySource === 'ACCOUNT_ASSUMED' ? (
          <>
            Este arquivo OFX não declarou uma moeda. O Finora usará {label}, a moeda da conta
            selecionada ({accountName}). Nenhuma conversão será realizada.
          </>
        ) : (
          <>
            Esta importação foi criada antes de o Finora registrar a moeda declarada pelo arquivo.
            Para importar os itens restantes, confirme que eles devem usar {label}, a moeda da
            conta selecionada ({accountName}).
          </>
        )}
      </p>

      {needsAck && (
        <div className="si-currency-ack">
          <label className="si-checkbox-row" htmlFor="si-currency-ack">
            <input
              id="si-currency-ack"
              type="checkbox"
              checked={acknowledged ?? false}
              onChange={(event) => onAcknowledgedChange?.(event.target.checked)}
            />
            Confirmo que os valores deste arquivo devem ser interpretados em{' '}
            {currency.effectiveCurrency}.
          </label>
        </div>
      )}
    </section>
  )
}
