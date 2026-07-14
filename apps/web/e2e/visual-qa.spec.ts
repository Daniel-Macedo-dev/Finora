import { expect, test, type Page } from '@playwright/test'
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
  { name: 'laptop-1024', width: 1024, height: 768 },
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
  await page.request.post(`${API}/wishlist/${item.id}/options`, {
    headers, data: { merchant: 'Loja TechPreço', kind: 'CASH', basePrice: 4650, shipping: 45 },
  })
  await page.request.post(`${API}/wishlist/${item.id}/options`, {
    headers, data: { merchant: 'MegaStore', kind: 'INSTALLMENT', basePrice: 5100, installmentCount: 10, installmentAmount: 510 },
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
  return { itemId: item.id as number, cardId: card.id as number, invoiceId: invoices[0].id as number }
}

async function capture(page: Page, path: string, name: string, viewport: (typeof VIEWPORTS)[0]) {
  await page.setViewportSize({ width: viewport.width, height: viewport.height })
  await page.goto(path)
  await page.waitForLoadState('networkidle')
  await page.screenshot({ path: `${OUT}/${viewport.name}/${name}.png`, fullPage: true })
}

test('captura estados principais em todos os viewports', async ({ page }) => {
  test.setTimeout(480_000)
  await registerViaUi(page)
  const { itemId, cardId, invoiceId } = await seedDemoData(page)

  const pages: Array<[string, string]> = [
    ['/dashboard', 'dashboard'],
    ['/transactions', 'transactions'],
    ['/credit-cards', 'credit-cards'],
    [`/credit-cards/${cardId}`, 'credit-card-detail'],
    [`/credit-cards/${cardId}/invoices/${invoiceId}`, 'invoice-detail'],
    ['/budgets', 'budgets'],
    ['/commitments', 'commitments'],
    ['/goals', 'goals'],
    ['/wishlist', 'wishlist'],
    [`/wishlist/${itemId}`, 'wishlist-detail'],
    ['/settings', 'settings'],
    ['/profile', 'profile'],
  ]
  for (const viewport of VIEWPORTS) {
    for (const [path, name] of pages) {
      await capture(page, path, name, viewport)
    }
  }

  // Dark theme (while still authenticated): dashboard and card screens.
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.addInitScript(() => localStorage.setItem('finora.theme', 'dark'))
  await capture(page, '/dashboard', 'dashboard-dark', VIEWPORTS[0])
  await capture(page, `/credit-cards/${cardId}`, 'credit-card-detail-dark', VIEWPORTS[0])
  await capture(page, `/credit-cards/${cardId}/invoices/${invoiceId}`, 'invoice-detail-dark', VIEWPORTS[0])

  // Auth screens (public).
  await page.getByRole('button', { name: 'Sair da conta' }).click().catch(() => {})
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
