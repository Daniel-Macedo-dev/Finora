import { expect, test, type Page } from '@playwright/test'
import { apiSession, pageGet, pagePost, pagePut, registerViaUi } from './helpers.ts'

/**
 * Multi-currency notifications against the real stack.
 *
 * <p>The financial sources are real: real accounts in real currencies, real
 * commitments, a real card and real forecast arithmetic. Nothing about the
 * money is faked, because the whole defect being closed here was a currency
 * that got lost somewhere between the source and the screen — and a stubbed
 * source cannot lose it.
 *
 * <p>The browser Notification API is the one boundary that is stubbed. Chromium
 * under Playwright cannot deterministically raise OS notification chrome, so the
 * constructor is replaced and its payload inspected. The server side of the
 * claim — what is disclosed and what is withheld — is exercised through the
 * real API in every case.
 */

const iso = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`

const shiftDays = (days: number) => {
  const date = new Date()
  date.setDate(date.getDate() + days)
  return date
}

async function expenseCategoryId(page: Page) {
  const categories = await (await pageGet(page, '/categories?type=EXPENSE')).json()
  return categories.find((category: { name: string }) => category.name === 'Assinaturas').id
}

async function createAccount(page: Page, name: string, currency: string, openingBalance: number) {
  const response = await pagePost(page, '/accounts', {
    name: `${name} ${Date.now()}`,
    type: 'CHECKING',
    openingBalance,
    currency,
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return (await response.json()).id as number
}

/** A commitment already two days overdue, in the account's own currency. */
async function overdueCommitment(
  page: Page,
  description: string,
  amount: number,
  currency: string,
  accountId: number,
) {
  const due = shiftDays(-2)
  const start = new Date(due)
  start.setMonth(start.getMonth() - 2)
  const response = await pagePost(page, '/commitments', {
    description,
    amount,
    categoryId: await expenseCategoryId(page),
    cadence: 'MONTHLY',
    dueDay: due.getDate(),
    startDate: iso(start),
    targetKind: 'ACCOUNT_TRANSACTION',
    accountId,
    currency,
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return (await response.json()).id as number
}

/** A commitment falling due soon and large enough to exhaust the account. */
async function upcomingCommitment(
  page: Page,
  description: string,
  amount: number,
  currency: string,
  accountId: number,
  inDays: number,
) {
  const due = shiftDays(inDays)
  const response = await pagePost(page, '/commitments', {
    description,
    amount,
    categoryId: await expenseCategoryId(page),
    cadence: 'MONTHLY',
    dueDay: due.getDate(),
    startDate: iso(new Date()),
    targetKind: 'ACCOUNT_TRANSACTION',
    accountId,
    currency,
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return (await response.json()).id as number
}

async function sync(page: Page) {
  const response = await pagePost(page, '/notifications/sync', {})
  expect(response.ok(), await response.text()).toBeTruthy()
}

async function setPreferences(page: Page, overrides: Record<string, unknown>) {
  const current = await (await pageGet(page, '/notification-preferences')).json()
  const response = await pagePut(page, '/notification-preferences', { ...current, ...overrides })
  expect(response.ok(), await response.text()).toBeTruthy()
}

async function inbox(page: Page) {
  const body = await (await pageGet(page, '/notifications?filter=ALL&size=100')).json()
  return body.content as Array<Record<string, unknown>>
}

const DRAWN_KEY = 'e2e:drawn-notifications'

/**
 * Replaces the browser Notification API before the app boots and records every
 * payload it is handed.
 *
 * <p>The record lives in `localStorage`, not on `window`. A claim is spent by
 * whichever page happens to synchronize first — often the dashboard the app
 * lands on after registering — and a per-page array would be wiped by the very
 * navigation that follows, losing the one delivery under test.
 */
async function stubNotificationApi(page: Page) {
  await page.addInitScript((key) => {
    class RecordingNotification {
      static permission = 'granted'
      static requestPermission = async () => 'granted'
      onclick: (() => void) | null = null
      constructor(title: string, options?: NotificationOptions) {
        const drawn = JSON.parse(window.localStorage.getItem(key) ?? '[]')
        drawn.push({ title, body: options?.body, tag: options?.tag })
        window.localStorage.setItem(key, JSON.stringify(drawn))
      }
      close() {
        /* no-op */
      }
    }
    Object.defineProperty(window, 'Notification', { value: RecordingNotification })
  }, DRAWN_KEY)
}

const drawnNotifications = (page: Page): Promise<Array<{ body?: string; tag?: string }>> =>
  page.evaluate((key) => JSON.parse(window.localStorage.getItem(key) ?? '[]'), DRAWN_KEY)

/* ---------- the inbox names the currency of its source ---------- */

test('a reais notification still reads exactly as it always did', async ({ page }) => {
  await registerViaUi(page)
  const account = await createAccount(page, 'Conta BRL', 'BRL', 1000)
  await overdueCommitment(page, 'Assinatura em reais', 120, 'BRL', account)
  await sync(page)

  await page.goto('/notifications')
  const item = page.locator('.notification-item', { hasText: 'Assinatura em reais' })
  await expect(item).toContainText('R$')
  await expect(item).toContainText('120,00')
})

test('a dollar commitment never wears a reais symbol', async ({ page }) => {
  await registerViaUi(page)
  const account = await createAccount(page, 'Conta USD', 'USD', 1000)
  await overdueCommitment(page, 'Assinatura em dólares', 80, 'USD', account)
  await sync(page)

  // The server is the authority, and it says USD.
  expect(await inbox(page)).toContainEqual(expect.objectContaining({ currency: 'USD' }))

  await page.goto('/notifications')
  const item = page.locator('.notification-item', { hasText: 'Assinatura em dólares' })
  await expect(item).toContainText('80,00')
  await expect(item).not.toContainText('R$')
  await expect(item).toContainText(/US\$|USD/)
})

test('a yen commitment is not given centavos it does not have', async ({ page }) => {
  await registerViaUi(page)
  const account = await createAccount(page, 'Conta JPY', 'JPY', 100000)
  await overdueCommitment(page, 'Assinatura em ienes', 3000, 'JPY', account)
  await sync(page)

  await page.goto('/notifications')
  const item = page.locator('.notification-item', { hasText: 'Assinatura em ienes' })
  await expect(item).toContainText('3.000')
  await expect(item).not.toContainText('3.000,00')
  await expect(item).not.toContainText('R$')
})

test('a dollar card invoice is announced in the card currency', async ({ page }) => {
  await registerViaUi(page)
  const account = await createAccount(page, 'Conta USD', 'USD', 5000)
  const card = await pagePost(page, '/credit-cards', {
    name: `Cartão USD ${Date.now()}`,
    brand: 'VISA',
    creditLimit: 5000,
    closingDay: shiftDays(-2).getDate(),
    dueDay: shiftDays(5).getDate(),
    currency: 'USD',
    defaultPaymentAccountId: account,
  })
  expect(card.ok(), await card.text()).toBeTruthy()
  const cardId = (await card.json()).id as number
  const purchase = await pagePost(page, `/credit-cards/${cardId}/purchases`, {
    description: 'Compra internacional',
    categoryId: await expenseCategoryId(page),
    purchaseDate: iso(shiftDays(-4)),
    totalAmount: 400,
    installmentCount: 1,
  })
  expect(purchase.ok(), await purchase.text()).toBeTruthy()

  await setPreferences(page, { upcomingLeadDays: 14 })
  await sync(page)

  const invoice = (await inbox(page)).find((row) => row.resourceType === 'CARD_INVOICE')
  expect(invoice, `nenhuma fatura notificada: ${JSON.stringify(await inbox(page))}`).toBeTruthy()
  expect(invoice!.currency).toBe('USD')

  await page.goto('/notifications')
  const item = page.locator('.notification-item', { hasText: 'Fatura' })
  await expect(item).not.toContainText('R$')
  await expect(item).toContainText(/US\$|USD/)
})

/* ---------- one cash risk per currency ---------- */

test('reais and dollars running dry raise two independent alerts', async ({ page }) => {
  await registerViaUi(page)
  const brl = await createAccount(page, 'Conta BRL', 'BRL', 100)
  const usd = await createAccount(page, 'Conta USD', 'USD', 50)
  const brlCommitment = await upcomingCommitment(page, 'Aluguel em reais', 500, 'BRL', brl, 3)
  await upcomingCommitment(page, 'Aluguel em dólares', 300, 'USD', usd, 4)
  await sync(page)

  const risks = (await inbox(page)).filter((row) => row.resourceType === 'FORECAST')
  expect(risks.map((risk) => risk.sourceKey).sort()).toEqual([
    'FORECAST:INSUFFICIENT_CASH:BRL',
    'FORECAST:INSUFFICIENT_CASH:USD',
  ])
  // Each states its own shortfall; nothing anywhere is the two added together.
  expect(risks.find((risk) => risk.currency === 'BRL')!.amount).toBe(-400)
  expect(risks.find((risk) => risk.currency === 'USD')!.amount).toBe(-250)

  // Both are visible, and each shows its own money.
  await page.goto('/notifications')
  const items = page.locator('.notification-item', { hasText: 'Saldo projetado' })
  await expect(items).toHaveCount(2)
  await expect(items.filter({ hasText: 'US$' })).toHaveCount(1)
  await expect(items.filter({ hasText: 'R$' })).toHaveCount(1)

  // Fixing the reais leaves the dollar risk exactly where it was.
  const paused = await pagePost(page, `/commitments/${brlCommitment}/pause`, {})
  expect(paused.ok(), await paused.text()).toBeTruthy()
  await sync(page)

  const after = (await inbox(page)).filter((row) => row.resourceType === 'FORECAST')
  expect(after.find((risk) => risk.currency === 'BRL')!.resolvedAt).not.toBeNull()
  expect(after.find((risk) => risk.currency === 'USD')!.resolvedAt).toBeNull()
})

test('a dollar-only owner with a reais base currency gets a dollar risk', async ({ page }) => {
  await registerViaUi(page)
  const usd = await createAccount(page, 'Conta USD', 'USD', 50)
  await upcomingCommitment(page, 'Aluguel em dólares', 300, 'USD', usd, 3)
  await sync(page)

  const risks = (await inbox(page)).filter((row) => row.resourceType === 'FORECAST')
  expect(risks).toHaveLength(1)
  expect(risks[0].currency).toBe('USD')
  expect(risks[0].sourceKey).toBe('FORECAST:INSUFFICIENT_CASH:USD')
})

/* ---------- browser claim privacy, against the real API ---------- */

test('a private delivery discloses neither the amount nor its currency', async ({ page }) => {
  await stubNotificationApi(page)
  await registerViaUi(page)
  const account = await createAccount(page, 'Conta USD', 'USD', 1000)
  // The baseline exists so enabling delivery never replays a backlog.
  await setPreferences(page, { browserEnabled: true, browserShowAmounts: false })
  await overdueCommitment(page, 'Assinatura em dólares', 80, 'USD', account)
  await sync(page)

  const claims = await (await pagePost(page, '/notifications/browser-claims', {})).json()
  expect(claims).toHaveLength(1)
  expect(claims[0].amount ?? null).toBeNull()
  expect(claims[0].currency ?? null).toBeNull()
  expect(claims[0].sourceKey).toBeUndefined()
  expect(JSON.stringify(claims)).not.toContain('USD')

  // The durable inbox is authenticated and keeps the whole truth.
  const durable = (await inbox(page))[0]
  expect(durable.amount).toBe(80)
  expect(durable.currency).toBe('USD')
  expect(durable.unread).toBe(true)
})

test('a disclosed dollar delivery is drawn as dollars', async ({ page }) => {
  await stubNotificationApi(page)
  await registerViaUi(page)
  const account = await createAccount(page, 'Conta USD', 'USD', 1000)
  await setPreferences(page, {
    browserEnabled: true,
    browserShowAmounts: true,
    browserMinimumSeverity: 'INFO',
  })
  await overdueCommitment(page, 'Assinatura em dólares', 80, 'USD', account)
  await sync(page)

  await page.goto('/notifications')
  await expect
    .poll(async () => (await drawnNotifications(page)).length, { timeout: 20_000 })
    .toBeGreaterThan(0)
  const drawn = await drawnNotifications(page)
  expect(drawn[0].body).not.toContain('R$')
  expect(drawn[0].body).toMatch(/US\$|USD/)
  expect(drawn[0].body).toContain('80,00')
  // De-duplication uses the row identity, never a currency-bearing source key.
  expect(drawn[0].tag).toMatch(/^\d+:\d+$/)
})

test('a private delivery draws the generic body and no denomination', async ({ page }) => {
  await stubNotificationApi(page)
  await registerViaUi(page)
  const account = await createAccount(page, 'Conta JPY', 'JPY', 100000)
  await setPreferences(page, {
    browserEnabled: true,
    browserShowAmounts: false,
    browserMinimumSeverity: 'INFO',
  })
  await overdueCommitment(page, 'Assinatura em ienes', 3000, 'JPY', account)
  await sync(page)

  await page.goto('/notifications')
  await expect
    .poll(async () => (await drawnNotifications(page)).length, { timeout: 20_000 })
    .toBeGreaterThan(0)
  const drawn = await drawnNotifications(page)
  expect(drawn[0].body).toBe('Abra o Finora para ver os detalhes.')
  expect(JSON.stringify(drawn[0])).not.toMatch(/JPY|JP¥|3\.000/)
})

/* ---------- isolation and lifecycle after currency-aware rendering ---------- */

test('a foreign owner cannot read a currency through another owner s notification', async ({
  page,
  request,
}) => {
  await registerViaUi(page)
  const account = await createAccount(page, 'Conta JPY', 'JPY', 100000)
  await overdueCommitment(page, 'Assinatura em ienes', 3000, 'JPY', account)
  await sync(page)
  const id = (await inbox(page))[0].id

  const other = await apiSession(request)
  const foreign = await request.post(`http://localhost:8080/api/notifications/${id}/read`, {
    headers: { 'X-XSRF-TOKEN': other.token },
  })
  expect(foreign.status()).toBe(404)
})

test('lifecycle actions still work on a foreign-currency notification', async ({ page }) => {
  await registerViaUi(page)
  const account = await createAccount(page, 'Conta USD', 'USD', 1000)
  await overdueCommitment(page, 'Assinatura em dólares', 80, 'USD', account)
  await sync(page)

  await page.goto('/notifications')
  await page.getByRole('button', { name: 'Marcar como lida' }).click()
  await expect(page.getByRole('button', { name: 'Marcar como não lida' })).toBeVisible()

  await page.getByRole('button', { name: /Adiar .* por um dia/ }).click()
  await page.getByRole('button', { name: 'Adiadas' }).click()
  const snoozed = page.locator('.notification-item', { hasText: 'Assinatura em dólares' })
  await expect(snoozed).toBeVisible()
  // The amount is still its own currency after every lifecycle action.
  await expect(snoozed).not.toContainText('R$')
  await expect(snoozed).toContainText(/US\$|USD/)
})
