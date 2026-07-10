import { test, type APIRequestContext, type Page } from '@playwright/test'
import {
  categoryId,
  createAccount,
  createTransaction,
  resetData,
  resetSettings,
} from './helpers.ts'

/**
 * Visual QA capture — not a regression test. Run explicitly with:
 *   VISUAL_QA=1 npx playwright test e2e/visual-qa.spec.ts
 * Screenshots land in qa-screenshots/ (gitignored).
 */
test.skip(!process.env.VISUAL_QA, 'Somente com VISUAL_QA=1')

const OUT = '../../qa-screenshots'

const VIEWPORTS = [
  { name: 'desktop-1440', width: 1440, height: 900 },
  { name: 'laptop-1024', width: 1024, height: 768 },
  { name: 'tablet-768', width: 768, height: 1024 },
  { name: 'mobile-390', width: 390, height: 844 },
]

async function seedDemoData(request: APIRequestContext) {
  await resetData(request)
  await resetSettings(request, { minimumCashBuffer: 2000, monthlyOpportunityRate: 0.008 })
  const accountId = await createAccount(request, 'Conta principal', 8500)

  const salario = await categoryId(request, 'Salário', 'INCOME')
  const moradia = await categoryId(request, 'Moradia', 'EXPENSE')
  const alimentacao = await categoryId(request, 'Alimentação', 'EXPENSE')
  const transporte = await categoryId(request, 'Transporte', 'EXPENSE')
  const lazer = await categoryId(request, 'Lazer', 'EXPENSE')
  const assinaturas = await categoryId(request, 'Assinaturas', 'EXPENSE')

  const now = new Date()
  for (let i = 3; i >= 0; i--) {
    const month = new Date(now.getFullYear(), now.getMonth() - i, 1)
    const key = `${month.getFullYear()}-${String(month.getMonth() + 1).padStart(2, '0')}`
    await createTransaction(request, {
      type: 'INCOME', amount: 6200, description: 'Salário',
      date: `${key}-05`, categoryId: salario, accountId,
    })
    await createTransaction(request, {
      type: 'EXPENSE', amount: 1800, description: 'Aluguel',
      date: `${key}-10`, categoryId: moradia, accountId,
    })
    await createTransaction(request, {
      type: 'EXPENSE', amount: 950 + i * 60, description: 'Supermercado',
      date: `${key}-12`, categoryId: alimentacao, accountId,
    })
    await createTransaction(request, {
      type: 'EXPENSE', amount: 320, description: 'Combustível',
      date: `${key}-15`, categoryId: transporte, accountId,
    })
    await createTransaction(request, {
      type: 'EXPENSE', amount: 260, description: 'Cinema e restaurantes',
      date: `${key}-20`, categoryId: lazer, accountId,
    })
  }

  const monthKey = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
  const api = 'http://localhost:8080/api'
  await request.post(`${api}/budgets`, {
    data: { month: monthKey, categoryId: alimentacao, limitAmount: 1100 },
  })
  await request.post(`${api}/budgets`, {
    data: { month: monthKey, categoryId: lazer, limitAmount: 300 },
  })
  await request.post(`${api}/commitments`, {
    data: {
      description: 'Streaming de vídeo', amount: 55.9, categoryId: assinaturas,
      cadence: 'MONTHLY', dueDay: 8, startDate: '2025-01-08',
    },
  })
  await request.post(`${api}/commitments`, {
    data: {
      description: 'Academia', amount: 129.9, categoryId: assinaturas,
      cadence: 'MONTHLY', dueDay: 15, startDate: '2025-03-15',
    },
  })
  await request.post(`${api}/goals`, {
    data: {
      name: 'Reserva de emergência', targetAmount: 20000, currentAmount: 8500,
    },
  })
  await request.post(`${api}/goals`, {
    data: {
      name: 'Viagem de férias', targetAmount: 6000, currentAmount: 1200,
      targetDate: `${now.getFullYear() + 1}-01-15`,
    },
  })
  const itemResponse = await request.post(`${api}/wishlist`, {
    data: {
      name: 'Notebook para trabalho', priority: 'HIGH', status: 'MONITORING',
      referencePrice: 5200, targetPrice: 4600,
    },
  })
  const item = (await itemResponse.json()) as { id: number }
  await request.post(`${api}/wishlist/${item.id}/options`, {
    data: { merchant: 'Loja TechPreço', kind: 'CASH', basePrice: 4650, shipping: 45 },
  })
  await request.post(`${api}/wishlist/${item.id}/options`, {
    data: {
      merchant: 'MegaStore Parcelas', kind: 'INSTALLMENT', basePrice: 5100,
      installmentCount: 10, installmentAmount: 510,
    },
  })
  return item.id
}

async function capture(page: Page, path: string, name: string, viewport: (typeof VIEWPORTS)[0]) {
  await page.setViewportSize({ width: viewport.width, height: viewport.height })
  await page.goto(path)
  await page.waitForLoadState('networkidle')
  await page.screenshot({
    path: `${OUT}/${viewport.name}/${name}.png`,
    fullPage: true,
  })
}

test('captura estados principais em todos os viewports', async ({ page, request }) => {
  test.setTimeout(300_000)
  const itemId = await seedDemoData(request)

  const pages: Array<[string, string]> = [
    ['/dashboard', 'dashboard'],
    ['/transactions', 'transactions'],
    ['/budgets', 'budgets'],
    ['/commitments', 'commitments'],
    ['/goals', 'goals'],
    ['/wishlist', 'wishlist'],
    [`/wishlist/${itemId}`, 'wishlist-detail'],
    ['/settings', 'settings'],
  ]

  for (const viewport of VIEWPORTS) {
    for (const [path, name] of pages) {
      await capture(page, path, name, viewport)
    }
  }

  // Dark mode sample
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.addInitScript(() => localStorage.setItem('finora.theme', 'dark'))
  await capture(page, '/dashboard', 'dashboard-dark', VIEWPORTS[0])
  await capture(page, `/wishlist/${itemId}`, 'wishlist-detail-dark', VIEWPORTS[0])

  // Empty state
  await resetData(request)
  await page.addInitScript(() => localStorage.removeItem('finora.theme'))
  await page.emulateMedia({ colorScheme: 'light' })
  await capture(page, '/dashboard', 'dashboard-empty', VIEWPORTS[0])
  await capture(page, '/dashboard', 'dashboard-empty-mobile', VIEWPORTS[3])
})
