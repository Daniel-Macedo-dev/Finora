import { formatMoney, type CurrencyCode } from '../lib/money'

interface MoneyProps {
  value: number | null | undefined
  /**
   * Currency the value is denominated in.
   *
   * <p>Optional only while call sites are being migrated: an amount without its
   * currency is ambiguous, and the BRL fallback below silently mislabels any
   * foreign amount as reais. Every production caller must pass it, and the
   * fallback is removed once the last one does.
   */
  currency?: CurrencyCode
  /** Colors the value by sign: positive green, negative red. */
  signed?: boolean
  className?: string
}

export default function Money({
  value,
  currency = 'BRL',
  signed = false,
  className = '',
}: MoneyProps) {
  const tone =
    signed && value !== null && value !== undefined
      ? value > 0
        ? 'money-positive'
        : value < 0
          ? 'money-negative'
          : ''
      : ''
  return (
    <span className={`money ${tone} ${className}`.trim()}>{formatMoney(value, currency)}</span>
  )
}
