import { formatMoney, type CurrencyTotals } from '../lib/money'

interface CurrencyTotalProps {
  label: string
  totals: CurrencyTotals | null | undefined
  className?: string
}

/**
 * Renders a total that may span currencies.
 *
 * <p>Finora has no exchange rates yet, so three different situations have to be
 * told apart, and collapsing any two of them would put a number in front of the
 * user that they would act on:
 *
 * <ul>
 *   <li>base-complete — one honest figure in the user's own currency;
 *   <li>homogeneous but foreign — one honest figure, explicitly labelled as
 *       being in that other currency and not a base-currency conclusion;
 *   <li>mixed — each currency on its own, consolidation reported as
 *       unavailable.
 * </ul>
 */
export default function CurrencyTotal({ label, totals, className = '' }: CurrencyTotalProps) {
  if (!totals) {
    return null
  }

  if (totals.baseComplete) {
    return (
      <p className={`stat-footnote ${className}`.trim()}>
        {label}: {formatMoney(totals.baseTotal, totals.baseCurrency)}
      </p>
    )
  }

  // One currency, but not the user's. The number is real; saying so plainly is
  // what keeps it from reading as a base-currency total.
  if (totals.homogeneous && totals.homogeneousCurrency) {
    const native = totals.homogeneousCurrency
    return (
      <div className={`stat-footnote currency-total-grouped ${className}`.trim()}>
        <p>
          {label}: <span className="currency-total-code">{native}</span>{' '}
          {formatMoney(totals.nativeTotal, native)}
        </p>
        <p role="note" className="currency-total-unavailable">
          Valor em {native}, não em {totals.baseCurrency}. Converter exige cotações, que ainda não
          existem no Finora.
        </p>
      </div>
    )
  }

  return (
    <div className={`stat-footnote currency-total-grouped ${className}`.trim()}>
      <p>{label}, por moeda:</p>
      <ul className="currency-total-list">
        {totals.byCurrency.map((entry) => (
          <li key={entry.currency}>
            <span className="currency-total-code">{entry.currency}</span>{' '}
            {formatMoney(entry.amount, entry.currency)}
          </li>
        ))}
      </ul>
      <p role="note" className="currency-total-unavailable">
        Total consolidado indisponível. Converter {totals.unconvertedCurrencies.join(', ')} para{' '}
        {totals.baseCurrency} exige cotações, que ainda não existem no Finora.
      </p>
    </div>
  )
}
