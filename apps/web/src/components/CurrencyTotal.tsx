import { formatMoney, type CurrencyTotals } from '../lib/money'

interface CurrencyTotalProps {
  label: string
  totals: CurrencyTotals | null | undefined
  className?: string
}

/**
 * Renders a total that may span currencies.
 *
 * <p>Finora has no exchange rates yet, so when foreign amounts are present
 * there is no honest single figure: each currency is shown on its own and the
 * consolidation is explicitly reported as unavailable. Showing only the base
 * currency, or adding the numbers, would both produce something the user would
 * act on.
 */
export default function CurrencyTotal({ label, totals, className = '' }: CurrencyTotalProps) {
  if (!totals) {
    return null
  }

  if (totals.complete) {
    return (
      <p className={`stat-footnote ${className}`.trim()}>
        {label}: {formatMoney(totals.total, totals.baseCurrency)}
      </p>
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
