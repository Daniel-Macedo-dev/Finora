import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import Money from './Money'
import MonthPicker from './MonthPicker'
import FormField from './FormField'
import { ErrorState } from './states'
import { ApiError } from '../lib/api'

describe('Money', () => {
  it('shows formatted currency with sign coloring', () => {
    render(<Money value={-150.5} signed />)
    const element = screen.getByText('-R$ 150,50')
    expect(element).toHaveClass('money-negative')
  })

  it('renders a dash for unavailable values', () => {
    render(<Money value={null} />)
    expect(screen.getByText('—')).toBeInTheDocument()
  })
})

describe('MonthPicker', () => {
  it('navigates to the previous and next month', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<MonthPicker month="2026-07" onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: 'Mês anterior' }))
    expect(onChange).toHaveBeenCalledWith('2026-06')

    await user.click(screen.getByRole('button', { name: 'Próximo mês' }))
    expect(onChange).toHaveBeenCalledWith('2026-08')
  })

  it('shows the readable month label', () => {
    render(<MonthPicker month="2026-07" onChange={() => {}} />)
    expect(screen.getByText('Julho de 2026')).toBeInTheDocument()
  })
})

describe('FormField', () => {
  it('associates label and error message with the control', () => {
    render(
      <FormField label="Valor" error="Informe um valor maior que zero.">
        <input className="input" />
      </FormField>,
    )
    const input = screen.getByLabelText('Valor')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    const error = screen.getByRole('alert')
    expect(error).toHaveTextContent('Informe um valor maior que zero.')
    expect(input.getAttribute('aria-describedby')).toBe(error.id)
  })
})

describe('ErrorState', () => {
  it('shows the API error message and retries', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    render(
      <ErrorState
        error={new ApiError(500, 'Erro', 'Falha temporária no servidor.')}
        onRetry={onRetry}
      />,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('Falha temporária no servidor.')
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})
