import { expect, test, type Page } from '@playwright/test'
import { pageGet, pagePost, pagePut, registerViaUi, uniqueIdentity } from './helpers.ts'

/**
 * The purchase analysis in a browser, across currencies.
 *
 * <p>A recommendation built from amounts that are not comparable would be the
 * most actionable wrong output Finora can produce, so these journeys check the
 * two halves of the contract from the outside: a complete single-currency
 * context still reaches every verdict it always did, and an incomplete one
 * reaches none of them — no BUY, no WAIT, no affordability badge, no figure
 * that would have needed a rate.
 *
 * <p>Fixtures are prepared through the ordinary authenticated API, but the
 * behaviour under test is never simulated: every assertion below reads the real
 * `/analysis` response rendered by the real panel on the real item route.
 */

const LAST_COMPLETE_MONTH = (() => {
  const now = new Date()
  const month = new Date(now.getFullYear(), now.getMonth() - 1, 5)
  return `${month.getFullYear()}-${String(month.getMonth() + 1).padStart(2, '0')}-05`
})()

/** The analysis panel, whichever of its two variants is on screen. */
function analysisSection(page: Page) {
  return page.getByRole('region', { name: 'Análise de compra' })
}

function unavailablePanel(page: Page) {
  return page.getByRole('region', { name: 'Análise indisponível' })
}

async function categoryId(page: Page, name: string, type: 'INCOME' | 'EXPENSE') {
  const categories = await (await pageGet(page, `/categories?type=${type}`)).json()
  const category = (categories as Array<{ id: number; name: string }>).find(
    (entry) => entry.name === name,
  )
  if (!category) {
    throw new Error(`Categoria padrão não encontrada: ${name}`)
  }
  return category.id
}

async function account(page: Page, name: string, balance: number, currency?: string) {
  return (
    await pagePost(page, '/accounts', {
      name,
      type: 'CHECKING',
      openingBalance: balance,
      ...(currency ? { currency } : {}),
    })
  ).json()
}

/**
 * An income movement with no account behind it.
 *
 * <p>Accountless on purpose: a movement linked to an account would move that
 * account's balance too, and a scenario that means to vary only the history
 * would silently be varying the cash as well.
 */
async function income(page: Page, amount: number, currency?: string) {
  const salario = await categoryId(page, 'Salário', 'INCOME')
  const response = await pagePost(page, '/transactions', {
    type: 'INCOME',
    amount,
    description: 'Receita',
    date: LAST_COMPLETE_MONTH,
    categoryId: salario,
    ...(currency ? { currency } : {}),
  })
  expect(response.ok(), await response.text()).toBeTruthy()
}

async function wishlistItem(page: Page, name: string, currency?: string) {
  const response = await pagePost(page, '/wishlist', {
    name,
    priority: 'MEDIUM',
    status: 'MONITORING',
    ...(currency ? { currency } : {}),
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return response.json()
}

async function cashOption(page: Page, itemId: number, merchant: string, basePrice: number) {
  const response = await pagePost(page, `/wishlist/${itemId}/options`, {
    merchant,
    kind: 'CASH',
    basePrice,
    shipping: 0,
    fees: 0,
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return response.json()
}

async function settings(page: Page, values: Record<string, unknown>) {
  const response = await pagePut(page, '/settings', {
    minimumCashBuffer: 0,
    maxInstallmentCommitmentRatio: 0.3,
    monthlyOpportunityRate: 0,
    budgetWarningThreshold: 0.8,
    ...values,
  })
  expect(response.ok(), await response.text()).toBeTruthy()
}

/** Opens the item and waits for the analysis to have resolved either way. */
async function openAnalysis(page: Page, itemId: number) {
  await page.goto(`/wishlist/${itemId}`)
  await expect(page.getByRole('heading', { name: 'Análise de compra' })).toBeVisible()
  await expect(analysisSection(page).locator('.skeleton')).toHaveCount(0)
}

/** No verdict, no affordability badge — in the accessibility tree, not just visually. */
async function expectNoVerdict(page: Page) {
  for (const verdict of ['Comprar à vista', 'Comprar parcelado', 'Aguardar', 'Sem opções']) {
    await expect(page.getByText(verdict, { exact: true })).toHaveCount(0)
  }
  await expect(page.getByText('Segura', { exact: true })).toHaveCount(0)
  await expect(page.getByText('Arriscada', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('region', { name: 'Premissas da análise' })).toHaveCount(0)
  await expect(page.getByRole('region', { name: 'Comparação de opções' })).toHaveCount(0)
}

test.describe('Cenário — Análise de compra e moedas', () => {
  test('contexto completo em reais continua recomendando comprar à vista', async ({ page }) => {
    await registerViaUi(page)
    await account(page, 'Conta', 10000)
    const item = await wishlistItem(page, 'Notebook de trabalho')
    await cashOption(page, item.id, 'Loja A', 4800)

    await openAnalysis(page, item.id)

    await expect(page.getByText('Comprar à vista', { exact: true })).toBeVisible()
    await expect(page.getByRole('region', { name: 'Premissas da análise' })).toBeVisible()
    // The figures are labelled with the currency they are actually in.
    await expect(page.getByText('R$ 10.000,00')).toBeVisible()
    await expect(unavailablePanel(page)).toHaveCount(0)
  })

  test('contexto completo em reais ainda recomenda comprar parcelado', async ({ page }) => {
    await registerViaUi(page)
    // A buffer the cash option cannot respect and the installment can.
    await settings(page, { minimumCashBuffer: 5000 })
    await account(page, 'Conta', 6000)
    await income(page, 10000)
    const item = await wishlistItem(page, 'Bicicleta')
    await cashOption(page, item.id, 'Loja à vista', 2000)
    await pagePost(page, `/wishlist/${item.id}/options`, {
      merchant: 'Loja parcelada',
      kind: 'INSTALLMENT',
      basePrice: 2400,
      shipping: 0,
      fees: 0,
      installmentCount: 12,
      installmentAmount: 200,
    })

    await openAnalysis(page, item.id)

    await expect(page.getByText('Comprar parcelado', { exact: true })).toBeVisible()
    await expect(page.getByText('Recomendada', { exact: true })).toBeVisible()
    // The cash option is present and judged unsafe, not hidden.
    await expect(page.getByText('Arriscada', { exact: true })).toBeVisible()
  })

  test('sem opção segura o veredito continua sendo aguardar', async ({ page }) => {
    await registerViaUi(page)
    await account(page, 'Conta', 100)
    const item = await wishlistItem(page, 'Geladeira')
    await cashOption(page, item.id, 'Loja A', 4800)

    await openAnalysis(page, item.id)

    await expect(page.getByText('Aguardar', { exact: true })).toBeVisible()
    await expect(page.getByText(/Faltam aproximadamente/)).toBeVisible()
  })

  test('usuário sem histórico recebe a análise de caixa, não indisponibilidade', async ({
    page,
  }) => {
    await registerViaUi(page)
    await account(page, 'Conta', 10000)
    const item = await wishlistItem(page, 'Monitor')
    await cashOption(page, item.id, 'Loja A', 4800)

    await openAnalysis(page, item.id)

    // No history is an absence the engine has always handled, and is emphatically
    // not the same as a history denominated in something it cannot convert.
    await expect(unavailablePanel(page)).toHaveCount(0)
    await expect(page.getByText('Comprar à vista', { exact: true })).toBeVisible()
    await expect(page.getByText(/usa apenas o caixa atual/)).toBeVisible()
    await expect(page.getByText('Sem histórico').first()).toBeVisible()
  })

  test('item estrangeiro fica indisponível e o resto da página continua utilizável', async ({
    page,
  }) => {
    await registerViaUi(page)
    await account(page, 'Conta', 100000)
    const item = await wishlistItem(page, 'Câmera importada', 'USD')
    await cashOption(page, item.id, 'Store A', 1100)
    const snapshot = await pagePost(page, `/wishlist/${item.id}/price-snapshots`, {
      clientRequestId: crypto.randomUUID(),
      merchant: 'Store A',
      paymentKind: 'CASH',
      basePrice: 1150,
      shipping: 0,
      fees: 0,
      observedOn: LAST_COMPLETE_MONTH,
      updateLinkedOption: false,
    })
    expect(snapshot.ok(), await snapshot.text()).toBeTruthy()

    await openAnalysis(page, item.id)

    await expect(unavailablePanel(page)).toBeVisible()
    // A heading under the section's own h2, so the state is part of the page
    // outline rather than a paragraph to be scrolled past.
    await expect(
      page.getByRole('heading', { level: 3, name: 'Análise financeira indisponível' }),
    ).toBeVisible()
    await expect(page.getByRole('heading', { level: 2, name: 'Análise de compra' })).toBeVisible()
    await expect(page.getByText(/precisa comparar valores em moedas diferentes/)).toBeVisible()
    // Named in words, not distinguished by colour, and never a bare "$".
    await expect(page.getByText('Moeda do item')).toBeVisible()
    await expect(page.getByText('USD — Dólar americano')).toBeVisible()
    await expect(page.getByText('Sua moeda base')).toBeVisible()
    await expect(page.getByText('BRL — Real brasileiro')).toBeVisible()
    await expectNoVerdict(page)
    // Nothing failed, so nothing is announced as a failure.
    await expect(page.getByRole('alert')).toHaveCount(0)

    // Everything that does not need a rate is still on the page and still works.
    await expect(page.getByRole('heading', { name: 'Opções de compra' })).toBeVisible()
    await expect(page.getByText('Store A').first()).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Histórico de preços' })).toBeVisible()
    // Reachable and operable from the keyboard, not only by pointer.
    const record = page.getByRole('button', { name: 'Registrar preço', exact: true })
    await record.focus()
    await expect(record).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('dialog')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).toBeHidden()
  })

  test('saldo em moeda estrangeira deixa a análise indisponível', async ({ page }) => {
    await registerViaUi(page)
    await account(page, 'Conta', 10000)
    await account(page, 'Checking USD', 1200, 'USD')
    const item = await wishlistItem(page, 'Notebook')
    await cashOption(page, item.id, 'Loja A', 4800)

    await openAnalysis(page, item.id)

    await expect(unavailablePanel(page)).toBeVisible()
    await expect(page.getByText(/saldo disponível está em USD/)).toBeVisible()
    await expectNoVerdict(page)
  })

  test('histórico em moeda estrangeira deixa a análise indisponível', async ({ page }) => {
    await registerViaUi(page)
    await account(page, 'Conta', 10000)
    await income(page, 3000)
    await income(page, 500, 'USD')
    const item = await wishlistItem(page, 'Notebook')
    await cashOption(page, item.id, 'Loja A', 4800)

    await openAnalysis(page, item.id)

    await expect(unavailablePanel(page)).toBeVisible()
    await expect(page.getByText(/renda dos últimos meses está em USD/)).toBeVisible()
    await expectNoVerdict(page)
  })

  test('compromisso recorrente estrangeiro deixa a análise indisponível', async ({ page }) => {
    await registerViaUi(page)
    await account(page, 'Conta', 10000)
    const moradia = await categoryId(page, 'Moradia', 'EXPENSE')
    const commitment = await pagePost(page, '/commitments', {
      description: 'Aluguel no exterior',
      amount: 900,
      categoryId: moradia,
      cadence: 'MONTHLY',
      dueDay: 10,
      startDate: '2025-01-10',
      currency: 'USD',
    })
    expect(commitment.ok(), await commitment.text()).toBeTruthy()
    const item = await wishlistItem(page, 'Notebook')
    await cashOption(page, item.id, 'Loja A', 4800)

    await openAnalysis(page, item.id)

    await expect(unavailablePanel(page)).toBeVisible()
    await expect(page.getByText(/compromissos recorrentes está em USD/)).toBeVisible()
    await expectNoVerdict(page)
  })

  test('os rótulos separam dólares americanos, canadenses e australianos', async ({ page }) => {
    await registerViaUi(page)
    await account(page, 'Conta', 10000)
    // A bare "$" would make these three indistinguishable to a reader.
    for (const [code, label] of [
      ['USD', 'USD — Dólar americano'],
      ['CAD', 'CAD — Dólar canadense'],
      ['AUD', 'AUD — Dólar australiano'],
    ] as const) {
      const item = await wishlistItem(page, `Item ${code}`, code)
      await cashOption(page, item.id, `Store ${code}`, 500)
      await openAnalysis(page, item.id)
      await expect(unavailablePanel(page)).toBeVisible()
      await expect(page.getByText(label)).toBeVisible()
      await expect(page.getByText('Precisaria converter')).toBeVisible()
    }
  })

  test('uma análise em ienes não inventa centavos', async ({ page }) => {
    await registerViaUi(page)
    // Only possible while the ledger is empty, which is exactly now.
    await settings(page, { baseCurrency: 'JPY' })
    await account(page, 'Conta', 100, 'JPY')
    const item = await wishlistItem(page, 'Câmera', 'JPY')
    await cashOption(page, item.id, 'Store JP', 480000)

    await openAnalysis(page, item.id)

    // Available, in yen, which has no cents at all: "JP¥ 100,00" would describe
    // money that does not exist.
    await expect(page.getByText('Aguardar', { exact: true })).toBeVisible()
    const assumptions = page.getByRole('region', { name: 'Premissas da análise' })
    await expect(assumptions.getByText('JP¥ 100', { exact: true })).toBeVisible()
    await expect(analysisSection(page).getByText(/R\$/)).toHaveCount(0)
    await expect(analysisSection(page).getByText('JP¥ 480.000').first()).toBeVisible()
  })

  test('o item de outra pessoa continua inacessível', async ({ page, browser }) => {
    const owner = await browser.newContext()
    const ownerPage = await owner.newPage()
    await registerViaUi(ownerPage, uniqueIdentity('dona'))
    const theirItem = await wishlistItem(ownerPage, 'Notebook alheio', 'USD')
    await cashOption(ownerPage, theirItem.id, 'Loja Deles', 1000)
    await owner.close()

    await registerViaUi(page)
    const response = await pageGet(page, `/wishlist/${theirItem.id}/analysis`)
    expect(response.status()).toBe(404)

    await page.goto(`/wishlist/${theirItem.id}`)
    await expect(page.getByText('Notebook alheio')).toHaveCount(0)
  })
})
