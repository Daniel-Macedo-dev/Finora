import { expect, test, type Page } from '@playwright/test'
import { pageGet, pagePost, registerViaUi } from './helpers.ts'

/**
 * Cash-flow forecast: recurring projections, card cash applied on the invoice
 * due date (never on the purchase date), negative-balance detection,
 * unassigned flows and the dashboard future-cash summary.
 */

function isoFromToday(days: number): string {
  const date = new Date()
  date.setDate(date.getDate() + days)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(
    date.getDate(),
  ).padStart(2, '0')}`
}

async function categoryIdOf(page: Page, name: string, type: 'INCOME' | 'EXPENSE') {
  const categories = await (await pageGet(page, `/categories?type=${type}`)).json()
  return (categories as Array<{ id: number; name: string }>).find(
    (category) => category.name === name,
  )!.id
}

test.describe('Cenário — Previsão de caixa', () => {
  test('projeta recorrentes, aplica fatura no vencimento e alerta saldo negativo', async ({
    page,
  }) => {
    await registerViaUi(page)
    const account = await (
      await pagePost(page, '/accounts', {
        name: 'Conta Prevista',
        type: 'CHECKING',
        openingBalance: 500,
      })
    ).json()
    const salario = await categoryIdOf(page, 'Salário', 'INCOME')
    const moradia = await categoryIdOf(page, 'Moradia', 'EXPENSE')
    const assinaturas = await categoryIdOf(page, 'Assinaturas', 'EXPENSE')

    // Weekly income of 200 into the account (projection via account target).
    await pagePost(page, '/commitments', {
      description: 'Renda extra',
      amount: 200,
      categoryId: salario,
      cadence: 'WEEKLY',
      dueDay: null,
      startDate: isoFromToday(1),
      endDate: null,
      active: true,
      paymentMethod: null,
      executionMode: 'MANUAL',
      targetKind: 'ACCOUNT_TRANSACTION',
      accountId: account.id,
      creditCardId: null,
      installmentCount: 1,
    })

    // One big projected expense drives the balance negative inside 90 days.
    await pagePost(page, '/commitments', {
      description: 'Aluguel do escritório',
      amount: 3000,
      categoryId: moradia,
      cadence: 'WEEKLY',
      dueDay: null,
      startDate: isoFromToday(20),
      endDate: isoFromToday(21),
      active: true,
      paymentMethod: null,
      executionMode: 'MANUAL',
      targetKind: 'ACCOUNT_TRANSACTION',
      accountId: account.id,
      creditCardId: null,
      installmentCount: 1,
    })

    // Projection-only definition → disclosed as unassigned, never in balance.
    await pagePost(page, '/commitments', {
      description: 'Plano sem conta',
      amount: 90,
      categoryId: assinaturas,
      cadence: 'WEEKLY',
      dueDay: null,
      startDate: isoFromToday(5),
      endDate: isoFromToday(6),
      active: true,
      paymentMethod: null,
      executionMode: 'MANUAL',
      targetKind: 'PROJECTION_ONLY',
      accountId: null,
      creditCardId: null,
      installmentCount: 1,
    })

    // Recurring card purchase: cash must land on the card's due day.
    const card = await (
      await pagePost(page, '/credit-cards', {
        name: 'Cartão Previsto',
        brand: 'MASTERCARD',
        creditLimit: 3000,
        closingDay: 10,
        dueDay: 17,
        defaultPaymentAccountId: account.id,
      })
    ).json()
    await pagePost(page, '/commitments', {
      description: 'Assinatura no cartão',
      amount: 120,
      categoryId: assinaturas,
      cadence: 'WEEKLY',
      dueDay: null,
      startDate: isoFromToday(2),
      endDate: null,
      active: true,
      paymentMethod: null,
      executionMode: 'MANUAL',
      targetKind: 'CREDIT_CARD_PURCHASE',
      accountId: null,
      creditCardId: card.id,
      installmentCount: 1,
    })

    // API-level proof: projected card cash sits on invoice due dates (day 17),
    // never on the weekly purchase dates.
    const forecast = await (await pageGet(page, '/forecast?days=90')).json()
    const cardEvents = forecast.events.filter(
      (event: { source: string }) => event.source === 'PROJECTED_RECURRING_CARD_PURCHASE',
    )
    expect(cardEvents.length).toBeGreaterThan(0)
    for (const event of cardEvents) {
      expect(Number(event.date.slice(8, 10))).toBe(17)
    }
    expect(forecast.firstNegativeDate).not.toBeNull()
    expect(forecast.unassignedOutflows).toBe(90)

    // The page tells the same story.
    await page.goto('/forecast')
    await expect(page.getByRole('heading', { name: 'Previsão de caixa' })).toBeVisible()
    await expect(page.getByText('Saldo hoje')).toBeVisible()
    await expect(page.getByText('Menor saldo projetado')).toBeVisible()
    await expect(page.getByRole('alert')).toContainText('negativo em')
    await expect(page.getByText(/Fluxos sem conta definida/)).toBeVisible()
    await expect(page.getByText('Renda extra').first()).toBeVisible()
    await expect(page.getByText(/Recorrente projetado/).first()).toBeVisible()
    await expect(page.getByText(/Compra recorrente projetada/).first()).toBeVisible()

    // Horizon selector reloads the projection.
    await page.getByRole('button', { name: '30 dias' }).click()
    await expect(page.getByRole('button', { name: '30 dias' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    await expect(page.getByText('Saldo hoje')).toBeVisible()

    // Account filter keeps the page consistent.
    await page.getByLabel('Filtrar por conta').selectOption(String(account.id))
    await expect(page.getByText('Saldo hoje')).toBeVisible()

    // Dashboard integration: compact future-cash summary, no duplicate page.
    await page.goto('/dashboard')
    await expect(page.getByText('Caixa futuro (30 dias)')).toBeVisible()
    await expect(page.getByText('Saldo projetado')).toBeVisible()
    await expect(page.getByText(/Próximo recorrente:/)).toBeVisible()
    await expect(page.getByRole('link', { name: 'Ver previsão completa' })).toBeVisible()
  })

  test('ocorrência executada substitui a projeção — sem contagem dupla', async ({ page }) => {
    await registerViaUi(page)
    const account = await (
      await pagePost(page, '/accounts', {
        name: 'Conta Única',
        type: 'CHECKING',
        openingBalance: 1000,
      })
    ).json()
    const assinaturas = await categoryIdOf(page, 'Assinaturas', 'EXPENSE')

    // Single occurrence tomorrow, account-targeted (the projection window
    // starts after today — today's cash is already in the opening balance).
    const commitment = await (
      await pagePost(page, '/commitments', {
        description: 'Mensalidade única',
        amount: 100,
        categoryId: assinaturas,
        cadence: 'WEEKLY',
        dueDay: null,
        startDate: isoFromToday(1),
        endDate: isoFromToday(2),
        active: true,
        paymentMethod: null,
        executionMode: 'MANUAL',
        targetKind: 'ACCOUNT_TRANSACTION',
        accountId: account.id,
        creditCardId: null,
        installmentCount: 1,
      })
    ).json()

    // Before materialization: one projected occurrence event.
    let forecast = await (await pageGet(page, '/forecast?days=30')).json()
    const projected = forecast.events.filter(
      (event: { source: string; description: string }) =>
        event.source === 'RECURRING_ACCOUNT_OCCURRENCE' &&
        event.description.includes('Mensalidade única'),
    )
    expect(projected).toHaveLength(1)
    expect(forecast.closingBalance).toBe(900)

    // Materialize it; the projection is replaced by the real transaction.
    await pagePost(
      page,
      `/commitments/${commitment.id}/occurrences/${isoFromToday(1)}/materialize`,
      {},
    )
    forecast = await (await pageGet(page, '/forecast?days=30')).json()
    const stillProjected = forecast.events.filter(
      (event: { source: string; description: string }) =>
        event.source === 'RECURRING_ACCOUNT_OCCURRENCE' &&
        event.description.includes('Mensalidade única'),
    )
    expect(stillProjected).toHaveLength(0)
    const actual = forecast.events.filter(
      (event: { source: string; description: string }) =>
        event.source === 'ACTUAL_TRANSACTION' &&
        event.description.includes('Mensalidade única'),
    )
    expect(actual).toHaveLength(1)
    // Balance reflects the real transaction exactly once.
    expect(forecast.openingBalance).toBe(1000)
    expect(forecast.closingBalance).toBe(900)

    // A skipped future occurrence disappears from the projection too.
    const skipTarget = await (
      await pagePost(page, '/commitments', {
        description: 'Curso adiável',
        amount: 300,
        categoryId: assinaturas,
        cadence: 'WEEKLY',
        dueDay: null,
        startDate: isoFromToday(3),
        endDate: isoFromToday(4),
        active: true,
        paymentMethod: null,
        executionMode: 'MANUAL',
        targetKind: 'ACCOUNT_TRANSACTION',
        accountId: account.id,
        creditCardId: null,
        installmentCount: 1,
      })
    ).json()
    forecast = await (await pageGet(page, '/forecast?days=30')).json()
    expect(forecast.closingBalance).toBe(600)
    await pagePost(
      page,
      `/commitments/${skipTarget.id}/occurrences/${isoFromToday(3)}/skip`,
      {},
    )
    forecast = await (await pageGet(page, '/forecast?days=30')).json()
    expect(forecast.closingBalance).toBe(900)
  })
})

test.describe('Cenário — Recorrentes e previsão no celular (390px)', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('cria recorrente e consulta a previsão pelo menu mobile', async ({ page }) => {
    await registerViaUi(page)
    const account = await (
      await pagePost(page, '/accounts', {
        name: 'Conta Mobile',
        type: 'CHECKING',
        openingBalance: 400,
      })
    ).json()

    await page.getByRole('button', { name: 'Abrir menu' }).click()
    await page.getByRole('link', { name: 'Recorrentes' }).click()
    await expect(page.getByRole('heading', { name: 'Recorrentes' })).toBeVisible()

    await page.getByRole('button', { name: 'Novo recorrente' }).first().click()
    await page.getByLabel('Descrição').fill('Internet fibra')
    await page.getByLabel('Valor (R$)').fill('99,90')
    await page.getByLabel('Categoria').selectOption({ label: 'Assinaturas (despesa)' })
    await page.getByLabel('Dia de vencimento').fill('10')
    await page.getByLabel('Início').fill(isoFromToday(-30))
    await page
      .getByLabel('O que cada ocorrência vira')
      .selectOption('ACCOUNT_TRANSACTION')
    await page.getByLabel('Conta', { exact: true }).selectOption(String(account.id))
    await page.getByRole('button', { name: 'Criar recorrente' }).click()
    await expect(page.getByRole('dialog')).toBeHidden()
    await expect(page.getByText('Internet fibra').first()).toBeVisible()

    await page.getByRole('button', { name: 'Abrir menu' }).click()
    await page.getByRole('link', { name: 'Previsão' }).click()
    await expect(page.getByRole('heading', { name: 'Previsão de caixa' })).toBeVisible()
    await expect(page.getByText('Saldo hoje')).toBeVisible()
    await expect(page.getByText('Internet fibra').first()).toBeVisible()
  })
})
