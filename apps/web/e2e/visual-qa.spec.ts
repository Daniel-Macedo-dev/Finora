import { execSync } from 'node:child_process'
import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
} from '@playwright/test'
import { registerViaUi } from './helpers.ts'

/**
 * Visual QA capture — not a regression test. Run explicitly with:
 *   VISUAL_QA=1 npx playwright test e2e/visual-qa.spec.ts
 * Screenshots land in qa-screenshots/ (gitignored). Data is seeded through
 * the authenticated page's own request context (shares session + CSRF cookies).
 */
test.skip(!process.env.VISUAL_QA, 'Somente com VISUAL_QA=1')

const OUT = '../../qa-screenshots'
const API = 'http://localhost:8080/api'

const VIEWPORTS = [
  { name: 'desktop-1440', width: 1440, height: 900 },
  { name: 'desktop-1280', width: 1280, height: 800 },
  { name: 'tablet-768', width: 768, height: 1024 },
  { name: 'mobile-390', width: 390, height: 844 },
]

async function csrfHeader(page: Page): Promise<Record<string, string>> {
  const token = await page.evaluate(() => {
    const match = document.cookie.match(/XSRF-TOKEN=([^;]+)/)
    return match ? decodeURIComponent(match[1]) : ''
  })
  return token ? { 'X-XSRF-TOKEN': token } : {}
}

async function categoryId(page: Page, name: string, type: 'INCOME' | 'EXPENSE'): Promise<number> {
  const categories = await (await page.request.get(`${API}/categories?type=${type}`)).json()
  return (categories as Array<{ id: number; name: string }>).find((c) => c.name === name)!.id
}

async function seedDemoData(page: Page) {
  const headers = await csrfHeader(page)
  await page.request.put(`${API}/settings`, {
    headers,
    data: {
      minimumCashBuffer: 2000,
      maxInstallmentCommitmentRatio: 0.3,
      monthlyOpportunityRate: 0.008,
      budgetWarningThreshold: 0.8,
    },
  })
  await page.request.post(`${API}/accounts`, {
    headers,
    data: { name: 'Conta principal', type: 'CHECKING', openingBalance: 8500 },
  })

  const salario = await categoryId(page, 'Salário', 'INCOME')
  const moradia = await categoryId(page, 'Moradia', 'EXPENSE')
  const alimentacao = await categoryId(page, 'Alimentação', 'EXPENSE')
  const transporte = await categoryId(page, 'Transporte', 'EXPENSE')
  const lazer = await categoryId(page, 'Lazer', 'EXPENSE')
  const assinaturas = await categoryId(page, 'Assinaturas', 'EXPENSE')

  const now = new Date()
  for (let i = 3; i >= 0; i--) {
    const month = new Date(now.getFullYear(), now.getMonth() - i, 1)
    const key = `${month.getFullYear()}-${String(month.getMonth() + 1).padStart(2, '0')}`
    const tx = (data: object) => page.request.post(`${API}/transactions`, { headers, data })
    await tx({ type: 'INCOME', amount: 6200, description: 'Salário', date: `${key}-05`, categoryId: salario })
    await tx({ type: 'EXPENSE', amount: 1800, description: 'Aluguel', date: `${key}-10`, categoryId: moradia })
    await tx({ type: 'EXPENSE', amount: 950 + i * 60, description: 'Supermercado', date: `${key}-12`, categoryId: alimentacao })
    await tx({ type: 'EXPENSE', amount: 320, description: 'Combustível', date: `${key}-15`, categoryId: transporte })
    await tx({ type: 'EXPENSE', amount: 260, description: 'Lazer', date: `${key}-20`, categoryId: lazer })
  }

  const monthKey = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
  await page.request.post(`${API}/budgets`, { headers, data: { month: monthKey, categoryId: alimentacao, limitAmount: 1100 } })
  await page.request.post(`${API}/budgets`, { headers, data: { month: monthKey, categoryId: lazer, limitAmount: 300 } })
  await page.request.post(`${API}/commitments`, {
    headers,
    data: { description: 'Streaming', amount: 55.9, categoryId: assinaturas, cadence: 'MONTHLY', dueDay: 8, startDate: '2025-01-08' },
  })
  await page.request.post(`${API}/goals`, { headers, data: { name: 'Reserva de emergência', targetAmount: 20000, currentAmount: 8500 } })

  const item = await (await page.request.post(`${API}/wishlist`, {
    headers,
    data: { name: 'Notebook para trabalho', priority: 'HIGH', status: 'MONITORING', referencePrice: 5200, targetPrice: 4600 },
  })).json()
  const cashOption = await (await page.request.post(`${API}/wishlist/${item.id}/options`, {
    headers, data: { merchant: 'Loja TechPreço', kind: 'CASH', basePrice: 4650, shipping: 45 },
  })).json()
  await page.request.post(`${API}/wishlist/${item.id}/options`, {
    headers, data: { merchant: 'MegaStore', kind: 'INSTALLMENT', basePrice: 5100, installmentCount: 10, installmentAmount: 510 },
  })
  const emptyItem = await (await page.request.post(`${API}/wishlist`, {
    headers,
    data: { name: 'Monitor sem observações', priority: 'MEDIUM', status: 'MONITORING', targetPrice: 1200 },
  })).json()
  const observation = (merchant: string, basePrice: number, observedOn: string, extra: object = {}) =>
    page.request.post(`${API}/wishlist/${item.id}/price-snapshots`, {
      headers,
      data: {
        clientRequestId: crypto.randomUUID(), merchant, paymentKind: 'CASH', basePrice,
        shipping: 0, fees: 0, observedOn, updateLinkedOption: false, ...extra,
      },
    })
  await observation('Loja com um nome excepcionalmente longo para validar o layout responsivo', 4890, '2026-04-10', {
    offerUrl: 'https://example.test/oferta-segura',
    notes: 'Observação extensa para validar quebra de linha, leitura e ações sem ocultar conteúdo importante.',
  })
  await observation('Loja TechPreço', 4750, '2026-05-10', { purchaseOptionId: cashOption.id })
  await observation('Loja TechPreço', 4450, '2026-06-10', { purchaseOptionId: cashOption.id })
  await observation('Oferta temporária', 4680, '2026-07-10')

  // Recurring definitions: income, account expense driving the forecast
  // negative, and a card target whose materialization fails (limit exceeded)
  // so the failed-occurrence state is capturable.
  const isoFromToday = (days: number) => {
    const date = new Date()
    date.setDate(date.getDate() + days)
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(
      date.getDate(),
    ).padStart(2, '0')}`
  }
  const recurring = (data: object) =>
    page.request.post(`${API}/commitments`, { headers, data })
  const accounts0 = await (await page.request.get(`${API}/accounts`)).json()
  await recurring({
    description: 'Salário CLT', amount: 6200, categoryId: salario, cadence: 'MONTHLY',
    dueDay: 5, startDate: '2025-01-05', executionMode: 'MANUAL',
    targetKind: 'ACCOUNT_TRANSACTION', accountId: accounts0[0].id, installmentCount: 1,
  })
  await recurring({
    description: 'Aluguel do escritório', amount: 15000, categoryId: moradia,
    cadence: 'MONTHLY', dueDay: 28, startDate: isoFromToday(10),
    executionMode: 'MANUAL', targetKind: 'ACCOUNT_TRANSACTION',
    accountId: accounts0[0].id, installmentCount: 1,
  })
  const failingCard = await (await page.request.post(`${API}/credit-cards`, {
    headers,
    data: { name: 'Cartão Apertado', brand: 'VISA', creditLimit: 100, closingDay: 5, dueDay: 12 },
  })).json()
  const failing = await (await recurring({
    description: 'Assinatura anual cara', amount: 900, categoryId: assinaturas,
    cadence: 'WEEKLY', dueDay: null, startDate: isoFromToday(-7),
    executionMode: 'MANUAL', targetKind: 'CREDIT_CARD_PURCHASE',
    creditCardId: failingCard.id, installmentCount: 1,
  })).json()
  await page.request.post(
    `${API}/commitments/${failing.id}/occurrences/${isoFromToday(-7)}/materialize`,
    { headers },
  )

  // Legacy-credit inventory: eligible sources, one converted, one reversed —
  // forged exactly like migration V7 (legacy rows cannot be created by API).
  // The container is the compose one locally or a service container on CI.
  const pgContainer = (() => {
    if (process.env.FINORA_PG_CONTAINER) {
      return process.env.FINORA_PG_CONTAINER
    }
    try {
      execSync('docker inspect finora-postgres', { stdio: 'ignore' })
      return 'finora-postgres'
    } catch {
      return execSync('docker ps -q --filter "ancestor=postgres:16.6-alpine"')
        .toString()
        .trim()
        .split('\n')[0]
    }
  })()
  const forgeLegacy = (id: number) =>
    execSync(
      `docker exec ${pgContainer} psql -U finora -d finora -c ` +
        `"UPDATE transactions SET payment_method = 'CREDIT', legacy_credit = TRUE WHERE id = ${id}"`,
    )
  const compras0 = await categoryId(page, 'Compras', 'EXPENSE')
  const legacyTx = async (amount: number, date: string, description: string) => {
    const created = await (
      await page.request.post(`${API}/transactions`, {
        headers,
        data: { type: 'EXPENSE', amount, description, date, categoryId: compras0 },
      })
    ).json()
    forgeLegacy(created.id)
    return created.id as number
  }
  await legacyTx(890.5, '2025-09-14', 'Geladeira antiga no crédito')
  await legacyTx(129.99, '2025-10-02', 'Tênis parcelado antigo')
  const convertedLegacy = await legacyTx(300, '2025-11-20', 'Micro-ondas antigo')
  const reversedLegacy = await legacyTx(150, '2025-08-05', 'Assinatura anual antiga')
  const legacyCard = await (await page.request.post(`${API}/credit-cards`, {
    headers,
    data: { name: 'Cartão Migração', brand: 'VISA', creditLimit: 8000, closingDay: 10, dueDay: 17 },
  })).json()
  const convertLegacy = (transactionId: number, firstInvoiceMonth: string, date: string) =>
    page.request.post(`${API}/legacy-conversions`, {
      headers,
      data: {
        transactionId,
        cardId: legacyCard.id,
        effectivePurchaseDate: date,
        installmentCount: 3,
        firstInvoiceMonth,
      },
    })
  await convertLegacy(convertedLegacy, '2025-12', '2025-11-20')
  const toReverse = await (await convertLegacy(reversedLegacy, '2025-09', '2025-08-05')).json()
  await page.request.post(`${API}/legacy-conversions/${toReverse.id}/reverse`, {
    headers,
    data: { reason: 'Cartão errado' },
  })
  // A legacy CREDIT recurring definition awaiting card mapping.
  const assinaturas0 = await categoryId(page, 'Assinaturas', 'EXPENSE')
  await page.request.post(`${API}/commitments`, {
    headers,
    data: {
      description: 'TV a cabo antiga', amount: 89.9, categoryId: assinaturas0,
      cadence: 'MONTHLY', dueDay: 3, startDate: '2025-02-03', paymentMethod: 'CREDIT',
    },
  })

  // Credit card with a mixed invoice history: paid, partially paid and open.
  const compras = await categoryId(page, 'Compras', 'EXPENSE')
  const card = await (await page.request.post(`${API}/credit-cards`, {
    headers,
    data: { name: 'Cartão Roxinho', issuer: 'Nu Pagamentos', brand: 'MASTERCARD',
            lastFourDigits: '4242', creditLimit: 6000, closingDay: 10, dueDay: 17 },
  })).json()
  const purchase = (data: object) =>
    page.request.post(`${API}/credit-cards/${card.id}/purchases`, { headers, data })
  await purchase({ description: 'Notebook parcelado', merchant: 'MegaStore', categoryId: compras,
    purchaseDate: '2031-03-05', totalAmount: 3100.01, installmentCount: 10 })
  await purchase({ description: 'Fone de ouvido', merchant: 'TechShop', categoryId: compras,
    purchaseDate: '2031-03-06', totalAmount: 349.9, installmentCount: 1 })
  const accounts = await (await page.request.get(`${API}/accounts`)).json()
  const invoices = await (await page.request.get(`${API}/credit-cards/${card.id}/invoices`)).json()
  await page.request.post(`${API}/credit-cards/${card.id}/invoices/${invoices[0].id}/payments`, {
    headers,
    data: { accountId: accounts[0].id, amount: 200, paidOn: '2031-03-15' },
  })
  await page.request.post(`${API}/notifications/sync`, { headers })
  return {
    itemId: item.id as number,
    emptyItemId: emptyItem.id as number,
    cardId: card.id as number,
    invoiceId: invoices[0].id as number,
    legacyCardId: legacyCard.id as number,
  }
}

async function capture(page: Page, path: string, name: string, viewport: (typeof VIEWPORTS)[0]) {
  await page.setViewportSize({ width: viewport.width, height: viewport.height })
  await page.goto(path)
  await page.waitForLoadState('networkidle')
  await page.screenshot({ path: `${OUT}/${viewport.name}/${name}.png`, fullPage: true })
}

test('captura estados principais em todos os viewports', async ({ page }) => {
  test.setTimeout(1_200_000)
  await page.addInitScript(() => {
    const state = localStorage.getItem('visual.notification.permission')
    if (state !== 'granted' && state !== 'denied') return
    class VisualNotification {
      static permission = state
      static requestPermission = async () => state
      onclick: (() => void) | null = null
      constructor(_title: string, _options?: NotificationOptions) { /* visual stub */ }
      close() { /* visual stub */ }
    }
    Object.defineProperty(window, 'Notification', { configurable: true, value: VisualNotification })
  })
  await registerViaUi(page)
  const { itemId, emptyItemId, cardId, invoiceId, legacyCardId } = await seedDemoData(page)

  const pages: Array<[string, string]> = [
    ['/dashboard', 'dashboard'],
    ['/transactions', 'transactions'],
    ['/legacy-credit', 'legacy-credit'],
    ['/credit-cards', 'credit-cards'],
    [`/credit-cards/${cardId}`, 'credit-card-detail'],
    [`/credit-cards/${cardId}/invoices/${invoiceId}`, 'invoice-detail'],
    ['/budgets', 'budgets'],
    ['/commitments', 'commitments'],
    ['/forecast', 'forecast'],
    ['/goals', 'goals'],
    ['/wishlist', 'wishlist'],
    [`/wishlist/${itemId}`, 'wishlist-detail'],
    ['/settings', 'settings'],
    ['/notifications', 'notifications'],
    ['/profile', 'profile'],
  ]
  for (const viewport of VIEWPORTS) {
    for (const [path, name] of pages) {
      await capture(page, path, name, viewport)
    }
  }

  // Notification-specific responsive/light/dark states, including the panel.
  for (const theme of ['light', 'dark'] as const) {
    await page.emulateMedia({ colorScheme: theme })
    await page.evaluate((value) => localStorage.setItem('finora.theme', value), theme)
    for (const viewport of VIEWPORTS) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await page.goto('/notifications')
      await page.waitForLoadState('networkidle')
      await page.screenshot({ path: `${OUT}/${viewport.name}/notifications-${theme}.png`, fullPage: true })
      await page.getByRole('button', { name: /Notificações:/ }).click()
      await page.screenshot({ path: `${OUT}/${viewport.name}/notification-panel-${theme}.png`, fullPage: true })
      await page.keyboard.press('Escape')
      await page.goto('/settings')
      await page.waitForLoadState('networkidle')
      await page.screenshot({ path: `${OUT}/${viewport.name}/notification-settings-${theme}.png`, fullPage: true })
      for (const permission of ['denied', 'granted'] as const) {
        await page.evaluate((value) => localStorage.setItem('visual.notification.permission', value), permission)
        await page.reload()
        await page.waitForLoadState('networkidle')
        await page.screenshot({
          path: `${OUT}/${viewport.name}/notification-settings-${permission}-${theme}.png`,
          fullPage: true,
        })
      }
    }
  }
  await page.evaluate(() => localStorage.removeItem('visual.notification.permission'))

  // Recurring dialogs: form, occurrence history, failed occurrence and the
  // reschedule flow — desktop and mobile.
  for (const viewport of [VIEWPORTS[0], VIEWPORTS[3]]) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await page.goto('/commitments')
    await page.waitForLoadState('networkidle')

    await page.getByRole('button', { name: 'Novo recorrente' }).first().click()
    await page.screenshot({ path: `${OUT}/${viewport.name}/commitment-form.png`, fullPage: true })
    await page.keyboard.press('Escape')

    await page.getByRole('button', { name: 'Ocorrências de Salário CLT' }).click()
    await page.waitForLoadState('networkidle')
    await page.screenshot({ path: `${OUT}/${viewport.name}/occurrences.png`, fullPage: true })
    await page.getByRole('button', { name: 'Reagendar' }).first().click()
    await page.screenshot({ path: `${OUT}/${viewport.name}/occurrence-reschedule.png`, fullPage: true })
    await page.keyboard.press('Escape')
    await page.keyboard.press('Escape')

    await page.getByRole('button', { name: 'Ocorrências de Assinatura anual cara' }).click()
    await page.waitForLoadState('networkidle')
    await page.screenshot({ path: `${OUT}/${viewport.name}/occurrence-failed.png`, fullPage: true })
    await page.keyboard.press('Escape')
  }

  // Legacy-conversion dialogs: wizard steps, conversion detail, batch and
  // recurring mapping — desktop and mobile.
  for (const viewport of [VIEWPORTS[0], VIEWPORTS[3]]) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await page.goto('/legacy-credit')
    await page.waitForLoadState('networkidle')

    await page.getByRole('button', { name: 'Converter', exact: true }).first().click()
    await page.screenshot({ path: `${OUT}/${viewport.name}/legacy-wizard-source.png`, fullPage: true })
    await page.getByRole('button', { name: 'Avançar' }).click()
    await page.getByLabel('Cartão que receberá a compra').selectOption(String(legacyCardId))
    await page.getByLabel('Número de parcelas').fill('3')
    await page.screenshot({ path: `${OUT}/${viewport.name}/legacy-wizard-card.png`, fullPage: true })
    await page.getByRole('button', { name: 'Avançar' }).click()
    await page.getByText('Situação da fatura').first().waitFor({ state: 'attached' })
    await page.screenshot({ path: `${OUT}/${viewport.name}/legacy-wizard-schedule.png`, fullPage: true })
    await page.getByRole('button', { name: 'Avançar' }).click()
    await page.screenshot({ path: `${OUT}/${viewport.name}/legacy-wizard-impact.png`, fullPage: true })
    await page.getByRole('button', { name: 'Avançar' }).click()
    await page.screenshot({ path: `${OUT}/${viewport.name}/legacy-wizard-confirm.png`, fullPage: true })
    await page.keyboard.press('Escape')

    await page.getByRole('button', { name: 'Ver conversão' }).first().click()
    await page.waitForLoadState('networkidle')
    await page.screenshot({ path: `${OUT}/${viewport.name}/legacy-detail.png`, fullPage: true })
    await page.keyboard.press('Escape')

    await page.getByLabel(/Selecionar Geladeira antiga/).check()
    await page.getByLabel(/Selecionar Tênis parcelado/).check()
    await page.getByRole('button', { name: 'Converter selecionadas' }).click()
    await page.screenshot({ path: `${OUT}/${viewport.name}/legacy-batch.png`, fullPage: true })
    await page.keyboard.press('Escape')

    await page.goto('/commitments')
    await page.waitForLoadState('networkidle')
    await page.getByRole('button', { name: /Crédito legado — migrar para cartão/ }).click()
    await page.screenshot({ path: `${OUT}/${viewport.name}/legacy-mapping.png`, fullPage: true })
    await page.keyboard.press('Escape')
  }

  // Dark theme (while still authenticated): dashboard, cards, recurring and
  // forecast screens.
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.addInitScript(() => localStorage.setItem('finora.theme', 'dark'))
  await capture(page, '/dashboard', 'dashboard-dark', VIEWPORTS[0])
  await capture(page, '/legacy-credit', 'legacy-credit-dark', VIEWPORTS[0])
  await page.getByRole('button', { name: 'Ver conversão' }).first().click()
  await page.waitForLoadState('networkidle')
  await page.screenshot({ path: `${OUT}/${VIEWPORTS[0].name}/legacy-detail-dark.png`, fullPage: true })
  await page.keyboard.press('Escape')
  await capture(page, `/credit-cards/${cardId}`, 'credit-card-detail-dark', VIEWPORTS[0])
  await capture(page, `/credit-cards/${cardId}/invoices/${invoiceId}`, 'invoice-detail-dark', VIEWPORTS[0])
  await capture(page, '/commitments', 'commitments-dark', VIEWPORTS[0])
  await capture(page, '/forecast', 'forecast-dark', VIEWPORTS[0])

  // Price-history states at every required viewport in light and dark themes.
  for (const theme of ['light', 'dark'] as const) {
    await page.emulateMedia({ colorScheme: theme })
    await page.evaluate((value) => localStorage.setItem('finora.theme', value), theme)
    for (const viewport of VIEWPORTS) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await page.goto(`/wishlist/${itemId}`)
      await page.waitForLoadState('networkidle')
      await page.evaluate((value) => { document.documentElement.dataset.theme = value }, theme)
      await page.getByRole('heading', { name: 'Histórico de preços' }).scrollIntoViewIfNeeded()
      await page.screenshot({ path: `${OUT}/${viewport.name}/price-history-${theme}.png`, fullPage: true })

      await page.getByRole('button', { name: 'Registrar preço', exact: true }).click()
      await page.screenshot({ path: `${OUT}/${viewport.name}/price-history-manual-${theme}.png`, fullPage: true })
      await page.keyboard.press('Escape')
      await page.getByRole('button', { name: 'Registrar preço atual' }).first().click()
      await page.screenshot({ path: `${OUT}/${viewport.name}/price-history-capture-${theme}.png`, fullPage: true })
      await page.keyboard.press('Escape')
      await page.getByRole('button', { name: /Editar observação de/ }).first().click()
      await page.screenshot({ path: `${OUT}/${viewport.name}/price-history-edit-${theme}.png`, fullPage: true })
      await page.keyboard.press('Escape')
      await page.getByRole('button', { name: /Excluir observação de/ }).first().click()
      await page.screenshot({ path: `${OUT}/${viewport.name}/price-history-delete-${theme}.png`, fullPage: true })
      await page.keyboard.press('Escape')

      await page.goto(`/wishlist/${emptyItemId}`)
      await page.waitForLoadState('networkidle')
      await page.evaluate((value) => { document.documentElement.dataset.theme = value }, theme)
      await page.screenshot({ path: `${OUT}/${viewport.name}/price-history-empty-${theme}.png`, fullPage: true })
    }
  }

  // Auth screens (public).
  await page.request.post(`${API}/auth/logout`, { headers: await csrfHeader(page) })
  await capture(page, '/login', 'login-dark', VIEWPORTS[0])
  await page.emulateMedia({ colorScheme: 'light' })
  await page.addInitScript(() => localStorage.setItem('finora.theme', 'light'))
  await capture(page, '/login', 'login', VIEWPORTS[0])
  await capture(page, '/login', 'login-mobile', VIEWPORTS[3])
  await capture(page, '/register', 'register', VIEWPORTS[0])
  await capture(page, '/register', 'register-mobile', VIEWPORTS[3])

  expect(itemId).toBeGreaterThan(0)
  expect(cardId).toBeGreaterThan(0)
})

/* ---------- offline synchronization ---------- */

/**
 * Every state the synchronization centre and its destructive dialogs can be in,
 * at four widths in both themes.
 *
 * Split into four independently rerunnable groups per theme rather than one
 * long pass. The single test this replaces took half an hour, and any locator
 * that matched nothing spent the whole budget waiting: a run that had captured
 * six states was indistinguishable from one still working, and rerunning the
 * conflict states meant rebuilding the ten before them. Each group now owns its
 * own account and its own browser context, so a failure names one group and
 * costs one group.
 */

const OFFLINE_PASSWORD = 'senha-offline-visual-qa'
const THEMES = ['light', 'dark'] as const
type Theme = (typeof THEMES)[number]

/**
 * Photographs one state at every width.
 *
 * Nothing is shot on faith. The theme is read off the rendered document rather
 * than trusted from the preference that was stored, the width is read back from
 * the window rather than assumed from the resize, the state's own marker must
 * be on screen, and no skeleton may remain — a frame of a loading placeholder
 * is worse than a missing frame, because it still lands in the folder looking
 * like evidence. Animations are awaited rather than slept through, so a capture
 * never catches a dialog or a drawer halfway.
 */
async function captureState(page: Page, state: string, theme: Theme, ready: Locator): Promise<void> {
  for (const viewport of VIEWPORTS) {
    const where = `${state} @ ${viewport.name} / ${theme}`
    await page.setViewportSize({ width: viewport.width, height: viewport.height })

    await expect(page.locator('html'), where).toHaveAttribute('data-theme', theme)
    await expect(ready.first(), where).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('.skeleton'), where).toHaveCount(0, { timeout: 15_000 })
    expect(await page.evaluate(() => window.innerWidth), where).toBe(viewport.width)

    await page.evaluate(async () => {
      await document.fonts.ready
      // Bounded: an infinite animation would otherwise hold the capture open
      // for as long as the test is allowed to run.
      await Promise.race([
        Promise.all(document.getAnimations().map((animation) => animation.finished.catch(() => undefined))),
        new Promise((resolve) => setTimeout(resolve, 1_500)),
      ])
    })

    // Checked rather than looked for. Sideways scroll is the defect a thumbnail
    // hides best — a contact sheet of a 390px frame looks identical whether the
    // content fits or runs 40px past the edge — and asking the layout directly
    // covers every state, width and theme instead of the ones someone zoomed in
    // on.
    const overflow = await page.evaluate(() => {
      const root = document.documentElement
      const dialog = document.querySelector('dialog[open]')
      return {
        page: root.scrollWidth - root.clientWidth,
        dialog: dialog ? dialog.scrollWidth - dialog.clientWidth : 0,
      }
    })
    expect(overflow.page, `rolagem horizontal da página em ${where}`).toBeLessThanOrEqual(1)
    expect(overflow.dialog, `rolagem horizontal do diálogo em ${where}`).toBeLessThanOrEqual(1)

    await page.screenshot({
      path: `${OUT}/${viewport.name}/sync-${state}-${theme}.png`,
      fullPage: true,
    })
  }

  // Back to the widest before anything else is driven. VIEWPORTS ends at 390px,
  // where the sign-out control lives inside a closed drawer and simply is not
  // on screen for the next step to click.
  await page.setViewportSize({ width: VIEWPORTS[0].width, height: VIEWPORTS[0].height })
  expect(
    await page.evaluate(() => window.innerWidth),
    `viewport restaurado após ${state}`,
  ).toBe(VIEWPORTS[0].width)
}

/**
 * A context pinned to one theme.
 *
 * Both signals are set: the stored preference decides, and the emulated OS
 * scheme agrees with it so nothing rendered from a media query disagrees with
 * the rest of the page. Reduced motion is emulated too — the application
 * honours it by collapsing every transition, which is what makes a capture
 * reproducible instead of a race against an easing curve.
 */
async function themedContext(browser: Browser, theme: Theme): Promise<BrowserContext> {
  const context = await browser.newContext({
    locale: 'pt-BR',
    colorScheme: theme,
    reducedMotion: 'reduce',
    viewport: { width: VIEWPORTS[0].width, height: VIEWPORTS[0].height },
  })
  await context.addInitScript((value) => {
    localStorage.setItem('finora.theme', value as string)
  }, theme)
  return context
}

/** Unlocks through whichever form this screen offers, or does nothing. */
async function unlockIfAsked(page: Page): Promise<void> {
  const offlineScreen = page.getByRole('heading', { name: 'Desbloquear dados offline' })
  const shell = page.locator('#main-content')
  await Promise.race([
    offlineScreen.waitFor({ state: 'visible', timeout: 20_000 }).catch(() => undefined),
    shell.waitFor({ state: 'visible', timeout: 20_000 }).catch(() => undefined),
  ])
  if (await offlineScreen.isVisible().catch(() => false)) {
    await page.getByLabel('Senha offline').fill(OFFLINE_PASSWORD)
    await page.getByRole('button', { name: 'Desbloquear', exact: true }).click()
    await expect(offlineScreen).toBeHidden({ timeout: 20_000 })
    return
  }
  const inPlace = page.getByRole('button', { name: 'Desbloquear cópia offline' })
  await inPlace.waitFor({ state: 'visible', timeout: 3_000 }).catch(() => undefined)
  if (await inPlace.isVisible().catch(() => false)) {
    await page.getByLabel('Senha offline').fill(OFFLINE_PASSWORD)
    await inPlace.click()
    await expect(inPlace).toBeHidden({ timeout: 20_000 })
  }
}

/**
 * Navigates and unlocks again when the load asks for it.
 *
 * The key lives only in memory, so every full navigation drops it. That is the
 * product's whole point, and it means a capture pass walking between screens
 * re-enters the offline password each time.
 */
async function visitUnlocked(page: Page, path: string): Promise<void> {
  await page.goto(path)
  await unlockIfAsked(page)
}

/** Registers, puts one row on the server, and takes the offline copy. */
async function seedOfflineAccount(page: Page): Promise<void> {
  await registerViaUi(page)

  // The copy is a snapshot of this moment, so the row a conflict or a pending
  // deletion will act on has to exist before it is taken.
  await page.goto('/transactions')
  await page.getByRole('button', { name: 'Nova transação' }).first().click()
  await page.getByLabel('Descrição', { exact: true }).fill('Disputada no servidor')
  await page.getByLabel('Valor (R$)', { exact: true }).fill('10,00')
  await page.getByLabel('Categoria', { exact: true }).selectOption({ index: 1 })
  await page.getByRole('button', { name: /Adicionar transação|Salvar/ }).first().click()
  await expect(page.getByText('Disputada no servidor')).toBeVisible()

  await page.goto('/settings')
  await page.getByLabel('Senha offline (mínimo de 12 caracteres)').fill(OFFLINE_PASSWORD)
  await page.getByLabel('Confirmar senha offline').fill(OFFLINE_PASSWORD)
  await page.getByRole('button', { name: 'Ativar acesso offline neste dispositivo' }).click()
  await expect(page.getByText('Acesso offline ativado neste dispositivo.')).toBeVisible({
    timeout: 20_000,
  })
}

/** Queues one creation from the transactions screen, offline. */
async function queueTransaction(page: Page, description: string, amount: string): Promise<void> {
  await page.getByRole('button', { name: 'Nova transação' }).first().click()
  await page.getByLabel('Descrição', { exact: true }).fill(description)
  await page.getByLabel('Valor (R$)', { exact: true }).fill(amount)
  await page.getByLabel('Categoria', { exact: true }).selectOption({ index: 1 })
  await page.getByRole('button', { name: /Adicionar transação|Salvar/ }).first().click()
  await expect(page.getByText('Criado offline').first()).toBeVisible({ timeout: 20_000 })
}

/**
 * Turns automatic replay off through the real settings control.
 *
 * The failure and conflict states have to hold still to be photographed. Left
 * on, the retry cycles every row back through "Sincronizando" seconds after it
 * lands and the capture races it.
 */
async function setAutoReplay(page: Page, enabled: boolean): Promise<void> {
  await visitUnlocked(page, '/settings')
  const toggle = page.getByLabel('Sincronizar automaticamente ao reconectar')
  if ((await toggle.isChecked()) !== enabled) await toggle.click()
  await expect(toggle).toBeChecked({ checked: enabled, timeout: 20_000 })
}

/** Reloads into the state a reopened tab is always in: vault present, locked. */
async function reloadLocked(page: Page, path: string): Promise<void> {
  await page.goto(path)
  await expect(page.locator('#main-content')).toBeVisible({ timeout: 20_000 })
  await expect(page.getByRole('button', { name: 'Sair da conta' })).toBeEnabled({ timeout: 20_000 })
}

for (const theme of THEMES) {
  test(`sincronização offline: estados base e pendentes (${theme})`, async ({ browser }) => {
    test.setTimeout(300_000)
    const context = await themedContext(browser, theme)
    const page = await context.newPage()
    page.setDefaultTimeout(20_000)
    await seedOfflineAccount(page)

    // Empty centre, online and explicitly unlocked: the navigation itself drops
    // the key, and a locked centre is a different state captured further down.
    await visitUnlocked(page, '/offline-sync')
    await captureState(page, 'empty', theme, page.getByRole('heading', { name: 'Nenhuma alteração pendente' }))

    await context.setOffline(true)
    await visitUnlocked(page, '/transactions')
    await expect(page.getByRole('heading', { name: 'Transações' })).toBeVisible({ timeout: 20_000 })

    // A deliberately long description, so the row, the shell badge and the
    // mobile layout are all exercised at their worst case rather than their
    // tidiest one.
    await queueTransaction(
      page,
      'Compra de supermercado com um nome longo o suficiente para testar o recorte da linha',
      '142,55',
    )
    await captureState(page, 'pending-row', theme, page.getByText('Criado offline').first())

    await page.getByRole('button', { name: 'Excluir Disputada no servidor' }).click()
    await page
      .getByRole('dialog', { name: 'Excluir transação' })
      .getByRole('button', { name: 'Excluir', exact: true })
      .click()
    await captureState(page, 'pending-delete', theme, page.getByText('Exclusão pendente'))

    // A dependent chain: an item, then an option that can only name it locally.
    await visitUnlocked(page, '/wishlist')
    await page.getByRole('button', { name: 'Novo item' }).first().click()
    await page.getByLabel('Nome do item').fill('Notebook criado offline')
    await page.getByRole('button', { name: 'Adicionar item' }).click()
    await page.getByRole('link', { name: 'Notebook criado offline' }).first().click()
    await page.getByRole('button', { name: 'Nova opção' }).click()
    await page.getByLabel('Loja / vendedor').fill('Loja com nome bastante longo para o layout')
    await page.getByLabel('Preço à vista (R$)').fill('3500,00')
    await page.getByRole('button', { name: 'Adicionar opção' }).click()
    await captureState(page, 'local-item-option', theme, page.getByText('Criado offline').first())

    await visitUnlocked(page, '/offline-sync')
    await captureState(page, 'dependent-queue', theme, page.getByText('Aguardando outro item').first())

    await context.close()
  })

  test(`sincronização offline: estados de ações destrutivas (${theme})`, async ({ browser }) => {
    test.setTimeout(300_000)
    const context = await themedContext(browser, theme)
    const page = await context.newPage()
    page.setDefaultTimeout(20_000)
    await seedOfflineAccount(page)

    await context.setOffline(true)
    await visitUnlocked(page, '/transactions')
    await queueTransaction(page, 'Alteração presa na cópia local', '73,00')
    await setAutoReplay(page, false)
    await context.setOffline(false)

    // Locked, with encrypted work still queued inside it. This is the ordinary
    // state of a reopened tab, and the one the warnings below exist for.
    await reloadLocked(page, '/offline-sync')
    await captureState(
      page,
      'locked-with-pending',
      theme,
      page.getByRole('heading', { name: 'Cópia offline bloqueada' }),
    )

    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await captureState(
      page,
      'locked-logout-warning',
      theme,
      page.getByRole('heading', { name: 'A cópia offline está bloqueada' }),
    )

    await page.getByRole('button', { name: 'Descartar cópia e sair' }).click()
    await captureState(page, 'destructive-final', theme, page.getByText('Essa ação não pode ser desfeita.'))
    await page.getByRole('button', { name: 'Voltar' }).click()
    await page.getByRole('button', { name: 'Cancelar' }).click()

    // The same decision with the queue readable, which is the one case that can
    // state exact numbers.
    await unlockIfAsked(page)
    await expect(page.getByText('Alteração presa na cópia local', { exact: true })).toBeVisible({
      timeout: 20_000,
    })
    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await captureState(
      page,
      'logout-warning',
      theme,
      page.getByRole('heading', { name: 'Sair com alterações offline pendentes' }),
    )
    await page.getByRole('button', { name: 'Cancelar' }).click()

    await page.getByRole('button', { name: /^Descartar:/ }).first().click()
    await captureState(
      page,
      'discard-confirm',
      theme,
      page.getByRole('heading', { name: 'Descartar alteração offline' }),
    )

    await context.close()
  })

  test(`sincronização offline: estados de falha (${theme})`, async ({ browser }) => {
    test.setTimeout(300_000)
    const context = await themedContext(browser, theme)
    const page = await context.newPage()
    page.setDefaultTimeout(20_000)
    await seedOfflineAccount(page)

    await context.setOffline(true)
    await visitUnlocked(page, '/transactions')
    await queueTransaction(page, 'Recusada pelo servidor', '58,90')
    await setAutoReplay(page, false)
    await context.setOffline(false)

    // Order matters and is not cosmetic: a permanently rejected entry is no
    // longer sendable, so capturing it first would leave nothing for the
    // temporary failure to happen to.
    await context.route('**/api/offline-sync/mutations', (route) =>
      route.fulfill({ status: 503, body: '{}' }),
    )
    await visitUnlocked(page, '/offline-sync')
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    // Exact, because "Falha" is a prefix of "Falha temporária": a loose match
    // here is satisfied by the wrong badge and photographs the wrong state.
    await captureState(page, 'retryable-failure', theme, page.getByText('Falha temporária', { exact: true }))
    await context.unroute('**/api/offline-sync/mutations')

    // A permanent rejection carrying a long, actionable message rather than a
    // retry loop.
    await context.route('**/api/offline-sync/mutations', async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      body.results = body.results.map((result: Record<string, unknown>) => ({
        ...result,
        status: 'REJECTED',
        error: {
          code: 'CATEGORY_TYPE_MISMATCH',
          detail:
            'A categoria escolhida não aceita esse tipo de lançamento. Edite a alteração '
            + 'escolhendo uma categoria compatível ou descarte-a para manter o que está no servidor.',
        },
      }))
      await route.fulfill({ response, json: body })
    })
    await visitUnlocked(page, '/offline-sync')
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    await captureState(page, 'permanent-failure', theme, page.getByText('Falha', { exact: true }))
    await context.unroute('**/api/offline-sync/mutations')

    await context.close()
  })

  test(`sincronização offline: estados de conflito (${theme})`, async ({ browser }) => {
    test.setTimeout(300_000)
    const context = await themedContext(browser, theme)
    const page = await context.newPage()
    page.setDefaultTimeout(20_000)
    await seedOfflineAccount(page)

    // Offline first, then the toggle: online, a navigation drops the in-memory
    // key and only the sync centre offers a way back in, so the checkbox on the
    // settings screen is inert. Offline, the unlock screen appears on any route.
    await context.setOffline(true)
    await visitUnlocked(page, '/transactions')
    await setAutoReplay(page, false)

    // Edited offline, on a row the copy already contains.
    await visitUnlocked(page, '/transactions')
    await page.getByRole('button', { name: 'Editar Disputada no servidor' }).click()
    await page.getByLabel('Valor (R$)', { exact: true }).fill('25,00')
    await page.getByRole('button', { name: /Salvar/ }).first().click()
    await expect(page.getByText('Pendente').first()).toBeVisible({ timeout: 20_000 })
    await context.setOffline(false)

    await context.route('**/api/offline-sync/mutations', async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      body.results = body.results.map((result: Record<string, unknown>) => ({
        ...result,
        status: 'CONFLICT',
        conflict: {
          conflictType: 'VERSION_MISMATCH',
          localBaseVersion: 0,
          serverVersion: 3,
          serverSnapshot: { description: 'Disputada no servidor', amount: 80, date: '2026-07-30' },
          resolutionOptions: ['KEEP_SERVER', 'APPLY_LOCAL', 'EDIT_AND_RETRY', 'DISCARD_LOCAL'],
          detail: 'Esta transação foi alterada em outro dispositivo depois da sua edição offline.',
        },
      }))
      await route.fulfill({ response, json: body })
    })
    await visitUnlocked(page, '/offline-sync')
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    await captureState(page, 'conflict', theme, page.getByText('Conflito', { exact: true }))

    await page.getByRole('button', { name: /^Resolver conflito:/ }).first().click()
    await captureState(
      page,
      'conflict-comparison',
      theme,
      page.getByRole('columnheader', { name: 'Valor salvo no servidor' }),
    )
    await context.unroute('**/api/offline-sync/mutations')

    await context.close()
  })
}
