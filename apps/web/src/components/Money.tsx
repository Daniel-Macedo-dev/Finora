import { formatBRL } from '../lib/format'

interface MoneyProps {
  value: number | null | undefined
  /** Colors the value by sign: positive green, negative red. */
  signed?: boolean
  className?: string
}

export default function Money({ value, signed = false, className = '' }: MoneyProps) {
  const tone =
    signed && value !== null && value !== undefined
      ? value > 0
        ? 'money-positive'
        : value < 0
          ? 'money-negative'
          : ''
      : ''
  return <span className={`money ${tone} ${className}`.trim()}>{formatBRL(value)}</span>
}
