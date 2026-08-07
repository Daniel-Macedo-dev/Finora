import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import InsightsPanel from './InsightsPanel'
import type { AggregateCoverage, Insight, InsightsData } from './types'

const COMPLETE: AggregateCoverage = {
  complete: true,
  missingCurrencies: [],
  unavailableRules: [],
}

function insight(overrides: Partial<Insight> = {}): Insight {
  return {
    type: 'EXPENSE_INCREASE',
    severity: 'WARNING',
    title: 'Gastos subiram em relação ao mês anterior',
    message: 'As despesas do mês estão 100% acima do mês anterior.',
    amount: 1000,
    currency: 'BRL',
    ...overrides,
  }
}

function data(overrides: Partial<InsightsData> = {}): InsightsData {
  return {
    month: '2026-08',
    baseCurrency: 'BRL',
    insights: [insight()],
    aggregateCoverage: COMPLETE,
    ...overrides,
  }
}

describe('InsightsPanel — denominação', () => {
  it('mantém a apresentação de um insight em moeda base', () => {
    render(<InsightsPanel data={data()} />)
    expect(screen.getByText('Gastos subiram em relação ao mês anterior')).toBeInTheDocument()
    expect(screen.getByText('Atenção')).toBeInTheDocument()
    expect(screen.getByText('R$ 1.000,00')).toBeInTheDocument()
  })

  it('mostra um valor nativo em dólares como dólares', () => {
    render(
      <InsightsPanel
        data={data({
          insights: [
            insight({ type: 'INVOICE_OVERDUE', severity: 'CRITICAL', amount: 1250.5, currency: 'USD' }),
          ],
        })}
      />,
    )
    expect(screen.getByText('US$ 1.250,50')).toBeInTheDocument()
    expect(screen.queryByText('R$ 1.250,50')).not.toBeInTheDocument()
  })

  it('mostra um valor nativo em euros como euros', () => {
    render(
      <InsightsPanel
        data={data({
          insights: [insight({ type: 'INVOICE_DUE_SOON', amount: 320, currency: 'EUR' })],
        })}
      />,
    )
    expect(screen.getByText(/€/)).toBeInTheDocument()
  })

  it('não inventa centavos em ienes', () => {
    render(
      <InsightsPanel
        data={data({
          insights: [insight({ type: 'CARD_UTILIZATION_HIGH', amount: 90000, currency: 'JPY' })],
        })}
      />,
    )
    expect(screen.getByText('JP¥ 90.000')).toBeInTheDocument()
    expect(screen.queryByText(/90\.000,00/)).not.toBeInTheDocument()
  })

  it('não transforma um valor ausente em zero', () => {
    render(
      <InsightsPanel data={data({ insights: [insight({ amount: null, currency: null })] })} />,
    )
    expect(screen.getByText('Gastos subiram em relação ao mês anterior')).toBeInTheDocument()
    expect(screen.queryByText('R$ 0,00')).not.toBeInTheDocument()
    expect(screen.queryByText(/^R\$/)).not.toBeInTheDocument()
  })
})

describe('InsightsPanel — cobertura', () => {
  const MIXED: InsightsData = data({
    insights: [
      insight({
        type: 'INVOICE_OVERDUE',
        severity: 'CRITICAL',
        title: 'Fatura vencida: Cartão USD',
        message: 'A fatura de Cartão USD venceu em 01/08/2026 com US$ 900,00 em aberto.',
        amount: 900,
        currency: 'USD',
      }),
    ],
    aggregateCoverage: {
      complete: false,
      missingCurrencies: ['USD', 'EUR'],
      unavailableRules: ['EXPENSE_INCREASE', 'CATEGORY_DOMINANT', 'WISHLIST_AFFORDABLE'],
    },
  })

  it('explica a limitação uma única vez, não uma por regra', () => {
    render(<InsightsPanel data={MIXED} />)
    expect(screen.getAllByText(/Algumas análises consolidadas ficaram de fora/)).toHaveLength(1)
  })

  it('descreve os grupos afetados sem repetir a mesma frase', () => {
    render(<InsightsPanel data={MIXED} />)
    // EXPENSE_INCREASE and CATEGORY_DOMINANT share one phrase; it appears once.
    expect(
      screen.getByText(
        /Ficaram de fora: a comparação de gastos do mês, a viabilidade das compras planejadas\./,
      ),
    ).toBeInTheDocument()
  })

  it('nunca mostra os códigos internos das regras', () => {
    const { container } = render(<InsightsPanel data={MIXED} />)
    for (const code of ['EXPENSE_INCREASE', 'CATEGORY_DOMINANT', 'WISHLIST_AFFORDABLE']) {
      expect(container.textContent).not.toContain(code)
    }
  })

  it('lista as moedas na ordem determinística que o servidor enviou', () => {
    render(<InsightsPanel data={MIXED} />)
    expect(
      screen.getByText(/USD — Dólar americano; EUR — Euro/),
    ).toBeInTheDocument()
  })

  it('mantém o insight nativo visível ao lado da limitação', () => {
    render(<InsightsPanel data={MIXED} />)
    expect(screen.getByText('Fatura vencida: Cartão USD')).toBeInTheDocument()
    expect(screen.getByText('US$ 900,00')).toBeInTheDocument()
  })

  it('não transforma uma regra suprimida em um card vazio', () => {
    render(<InsightsPanel data={MIXED} />)
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
  })

  it('não é um alerta: nada falhou', () => {
    render(<InsightsPanel data={MIXED} />)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('não mostra aviso nenhum quando não houve limitação de moeda', () => {
    render(<InsightsPanel data={data({ insights: [] })} />)
    expect(screen.queryByText(/análises consolidadas ficaram de fora/)).not.toBeInTheDocument()
    expect(screen.getByText(/Nada digno de nota por enquanto/)).toBeInTheDocument()
  })
})
