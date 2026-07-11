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
  return item.id as number
}

async function capture(page: Page, path: string, name: string, viewport: (typeof VIEWPORTS)[0]) {
  await page.setViewportSize({ width: viewport.width, height: viewport.height })
  await page.goto(path)
  await page.waitForLoadState('networkidle')
  await page.screenshot({ path: `${OUT}/${viewport.name}/${name}.png`, fullPage: true })
}

test('captura estados principais em todos os viewports', async ({ page }) => {
  test.setTimeout(300_000)
  await registerViaUi(page)
  const itemId = await seedDemoData(page)

  const pages: Array<[string, string]> = [
    ['/dashboard', 'dashboard'],
    ['/transactions', 'transactions'],
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

  // Auth screens (public).
  await page.getByRole('button', { name: 'Sair da conta' }).click().catch(() => {})
  await capture(page, '/login', 'login', VIEWPORTS[0])
  await capture(page, '/login', 'login-mobile', VIEWPORTS[3])
  await capture(page, '/register', 'register', VIEWPORTS[0])
  await capture(page, '/register', 'register-mobile', VIEWPORTS[3])

  // Dark theme auth screen.
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.addInitScript(() => localStorage.setItem('finora.theme', 'dark'))
  await capture(page, '/login', 'login-dark', VIEWPORTS[0])

  expect(itemId).toBeGreaterThan(0)
})
