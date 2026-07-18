import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, renderHook, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import LegacyConversionsPage from './LegacyConversionsPage'
import LegacyConversionDetail from './LegacyConversionDetail'
import LegacyBatchConversion from './LegacyBatchConversion'
import LegacyCardMappingDialog from '../commitments/LegacyCardMappingDialog'
import { useConvertLegacy } from './api'
import { INVENTORY_STATE_LABELS, type ConversionInventoryItem } from './types'
import type { Commitment } from '../commitments/types'

/* ---------- API doubles ---------- */

const ELIGIBLE_ITEM: ConversionInventoryItem = {
  transactionId: 101,
  description: 'Compra antiga no crédito',
  amount: 100,
  date: '2025-11-20',
  category: { id: 1, name: 'Compras' },
  accountName: null,
  state: 'ELIGIBLE',
  stateReasonCode: null,
  stateMessage: null,
  conversionId: null,
  generatedCardPurchaseId: null,
  cardId: null,
}

const CONVERTED_ITEM: ConversionInventoryItem = {
  ...ELIGIBLE_ITEM,
  transactionId: 102,
  description: 'Notebook antigo',
  amount: 300,
  state: 'CONVERTED',
  conversionId: 7,
  generatedCardPurchaseId: 55,
  cardId: 20,
}

const INVENTORY = {
  summary: { eligibleCount: 2, convertedCount: 1, reversedCount: 1, pendingAmount: 420 },
  page: { content: [ELIGIBLE_ITEM, CONVERTED_ITEM], page: 0, size: 20, totalElements: 22, totalPages: 2 },
}

const CARDS = [
  {
    id: 20,
    name: 'Cartão Roxo',
    archived: false,
    limit: { creditLimit: 10000, usedLimit: 0, availableLimit: 10000, utilizationPercent: 0 },
  },
]

const PREVIEW = {
  source: {
    transactionId: 101,
    description: 'Compra antiga no crédito',
    amount: 100,
    date: '2025-11-20',
    category: { id: 1, name: 'Compras' },
    accountName: null,
    affectsAccountBalance: false,
  },
  card: { cardId: 20, name: 'Cartão Roxo', closingDay: 10, dueDay: 17, archived: false },
  totalAmount: 100,
  installmentCount: 3,
  firstInvoiceMonth: '2025-12',
  installments: [
    { sequenceNumber: 1, totalInstallments: 3, amount: 33.33, invoiceMonth: '2025-12', closingDate: '2025-12-10', dueDate: '2025-12-17', invoiceExists: false, invoiceStatus: null, invoiceAmountPaid: null },
    { sequenceNumber: 2, totalInstallments: 3, amount: 33.33, invoiceMonth: '2026-01', closingDate: '2026-01-10', dueDate: '2026-01-17', invoiceExists: false, invoiceStatus: null, invoiceAmountPaid: null },
    { sequenceNumber: 3, totalInstallments: 3, amount: 33.34, invoiceMonth: '2026-02', closingDate: '2026-02-10', dueDate: '2026-02-17', invoiceExists: false, invoiceStatus: null, invoiceAmountPaid: null },
  ],
  limit: { creditLimit: 10000, availableBefore: 10000, availableAfter: 9900, sufficient: true },
  monthlyExpenseShift: [
    { month: '2025-11', delta: -100 },
    { month: '2025-12', delta: 33.33 },
  ],
  cashFlow: {
    sourceAffectsAccountBalance: false,
    removesSourceCashEffect: false,
    invoicePaymentAccountAssigned: false,
    explanation: 'O caixa só se move quando a fatura for paga.',
  },
  forecastExplanation: 'A previsão passa a considerar as faturas geradas.',
  warnings: [{ code: 'MONTHLY_REDISTRIBUTION', message: 'A despesa muda de mês.' }],
  blockers: [],
  convertible: true,
}

const CONVERSION = {
  id: 7,
  sourceTransactionId: 102,
  sourceDescription: 'Notebook antigo',
  amount: 300,
  originalTransactionDate: '2025-11-20',
  cardPurchaseId: 55,
  cardId: 20,
  cardName: 'Cartão Roxo',
  effectivePurchaseDate: '2025-11-20',
  installmentCount: 1,
  firstInvoiceMonth: '2025-12',
  status: 'ACTIVE',
  convertedAt: '2026-07-01T12:00:00Z',
  reversedAt: null,
  reversalReason: null,
  reversible: true,
  reversalBlockedCode: null,
  reversalBlockedMessage: null,
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

type Route = {
  method: string
  match: (url: string) => boolean
  respond: (init?: RequestInit) => Response
}

/** Routes evaluated in order; the first method+url match wins. */
function mockApi(routes: Route[] = []) {
  const calls: Array<{ method: string; url: string; body: unknown }> = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = (init?.method ?? 'GET').toUpperCase()
      calls.push({
        method,
        url,
        body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      })
      for (const route of routes) {
        if (route.method === method && route.match(url)) {
          return route.respond(init)
        }
      }
      if (url.includes('/legacy-conversions?') || url.endsWith('/legacy-conversions')) {
        return jsonResponse(INVENTORY)
      }
      if (url.includes('/credit-cards')) {
        return jsonResponse(CARDS)
      }
      if (url.includes('/categories')) {
        return jsonResponse([{ id: 1, name: 'Compras', type: 'EXPENSE', active: true, isDefault: true }])
      }
      return jsonResponse([])
    }),
  )
  return calls
}

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
  return queryClient
}

afterEach(() => {
  vi.unstubAllGlobals()
})

/* ---------- inventory ---------- */

describe('LegacyConversionsPage', () => {
  it('renders summary values, rows, friendly state labels and pagination', async () => {
    const calls = mockApi()
    renderWithProviders(<LegacyConversionsPage />)

    expect(await screen.findByText('Elegíveis para conversão')).toBeInTheDocument()
    expect(screen.getByText('Valor histórico pendente')).toBeInTheDocument()
    expect(screen.getByText(/420,00/)).toBeInTheDocument()

    // Rows show friendly labels, never raw enum names.
    const table = screen.getByRole('table')
    expect(screen.getByText('Compra antiga no crédito')).toBeInTheDocument()
    expect(within(table).getByText(INVENTORY_STATE_LABELS.ELIGIBLE)).toBeInTheDocument()
    expect(within(table).getByText(INVENTORY_STATE_LABELS.CONVERTED)).toBeInTheDocument()
    expect(screen.queryByText('ELIGIBLE')).not.toBeInTheDocument()

    // Pagination reflects the server page and requests the next one.
    expect(screen.getByText('Página 1 de 2')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Próxima' }))
    await waitFor(() =>
      expect(calls.some((call) => call.url.includes('page=1'))).toBe(true),
    )
  })

  it('applies the conversion-state filter as a query parameter', async () => {
    const calls = mockApi()
    renderWithProviders(<LegacyConversionsPage />)
    await screen.findByText('Compra antiga no crédito')

    await userEvent.selectOptions(
      screen.getByLabelText('Filtrar por estado da conversão'),
      'CONVERTED',
    )
    await waitFor(() =>
      expect(calls.some((call) => call.url.includes('state=CONVERTED'))).toBe(true),
    )
  })

  it('selects only convertible rows and offers the batch action', async () => {
    mockApi()
    renderWithProviders(<LegacyConversionsPage />)
    await screen.findByText('Compra antiga no crédito')

    // The converted row cannot be selected.
    expect(screen.getByLabelText('Selecionar Notebook antigo')).toBeDisabled()

    await userEvent.click(screen.getByLabelText('Selecionar Compra antiga no crédito'))
    expect(screen.getByText('1 transação selecionada')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /Converter selecionadas/ }),
    ).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Limpar seleção' }))
    expect(screen.queryByText('1 transação selecionada')).not.toBeInTheDocument()
  })
})

/* ---------- wizard ---------- */

describe('LegacyConversionWizard', () => {
  async function openWizardAtPreview(routes: Route[] = []) {
    const calls = mockApi([
      {
        method: 'POST',
        match: (url) => url.endsWith('/legacy-conversions/preview'),
        respond: () => jsonResponse(PREVIEW),
      },
      ...routes,
    ])
    renderWithProviders(<LegacyConversionsPage />)
    await screen.findByText('Compra antiga no crédito')
    await userEvent.click(screen.getByRole('button', { name: 'Converter' }))

    // Step 1: immutable source facts.
    expect(await screen.findByText('Valor histórico')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Avançar' }))

    // Step 2: parameters.
    await userEvent.selectOptions(
      await screen.findByLabelText('Cartão que receberá a compra'),
      '20',
    )
    const installmentsInput = screen.getByLabelText('Número de parcelas')
    await userEvent.clear(installmentsInput)
    await userEvent.type(installmentsInput, '3')
    await userEvent.click(screen.getByRole('button', { name: 'Avançar' }))
    return calls
  }

  it('shows the backend schedule exactly, with cent-exact amounts', async () => {
    await openWizardAtPreview()

    // Step 3: the deterministic schedule straight from the preview.
    expect(await screen.findByText('1/3')).toBeInTheDocument()
    expect(screen.getAllByText(/33,33/).length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText(/33,34/)).toBeInTheDocument()
    expect(screen.getByText('A despesa muda de mês.')).toBeInTheDocument()

    // Step 4: financial impact from the same preview.
    await userEvent.click(screen.getByRole('button', { name: 'Avançar' }))
    expect(await screen.findByText('Limite disponível antes')).toBeInTheDocument()
    expect(screen.getByText('O caixa só se move quando a fatura for paga.')).toBeInTheDocument()
    expect(
      screen.getByText('A previsão passa a considerar as faturas geradas.'),
    ).toBeInTheDocument()
  })

  it('converts with the backend-confirmed first invoice and refreshes the app', async () => {
    const calls = await openWizardAtPreview([
      {
        method: 'POST',
        match: (url) => url.endsWith('/legacy-conversions'),
        respond: () => jsonResponse(CONVERSION, 201),
      },
      {
        method: 'GET',
        match: (url) => url.endsWith('/legacy-conversions/7'),
        respond: () => jsonResponse(CONVERSION),
      },
    ])
    await screen.findByText('1/3')
    await userEvent.click(screen.getByRole('button', { name: 'Avançar' }))
    await screen.findByText('Limite disponível antes')
    await userEvent.click(screen.getByRole('button', { name: 'Avançar' }))

    const confirm = await screen.findByRole('button', {
      name: 'Confirmar e criar compra real no cartão',
    })
    await userEvent.click(confirm)

    await waitFor(() => {
      const convertCall = calls.find(
        (call) => call.method === 'POST' && call.url.endsWith('/legacy-conversions'),
      )
      expect(convertCall).toBeDefined()
      expect(convertCall!.body).toMatchObject({
        transactionId: 101,
        cardId: 20,
        installmentCount: 3,
        firstInvoiceMonth: '2025-12',
      })
    })
    // Success hands off to the conversion detail.
    expect(await screen.findByText('Detalhe da conversão')).toBeInTheDocument()
  })

  it('keeps the wizard open with the backend reason when conversion fails', async () => {
    await openWizardAtPreview([
      {
        method: 'POST',
        match: (url) => url.endsWith('/legacy-conversions'),
        respond: () =>
          jsonResponse(
            {
              title: 'Regra de negócio',
              detail: 'O limite disponível do cartão é insuficiente.',
              code: 'INSUFFICIENT_CARD_LIMIT',
            },
            422,
          ),
      },
    ])
    await screen.findByText('1/3')
    await userEvent.click(screen.getByRole('button', { name: 'Avançar' }))
    await screen.findByText('Limite disponível antes')
    await userEvent.click(screen.getByRole('button', { name: 'Avançar' }))
    await userEvent.click(
      await screen.findByRole('button', { name: 'Confirmar e criar compra real no cartão' }),
    )

    expect(
      await screen.findByText('O limite disponível do cartão é insuficiente.'),
    ).toBeInTheDocument()
    // Still on the confirmation step — nothing was lost and no success shown.
    expect(
      screen.getByRole('button', { name: 'Confirmar e criar compra real no cartão' }),
    ).toBeInTheDocument()
    expect(screen.queryByText('Detalhe da conversão')).not.toBeInTheDocument()
  })

  it('blocks confirmation while the preview reports blockers', async () => {
    mockApi([
      {
        method: 'POST',
        match: (url) => url.endsWith('/legacy-conversions/preview'),
        respond: () =>
          jsonResponse({
            ...PREVIEW,
            convertible: false,
            blockers: [
              { code: 'INSUFFICIENT_CARD_LIMIT', message: 'Limite insuficiente no cartão.' },
            ],
          }),
      },
    ])
    renderWithProviders(<LegacyConversionsPage />)
    await screen.findByText('Compra antiga no crédito')
    await userEvent.click(screen.getByRole('button', { name: 'Converter' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Avançar' }))
    await userEvent.selectOptions(
      await screen.findByLabelText('Cartão que receberá a compra'),
      '20',
    )
    await userEvent.click(screen.getByRole('button', { name: 'Avançar' }))

    expect(await screen.findByText('Limite insuficiente no cartão.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Avançar' }))
    await screen.findByText('Limite disponível antes')
    await userEvent.click(screen.getByRole('button', { name: 'Avançar' }))
    expect(
      await screen.findByRole('button', { name: 'Confirmar e criar compra real no cartão' }),
    ).toBeDisabled()
  })
})

/* ---------- invalidation ---------- */

describe('conversion invalidation', () => {
  it('refreshes every affected aggregate after a conversion', async () => {
    mockApi([
      {
        method: 'POST',
        match: (url) => url.endsWith('/legacy-conversions'),
        respond: () => jsonResponse(CONVERSION, 201),
      },
    ])
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidated: string[] = []
    vi.spyOn(queryClient, 'invalidateQueries').mockImplementation(async (filters) => {
      invalidated.push(String(((filters?.queryKey ?? []) as unknown[])[0]))
    })

    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => useConvertLegacy(), { wrapper })
    result.current.mutate({
      transactionId: 101,
      cardId: 20,
      effectivePurchaseDate: '2025-11-20',
      installmentCount: 1,
      firstInvoiceMonth: '2025-12',
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    for (const key of [
      'legacy-conversions',
      'transactions',
      'credit-cards',
      'budgets',
      'dashboard',
      'insights',
      'forecast',
      'accounts',
    ]) {
      expect(invalidated).toContain(key)
    }
  })
})

/* ---------- detail and reversal ---------- */

describe('LegacyConversionDetail', () => {
  it('renders the audit record and reverses after explicit confirmation', async () => {
    const calls = mockApi([
      {
        method: 'GET',
        match: (url) => url.endsWith('/legacy-conversions/7'),
        respond: () => jsonResponse(CONVERSION),
      },
      {
        method: 'POST',
        match: (url) => url.endsWith('/legacy-conversions/7/reverse'),
        respond: () =>
          jsonResponse({
            ...CONVERSION,
            status: 'REVERSED',
            reversedAt: '2026-07-02T12:00:00Z',
            reversalReason: 'Cartão errado',
            reversible: false,
          }),
      },
    ])
    renderWithProviders(<LegacyConversionDetail conversionId={7} onClose={vi.fn()} />)

    expect(await screen.findByText('Notebook antigo (20/11/2025)')).toBeInTheDocument()
    expect(screen.getByText('Ativa')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Cartão Roxo' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Estornar conversão' }))
    // The destructive call only happens after the second, explicit step.
    expect(
      calls.find((call) => call.url.endsWith('/legacy-conversions/7/reverse')),
    ).toBeUndefined()
    await userEvent.type(screen.getByLabelText('Motivo (opcional)'), 'Cartão errado')
    await userEvent.click(screen.getByRole('button', { name: 'Estornar conversão' }))

    await waitFor(() => {
      const reverseCall = calls.find((call) =>
        call.url.endsWith('/legacy-conversions/7/reverse'),
      )
      expect(reverseCall).toBeDefined()
      expect(reverseCall!.body).toEqual({ reason: 'Cartão errado' })
    })
  })

  it('disables reversal and explains the settlement block', async () => {
    mockApi([
      {
        method: 'GET',
        match: (url) => url.endsWith('/legacy-conversions/7'),
        respond: () =>
          jsonResponse({
            ...CONVERSION,
            reversible: false,
            reversalBlockedCode: 'CONVERSION_SETTLED',
            reversalBlockedMessage: 'Uma fatura da compra gerada já recebeu pagamento.',
          }),
      },
    ])
    renderWithProviders(<LegacyConversionDetail conversionId={7} onClose={vi.fn()} />)

    expect(
      await screen.findByText(/Uma fatura da compra gerada já recebeu pagamento\./),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Estornar conversão' })).toBeDisabled()
  })
})

/* ---------- batch ---------- */

describe('LegacyBatchConversion', () => {
  it('previews per item and reports mixed results without hiding successes', async () => {
    const second: ConversionInventoryItem = {
      ...ELIGIBLE_ITEM,
      transactionId: 104,
      description: 'Geladeira antiga',
      amount: 900,
    }
    mockApi([
      {
        method: 'POST',
        match: (url) => url.endsWith('/legacy-conversions/preview'),
        respond: () => jsonResponse(PREVIEW),
      },
      {
        method: 'POST',
        match: (url) => url.endsWith('/legacy-conversions/batch'),
        respond: () =>
          jsonResponse({
            total: 2,
            succeeded: 1,
            alreadyConverted: 0,
            failed: 1,
            skipped: 0,
            results: [
              { transactionId: 101, status: 'SUCCESS', conversionId: 9, generatedCardPurchaseId: 77, errorCode: null, message: 'Conversão concluída.' },
              { transactionId: 104, status: 'FAILED', conversionId: null, generatedCardPurchaseId: null, errorCode: 'INSUFFICIENT_CARD_LIMIT', message: 'Limite insuficiente para esta compra.' },
            ],
          }),
      },
    ])
    renderWithProviders(
      <LegacyBatchConversion
        sources={[ELIGIBLE_ITEM, second]}
        onClose={vi.fn()}
        onFinished={vi.fn()}
      />,
    )

    // Each item is configured independently.
    await screen.findAllByRole('option', { name: /Cartão Roxo/ })
    const cardSelects = screen.getAllByLabelText('Cartão')
    expect(cardSelects).toHaveLength(2)
    await userEvent.selectOptions(cardSelects[0], '20')
    await userEvent.selectOptions(cardSelects[1], '20')

    await userEvent.click(screen.getByRole('button', { name: 'Calcular faturas' }))
    const firstInvoices = await screen.findAllByText(/Primeira fatura: dezembro de 2025/i)
    expect(firstInvoices).toHaveLength(2)

    await userEvent.click(screen.getByRole('button', { name: 'Converter 2 transações' }))

    // One result per item, in friendly labels; the failure hides nothing.
    expect(await screen.findByText('Convertida')).toBeInTheDocument()
    expect(screen.getByText('Falhou')).toBeInTheDocument()
    expect(screen.getByText('Limite insuficiente para esta compra.')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Tentar novamente 1 falha' }),
    ).toBeInTheDocument()
  })
})

/* ---------- recurring mapping ---------- */

describe('LegacyCardMappingDialog', () => {
  const LEGACY_COMMITMENT = {
    id: 5,
    description: 'Streaming antigo',
    amount: 39.9,
    category: { id: 1, name: 'Assinaturas', type: 'EXPENSE' },
    cadence: 'MONTHLY',
    dueDay: 8,
    startDate: '2025-01-08',
    endDate: null,
    active: true,
    paymentMethod: 'CREDIT',
    nextDueDate: '2026-08-08',
    executionMode: 'MANUAL',
    targetKind: 'PROJECTION_ONLY',
    accountId: null,
    accountName: null,
    creditCardId: null,
    creditCardName: null,
    installmentCount: 1,
    legacyProjectionOnly: true,
    failedOccurrences: 0,
  } as Commitment

  it('maps to a real card and discloses that history is never backfilled', async () => {
    const calls = mockApi([
      {
        method: 'POST',
        match: (url) => url.endsWith('/commitments/5/legacy-card-mapping'),
        respond: () =>
          jsonResponse({
            ...LEGACY_COMMITMENT,
            targetKind: 'CREDIT_CARD_PURCHASE',
            creditCardId: 20,
            creditCardName: 'Cartão Roxo',
            legacyProjectionOnly: false,
          }),
      },
    ])
    const onClose = vi.fn()
    renderWithProviders(
      <LegacyCardMappingDialog commitment={LEGACY_COMMITMENT} onClose={onClose} />,
    )

    // The no-backfill promise is stated before confirming.
    expect(screen.getByText(/Sem retroativos/)).toBeInTheDocument()
    expect(screen.getByText(/nada é criado para o passado/)).toBeInTheDocument()

    await screen.findByRole('option', { name: /Cartão Roxo/ })
    await userEvent.selectOptions(screen.getByLabelText('Cartão de destino'), '20')
    await userEvent.selectOptions(screen.getByLabelText('Modo de execução'), 'AUTOMATIC')
    await userEvent.click(screen.getByRole('button', { name: 'Migrar para o cartão' }))

    await waitFor(() => {
      const call = calls.find((entry) =>
        entry.url.endsWith('/commitments/5/legacy-card-mapping'),
      )
      expect(call).toBeDefined()
      expect(call!.body).toEqual({
        creditCardId: 20,
        installmentCount: 1,
        executionMode: 'AUTOMATIC',
      })
    })
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })
})
