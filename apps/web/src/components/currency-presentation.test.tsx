import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import CurrencyStat from './CurrencyStat'
import CurrencyTotal from './CurrencyTotal'
import { emptyTotals, type CurrencyTotals } from '../lib/money'

function totals(overrides: Partial<CurrencyTotals>): CurrencyTotals {
  return { ...emptyTotals('BRL'), ...overrides }
}

const MIXED = totals({
  byCurrency: [
    { amount: 8000, currency: 'BRL' },
    { amount: 1200, currency: 'USD' },
  ],
  homogeneous: false,
  homogeneousCurrency: null,
  nativeTotal: null,
  baseComplete: false,
  baseTotal: null,
  unconvertedCurrencies: ['USD'],
})

const FOREIGN_ONLY = totals({
  byCurrency: [{ amount: 1500, currency: 'USD' }],
  homogeneous: true,
  homogeneousCurrency: 'USD',
  nativeTotal: 1500,
  baseComplete: false,
  baseTotal: null,
  unconvertedCurrencies: ['USD'],
})

describe('CurrencyStat', () => {
  it('shows one figure when everything is already in the base currency', () => {
    render(
      <CurrencyStat
        totals={totals({
          byCurrency: [{ amount: 8000, currency: 'BRL' }],
          nativeTotal: 8000,
          baseTotal: 8000,
        })}
      />,
    )
    expect(screen.getByText('R$ 8.000,00')).toBeInTheDocument()
  })

  it('labels a homogeneous foreign total as foreign instead of as the base', () => {
    render(<CurrencyStat totals={FOREIGN_ONLY} />)
    expect(screen.getByText('US$ 1.500,00')).toBeInTheDocument()
    expect(screen.getByText(/Valor em USD, não em BRL/)).toBeInTheDocument()
  })

  it('lists each currency and refuses a consolidated figure when mixed', () => {
    render(<CurrencyStat totals={MIXED} />)
    expect(screen.getByText('R$ 8.000,00')).toBeInTheDocument()
    expect(screen.getByText('US$ 1.200,00')).toBeInTheDocument()
    // Crucially: no 9200, and the absence is explained rather than shown as 0.
    expect(screen.queryByText(/9\.200/)).not.toBeInTheDocument()
    expect(screen.getByRole('note')).toHaveTextContent(/Sem total consolidado/)
    expect(screen.getByRole('note')).toHaveTextContent(/USD/)
  })

  it('never renders an unavailable total as zero', () => {
    render(<CurrencyStat totals={MIXED} />)
    expect(screen.queryByText('R$ 0,00')).not.toBeInTheDocument()
  })

  it('keeps USD and CAD distinguishable rather than collapsing to a bare dollar', () => {
    render(
      <CurrencyStat
        totals={totals({
          byCurrency: [
            { amount: 100, currency: 'USD' },
            { amount: 100, currency: 'CAD' },
          ],
          homogeneous: false,
          homogeneousCurrency: null,
          nativeTotal: null,
          baseComplete: false,
          baseTotal: null,
          unconvertedCurrencies: ['USD', 'CAD'],
        })}
      />,
    )
    const usd = screen.getByText(/US\$\s?100,00/)
    const cad = screen.getByText(/CA\$\s?100,00/)
    expect(usd).not.toBe(cad)
  })

  it('renders JPY without decimals', () => {
    render(
      <CurrencyStat
        totals={totals({
          baseCurrency: 'JPY',
          byCurrency: [{ amount: 1500, currency: 'JPY' }],
          homogeneousCurrency: 'JPY',
          nativeTotal: 1500,
          baseTotal: 1500,
        })}
      />,
    )
    expect(screen.getByText(/1\.500/)).toBeInTheDocument()
    expect(screen.queryByText(/1\.500,00/)).not.toBeInTheDocument()
  })
})

describe('CurrencyTotal', () => {
  it('states the total plainly when nothing needs converting', () => {
    render(
      <CurrencyTotal
        label="Total projetado"
        totals={totals({
          byCurrency: [{ amount: 250, currency: 'BRL' }],
          nativeTotal: 250,
          baseTotal: 250,
        })}
      />,
    )
    expect(screen.getByText(/Total projetado: R\$ 250,00/)).toBeInTheDocument()
  })

  it('explains why a mixed set has no consolidated total', () => {
    render(<CurrencyTotal label="Total projetado" totals={MIXED} />)
    expect(screen.getByRole('note')).toHaveTextContent(/exige cotações/)
    expect(screen.getByText('BRL')).toBeInTheDocument()
    expect(screen.getByText('USD')).toBeInTheDocument()
  })

  it('does not present a foreign native total as a base-currency total', () => {
    render(<CurrencyTotal label="Total projetado" totals={FOREIGN_ONLY} />)
    expect(screen.getByRole('note')).toHaveTextContent(/Valor em USD, não em BRL/)
  })
})
