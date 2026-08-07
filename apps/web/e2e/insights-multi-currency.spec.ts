import { expect, test, type Page } from '@playwright/test'
import { pageGet, pagePost, pagePut, registerViaUi, uniqueIdentity } from './helpers.ts'

/**
 * Insights in a browser, across currencies.
 *
 * <p>Two kinds of rule are checked from the outside and they must fail
 * differently: a conclusion drawn from across the ledger disappears the moment
 * its operands stop being comparable, while a conclusion drawn from one card
 * survives any amount of mixed currency elsewhere and keeps its own symbol.
 *
 * <p>The third thing under test is the difference between the two silences —
 * a rule that had nothing to say must never be reported as a currency problem,
 * because that would make an ordinary account look broken.
 */

function isoDay(month: Date, day: number) {
  return `${month.getFullYear()}-${String(month.getMonth() + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

const NOW = new Date()
const THIS_MONTH = new Date(NOW.getFullYear(), NOW.getMonth(), 1)
const LAST_MONTH = new Date(NOW.getFullYear(), NOW.getMonth() - 1, 1)

/** The insights panel, whichever state it is in. */
function panel(page: Page) {
  return page.getByRole('region', { name: 'Insights' })
}

function coverageNotice(page: Page) {
  return page.getByText('Algumas análises consolidadas ficaram de fora')
}

async function categoryId(page: Page, name: string, type: 'INCOME' | 'EXPENSE') {
  const categories = await (await pageGet(page, `/categories?type=${type}`)).json()
  const category = (categories as Array<{ id: number; name: string }>).find((c) => c.name === name)
  if (!category) {
    throw new Error(`Categoria padrão não encontrada: ${name}`)
  }
  return category.id
}

async function expense(page: Page, amount: number, month: Date, category: string, currency?: string) {
  const response = await pagePost(page, '/transactions', {
    type: 'EXPENSE',
    amount,
    description: 'Despesa',
    date: isoDay(month, 10),
    categoryId: await categoryId(page, category, 'EXPENSE'),
    ...(currency ? { currency } : {}),
  })
  expect(response.ok(), await response.text()).toBeTruthy()
}

/** Income across the whole context window, so the averages have history. */
async function incomeHistory(page: Page, amount: number, currency?: string) {
  const salario = await categoryId(page, 'Salário', 'INCOME')
  for (let back = 1; back <= 3; back += 1) {
    const month = new Date(NOW.getFullYear(), NOW.getMonth() - back, 1)
    const response = await pagePost(page, '/transactions', {
      type: 'INCOME',
      amount,
      description: 'Receita',
      date: isoDay(month, 5),
      categoryId: salario,
      ...(currency ? { currency } : {}),
    })
    expect(response.ok(), await response.text()).toBeTruthy()
  }
}

async function account(page: Page, name: string, balance: number, currency?: string) {
  const response = await pagePost(page, '/accounts', {
    name,
    type: 'CHECKING',
    openingBalance: balance,
    ...(currency ? { currency } : {}),
  })
  expect(response.ok(), await response.text()).toBeTruthy()
}

async function commitment(page: Page, amount: number, currency?: string) {
  const response = await pagePost(page, '/commitments', {
    description: 'Aluguel',
    amount,
    categoryId: await categoryId(page, 'Moradia', 'EXPENSE'),
    cadence: 'MONTHLY',
    dueDay: 10,
    startDate: '2025-01-10',
    ...(currency ? { currency } : {}),
  })
  expect(response.ok(), await response.text()).toBeTruthy()
}

async function card(page: Page, name: string, creditLimit: number, currency?: string) {
  const response = await pagePost(page, '/credit-cards', {
    name,
    brand: 'VISA',
    creditLimit,
    closingDay: 10,
    dueDay: 17,
    ...(currency ? { currency } : {}),
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return (await response.json()).id as number
}

/** A purchase old enough that its only invoice is already overdue. */
async function overduePurchase(page: Page, cardId: number, total: number) {
  const old = new Date(NOW.getFullYear(), NOW.getMonth() - 3, 1)
  const response = await pagePost(page, `/credit-cards/${cardId}/purchases`, {
    description: 'Compra',
    merchant: 'Loja',
    categoryId: await categoryId(page, 'Compras', 'EXPENSE'),
    purchaseDate: isoDay(old, 1),
    totalAmount: total,
    installmentCount: 1,
  })
  expect(response.ok(), await response.text()).toBeTruthy()
}

async function wishlistItem(page: Page, name: string, price: number, currency?: string) {
  const item = await (
    await pagePost(page, '/wishlist', {
      name,
      priority: 'MEDIUM',
      status: 'MONITORING',
      ...(currency ? { currency } : {}),
    })
  ).json()
  const response = await pagePost(page, `/wishlist/${item.id}/options`, {
    merchant: 'Loja',
    kind: 'CASH',
    basePrice: price,
    shipping: 0,
    fees: 0,
  })
  expect(response.ok(), await response.text()).toBeTruthy()
}

async function goal(page: Page, name: string, target: number, currency?: string) {
  const next = new Date(NOW.getFullYear(), NOW.getMonth() + 1, 28)
  const response = await pagePost(page, '/goals', {
    name,
    targetAmount: target,
    currentAmount: 0,
    targetDate: isoDay(next, 28),
    ...(currency ? { currency } : {}),
  })
  expect(response.ok(), await response.text()).toBeTruthy()
}

/** Opens the dashboard and waits for the insights panel to have resolved. */
async function openDashboard(page: Page) {
  await page.goto('/dashboard')
  await expect(page.getByRole('heading', { name: 'Insights' })).toBeVisible()
  await expect(panel(page).getByText('Analisando dados…')).toHaveCount(0)
  await expect(panel(page).locator('.skeleton')).toHaveCount(0)
}

test.describe('Cenário — Insights e moedas', () => {
  test('um mês inteiramente em reais mantém as conclusões consolidadas', async ({ page }) => {
    await registerViaUi(page)
    await expense(page, 1000, LAST_MONTH, 'Alimentação')
    await expense(page, 2000, THIS_MONTH, 'Alimentação')
    await expense(page, 200, THIS_MONTH, 'Lazer')

    await openDashboard(page)

    await expect(panel(page).getByText('Gastos subiram em relação ao mês anterior')).toBeVisible()
    await expect(panel(page).getByText('Uma categoria concentra os gastos')).toBeVisible()
    await expect(coverageNotice(page)).toHaveCount(0)
  })

  test('compromissos e parcelas em reais continuam pesando na renda', async ({ page }) => {
    await registerViaUi(page)
    await account(page, 'Conta', 10000)
    await incomeHistory(page, 1000)
    await commitment(page, 500)

    await openDashboard(page)

    await expect(panel(page).getByText('Compromissos recorrentes pesam na renda')).toBeVisible()
    await expect(panel(page).getByText('R$ 500,00', { exact: true })).toBeVisible()
    await expect(coverageNotice(page)).toHaveCount(0)
  })

  test('uma fatura vencida em dólares é anunciada em dólares', async ({ page }) => {
    await registerViaUi(page)
    // The dashboard only renders its panels once the month has data at all.
    await account(page, 'Conta', 10000)
    const cardId = await card(page, 'Cartão USD', 20000, 'USD')
    await overduePurchase(page, cardId, 900)

    await openDashboard(page)

    await expect(panel(page).getByText('Fatura vencida: Cartão USD')).toBeVisible()
    await expect(panel(page).getByText('US$ 900,00', { exact: true })).toBeVisible()
    // The whole panel, so a stray reais symbol anywhere would fail.
    await expect(panel(page).getByText(/R\$/)).toHaveCount(0)
  })

  test('um cartão em ienes informa o limite restante sem centavos', async ({ page }) => {
    await registerViaUi(page)
    await account(page, 'Conta', 10000)
    const cardId = await card(page, 'Cartão JPY', 100000, 'JPY')
    await overduePurchase(page, cardId, 95000)

    await openDashboard(page)

    await expect(panel(page).getByText('Limite quase comprometido: Cartão JPY')).toBeVisible()
    await expect(panel(page).getByText('JP¥ 5.000', { exact: true })).toBeVisible()
    await expect(panel(page).getByText(/R\$/)).toHaveCount(0)
  })

  test('gastos mistos suprimem as conclusões do mês e explicam por quê', async ({ page }) => {
    await registerViaUi(page)
    await expense(page, 1000, LAST_MONTH, 'Alimentação')
    await expense(page, 2000, THIS_MONTH, 'Alimentação')
    await expense(page, 500, THIS_MONTH, 'Lazer', 'USD')
    // A native alert in the same account, to prove the two do not share a fate.
    const cardId = await card(page, 'Cartão USD', 20000, 'USD')
    await overduePurchase(page, cardId, 900)

    await openDashboard(page)

    await expect(panel(page).getByText('Gastos subiram em relação ao mês anterior')).toHaveCount(0)
    await expect(panel(page).getByText('Uma categoria concentra os gastos')).toHaveCount(0)
    // One explanation for the whole month, never one per withheld rule.
    await expect(coverageNotice(page)).toHaveCount(1)
    await expect(panel(page).getByText(/a comparação de gastos do mês/)).toBeVisible()
    await expect(panel(page).getByText(/USD — Dólar americano/)).toBeVisible()
    // Native alerts are untouched by an aggregate limitation.
    await expect(panel(page).getByText('Fatura vencida: Cartão USD')).toBeVisible()
    await expect(panel(page).getByText('US$ 900,00', { exact: true })).toBeVisible()
    // Nothing failed, so nothing is announced as a failure.
    await expect(page.getByRole('alert')).toHaveCount(0)
  })

  test('um compromisso estrangeiro suprime a razão sobre a renda', async ({ page }) => {
    await registerViaUi(page)
    await incomeHistory(page, 1000)
    await commitment(page, 500)
    await commitment(page, 100, 'USD')

    await openDashboard(page)

    await expect(panel(page).getByText('Compromissos recorrentes pesam na renda')).toHaveCount(0)
    await expect(coverageNotice(page)).toHaveCount(1)
    await expect(
      panel(page).getByText(/o peso dos compromissos e parcelas na renda/),
    ).toBeVisible()
  })

  test('uma meta em dólares nunca é medida contra a sobra em reais', async ({ page }) => {
    await registerViaUi(page)
    await incomeHistory(page, 1000)
    await goal(page, 'Viagem', 90000, 'USD')

    await openDashboard(page)

    await expect(panel(page).getByText(/Meta fora do ritmo/)).toHaveCount(0)
    await expect(coverageNotice(page)).toHaveCount(1)
    await expect(panel(page).getByText(/o ritmo das metas/)).toBeVisible()
  })

  test('um item estrangeiro nunca é declarado viável com o caixa em reais', async ({ page }) => {
    await registerViaUi(page)
    await account(page, 'Conta', 10000)
    await wishlistItem(page, 'Câmera importada', 100, 'USD')

    await openDashboard(page)

    await expect(panel(page).getByText(/Compra viável/)).toHaveCount(0)
    await expect(coverageNotice(page)).toHaveCount(1)
    await expect(panel(page).getByText(/a viabilidade das compras planejadas/)).toBeVisible()
  })

  test('caixa misto impede a conclusão de viabilidade', async ({ page }) => {
    await registerViaUi(page)
    await account(page, 'Conta', 10000)
    await account(page, 'Conta USD', 2000, 'USD')
    await wishlistItem(page, 'Notebook', 1000)

    await openDashboard(page)

    await expect(panel(page).getByText(/Compra viável/)).toHaveCount(0)
    await expect(coverageNotice(page)).toHaveCount(1)
  })

  test('um mês em reais com caixa completo ainda declara a compra viável', async ({ page }) => {
    await registerViaUi(page)
    await account(page, 'Conta', 10000)
    await wishlistItem(page, 'Notebook', 1000)

    await openDashboard(page)

    await expect(panel(page).getByText('Compra viável: Notebook')).toBeVisible()
    await expect(panel(page).getByText('R$ 1.000,00', { exact: true })).toBeVisible()
    await expect(coverageNotice(page)).toHaveCount(0)
  })

  test('uma conta vazia não é apresentada como um problema de moeda', async ({ page }) => {
    await registerViaUi(page)
    // Enough for the dashboard to render, and nothing any rule reacts to.
    await account(page, 'Conta', 10000)

    await openDashboard(page)

    await expect(panel(page).getByText(/Nada digno de nota por enquanto/)).toBeVisible()
    await expect(coverageNotice(page)).toHaveCount(0)
  })

  test('os dados estrangeiros de outra pessoa não produzem nem suprimem nada', async ({
    page,
    browser,
  }) => {
    const theirs = await browser.newContext()
    const theirPage = await theirs.newPage()
    await registerViaUi(theirPage, uniqueIdentity('outra'))
    await account(theirPage, 'Conta USD', 90000, 'USD')
    await expense(theirPage, 9000, THIS_MONTH, 'Lazer', 'USD')
    await wishlistItem(theirPage, 'Câmera deles', 100, 'USD')
    await theirs.close()

    await registerViaUi(page)
    await account(page, 'Conta', 10000)
    await wishlistItem(page, 'Notebook', 1000)
    await expense(page, 1000, LAST_MONTH, 'Alimentação')
    await expense(page, 2000, THIS_MONTH, 'Alimentação')

    await openDashboard(page)

    await expect(panel(page).getByText('Compra viável: Notebook')).toBeVisible()
    await expect(panel(page).getByText('Gastos subiram em relação ao mês anterior')).toBeVisible()
    await expect(coverageNotice(page)).toHaveCount(0)
  })
})

test.describe('Cenário — Contrato dos insights', () => {
  test('todo valor exibido vem acompanhado da sua moeda', async ({ page }) => {
    await registerViaUi(page)
    await account(page, 'Conta', 10000)
    await wishlistItem(page, 'Notebook', 1000)
    const cardId = await card(page, 'Cartão USD', 20000, 'USD')
    await overduePurchase(page, cardId, 900)

    // The API is the contract; the panel only presents it.
    const response = await pageGet(page, '/insights')
    expect(response.ok()).toBeTruthy()
    const body = await response.json()
    expect(body.baseCurrency).toBe('BRL')
    for (const insight of body.insights as Array<{ amount: number | null; currency: string | null }>) {
      expect(insight.amount === null).toBe(insight.currency === null)
    }
    expect(body.aggregateCoverage).toBeDefined()

    await openDashboard(page)
    await expect(panel(page).getByText('US$ 900,00', { exact: true })).toBeVisible()
    await expect(panel(page).getByText('R$ 1.000,00', { exact: true })).toBeVisible()
  })

  test('a explicação usa apenas o campo estruturado, nunca o texto da mensagem', async ({
    page,
  }) => {
    await registerViaUi(page)
    await pagePut(page, '/settings', {
      minimumCashBuffer: 0,
      maxInstallmentCommitmentRatio: 0.3,
      monthlyOpportunityRate: 0,
      budgetWarningThreshold: 0.8,
    })
    await expense(page, 1000, LAST_MONTH, 'Alimentação')
    await expense(page, 2000, THIS_MONTH, 'Alimentação')
    await expense(page, 300, THIS_MONTH, 'Lazer', 'EUR')

    const body = await (await pageGet(page, '/insights')).json()
    expect(body.aggregateCoverage.complete).toBe(false)
    expect(body.aggregateCoverage.missingCurrencies).toEqual(['EUR'])

    await openDashboard(page)
    // The rule codes are a machine contract and never reach the screen.
    for (const code of body.aggregateCoverage.unavailableRules as string[]) {
      await expect(panel(page).getByText(code, { exact: false })).toHaveCount(0)
    }
    await expect(panel(page).getByText(/EUR — Euro/)).toBeVisible()
  })
})
