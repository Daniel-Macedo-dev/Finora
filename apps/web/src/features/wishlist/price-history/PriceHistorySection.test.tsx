import '@testing-library/jest-dom/vitest'
import { render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { PriceHistorySummary } from '../types'
import { PriceSummary } from './PriceHistorySection'
import { createRequestId } from './requestId'

vi.mock('recharts', () => ({
  CartesianGrid: () => null, Line: () => null, LineChart: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  ResponsiveContainer: ({ children }: { children: ReactNode }) => <div>{children}</div>, Tooltip: () => null,
  XAxis: () => null, YAxis: () => null,
}))

const base: PriceHistorySummary = {
  observationCount: 3, seriesCount: 1, firstObservedOn: '2026-06-01', lastObservedOn: '2026-07-01',
  bestCurrentOptionCost: 1700, latestObservedBestCost: 1500, previousComparableCost: 1600,
  absoluteChange: -100, percentageChange: -6.25, historicalMinimum: 1450,
  historicalMaximum: 1800, historicalAverage: 1583.33, targetPrice: 1550,
  distanceToTarget: -50, distanceToTargetPercentage: -3.23, targetReached: true,
  latestMerchant: 'Loja', latestPaymentKind: 'CASH', latestSeriesKey: 'MANUAL:loja:CASH',
  daysSinceLatestObservation: 2,
}

describe('PriceSummary', () => {
  it('renders backend aggregates and target reached text', () => {
    render(<PriceSummary data={base} />)
    expect(screen.getByText('R$ 1.500,00')).toBeInTheDocument()
    expect(screen.getByText('R$ 1.450,00')).toBeInTheDocument()
    expect(screen.getByText('Preço alvo atingido')).toBeInTheDocument()
    expect(screen.getByText(/Preço diminuiu 6,25%/)).toBeInTheDocument()
  })

  it('describes an increase and a target not reached without relying on color', () => {
    render(<PriceSummary data={{ ...base, percentageChange: 5, targetReached: false,
      distanceToTarget: 100 }} />)
    expect(screen.getByText(/Preço aumentou 5%/)).toBeInTheDocument()
    expect(screen.getByText(/Preço alvo ainda não atingido/)).toBeInTheDocument()
  })

  it('renders unchanged and no-target states deterministically', () => {
    const { rerender } = render(<PriceSummary data={{ ...base, percentageChange: 0 }} />)
    expect(screen.getByText(/Preço sem alteração 0%/)).toBeInTheDocument()
    rerender(<PriceSummary data={{ ...base, targetReached: null, targetPrice: null,
      distanceToTarget: null, distanceToTargetPercentage: null }} />)
    expect(screen.queryByText(/Preço alvo/)).not.toBeInTheDocument()
  })
})

describe('createRequestId', () => {
  it('creates valid unique UUIDs for logical submissions', () => {
    const first = createRequestId(); const second = createRequestId()
    expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(second).not.toBe(first)
  })
})
