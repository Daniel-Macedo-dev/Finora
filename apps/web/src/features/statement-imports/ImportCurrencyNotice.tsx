import type { ReactNode } from 'react'
import { currencyLabel } from '../../lib/money'
import type {
  StatementCurrencyContext,
  StatementCurrencySource,
  StatementImportFormat,
} from './types'

interface Wording {
  /** "USD — Dólar americano". */
  label: string
  /** The bare code, for the acknowledgement sentence. */
  code: string
  /** Present only for a file that declared its own currency. */
  declared: string | null
  format: StatementImportFormat
  accountName: string
}

/**
 * One sentence per currency source, keyed exhaustively.
 *
 * <p>A record rather than a ternary chain on purpose: a chain's final branch
 * would absorb any source added later, and the source it falls through to today
 * makes a specific claim about Finora's own history. A new source must be given
 * its own wording, and the compiler is what insists on it.
 */
const EXPLANATIONS: Record<StatementCurrencySource, (wording: Wording) => ReactNode> = {
  ACCOUNT: ({ label, format, accountName }) => (
    <>
      Os valores deste {format} serão importados em {label} porque essa é a moeda da conta
      selecionada ({accountName}). Nenhuma conversão será realizada.
    </>
  ),
  FILE: ({ label, declared }) => (
    <>
      O arquivo declara {declared} e a conta selecionada também usa {label}. Os valores serão
      importados sem conversão.
    </>
  ),
  ACCOUNT_ASSUMED: ({ label, accountName }) => (
    <>
      Este arquivo OFX não declarou uma moeda. O Finora usará {label}, a moeda da conta
      selecionada ({accountName}). Nenhuma conversão será realizada.
    </>
  ),
  // Deliberately not "the file declared no currency": nobody knows what it
  // declared, because the parser of the day never read it.
  LEGACY_UNKNOWN: ({ label, accountName }) => (
    <>
      Esta importação foi criada antes de o Finora registrar a moeda declarada pelo arquivo. Para
      importar os itens restantes, confirme que eles devem usar {label}, a moeda da conta
      selecionada ({accountName}).
    </>
  ),
}

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
 * <p>The four sources get genuinely different wording — see {@link EXPLANATIONS}.
 */
export default function ImportCurrencyNotice({
  currency,
  format,
  accountName,
  acknowledged,
  onAcknowledgedChange,
}: ImportCurrencyNoticeProps) {
  const wording: Wording = {
    label: currencyLabel(currency.effectiveCurrency),
    code: currency.effectiveCurrency,
    declared: currency.declaredCurrency,
    format,
    accountName,
  }
  const needsAck =
    currency.currencyAcknowledgementRequired && onAcknowledgedChange !== undefined

  return (
    <section className="si-currency-notice" aria-label="Moeda da importação">
      <p className="si-currency-headline">{EXPLANATIONS[currency.currencySource](wording)}</p>

      {needsAck && (
        <div className="si-currency-ack">
          <label className="si-checkbox-row" htmlFor="si-currency-ack">
            <input
              id="si-currency-ack"
              type="checkbox"
              checked={acknowledged ?? false}
              onChange={(event) => onAcknowledgedChange?.(event.target.checked)}
            />
            Confirmo que os valores deste arquivo devem ser interpretados em {wording.code}.
          </label>
        </div>
      )}
    </section>
  )
}
