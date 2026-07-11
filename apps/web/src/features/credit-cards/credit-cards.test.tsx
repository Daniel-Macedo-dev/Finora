import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { previewInstallments } from './allocation'
import CardUtilization from './CardUtilization'
import InstallmentPreview from './InstallmentPreview'
import InvoiceStatusBadge from './InvoiceStatusBadge'

describe('previewInstallments', () => {
  it('splits cents deterministically with the remainder on the last installments', () => {
    expect(previewInstallments(100, 3)).toEqual([33.33, 33.33, 33.34])
    expect(previewInstallments(100.01, 3)).toEqual([33.33, 33.34, 33.34])
  })

  it('sums exactly to the total for many shapes', () => {
    for (const [total, count] of [
      [1200, 12],
      [999.99, 7],
      [0.05, 5],
      [123456.78, 48],
    ] as const) {
      const parts = previewInstallments(total, count)!
      expect(parts).toHaveLength(count)
      const cents = parts.reduce((sum, part) => sum + Math.round(part * 100), 0)
      expect(cents).toBe(Math.round(total * 100))
    }
  })

  it('rejects impossible splits', () => {
    expect(previewInstallments(0, 1)).toBeNull()
    expect(previewInstallments(0.01, 2)).toBeNull()
    expect(previewInstallments(10, 0)).toBeNull()
  })
})

describe('InstallmentPreview', () => {
  it('shows the uniform split as a single line', () => {
    render(<InstallmentPreview total={1200} count={12} />)
    expect(screen.getByText(/12× de/)).toBeInTheDocument()
    expect(screen.getByText('R$ 100,00')).toBeInTheDocument()
  })

  it('lists uneven installments individually', () => {
    render(<InstallmentPreview total={100} count={3} />)
    expect(screen.getAllByText('R$ 33,33')).toHaveLength(2)
    expect(screen.getByText('R$ 33,34')).toBeInTheDocument()
  })

  it('renders nothing without a valid amount', () => {
    const { container } = render(<InstallmentPreview total={null} count={3} />)
    expect(container).toBeEmptyDOMElement()
  })
})

describe('InvoiceStatusBadge', () => {
  it('labels every status in pt-BR, never color alone', () => {
    render(<InvoiceStatusBadge status="OVERDUE" />)
    expect(screen.getByText('Vencida')).toBeInTheDocument()
  })

  it('marks a paid invoice as positive', () => {
    render(<InvoiceStatusBadge status="PAID" />)
    expect(screen.getByText('Paga')).toHaveClass('badge-positive')
  })
})

describe('CardUtilization', () => {
  it('exposes utilization through the meter role', () => {
    render(
      <CardUtilization
        limit={{
          creditLimit: 1000,
          usedLimit: 850,
          availableLimit: 150,
          utilizationPercent: 85,
        }}
      />,
    )
    const meter = screen.getByRole('meter', { name: 'Utilização do limite' })
    expect(meter).toHaveAttribute('aria-valuenow', '85')
    expect(screen.getByText('R$ 150,00')).toBeInTheDocument()
  })
})
