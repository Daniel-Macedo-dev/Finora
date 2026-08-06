import { formatMoney, type CurrencyTotals } from '../lib/money'

interface CurrencyStatProps {
  totals: CurrencyTotals
  /** Colors the value by sign, for figures that can legitimately go negative. */
  signed?: boolean
}

function tone(value: number, signed: boolean): string {
  if (!signed) return ''
  return value > 0 ? 'money-positive' : value < 0 ? 'money-negative' : ''
}

/**
 * A headline figure that may not exist.
 *
 * <p>Three situations have to stay distinguishable, and the failure mode of
 * collapsing them is a number somebody acts on:
 *
 * <ul>
 *   <li>base-complete — one figure in the user's own currency, exactly as before;
 *   <li>homogeneous but foreign — one real figure, said out loud to be in that
 *       other currency;
 *   <li>mixed — each currency listed, with no consolidated figure at all.
 * </ul>
 *
 * <p>An unavailable total is never rendered as zero, and never as a bare dash:
 * the reason is written out, because "—" alone reads as "nothing".
 */
export default function CurrencyStat({ totals, signed = false }: CurrencyStatProps) {
  if (totals.baseComplete) {
    const value = totals.baseTotal ?? 0
    return (
      <span className={`money ${tone(value, signed)}`.trim()}>
        {formatMoney(value, totals.baseCurrency)}
      </span>
    )
  }

  if (totals.homogeneous && totals.homogeneousCurrency) {
    const currency = totals.homogeneousCurrency
    const value = totals.nativeTotal ?? 0
    return (
      <span className="currency-stat-native">
        <span className={`money ${tone(value, signed)}`.trim()}>
          {formatMoney(value, currency)}
        </span>
        <span className="currency-stat-note">
          Valor em {currency}, não em {totals.baseCurrency}
        </span>
      </span>
    )
  }

  return (
    <span className="currency-stat-grouped">
      <ul className="currency-stat-list">
        {totals.byCurrency.map((entry) => (
          <li key={entry.currency}>
            <span className="currency-total-code">{entry.currency}</span>{' '}
            <span className={`money ${tone(entry.amount, signed)}`.trim()}>
              {formatMoney(entry.amount, entry.currency)}
            </span>
          </li>
        ))}
      </ul>
      <span className="currency-stat-note" role="note">
        Sem total consolidado: converter {totals.unconvertedCurrencies.join(', ')} para{' '}
        {totals.baseCurrency} exige cotações, que ainda não existem no Finora.
      </span>
    </span>
  )
}
