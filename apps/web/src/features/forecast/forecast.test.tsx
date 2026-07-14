import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import ForecastPage from './ForecastPage'
import type { Forecast } from './types'

const BASE_FORECAST: Forecast = {
  from: '2026-07-14',
  to: '2026-10-12',
  accountId: null,
  openingBalance: 1500,
  projectedIncome: 3000,
  projectedAccountExpenses: 1200,
  projectedInvoiceOutflows: 800,
  closingBalance: 2500,
  lowestBalance: 700,
  lowestBalanceDate: '2026-08-05',
  firstNegativeDate: null,
  unassignedInflows: 0,
  unassignedOutflows: 0,
  events: [
    {
      date: '2026-08-01',
      description: 'Salário',
      amount: 3000,
      source: 'RECURRING_ACCOUNT_OCCURRENCE',
      accountId: 10,
      accountName: 'Conta Principal',
      unassigned: false,
      commitmentId: 5,
      transactionId: null,
      invoiceId: null,
      creditCardId: null,
      balanceAfter: 4500,
    },
    {
      date: '2026-08-10',
      description: 'Fatura Cartão Roxo',
      amount: -800,
      source: 'CARD_INVOICE',
      accountId: 10,
      accountName: 'Conta Principal',
      unassigned: false,
      commitmentId: null,
      transactionId: null,
      invoiceId: 33,
      creditCardId: 20,
      balanceAfter: 3700,
    },
  ],
  months: [
    { month: '2026-08', inflows: 3000, outflows: 2000, net: 1000, endBalance: 2500 },
  ],
}

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

function renderForecast(forecast: Forecast) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/forecast')) {
        return jsonResponse(forecast)
      }
      if (url.includes('/accounts')) {
        return jsonResponse([])
      }
      return jsonResponse([])
    }),
  )
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ForecastPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ForecastPage', () => {
  it('shows the KPI cards with opening, lowest and projected flows', async () => {
    renderForecast(BASE_FORECAST)
    expect(await screen.findByText('Saldo hoje')).toBeInTheDocument()
    expect(screen.getByText('Menor saldo projetado')).toBeInTheDocument()
    expect(screen.getByText('Entradas × saídas projetadas')).toBeInTheDocument()
    // Sum of account expenses (1200) and invoice outflows (800)
    expect(screen.getByText(/R\$\s*3\.000,00\s*·\s*R\$\s*2\.000,00/)).toBeInTheDocument()
  })

  it('does not warn when the balance never goes negative', async () => {
    renderForecast(BASE_FORECAST)
    await screen.findByText('Saldo hoje')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('warns about the first projected negative-balance date', async () => {
    renderForecast({
      ...BASE_FORECAST,
      lowestBalance: -350,
      firstNegativeDate: '2026-09-02',
    })
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/negativo em/i)
    expect(alert).toHaveTextContent('02/09/2026')
  })

  it('discloses unassigned flows separately from the balance', async () => {
    renderForecast({
      ...BASE_FORECAST,
      unassignedInflows: 500,
      unassignedOutflows: 120,
    })
    await screen.findByText('Saldo hoje')
    const status = screen.getByText(/fluxos sem conta definida/i).closest('[role="status"]')
    expect(status).not.toBeNull()
    expect(status).toHaveTextContent(/500,00/)
    expect(status).toHaveTextContent(/120,00/)
  })

  it('labels each event with its deterministic source', async () => {
    renderForecast(BASE_FORECAST)
    expect(await screen.findByText(/Recorrente projetado/)).toBeInTheDocument()
    expect(screen.getByText(/Fatura de cartão(?! no vencimento)/)).toBeInTheDocument()
  })

  it('links invoice events to the invoice detail page', async () => {
    renderForecast(BASE_FORECAST)
    const link = await screen.findByRole('link', { name: 'Fatura Cartão Roxo' })
    expect(link).toHaveAttribute('href', '/credit-cards/20/invoices/33')
  })

  it('shows an empty state when there are no projected events', async () => {
    renderForecast({
      ...BASE_FORECAST,
      events: [],
      months: [],
      closingBalance: BASE_FORECAST.openingBalance,
      lowestBalance: BASE_FORECAST.openingBalance,
    })
    expect(await screen.findByText('Nenhum evento no horizonte')).toBeInTheDocument()
  })
})
