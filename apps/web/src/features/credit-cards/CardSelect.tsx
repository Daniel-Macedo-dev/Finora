import { formatBRL } from '../../lib/format'
import type { CreditCard } from './types'

interface CardSelectProps extends Omit<React.ComponentProps<'select'>, 'value' | 'onChange'> {
  cards: CreditCard[]
  value: string
  onChange: (cardId: string) => void
}

/**
 * Card dropdown used by every flow that targets a real card: shows each
 * active card with its available limit so the user can judge fit before any
 * preview runs. Archived cards never appear. Extra select props (id, aria-*)
 * are forwarded so FormField's label wiring keeps working.
 */
export default function CardSelect({ cards, value, onChange, ...selectProps }: CardSelectProps) {
  return (
    <select
      className="select"
      value={value}
      onChange={(event) => onChange(event.target.value)}
      {...selectProps}
    >
      <option value="">Escolha um cartão…</option>
      {cards
        .filter((card) => !card.archived)
        .map((card) => (
          <option key={card.id} value={card.id}>
            {card.name} — limite disponível {formatBRL(card.limit.availableLimit)}
          </option>
        ))}
    </select>
  )
}
