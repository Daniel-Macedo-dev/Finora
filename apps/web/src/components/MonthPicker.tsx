import { ChevronLeft, ChevronRight } from 'lucide-react'
import { addMonths, currentMonth } from '../lib/month'
import { formatMonth } from '../lib/format'
import './MonthPicker.css'

/** "julho de 2026" -> "Julho de 2026" (capitalize only the month name). */
function capitalizeMonth(label: string): string {
  return label.charAt(0).toUpperCase() + label.slice(1)
}

interface MonthPickerProps {
  month: string
  onChange: (month: string) => void
}

export default function MonthPicker({ month, onChange }: MonthPickerProps) {
  const isCurrent = month === currentMonth()
  return (
    <div className="month-picker" role="group" aria-label="Selecionar mês">
      <button
        type="button"
        className="btn btn-ghost btn-icon"
        onClick={() => onChange(addMonths(month, -1))}
        aria-label="Mês anterior"
      >
        <ChevronLeft size={18} aria-hidden="true" />
      </button>
      <span className="month-picker-label">{capitalizeMonth(formatMonth(month))}</span>
      <button
        type="button"
        className="btn btn-ghost btn-icon"
        onClick={() => onChange(addMonths(month, 1))}
        aria-label="Próximo mês"
      >
        <ChevronRight size={18} aria-hidden="true" />
      </button>
      {!isCurrent && (
        <button
          type="button"
          className="btn btn-ghost month-picker-today"
          onClick={() => onChange(currentMonth())}
        >
          Mês atual
        </button>
      )}
    </div>
  )
}
