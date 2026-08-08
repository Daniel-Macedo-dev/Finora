import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import ImportDetail from './ImportDetail'
import type {
  BatchDetail,
  StatementCurrencyContext,
  StatementCurrencySource,
  StatementItem,
} from './types'
import type { CurrencyCode } from '../../lib/money'

/**
 * Currency presentation of the statement-import workbench.
 *
 * <p>Two properties matter throughout: an amount is never rendered in a
 * currency the server did not name, and the four currency sources say
 * genuinely different things — in particular a pre-V16 import must not be
 * described as a file that declared nothing, because that is unknown.
 */

/* ---------- fixtures ---------- */

function currencyContext(
  accountCurrency: CurrencyCode,
  currencySource: StatementCurrencySource,
  declaredCurrency: CurrencyCode | null = null,
  currencyAcknowledgementRequired = false,
): StatementCurrencyContext {
  return {
    accountCurrency,
    currencySource,
    declaredCurrency,
    effectiveCurrency: accountCurrency,
    valuesAreConverted: false,
    currencyAcknowledgementRequired,
  }
}

function item(overrides: Partial<StatementItem> = {}): StatementItem {
  return {
    id: 11,
    sourceIndex: 1,
    externalId: 'FIT-1',
    sourceType: 'DEBIT',
    postedDate: '2026-06-05',
    amount: 25.9,
    currency: 'BRL',
    type: 'EXPENSE',
    description: 'Assinatura mensal',
    memo: null,
    originalDate: '2026-06-05',
    originalAmount: 25.9,
    originalType: 'EXPENSE',
    originalDescription: 'Assinatura mensal',
    suggestedCategoryId: null,
    suggestedCategoryName: null,
    matchedRuleId: null,
    matchedRulePattern: null,
    ruleConfidence: null,
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
    ...overrides,
  }
}

function batch(
  currency: StatementCurrencyContext,
  overrides: Partial<BatchDetail> = {},
): BatchDetail {
  const code = currency.accountCurrency
  const items = overrides.items ?? [item({ currency: code })]
  return {
    id: 1,
    createdAt: '2026-07-19T10:00:00Z',
    accountId: 3,
    accountName: 'Conta internacional',
    currency,
    originalFilename: 'extrato.ofx',
    format: 'OFX',
    status: 'PREVIEW_READY',
    fileSha256: 'abc123',
    fileSizeBytes: 2048,
    sourceAccountHint: null,
    fileAlreadyImported: false,
    csvMapping: null,
    csvMappingSuggestion: null,
    csvRawPreview: null,
    confirmedAt: null,
    undoneAt: null,
    ...overrides,
    items,
    totals: {
      currency: code,
      totalRows: items.length,
      readyCount: items.length,
      invalidCount: 0,
      importedCount: 0,
      failedCount: 0,
      skippedCount: 0,
      undoneCount: 0,
      excludedCount: 0,
      includedPendingCount: items.filter((entry) => entry.importable).length,
      exactDuplicateCount: 0,
      possibleDuplicateCount: 0,
      withinFileDuplicateCount: 0,
      unmappedCategoryCount: 0,
      pendingIncomeTotal: 0,
      pendingExpenseTotal: 25.9,
      pendingNetEffect: -25.9,
      ...overrides.totals,
    },
  }
}

const ACCOUNTS = [
  {
    id: 3,
    name: 'Conta internacional',
    type: 'CHECKING',
    openingBalance: 400,
    currentBalance: 400,
    currency: 'USD',
    archived: false,
    displayOrder: 0,
  },
  {
    id: 4,
    name: 'Conta corrente',
    type: 'CHECKING',
    openingBalance: 1000,
    currentBalance: 1000,
    currency: 'BRL',
    archived: false,
    displayOrder: 1,
  },
]

const CATEGORIES = [
  { id: 1, name: 'Alimentação', type: 'EXPENSE', active: true, isDefault: true },
  { id: 9, name: 'Salário', type: 'INCOME', active: true, isDefault: true },
]

/* ---------- harness ---------- */

interface Call {
  method: string
  url: string
  body: unknown
}

/**
 * Text matcher for money. Intl output carries a locale-chosen minus sign and
 * non-breaking spaces, and neither is what a test is asserting about, so both
 * are normalized away before comparing.
 */
function money(expected: string) {
  const normalize = (value: string) =>
    value.replace(/[−‒-―]/g, '-').replace(/[   ]/g, ' ').trim()
  const wanted = normalize(expected)
  return (_content: string, element: Element | null) =>
    element !== null
    && element.children.length === 0
    && normalize(element.textContent ?? '') === wanted
}

/**
 * The rendered amount of one preview row, normalized.
 *
 * Scoped to the row so a stat card showing the same figure cannot answer for
 * it, and normalized because Intl output carries a locale-chosen minus sign and
 * non-breaking spaces that no test here is asserting about.
 */
async function rowAmount(description: string): Promise<string> {
  const row = await screen.findByRole('row', { name: new RegExp(description) })
  return normalizeMoney(within(row).getAllByRole('cell')[3].textContent ?? '')
}

function normalizeMoney(value: string): string {
  return value
    .replace(/[−‒-―]/g, '-')
    .replace(/[   ]/g, ' ')
    .trim()
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function mockApi(detail: BatchDetail, confirmStatus = 200): Call[] {
  const calls: Call[] = []
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
      if (url.includes('/confirm')) {
        return confirmStatus === 200
          ? jsonResponse({
              batchId: detail.id,
              batchStatus: 'COMPLETED',
              results: [],
              totals: detail.totals,
            })
          : jsonResponse(
              {
                code: 'STATEMENT_CURRENCY_ACK_REQUIRED',
                detail: 'Confirme a moeda antes de importar.',
              },
              422,
            )
      }
      if (method === 'PATCH' && /\/statement-imports\/\d+$/.test(url)) {
        return jsonResponse(
          {
            code: 'STATEMENT_CURRENCY_MISMATCH',
            detail:
              'O arquivo declara USD — Dólar americano e a conta escolhida usa '
              + 'BRL — Real brasileiro. Os valores não são convertidos.',
          },
          422,
        )
      }
      if (url.includes('/statement-imports/')) {
        return jsonResponse(detail)
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

function renderDetail(detail: BatchDetail, confirmStatus = 200): Call[] {
  const calls = mockApi(detail, confirmStatus)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ImportDetail batchId={detail.id} onBack={() => {}} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return calls
}

afterEach(() => {
  vi.unstubAllGlobals()
})

/* ---------- CSV: the account is the contract ---------- */

describe('CSV currency presentation', () => {
  it('states the destination currency and that nothing is converted', async () => {
    renderDetail(
      batch(currencyContext('USD', 'ACCOUNT'), {
        format: 'CSV',
        originalFilename: 'extrato.csv',
        items: [item({ currency: 'USD' })],
      }),
    )

    const notice = await screen.findByRole('region', { name: 'Moeda da importação' })
    expect(notice).toHaveTextContent(/em USD — Dólar americano/)
    expect(notice).toHaveTextContent(/moeda da conta selecionada/)
    expect(notice).toHaveTextContent(/Nenhuma conversão será realizada/)
    // The account is the contract, so nothing is asked of the user.
    expect(screen.queryByLabelText(/Confirmo que os valores/)).not.toBeInTheDocument()
  })

  it('keeps a BRL batch reading as reais', async () => {
    renderDetail(
      batch(currencyContext('BRL', 'ACCOUNT'), {
        format: 'CSV',
        items: [item({ currency: 'BRL' })],
      }),
    )
    const notice = await screen.findByRole('region', { name: 'Moeda da importação' })
    expect(notice).toHaveTextContent(/BRL — Real brasileiro/)
    expect(await rowAmount('Assinatura mensal')).toBe('-R$ 25,90')
  })
})

/* ---------- amounts are never rendered in a currency nobody named ---------- */

describe('amount denomination', () => {
  it('renders a USD batch as dollars, never as reais', async () => {
    renderDetail(
      batch(currencyContext('USD', 'FILE', 'USD'), { items: [item({ currency: 'USD' })] }),
    )

    expect(await rowAmount('Assinatura mensal')).toBe('-US$ 25,90')
    expect(screen.queryByText(money('-R$ 25,90'))).not.toBeInTheDocument()
    // The column header names the denomination once for the whole table.
    expect(screen.getByRole('columnheader', { name: 'Valor (USD)' })).toBeInTheDocument()
  })

  it.each([
    ['CAD', 'CA$'],
    ['AUD', 'AU$'],
  ] as const)('keeps %s distinguishable from other dollars', async (code, symbol) => {
    renderDetail(
      batch(currencyContext(code, 'FILE', code), { items: [item({ currency: code })] }),
    )
    const amount = await rowAmount('Assinatura mensal')
    // A bare "$" would not say whose dollars these are.
    expect(amount).toBe(`-${symbol} 25,90`)
    expect(amount).not.toMatch(/(^|\s)\$/)
    expect(screen.getByRole('columnheader', { name: `Valor (${code})` })).toBeInTheDocument()
  })

  it('renders yen without decimals', async () => {
    renderDetail(
      batch(currencyContext('JPY', 'FILE', 'JPY'), {
        items: [item({ currency: 'JPY', amount: 1200, originalAmount: 1200 })],
        totals: { pendingExpenseTotal: 1200, pendingNetEffect: -1200 } as never,
      }),
    )

    // 1200 yen, not "1.200,00" — those centavos do not exist.
    expect(await rowAmount('Assinatura mensal')).toBe('-JP¥ 1.200')
    expect(screen.queryByText(/1\.200,00/)).not.toBeInTheDocument()
  })

  it('shows an absent amount as a dash rather than zero', async () => {
    renderDetail(
      batch(currencyContext('USD', 'FILE', 'USD'), {
        items: [
          item({
            currency: 'USD',
            amount: null,
            originalAmount: null,
            status: 'INVALID',
            importable: false,
            validationCode: 'STATEMENT_ROW_MISSING_AMOUNT',
            validationMessage: 'O lançamento não possui valor.',
          }),
        ],
      }),
    )

    await screen.findByText('O lançamento não possui valor.')
    const row = screen.getByRole('row', { name: /Assinatura mensal/ })
    expect(within(row).getByText('—')).toBeInTheDocument()
    expect(within(row).queryByText(/0,00/)).not.toBeInTheDocument()
  })

  it('denominates the duplicate review against the matched transaction currency', async () => {
    renderDetail(
      batch(currencyContext('USD', 'FILE', 'USD'), {
        items: [
          item({
            currency: 'USD',
            duplicateStatus: 'POSSIBLE_DUPLICATE',
            importable: false,
            matchedTransaction: {
              id: 900,
              date: '2026-06-04',
              description: 'Assinatura',
              amount: 25.9,
              currency: 'USD',
              type: 'EXPENSE',
              categoryName: 'Alimentação',
            },
          }),
        ],
      }),
    )

    await userEvent.click(await screen.findByRole('button', { name: /Possível duplicata/ }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getAllByText(money('-US$ 25,90'))).toHaveLength(2)
    expect(within(dialog).queryByText(money('-R$ 25,90'))).not.toBeInTheDocument()
  })

  it('reports batch totals in the batch currency', async () => {
    renderDetail(
      batch(currencyContext('USD', 'FILE', 'USD'), { items: [item({ currency: 'USD' })] }),
    )

    const confirmation = await screen.findByRole('region', {
      name: 'Confirmação da importação',
    })
    expect(within(confirmation).getByText('USD — Dólar americano')).toBeInTheDocument()
    expect(within(confirmation).getByText(money('US$ 25,90'))).toBeInTheDocument()
    expect(within(confirmation).queryByText(/R\$/)).not.toBeInTheDocument()
  })
})

/* ---------- the four sources say different things ---------- */

describe('currency source explanations', () => {
  it('states a matching file declaration as a fact, not an alert', async () => {
    renderDetail(
      batch(currencyContext('USD', 'FILE', 'USD'), { items: [item({ currency: 'USD' })] }),
    )

    const notice = await screen.findByRole('region', { name: 'Moeda da importação' })
    expect(notice).toHaveTextContent(/O arquivo declara USD/)
    expect(notice).toHaveTextContent(/sem conversão/)
    // Stable metadata: not an alert, and nothing to confirm.
    expect(notice.getAttribute('role')).not.toBe('alert')
    expect(screen.queryByLabelText(/Confirmo que os valores/)).not.toBeInTheDocument()
  })

  it('explains a missing declaration and requires an unchecked acknowledgement', async () => {
    renderDetail(
      batch(currencyContext('USD', 'ACCOUNT_ASSUMED', null, true), {
        items: [item({ currency: 'USD' })],
      }),
    )

    const notice = await screen.findByRole('region', { name: 'Moeda da importação' })
    expect(notice).toHaveTextContent(/não declarou uma moeda/)
    expect(notice).toHaveTextContent(/USD — Dólar americano/)
    expect(notice).toHaveTextContent(/Nenhuma conversão será realizada/)

    // The control is labelled, keyboard-reachable and starts unchecked.
    const acknowledgement = screen.getByRole('checkbox', {
      name: /Confirmo que os valores deste arquivo devem ser interpretados em USD/,
    })
    expect(acknowledgement).not.toBeChecked()
    acknowledgement.focus()
    expect(acknowledgement).toHaveFocus()
  })

  it('describes a pre-V16 import without claiming the file omitted a currency', async () => {
    renderDetail(
      batch(currencyContext('USD', 'LEGACY_UNKNOWN', null, true), {
        items: [item({ currency: 'USD' })],
      }),
    )

    const notice = await screen.findByRole('region', { name: 'Moeda da importação' })
    expect(notice).toHaveTextContent(/antes de o Finora registrar a moeda declarada/)
    // The evidence is missing, not negative: this claim must never appear.
    expect(notice).not.toHaveTextContent(/não declarou uma moeda/)
    expect(
      screen.getByRole('checkbox', { name: /interpretados em USD/ }),
    ).not.toBeChecked()
  })
})

/* ---------- the acknowledgement gate ---------- */

describe('acknowledgement gate', () => {
  it('blocks confirmation until the assumption is acknowledged, then sends it', async () => {
    const assumed = batch(currencyContext('USD', 'ACCOUNT_ASSUMED', null, true), {
      items: [item({ currency: 'USD' })],
    })
    const calls = renderDetail(assumed)

    const button = await screen.findByRole('button', { name: /Importar 1 lançamento/ })
    expect(button).toBeDisabled()
    // The block explains itself instead of silently doing nothing.
    expect(
      screen.getByText(/Confirme acima que os valores devem ser interpretados em USD/),
    ).toBeInTheDocument()

    await userEvent.click(screen.getByRole('checkbox', { name: /interpretados em USD/ }))
    expect(button).toBeEnabled()

    await userEvent.click(button)
    await userEvent.click(await screen.findByRole('button', { name: 'Criar transações' }))

    await waitFor(() =>
      expect(calls.some((call) => call.url.includes('/confirm'))).toBe(true),
    )
    const confirmCall = calls.find((call) => call.url.includes('/confirm'))
    expect(confirmCall?.body).toEqual({ acknowledgeAccountCurrency: true })
  })

  it('never sends an acknowledgement for a batch that does not need one', async () => {
    const declared = batch(currencyContext('USD', 'FILE', 'USD'), {
      items: [item({ currency: 'USD' })],
    })
    const calls = renderDetail(declared)

    await userEvent.click(await screen.findByRole('button', { name: /Importar 1 lançamento/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'Criar transações' }))

    await waitFor(() =>
      expect(calls.some((call) => call.url.includes('/confirm'))).toBe(true),
    )
    expect(calls.find((call) => call.url.includes('/confirm'))?.body).toBeUndefined()
  })

  it('drops the acknowledgement when the destination account changes', async () => {
    // The consent was given for one destination. A batch that comes back on
    // another account is a different assumption, so the box must be clear.
    const assumed = batch(currencyContext('USD', 'ACCOUNT_ASSUMED', null, true), {
      items: [item({ currency: 'USD' })],
    })
    const calls = mockApi(assumed)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { rerender } = render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <ImportDetail batchId={assumed.id} onBack={() => {}} />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await userEvent.click(
      await screen.findByRole('checkbox', { name: /interpretados em USD/ }),
    )
    expect(await screen.findByRole('button', { name: /Importar 1 lançamento/ })).toBeEnabled()

    // The batch is refetched on a different account (EUR), as it would be after
    // a successful destination change.
    const moved = batch(currencyContext('EUR', 'ACCOUNT_ASSUMED', null, true), {
      accountId: 9,
      accountName: 'Conta euro',
      items: [item({ currency: 'EUR' })],
    })
    vi.unstubAllGlobals()
    mockApi(moved)
    await queryClient.invalidateQueries()
    rerender(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <ImportDetail batchId={moved.id} onBack={() => {}} />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await waitFor(() =>
      expect(
        screen.getByRole('checkbox', { name: /interpretados em EUR/ }),
      ).not.toBeChecked(),
    )
    expect(screen.getByRole('button', { name: /Importar 1 lançamento/ })).toBeDisabled()
    expect(calls.length).toBeGreaterThan(0)
  })
})

/* ---------- destination account ---------- */

describe('destination account', () => {
  it('names each account currency and warns that no conversion happens', async () => {
    renderDetail(
      batch(currencyContext('USD', 'ACCOUNT_ASSUMED', null, true), {
        format: 'CSV',
        items: [item({ currency: 'USD' })],
      }),
    )

    const select = await screen.findByLabelText('Conta de destino')
    expect(
      await within(select).findByRole('option', {
        name: 'Conta internacional • Conta corrente • USD — Dólar americano',
      }),
    ).toBeInTheDocument()
    expect(
      within(select).getByRole('option', {
        name: 'Conta corrente • Conta corrente • BRL — Real brasileiro',
      }),
    ).toBeInTheDocument()
    expect(screen.getByText(/Nenhuma conversão é realizada/)).toBeInTheDocument()
  })

  it('surfaces a refused currency change naming both currencies', async () => {
    renderDetail(
      batch(currencyContext('USD', 'FILE', 'USD'), { items: [item({ currency: 'USD' })] }),
    )

    // A file-declared batch says up front which accounts can receive it.
    expect(
      await screen.findByText(/só contas em USD podem receber este extrato/),
    ).toBeInTheDocument()

    await screen.findByRole('option', { name: /^Conta corrente •/ })
    await userEvent.selectOptions(screen.getByLabelText('Conta de destino'), '4')

    const error = await screen.findByRole('alert')
    expect(error).toHaveTextContent(/USD/)
    expect(error).toHaveTextContent(/BRL/)
    // Conversion is never offered, because it does not exist.
    expect(error).not.toHaveTextContent(/converter/)
  })
})

/* ---------- no BRL-only helper survives in this feature ---------- */

describe('currency helpers', () => {
  it('imports no BRL-only formatter anywhere in the statement-import feature', async () => {
    const modules = import.meta.glob('./*.tsx', { query: '?raw', import: 'default' })
    const offenders: string[] = []
    for (const [path, load] of Object.entries(modules)) {
      if (path.includes('.test.')) {
        continue
      }
      if (/\bformatBRL\b/.test((await load()) as string)) {
        offenders.push(path)
      }
    }
    expect(offenders).toEqual([])
  })
})
