import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, renderHook, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import StatementImportsPage from './StatementImportsPage'
import ImportDetail from './ImportDetail'
import CategoryRuleManager from './CategoryRuleManager'
import { useConfirmImport } from './api'
import type { BatchDetail, CategoryRule, StatementItem } from './types'

/* ---------- fixtures ---------- */

const BASE_ITEM: StatementItem = {
  id: 11,
  sourceIndex: 1,
  externalId: 'FIT-1',
  sourceType: 'DEBIT',
  postedDate: '2026-06-05',
  amount: 25.9,
  type: 'EXPENSE',
  description: 'Padaria São João',
  memo: 'Compra no débito',
  originalDate: '2026-06-05',
  originalAmount: 25.9,
  originalType: 'EXPENSE',
  originalDescription: 'Padaria Sao Joao',
  suggestedCategoryId: 1,
  suggestedCategoryName: 'Alimentação',
  matchedRuleId: 5,
  matchedRulePattern: 'padaria',
  ruleConfidence: 'LOW',
  selectedCategoryId: 1,
  selectedCategoryName: 'Alimentação',
  included: true,
  duplicateStatus: 'UNIQUE',
  duplicateOverride: false,
  matchedTransaction: null,
  status: 'READY',
  validationCode: null,
  validationMessage: null,
  resultCode: null,
  resultMessage: null,
  transactionId: null,
  importedAt: null,
  undoneAt: null,
  importable: true,
}

const INCOME_ITEM: StatementItem = {
  ...BASE_ITEM,
  id: 12,
  sourceIndex: 2,
  externalId: 'FIT-2',
  type: 'INCOME',
  amount: 5200,
  description: 'Salário de junho',
  originalDescription: 'Salario de junho',
  suggestedCategoryId: null,
  suggestedCategoryName: null,
  matchedRuleId: null,
  matchedRulePattern: null,
  ruleConfidence: null,
  selectedCategoryId: null,
  selectedCategoryName: null,
}

const POSSIBLE_DUPLICATE_ITEM: StatementItem = {
  ...BASE_ITEM,
  id: 13,
  sourceIndex: 3,
  externalId: null,
  description: 'Estacionamento centro',
  duplicateStatus: 'POSSIBLE_DUPLICATE',
  importable: false,
  matchedTransaction: {
    id: 900,
    date: '2026-06-04',
    description: 'Estacionamento',
    amount: 8,
    type: 'EXPENSE',
    categoryName: 'Transporte',
  },
}

const EXACT_DUPLICATE_ITEM: StatementItem = {
  ...BASE_ITEM,
  id: 14,
  sourceIndex: 4,
  description: 'Mensalidade academia',
  duplicateStatus: 'EXACT_DUPLICATE',
  importable: false,
}

const INVALID_ITEM: StatementItem = {
  ...BASE_ITEM,
  id: 15,
  sourceIndex: 5,
  description: 'Linha quebrada',
  amount: null,
  status: 'INVALID',
  importable: false,
  validationCode: 'STATEMENT_AMOUNT_INVALID',
  validationMessage: 'Valor inválido na linha 5.',
}

const TOTALS: BatchDetail['totals'] = {
  totalRows: 5,
  readyCount: 3,
  invalidCount: 1,
  importedCount: 0,
  failedCount: 0,
  skippedCount: 0,
  undoneCount: 0,
  excludedCount: 0,
  includedPendingCount: 3,
  exactDuplicateCount: 1,
  possibleDuplicateCount: 1,
  withinFileDuplicateCount: 0,
  unmappedCategoryCount: 1,
  pendingIncomeTotal: 5200,
  pendingExpenseTotal: 25.9,
  pendingNetEffect: 5174.1,
}

const BATCH: BatchDetail = {
  id: 1,
  createdAt: '2026-07-19T10:00:00Z',
  accountId: 3,
  accountName: 'Conta Corrente',
  originalFilename: 'extrato.ofx',
  format: 'OFX',
  status: 'PREVIEW_READY',
  fileSha256: 'abc123',
  fileSizeBytes: 2048,
  sourceAccountHint: 'Banco 0260 · conta •••5-678',
  fileAlreadyImported: false,
  csvMapping: null,
  csvMappingSuggestion: null,
  csvRawPreview: null,
  confirmedAt: null,
  undoneAt: null,
  totals: TOTALS,
  items: [BASE_ITEM, INCOME_ITEM, POSSIBLE_DUPLICATE_ITEM, EXACT_DUPLICATE_ITEM, INVALID_ITEM],
}

const CSV_BATCH: BatchDetail = {
  ...BATCH,
  id: 2,
  originalFilename: 'extrato.csv',
  format: 'CSV',
  status: 'NEEDS_MAPPING',
  sourceAccountHint: null,
  csvMappingSuggestion: {
    encoding: 'UTF_8',
    delimiter: 'SEMICOLON',
    hasHeader: true,
    datePatterns: ['dd/MM/yyyy'],
  },
  csvRawPreview: [
    ['Data', 'Descrição', 'Débito', 'Crédito'],
    ['05/06/2026', 'Mercado Central', '120,50', ''],
    ['06/06/2026', 'Pix recebido', '', '80,00'],
  ],
  totals: { ...TOTALS, totalRows: 0, includedPendingCount: 0 },
  items: [],
}

const HISTORY = {
  content: [
    {
      id: 1,
      createdAt: '2026-07-19T10:00:00Z',
      accountId: 3,
      accountName: 'Conta Corrente',
      originalFilename: 'extrato.ofx',
      format: 'OFX',
      status: 'PREVIEW_READY',
      totalRows: 5,
      importedCount: 0,
      failedCount: 0,
      confirmedAt: null,
      undoneAt: null,
    },
    {
      id: 2,
      createdAt: '2026-07-18T10:00:00Z',
      accountId: 3,
      accountName: 'Conta Corrente',
      originalFilename: 'junho.csv',
      format: 'CSV',
      status: 'PARTIALLY_COMPLETED',
      totalRows: 10,
      importedCount: 8,
      failedCount: 2,
      confirmedAt: '2026-07-18T11:00:00Z',
      undoneAt: null,
    },
  ],
  page: 0,
  size: 20,
  totalElements: 2,
  totalPages: 1,
}

const RULES: CategoryRule[] = [
  {
    id: 5,
    active: true,
    transactionType: 'EXPENSE',
    accountId: null,
    accountName: null,
    matchField: 'DESCRIPTION',
    operation: 'CONTAINS',
    pattern: 'padaria',
    categoryId: 1,
    categoryName: 'Alimentação',
    priority: 100,
    matchCount: 4,
    lastUsedAt: null,
  },
]

const ACCOUNTS = [
  {
    id: 3,
    name: 'Conta Corrente',
    type: 'CHECKING',
    openingBalance: 1000,
    currentBalance: 1000,
    archived: false,
    displayOrder: 0,
  },
  {
    id: 4,
    name: 'Carteira',
    type: 'CASH',
    openingBalance: 50,
    currentBalance: 50,
    archived: false,
    displayOrder: 1,
  },
]

const CATEGORIES = [
  { id: 1, name: 'Alimentação', type: 'EXPENSE', active: true, isDefault: true },
  { id: 2, name: 'Transporte', type: 'EXPENSE', active: true, isDefault: true },
  { id: 9, name: 'Salário', type: 'INCOME', active: true, isDefault: true },
]

/* ---------- test harness ---------- */

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
        body: typeof init?.body === 'string' ? JSON.parse(init.body) : init?.body,
      })
      for (const route of routes) {
        if (route.method === method && route.match(url)) {
          return route.respond(init)
        }
      }
      if (method === 'GET' && url.includes('/statement-imports/1')) {
        return jsonResponse(BATCH)
      }
      if (method === 'GET' && url.includes('/statement-imports/2')) {
        return jsonResponse(CSV_BATCH)
      }
      if (method === 'GET' && url.includes('/statement-imports')) {
        return jsonResponse(HISTORY)
      }
      if (url.includes('/category-mapping-rules')) {
        return jsonResponse(RULES)
      }
      if (url.includes('/accounts')) {
        return jsonResponse(ACCOUNTS)
      }
      if (url.includes('/categories')) {
        const type = new URL(url, 'http://localhost').searchParams.get('type')
        return jsonResponse(
          type ? CATEGORIES.filter((category) => category.type === type) : CATEGORIES,
        )
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

/* ---------- history and upload ---------- */

describe('StatementImportsPage', () => {
  it('lists the import history with friendly status labels and counts', async () => {
    mockApi()
    renderWithProviders(<StatementImportsPage />)

    expect(await screen.findByText('extrato.ofx')).toBeInTheDocument()
    expect(screen.getByText('junho.csv')).toBeInTheDocument()
    expect(screen.getByText('Parcialmente concluída')).toBeInTheDocument()
    expect(screen.getByText(/2 com falha/)).toBeInTheDocument()
    expect(screen.queryByText('PARTIALLY_COMPLETED')).not.toBeInTheDocument()
  })

  it('opens the upload dialog with the privacy explanation and account limits', async () => {
    mockApi()
    renderWithProviders(<StatementImportsPage />)

    await userEvent.click(
      (await screen.findAllByRole('button', { name: /Importar extrato/ }))[0],
    )
    expect(screen.getByText(/não fica armazenado/)).toBeInTheDocument()
    expect(screen.getByText(/Tamanho máximo: 5 MB/)).toBeInTheDocument()

    // Only bank accounts are offered — the CASH wallet never appears.
    const accountSelect = screen.getByLabelText('Conta de destino')
    expect(within(accountSelect).getByText('Conta Corrente')).toBeInTheDocument()
    expect(within(accountSelect).queryByText('Carteira')).not.toBeInTheDocument()
  })

  it('rejects unsupported extensions and oversized files locally', async () => {
    mockApi()
    renderWithProviders(<StatementImportsPage />)
    await userEvent.click(
      (await screen.findAllByRole('button', { name: /Importar extrato/ }))[0],
    )

    const fileInput = screen.getByLabelText('Arquivo do extrato')
    await userEvent.upload(fileInput, new File(['x'], 'planilha.xlsx'), {
      applyAccept: false,
    })
    expect(
      await screen.findByText('Envie um arquivo CSV ou OFX exportado pelo seu banco.'),
    ).toBeInTheDocument()

    const huge = new File([new ArrayBuffer(5 * 1024 * 1024 + 1)], 'grande.csv')
    await userEvent.upload(fileInput, huge, { applyAccept: false })
    expect(await screen.findByText(/excede o limite/)).toBeInTheDocument()

    // The submit stays disabled without a valid file.
    expect(screen.getByRole('button', { name: 'Enviar extrato' })).toBeDisabled()
  })

  it('uploads as multipart and opens the created batch', async () => {
    const calls = mockApi([
      {
        method: 'POST',
        match: (url) => url.endsWith('/statement-imports'),
        respond: () => jsonResponse(BATCH, 201),
      },
    ])
    renderWithProviders(<StatementImportsPage />)
    await userEvent.click(
      (await screen.findAllByRole('button', { name: /Importar extrato/ }))[0],
    )

    await userEvent.selectOptions(screen.getByLabelText('Conta de destino'), '3')
    await userEvent.upload(
      screen.getByLabelText('Arquivo do extrato'),
      new File(['ofx'], 'extrato.ofx'),
    )
    await userEvent.click(screen.getByRole('button', { name: 'Enviar extrato' }))

    // The upload body is FormData (never JSON) and the workbench opens.
    await waitFor(() => {
      const upload = calls.find(
        (call) => call.method === 'POST' && call.url.endsWith('/statement-imports'),
      )
      expect(upload?.body).toBeInstanceOf(FormData)
    })
    expect(await screen.findByText('Confirmar importação')).toBeInTheDocument()
  })
})

/* ---------- CSV mapping ---------- */

describe('CSV mapping step', () => {
  it('shows the raw rows and maps debit/credit columns through the backend preview', async () => {
    const calls = mockApi([
      {
        method: 'PUT',
        match: (url) => url.includes('/statement-imports/2/csv-mapping'),
        respond: () =>
          jsonResponse({
            batchId: 2,
            sampleSize: 2,
            validCount: 2,
            invalidCount: 0,
            entries: [
              {
                sourceIndex: 1,
                postedDate: '2026-06-05',
                amount: 120.5,
                type: 'EXPENSE',
                description: 'Mercado Central',
                memo: null,
                externalId: null,
                validationCode: null,
                validationMessage: null,
              },
            ],
          }),
      },
      {
        method: 'POST',
        match: (url) => url.includes('/statement-imports/2/reparse'),
        respond: () => jsonResponse({ ...CSV_BATCH, status: 'PREVIEW_READY' }),
      },
    ])
    renderWithProviders(<ImportDetail batchId={2} onBack={() => undefined} />)

    // Raw preview helps identify each column.
    expect(await screen.findByText('Mercado Central')).toBeInTheDocument()
    expect(screen.getByText('Aguardando mapeamento')).toBeInTheDocument()

    // Debit/credit mode replaces the single amount column.
    await userEvent.click(
      screen.getByRole('radio', { name: /Colunas separadas de débito e crédito/ }),
    )
    await userEvent.selectOptions(screen.getByLabelText('Coluna de data'), '0')
    await userEvent.selectOptions(screen.getByLabelText('Coluna de descrição'), '1')
    await userEvent.selectOptions(screen.getByLabelText('Coluna de débito (saídas)'), '2')
    await userEvent.selectOptions(screen.getByLabelText('Coluna de crédito (entradas)'), '3')
    await userEvent.click(screen.getByRole('button', { name: 'Testar mapeamento' }))

    await waitFor(() => {
      const mapping = calls.find((call) => call.method === 'PUT')
      expect(mapping?.body).toMatchObject({
        delimiter: 'SEMICOLON',
        hasHeader: true,
        decimalSeparator: 'COMMA',
        dateColumn: 0,
        descriptionColumn: 1,
        amountColumn: null,
        debitColumn: 2,
        creditColumn: 3,
      })
    })
    expect(await screen.findByText(/2 de 2/)).toBeInTheDocument()

    // Only then the authoritative parse runs.
    await userEvent.click(screen.getByRole('button', { name: 'Processar arquivo' }))
    await waitFor(() =>
      expect(calls.some((call) => call.url.includes('/reparse'))).toBe(true),
    )
  })

  it('refuses to preview while mandatory columns are missing', async () => {
    const calls = mockApi()
    renderWithProviders(<ImportDetail batchId={2} onBack={() => undefined} />)

    await userEvent.click(
      await screen.findByRole('button', { name: 'Testar mapeamento' }),
    )
    expect(
      await screen.findByText('Escolha as colunas de data e descrição.'),
    ).toBeInTheDocument()
    expect(calls.some((call) => call.method === 'PUT')).toBe(false)
  })
})

/* ---------- preview, duplicates and categories ---------- */

describe('ImportDetail preview', () => {
  it('renders totals, validation reasons, duplicate badges and suggestions', async () => {
    mockApi()
    renderWithProviders(<ImportDetail batchId={1} onBack={() => undefined} />)

    expect(await screen.findByText('Lançamentos no arquivo')).toBeInTheDocument()
    // Backend totals, never recomputed locally (stats and confirm summary).
    expect(screen.getAllByText(/R\$\s*5\.174,10/).length).toBeGreaterThan(0)

    // The invalid reason is visible text, not a hidden tooltip.
    expect(screen.getByText('Valor inválido na linha 5.')).toBeInTheDocument()

    // Duplicates and rule suggestion surface with friendly labels.
    expect(screen.getByRole('button', { name: /Possível duplicata/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Duplicata exata/ })).toBeInTheDocument()
    expect(screen.getAllByText(/Sugerida pela regra “padaria”/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/confiança baixa/).length).toBeGreaterThan(0)
  })

  it('assigns a category through the row select', async () => {
    const calls = mockApi([
      {
        method: 'PATCH',
        match: (url) => url.includes('/items/12'),
        respond: () => jsonResponse({ ...INCOME_ITEM, selectedCategoryId: 9 }),
      },
    ])
    renderWithProviders(<ImportDetail batchId={1} onBack={() => undefined} />)

    const select = await screen.findByLabelText('Categoria de Salário de junho')
    // The income categories load asynchronously into the select.
    await within(select).findByRole('option', { name: 'Salário' })
    await userEvent.selectOptions(select, '9')
    await waitFor(() => {
      const patch = calls.find((call) => call.method === 'PATCH')
      expect(patch?.body).toEqual({ selectedCategoryId: 9 })
    })
  })

  it('excludes the visible rows in bulk', async () => {
    const calls = mockApi([
      {
        method: 'PATCH',
        match: (url) => url.includes('/items/'),
        respond: () => jsonResponse(BASE_ITEM),
      },
    ])
    renderWithProviders(<ImportDetail batchId={1} onBack={() => undefined} />)

    await userEvent.click(await screen.findByRole('button', { name: 'Excluir visíveis' }))
    await waitFor(() => {
      const patches = calls.filter((call) => call.method === 'PATCH')
      expect(patches.length).toBeGreaterThan(0)
      expect(patches.every((call) => JSON.stringify(call.body) === '{"included":false}')).toBe(
        true,
      )
    })
  })

  it('reviews a possible duplicate side by side and imports it only on explicit override', async () => {
    const calls = mockApi([
      {
        method: 'PATCH',
        match: (url) => url.includes('/items/13'),
        respond: () => jsonResponse({ ...POSSIBLE_DUPLICATE_ITEM, duplicateOverride: true }),
      },
    ])
    renderWithProviders(<ImportDetail batchId={1} onBack={() => undefined} />)

    await userEvent.click(await screen.findByRole('button', { name: /Possível duplicata/ }))

    // Both sides visible for comparison, scoped to the dialog.
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getByText('Lançamento do extrato', { exact: false })).toBeInTheDocument()
    expect(dialog.getByText('Transação existente no Finora')).toBeInTheDocument()
    expect(dialog.getByText('Estacionamento')).toBeInTheDocument()
    expect(dialog.getByText('Transporte')).toBeInTheDocument()

    await userEvent.click(dialog.getByRole('button', { name: 'Importar mesmo assim' }))
    await waitFor(() => {
      const patch = calls.find((call) => call.method === 'PATCH')
      expect(patch?.body).toEqual({ duplicateOverride: true, included: true })
    })
  })

  it('never offers an override for exact duplicates', async () => {
    mockApi()
    renderWithProviders(<ImportDetail batchId={1} onBack={() => undefined} />)

    await userEvent.click(await screen.findByRole('button', { name: /Duplicata exata/ }))
    expect(
      screen.getByText(/não pode ser importado de novo/),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Importar mesmo assim' }),
    ).not.toBeInTheDocument()
  })

  it('shows original parsed values in the editor and saves a correction as a rule', async () => {
    const calls = mockApi([
      {
        method: 'PATCH',
        match: (url) => url.includes('/items/11'),
        respond: () => jsonResponse(BASE_ITEM),
      },
      {
        method: 'POST',
        match: (url) => url.includes('/category-mapping-rules'),
        respond: () => jsonResponse(RULES[0], 201),
      },
    ])
    renderWithProviders(<ImportDetail batchId={1} onBack={() => undefined} />)

    await userEvent.click(await screen.findByRole('button', { name: 'Editar Padaria São João' }))
    expect(screen.getByText(/Valores lidos do arquivo/)).toBeInTheDocument()
    expect(screen.getByText(/Padaria Sao Joao/)).toBeInTheDocument()

    await userEvent.click(
      screen.getByRole('checkbox', { name: /Criar uma regra para categorizar/ }),
    )
    await userEvent.click(screen.getByRole('button', { name: 'Salvar alterações' }))

    await waitFor(() => {
      const rule = calls.find(
        (call) => call.method === 'POST' && call.url.includes('/category-mapping-rules'),
      )
      expect(rule?.body).toMatchObject({
        transactionType: 'EXPENSE',
        operation: 'CONTAINS',
        pattern: 'Padaria São João',
        categoryId: 1,
      })
    })
  })
})

/* ---------- confirmation, results and undo ---------- */

describe('Confirmation and undo', () => {
  it('blocks confirmation while included rows lack categories', async () => {
    mockApi()
    renderWithProviders(<ImportDetail batchId={1} onBack={() => undefined} />)

    expect(await screen.findByText(/ainda está sem categoria/)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /Importar 3 lançamentos/ }),
    ).toBeDisabled()
  })

  it('confirms explicitly and shows one structured result per item, including failures', async () => {
    const mappedBatch: BatchDetail = {
      ...BATCH,
      totals: { ...TOTALS, unmappedCategoryCount: 0 },
      items: BATCH.items.map((item) =>
        item.id === 12 ? { ...item, selectedCategoryId: 9, selectedCategoryName: 'Salário' } : item,
      ),
    }
    const calls = mockApi([
      {
        method: 'GET',
        match: (url) => url.includes('/statement-imports/1'),
        respond: () => jsonResponse(mappedBatch),
      },
      {
        method: 'POST',
        match: (url) => url.includes('/confirm'),
        respond: () =>
          jsonResponse({
            batchId: 1,
            batchStatus: 'PARTIALLY_COMPLETED',
            results: [
              {
                itemId: 11,
                result: 'SUCCESS',
                transactionId: 501,
                code: 'STATEMENT_IMPORTED',
                message: 'Lançamento importado.',
              },
              {
                itemId: 12,
                result: 'FAILED',
                transactionId: null,
                code: 'STATEMENT_ITEM_FAILED',
                message: 'Não foi possível importar este lançamento.',
              },
            ],
            totals: mappedBatch.totals,
          }),
      },
    ])
    renderWithProviders(<ImportDetail batchId={1} onBack={() => undefined} />)

    const confirmButton = await screen.findByRole('button', {
      name: /Importar 3 lançamentos \(cria transações reais\)/,
    })
    expect(confirmButton).toBeEnabled()
    await userEvent.click(confirmButton)
    // The dialog restates that real transactions will be created.
    await userEvent.click(screen.getByRole('button', { name: 'Criar transações' }))

    await waitFor(() =>
      expect(calls.some((call) => call.url.includes('/confirm'))).toBe(true),
    )
    expect(await screen.findByText('Resultado da operação')).toBeInTheDocument()
    expect(screen.getByText('Lançamento importado.')).toBeInTheDocument()
    expect(screen.getByText('Não foi possível importar este lançamento.')).toBeInTheDocument()
    expect(screen.getByText(/1 lançamento falhou/)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Tentar novamente os lançamentos com falha' }),
    ).toBeInTheDocument()
  })

  it('invalidates every financial aggregate after a confirmation settles', async () => {
    mockApi([
      {
        method: 'POST',
        match: (url) => url.includes('/confirm'),
        respond: () =>
          jsonResponse({ batchId: 1, batchStatus: 'COMPLETED', results: [], totals: TOTALS }),
      },
    ])
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => useConfirmImport(), { wrapper })

    await result.current.mutateAsync({ batchId: 1 })
    await waitFor(() => {
      const keys = invalidate.mock.calls.map((call) => String(call[0]?.queryKey))
      for (const key of [
        'statement-imports',
        'transactions',
        'accounts',
        'budgets',
        'dashboard',
        'insights',
        'forecast',
      ]) {
        expect(keys).toContain(key)
      }
    })
  })

  it('requires explicit confirmation to undo and explains the financial effect', async () => {
    const importedBatch: BatchDetail = {
      ...BATCH,
      status: 'COMPLETED',
      totals: { ...TOTALS, importedCount: 2, includedPendingCount: 0, unmappedCategoryCount: 0 },
      items: [
        { ...BASE_ITEM, status: 'IMPORTED', transactionId: 501, importable: false },
        { ...INCOME_ITEM, status: 'IMPORTED', transactionId: 502, importable: false },
      ],
    }
    const calls = mockApi([
      {
        method: 'GET',
        match: (url) => url.includes('/statement-imports/1'),
        respond: () => jsonResponse(importedBatch),
      },
      {
        method: 'POST',
        match: (url) => url.includes('/undo'),
        respond: () =>
          jsonResponse({
            batchId: 1,
            batchStatus: 'UNDONE',
            results: [
              {
                itemId: 11,
                result: 'UNDONE',
                transactionId: null,
                code: 'STATEMENT_UNDONE',
                message: 'Importação desfeita.',
              },
              {
                itemId: 12,
                result: 'BLOCKED',
                transactionId: 502,
                code: 'STATEMENT_UNDO_BLOCKED',
                message: 'Esta transação está vinculada a outra área do Finora.',
              },
            ],
            totals: importedBatch.totals,
          }),
      },
    ])
    renderWithProviders(<ImportDetail batchId={1} onBack={() => undefined} />)

    await userEvent.click(
      await screen.findByRole('button', { name: 'Desfazer importação' }),
    )
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getByText(/efeito financeiro desaparece/)).toBeInTheDocument()
    await userEvent.click(dialog.getByRole('button', { name: 'Desfazer importação' }))

    await waitFor(() =>
      expect(calls.some((call) => call.url.includes('/undo'))).toBe(true),
    )
    // Blocked items stay visible with their reason.
    expect(await screen.findByText('Importação desfeita.')).toBeInTheDocument()
    expect(
      screen.getByText('Esta transação está vinculada a outra área do Finora.'),
    ).toBeInTheDocument()

    // Per-item undo is available on imported rows.
    expect(
      screen.getByRole('button', { name: 'Desfazer importação de Padaria São João' }),
    ).toBeInTheDocument()
  })
})

/* ---------- category rules ---------- */

describe('CategoryRuleManager', () => {
  it('lists rules and toggles activation with a full update', async () => {
    const calls = mockApi([
      {
        method: 'PUT',
        match: (url) => url.includes('/category-mapping-rules/5'),
        respond: () => jsonResponse({ ...RULES[0], active: false }),
      },
    ])
    renderWithProviders(<CategoryRuleManager onClose={() => undefined} />)

    expect(await screen.findByText(/Descrição contém “padaria”/)).toBeInTheDocument()
    expect(screen.getByText(/determinística/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('checkbox', { name: /Regra “padaria” ativa/ }))
    await waitFor(() => {
      const update = calls.find((call) => call.method === 'PUT')
      expect(update?.body).toMatchObject({ active: false, pattern: 'padaria', categoryId: 1 })
    })
  })

  it('validates the rule form before creating', async () => {
    const calls = mockApi()
    renderWithProviders(<CategoryRuleManager onClose={() => undefined} />)

    await userEvent.click(await screen.findByRole('button', { name: 'Nova regra' }))
    await userEvent.click(screen.getByRole('button', { name: 'Criar regra' }))
    expect(
      await screen.findByText('Informe o texto da regra e a categoria.'),
    ).toBeInTheDocument()
    expect(calls.some((call) => call.method === 'POST')).toBe(false)
  })
})
