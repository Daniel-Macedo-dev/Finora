import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import AnalysisPanel from './AnalysisPanel'
import type {
  AvailablePurchaseAnalysis,
  UnavailablePurchaseAnalysis,
} from './types'

const AVAILABLE: AvailablePurchaseAnalysis = {
  availability: 'AVAILABLE',
  itemId: 7,
  itemName: 'Notebook',
  baseCurrency: 'BRL',
  itemCurrency: 'BRL',
  assumptions: {
    availableCash: 10000,
    minimumCashBuffer: 1000,
    monthlyOpportunityRate: 0,
    maxInstallmentCommitmentRatio: 0.3,
    avgMonthlyIncome: 5000,
    avgMonthlyExpense: 3000,
    avgMonthlySurplus: 2000,
    monthlyCommitments: 500,
    cardOutstandingTotal: 0,
    nextMonthCardInstallments: 0,
    historyMonthsUsed: 3,
  },
  options: [
    {
      optionId: 1,
      merchant: 'Loja A',
      kind: 'CASH',
      nominalCost: 4800,
      presentValue: 4800,
      upfrontCost: 4800,
      monthlyBurden: null,
      installmentCount: null,
      cashAfterPurchase: 5200,
      card: null,
      safe: true,
      issues: [],
    },
  ],
  recommendation: {
    type: 'BUY_CASH',
    recommendedOptionId: 1,
    reasonCodes: ['LOWEST_PRESENT_VALUE'],
    explanation: 'Comprar à vista em Loja A mantém o caixa acima da reserva mínima.',
    warnings: [],
    requiredAdditionalCash: null,
    estimatedMonthsToAfford: null,
  },
}

const UNAVAILABLE: UnavailablePurchaseAnalysis = {
  availability: 'EXCHANGE_RATE_REQUIRED',
  itemId: 9,
  itemName: 'Camera',
  baseCurrency: 'BRL',
  itemCurrency: 'USD',
  missingCurrencies: ['USD', 'EUR'],
  unavailableReasons: [
    {
      code: 'ITEM_CURRENCY_DIFFERS_FROM_BASE',
      message: 'Camera está em USD e sua análise financeira é feita em BRL.',
    },
    {
      code: 'AVAILABLE_CASH_INCOMPLETE',
      message: 'Parte do seu saldo disponível está em EUR.',
    },
  ],
}

describe('AnalysisPanel — available', () => {
  it('keeps the existing recommendation presentation', () => {
    render(<AnalysisPanel analysis={AVAILABLE} />)
    expect(screen.getByText('Comprar à vista')).toBeInTheDocument()
    expect(screen.getByText(/mantém o caixa acima da reserva mínima/)).toBeInTheDocument()
  })

  it('formats the assumptions in the analysis currency', () => {
    render(<AnalysisPanel analysis={AVAILABLE} />)
    expect(screen.getByText('R$ 10.000,00')).toBeInTheDocument()
    expect(screen.getByText('R$ 1.000,00')).toBeInTheDocument()
  })

  it('formats a non-BRL analysis in its own currency', () => {
    render(
      <AnalysisPanel
        analysis={{ ...AVAILABLE, baseCurrency: 'USD', itemCurrency: 'USD' }}
      />,
    )
    // US$ rather than R$: the whole analysis is denominated in the base.
    expect(screen.getByText('US$ 10.000,00')).toBeInTheDocument()
    expect(screen.queryByText('R$ 10.000,00')).not.toBeInTheDocument()
  })
})

describe('AnalysisPanel — exchange rate required', () => {
  it('explains why the analysis cannot be produced', () => {
    render(<AnalysisPanel analysis={UNAVAILABLE} />)
    expect(screen.getByText(/Análise financeira indisponível/)).toBeInTheDocument()
    expect(
      screen.getByText(/precisa comparar valores em moedas diferentes/),
    ).toBeInTheDocument()
  })

  it('names both currencies unambiguously', () => {
    render(<AnalysisPanel analysis={UNAVAILABLE} />)
    expect(screen.getByText('USD — Dólar americano')).toBeInTheDocument()
    expect(screen.getByText('BRL — Real brasileiro')).toBeInTheDocument()
  })

  it('lists the missing currencies in the order the server sent', () => {
    render(<AnalysisPanel analysis={UNAVAILABLE} />)
    expect(screen.getByText('USD, EUR')).toBeInTheDocument()
  })

  it('shows every blocking reason', () => {
    render(<AnalysisPanel analysis={UNAVAILABLE} />)
    expect(screen.getByText(/Camera está em USD/)).toBeInTheDocument()
    expect(screen.getByText(/saldo disponível está em EUR/)).toBeInTheDocument()
  })

  it.each([['Comprar à vista'], ['Comprar parcelado'], ['Aguardar'], ['Sem opções']])(
    'never shows the %s verdict',
    (label) => {
      render(<AnalysisPanel analysis={UNAVAILABLE} />)
      expect(screen.queryByText(label)).not.toBeInTheDocument()
    },
  )

  it('shows no safe or unsafe badge', () => {
    render(<AnalysisPanel analysis={UNAVAILABLE} />)
    expect(screen.queryByText(/Segura/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Arriscada/)).not.toBeInTheDocument()
  })

  it('shows no assumption figures at all', () => {
    render(<AnalysisPanel analysis={UNAVAILABLE} />)
    expect(screen.queryByText(/Caixa disponível/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Reserva mínima/)).not.toBeInTheDocument()
  })

  it('never renders an unavailable value as zero', () => {
    render(<AnalysisPanel analysis={UNAVAILABLE} />)
    expect(screen.queryByText('R$ 0,00')).not.toBeInTheDocument()
    expect(screen.queryByText('US$ 0,00')).not.toBeInTheDocument()
  })

  it('says the options and history are still available', () => {
    render(<AnalysisPanel analysis={UNAVAILABLE} />)
    expect(
      screen.getByText(/opções de compra e o histórico de preços continuam disponíveis/),
    ).toBeInTheDocument()
  })

  it('is described as a limitation, not an application error', () => {
    render(<AnalysisPanel analysis={UNAVAILABLE} />)
    // No alert role: nothing failed, and repeatedly interrupting a screen
    // reader for a stable explanation would be noise.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(
      screen.getByRole('region', { name: 'Análise indisponível' }),
    ).toBeInTheDocument()
  })

  it('keeps JPY free of invented decimals', () => {
    render(
      <AnalysisPanel
        analysis={{ ...UNAVAILABLE, itemCurrency: 'JPY', missingCurrencies: ['JPY'] }}
      />,
    )
    expect(screen.getByText('JPY — Iene japonês')).toBeInTheDocument()
  })
})
