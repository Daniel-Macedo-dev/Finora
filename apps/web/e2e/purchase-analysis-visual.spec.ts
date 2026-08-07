import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
} from '@playwright/test'
import { pagePost, pagePut, registerViaUi, uniqueIdentity } from './helpers.ts'

/**
 * Visual QA for the two states the purchase analysis can be in.
 *
 * <p>Capture only — not a regression test. Run explicitly with:
 *   $env:VISUAL_QA = "1"; npx playwright test e2e/purchase-analysis-visual.spec.ts
 *
 * <p>The matrix is 2 states × 4 viewports × 2 themes = 16 frames, and nothing is
 * shot on faith: the route, the scenario, the signed-in user, the state's own
 * marker, the width and the theme are all read back from the running page
 * before the shutter, and the frames that must not contain a verdict are
 * checked for one rather than eyeballed afterwards. A screenshot of the wrong
 * state is worse than a missing one, because it still lands in the folder
 * looking like evidence.
 */
test.skip(!process.env.VISUAL_QA, 'Somente com VISUAL_QA=1')

const OUT = '../../qa-screenshots/purchase-analysis'

const VIEWPORTS = [
  { name: '390x844', width: 390, height: 844 },
  { name: '768x1024', width: 768, height: 1024 },
  { name: '1280x800', width: 1280, height: 800 },
  { name: '1440x900', width: 1440, height: 900 },
]

const THEMES = ['light', 'dark'] as const
type Theme = (typeof THEMES)[number]

/** Verdict wording that must never appear beside an unavailable analysis. */
const VERDICTS = ['Comprar à vista', 'Comprar parcelado', 'Aguardar', 'Sem opções']

async function themedContext(browser: Browser, theme: Theme): Promise<BrowserContext> {
  const context = await browser.newContext({
    locale: 'pt-BR',
    colorScheme: theme,
    // Honoured by the application as "collapse every transition", which is what
    // makes a capture reproducible instead of a race against an easing curve.
    reducedMotion: 'reduce',
    viewport: { width: VIEWPORTS[3].width, height: VIEWPORTS[3].height },
  })
  await context.addInitScript((value) => {
    localStorage.setItem('finora.theme', value as string)
  }, theme)
  return context
}

interface Scenario {
  /** Slug used in the file name. */
  state: 'available' | 'exchange-rate-required'
  itemId: number
  itemName: string
  email: string
  /** On-screen proof that this exact state rendered. */
  marker: (page: Page) => Locator
}

/**
 * A complete BRL context: cash, three-month history, two competing options.
 *
 * <p>Every figure is fixed, so the same frame comes out of every run: the cash
 * option wins on present value at the configured opportunity rate while both
 * options stay inside the buffer, which is the state that shows a
 * recommendation, its assumptions and a full option comparison at once.
 */
async function seedAvailable(page: Page): Promise<Scenario> {
  const identity = await registerViaUi(page, uniqueIdentity('disponivel'))
  await pagePut(page, '/settings', {
    minimumCashBuffer: 2000,
    maxInstallmentCommitmentRatio: 0.3,
    monthlyOpportunityRate: 0.008,
    budgetWarningThreshold: 0.8,
  })
  await pagePost(page, '/accounts', {
    name: 'Conta principal',
    type: 'CHECKING',
    openingBalance: 8500,
  })

  const categories = async (type: 'INCOME' | 'EXPENSE') =>
    (await (await page.request.get('http://localhost:8080/api/categories?type=' + type)).json()) as
      Array<{ id: number; name: string }>
  const salario = (await categories('INCOME')).find((c) => c.name === 'Salário')!.id
  const moradia = (await categories('EXPENSE')).find((c) => c.name === 'Moradia')!.id

  // Accountless on purpose: linked movements would move the balance too, and
  // the cash figure above has to stay exactly what it says.
  const now = new Date()
  for (let back = 1; back <= 3; back += 1) {
    const month = new Date(now.getFullYear(), now.getMonth() - back, 5)
    const day = `${month.getFullYear()}-${String(month.getMonth() + 1).padStart(2, '0')}-05`
    await pagePost(page, '/transactions', {
      type: 'INCOME', amount: 6200, description: 'Salário', date: day, categoryId: salario,
    })
    await pagePost(page, '/transactions', {
      type: 'EXPENSE', amount: 3330, description: 'Aluguel', date: day, categoryId: moradia,
    })
  }

  const item = await (
    await pagePost(page, '/wishlist', {
      name: 'Notebook para trabalho',
      priority: 'HIGH',
      status: 'MONITORING',
      referencePrice: 5200,
      targetPrice: 4600,
    })
  ).json()
  await pagePost(page, `/wishlist/${item.id}/options`, {
    merchant: 'Loja TechPreço', kind: 'CASH', basePrice: 4650, shipping: 45, fees: 0,
  })
  await pagePost(page, `/wishlist/${item.id}/options`, {
    merchant: 'MegaStore', kind: 'INSTALLMENT', basePrice: 5100, shipping: 0, fees: 0,
    installmentCount: 10, installmentAmount: 510,
  })

  return {
    state: 'available',
    itemId: item.id,
    itemName: 'Notebook para trabalho',
    email: identity.email,
    marker: (target) => target.getByRole('region', { name: 'Recomendação' }),
  }
}

/**
 * A dollar item held by a user whose cash is partly in euros.
 *
 * <p>Two independent blocking reasons and two currencies to convert, so the
 * explanation, the currency pair and the list of what a future rate would have
 * to cover are all on screen rather than one lonely sentence.
 */
async function seedUnavailable(page: Page): Promise<Scenario> {
  const identity = await registerViaUi(page, uniqueIdentity('indisponivel'))
  await pagePost(page, '/accounts', {
    name: 'Conta principal', type: 'CHECKING', openingBalance: 12000,
  })
  await pagePost(page, '/accounts', {
    name: 'Conta em euros', type: 'CHECKING', openingBalance: 800, currency: 'EUR',
  })
  const item = await (
    await pagePost(page, '/wishlist', {
      name: 'Câmera importada',
      priority: 'HIGH',
      status: 'MONITORING',
      currency: 'USD',
      referencePrice: 1400,
      targetPrice: 1200,
    })
  ).json()
  await pagePost(page, `/wishlist/${item.id}/options`, {
    merchant: 'Store A', kind: 'CASH', basePrice: 1290, shipping: 35, fees: 0,
  })

  return {
    state: 'exchange-rate-required',
    itemId: item.id,
    itemName: 'Câmera importada',
    email: identity.email,
    marker: (target) => target.getByRole('region', { name: 'Análise indisponível' }),
  }
}

/**
 * Photographs one state at every width, checking first that it is that state.
 *
 * <p>Sideways scroll and clipping are asked of the layout directly rather than
 * looked for in a thumbnail afterwards: a 390px frame looks identical whether
 * the content fits or runs forty pixels past the edge.
 */
async function captureScenario(page: Page, scenario: Scenario, theme: Theme): Promise<void> {
  const me = await (await page.request.get('http://localhost:8080/api/auth/me')).json()
  expect(me.email, 'usuário autenticado do cenário').toBe(scenario.email)

  for (const viewport of VIEWPORTS) {
    const where = `${scenario.state} @ ${viewport.name} / ${theme}`
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await page.goto(`/wishlist/${scenario.itemId}`)
    await page.waitForLoadState('networkidle')

    await expect(page, where).toHaveURL(new RegExp(`/wishlist/${scenario.itemId}$`))
    await expect(page.getByRole('heading', { name: scenario.itemName }), where).toBeVisible()
    await expect(page.locator('html'), where).toHaveAttribute('data-theme', theme)
    await expect(scenario.marker(page), where).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('.skeleton'), where).toHaveCount(0, { timeout: 15_000 })
    expect(await page.evaluate(() => window.innerWidth), where).toBe(viewport.width)

    await page.evaluate(async () => {
      await document.fonts.ready
      await Promise.race([
        Promise.all(
          document.getAnimations().map((animation) => animation.finished.catch(() => undefined)),
        ),
        new Promise((resolve) => setTimeout(resolve, 1_500)),
      ])
    })

    const analysis = page.getByRole('region', { name: 'Análise de compra' })
    if (scenario.state === 'exchange-rate-required') {
      for (const verdict of VERDICTS) {
        await expect(page.getByText(verdict, { exact: true }), `${verdict} em ${where}`)
          .toHaveCount(0)
      }
      await expect(page.getByText('Segura', { exact: true }), where).toHaveCount(0)
      await expect(page.getByText('Arriscada', { exact: true }), where).toHaveCount(0)
      await expect(page.getByRole('region', { name: 'Premissas da análise' }), where)
        .toHaveCount(0)
      // No amount at all inside the panel: neither a foreign figure wearing a
      // reais symbol nor an absent one rendered as zero.
      const money = await analysis.evaluate(
        (node) => /R\$|US\$|€|JP¥|0,00/.test(node.textContent ?? ''),
      )
      expect(money, `valor monetário no painel indisponível em ${where}`).toBe(false)
    } else {
      const assumptions = page.getByRole('region', { name: 'Premissas da análise' })
      await expect(assumptions, where).toBeVisible()
      await expect(page.getByRole('region', { name: 'Comparação de opções' }), where).toBeVisible()
      // Explicit currency, from the seeded cash figure that cannot drift.
      await expect(assumptions.getByText('R$ 8.500,00'), where).toBeVisible()
    }

    const layout = await page.evaluate(() => {
      const root = document.documentElement
      const clipped = (selector: string) =>
        Array.from(document.querySelectorAll(selector)).filter(
          (node) => node.scrollHeight - node.clientHeight > 1,
        ).length
      const wide = (selector: string) =>
        Array.from(document.querySelectorAll(selector)).filter(
          (node) => node.scrollWidth - node.clientWidth > 1,
        ).length
      return {
        page: root.scrollWidth - root.clientWidth,
        cards: wide('.card'),
        clippedText: clipped('.analysis-explanation, .analysis-unavailable-title, .badge'),
      }
    })
    expect(layout.page, `rolagem horizontal da página em ${where}`).toBeLessThanOrEqual(1)
    expect(layout.cards, `card com rolagem lateral em ${where}`).toBe(0)
    expect(layout.clippedText, `texto cortado em ${where}`).toBe(0)

    await page.screenshot({
      path: `${OUT}/purchase-analysis-${scenario.state}-${viewport.name}-${theme}.png`,
      fullPage: true,
    })
  }
}

for (const theme of THEMES) {
  test(`análise de compra: estado disponível (${theme})`, async ({ browser }) => {
    test.setTimeout(300_000)
    const context = await themedContext(browser, theme)
    const page = await context.newPage()
    page.setDefaultTimeout(20_000)
    await captureScenario(page, await seedAvailable(page), theme)
    await context.close()
  })

  test(`análise de compra: cotação necessária (${theme})`, async ({ browser }) => {
    test.setTimeout(300_000)
    const context = await themedContext(browser, theme)
    const page = await context.newPage()
    page.setDefaultTimeout(20_000)
    await captureScenario(page, await seedUnavailable(page), theme)
    await context.close()
  })
}
