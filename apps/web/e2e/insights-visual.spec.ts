import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
} from '@playwright/test'
import { pageGet, pagePost, registerViaUi, uniqueIdentity } from './helpers.ts'

/**
 * Visual QA for the three states the insights panel can be in.
 *
 * <p>Capture only — not a regression test. Run explicitly with:
 *   $env:VISUAL_QA = "1"; npx playwright test e2e/insights-visual.spec.ts
 *
 * <p>3 states × 4 viewports × 2 themes = 24 frames. The state each frame is
 * supposed to be in is asserted before the shutter rather than judged from the
 * thumbnail afterwards: a withheld aggregate leaking back into the mixed frame,
 * a foreign amount wearing a reais symbol, or an absent amount rendered as zero
 * are all invisible at contact-sheet size and all fail here instead.
 */
test.skip(!process.env.VISUAL_QA, 'Somente com VISUAL_QA=1')

const OUT = '../../qa-screenshots/insights'

const VIEWPORTS = [
  { name: '390x844', width: 390, height: 844 },
  { name: '768x1024', width: 768, height: 1024 },
  { name: '1280x800', width: 1280, height: 800 },
  { name: '1440x900', width: 1440, height: 900 },
]

const THEMES = ['light', 'dark'] as const
type Theme = (typeof THEMES)[number]

const NOW = new Date()

function isoDay(month: Date, day: number) {
  return `${month.getFullYear()}-${String(month.getMonth() + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

async function themedContext(browser: Browser, theme: Theme): Promise<BrowserContext> {
  const context = await browser.newContext({
    locale: 'pt-BR',
    colorScheme: theme,
    reducedMotion: 'reduce',
    viewport: { width: VIEWPORTS[3].width, height: VIEWPORTS[3].height },
  })
  await context.addInitScript((value) => {
    localStorage.setItem('finora.theme', value as string)
  }, theme)
  return context
}

async function categoryId(page: Page, name: string, type: 'INCOME' | 'EXPENSE') {
  const categories = await (await pageGet(page, `/categories?type=${type}`)).json()
  return (categories as Array<{ id: number; name: string }>).find((c) => c.name === name)!.id
}

async function expense(page: Page, amount: number, month: Date, category: string, currency?: string) {
  await pagePost(page, '/transactions', {
    type: 'EXPENSE',
    amount,
    description: 'Despesa',
    date: isoDay(month, 10),
    categoryId: await categoryId(page, category, 'EXPENSE'),
    ...(currency ? { currency } : {}),
  })
}

async function account(page: Page, name: string, balance: number, currency?: string) {
  await pagePost(page, '/accounts', {
    name,
    type: 'CHECKING',
    openingBalance: balance,
    ...(currency ? { currency } : {}),
  })
}

async function overdueCard(page: Page, name: string, limit: number, total: number, currency: string) {
  const card = await (
    await pagePost(page, '/credit-cards', {
      name,
      brand: 'VISA',
      creditLimit: limit,
      closingDay: 10,
      dueDay: 17,
      currency,
    })
  ).json()
  await pagePost(page, `/credit-cards/${card.id}/purchases`, {
    description: 'Compra',
    merchant: 'Loja',
    categoryId: await categoryId(page, 'Compras', 'EXPENSE'),
    purchaseDate: isoDay(new Date(NOW.getFullYear(), NOW.getMonth() - 3, 1), 1),
    totalAmount: total,
    installmentCount: 1,
  })
}

interface Scenario {
  state: 'base-complete' | 'mixed-currency' | 'jpy-native'
  email: string
  /** On-screen proof that this exact state rendered, resolved inside the panel. */
  marker: (panel: Locator) => Locator
  /** Whether the coverage notice must be present, exactly once. */
  coverage: boolean
  /** Native amounts that must appear literally, in their own currency. */
  nativeAmounts: string[]
  /** Text that must be absent — a withheld aggregate leaking back in. */
  absent: string[]
}

/** Everything comparable: aggregates and a native alert side by side. */
async function seedBaseComplete(page: Page): Promise<Scenario> {
  const identity = await registerViaUi(page, uniqueIdentity('completo'))
  await account(page, 'Conta principal', 12000)
  await expense(page, 1000, new Date(NOW.getFullYear(), NOW.getMonth() - 1, 1), 'Alimentação')
  await expense(page, 2400, new Date(NOW.getFullYear(), NOW.getMonth(), 1), 'Alimentação')
  await expense(page, 300, new Date(NOW.getFullYear(), NOW.getMonth(), 1), 'Lazer')
  await overdueCard(page, 'Cartão Roxinho', 6000, 900, 'BRL')
  return {
    state: 'base-complete',
    email: identity.email,
    marker: (target) => target.getByText('Gastos subiram em relação ao mês anterior'),
    coverage: false,
    nativeAmounts: ['R$ 900,00'],
    absent: ['Algumas análises consolidadas ficaram de fora'],
  }
}

/** A dollar month: aggregates withheld, the native dollar alert untouched. */
async function seedMixed(page: Page): Promise<Scenario> {
  const identity = await registerViaUi(page, uniqueIdentity('misto'))
  await account(page, 'Conta principal', 12000)
  await expense(page, 1000, new Date(NOW.getFullYear(), NOW.getMonth() - 1, 1), 'Alimentação')
  await expense(page, 2400, new Date(NOW.getFullYear(), NOW.getMonth(), 1), 'Alimentação')
  await expense(page, 500, new Date(NOW.getFullYear(), NOW.getMonth(), 1), 'Lazer', 'USD')
  await overdueCard(page, 'Cartão Internacional', 6000, 900, 'USD')
  return {
    state: 'mixed-currency',
    email: identity.email,
    marker: (target) => target.getByText('Algumas análises consolidadas ficaram de fora'),
    coverage: true,
    nativeAmounts: ['US$ 900,00'],
    absent: ['Gastos subiram em relação ao mês anterior', 'Uma categoria concentra os gastos'],
  }
}

/** A zero-decimal currency, where an invented ",00" would be money that is not real. */
async function seedYen(page: Page): Promise<Scenario> {
  const identity = await registerViaUi(page, uniqueIdentity('iene'))
  await account(page, 'Conta principal', 12000)
  await overdueCard(page, 'Cartão Japão', 100000, 95000, 'JPY')
  return {
    state: 'jpy-native',
    email: identity.email,
    marker: (target) => target.getByText('Fatura vencida: Cartão Japão'),
    coverage: false,
    nativeAmounts: ['JP¥ 95.000', 'JP¥ 5.000'],
    absent: ['Algumas análises consolidadas ficaram de fora'],
  }
}

async function captureScenario(page: Page, scenario: Scenario, theme: Theme): Promise<void> {
  const me = await (await page.request.get('http://localhost:8080/api/auth/me')).json()
  expect(me.email, 'usuário autenticado do cenário').toBe(scenario.email)

  for (const viewport of VIEWPORTS) {
    const where = `${scenario.state} @ ${viewport.name} / ${theme}`
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await page.goto('/dashboard')
    await page.waitForLoadState('networkidle')

    await expect(page, where).toHaveURL(/\/dashboard$/)
    await expect(page.locator('html'), where).toHaveAttribute('data-theme', theme)
    const panel = page.getByRole('region', { name: 'Insights' })
    await expect(panel, where).toBeVisible()
    await expect(scenario.marker(panel), where).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('.skeleton'), where).toHaveCount(0, { timeout: 15_000 })
    expect(await page.evaluate(() => window.innerWidth), where).toBe(viewport.width)

    // Park the pointer outside the content before anything is measured. Left
    // where a resize happened to leave it, it can rest over a chart and freeze
    // a tooltip into the frame — a hover state photographed as if it were the
    // page's resting state.
    await page.mouse.move(0, 0)
    await page.evaluate(async () => {
      await document.fonts.ready
      await Promise.race([
        Promise.all(
          document.getAnimations().map((animation) => animation.finished.catch(() => undefined)),
        ),
        new Promise((resolve) => setTimeout(resolve, 1_500)),
      ])
    })
    // The wrapper stays mounted and hidden; only a visible one is a stuck hover.
    await expect(page.locator('.recharts-tooltip-wrapper:visible'), `tooltip aberto em ${where}`)
      .toHaveCount(0)

    // Exactly one notice when expected, none when not — never one per rule.
    await expect(page.locator('.insight-coverage'), where)
      .toHaveCount(scenario.coverage ? 1 : 0)
    for (const absent of scenario.absent) {
      await expect(panel.getByText(absent), `${absent} em ${where}`).toHaveCount(0)
    }
    for (const amount of scenario.nativeAmounts) {
      await expect(panel.getByText(amount, { exact: true }), `${amount} em ${where}`).toBeVisible()
    }
    if (scenario.state !== 'base-complete') {
      // A foreign figure wearing a reais symbol is the defect a thumbnail hides.
      await expect(panel.getByText(/R\$/), `símbolo de real em ${where}`).toHaveCount(0)
    }
    // An absent amount must render nothing at all, never a zero.
    await expect(panel.locator('.insight-amount').getByText(/^(R\$|US\$|JP¥) 0(,00)?$/), where)
      .toHaveCount(0)

    const layout = await page.evaluate(() => {
      const root = document.documentElement
      const wide = (selector: string) =>
        Array.from(document.querySelectorAll(selector)).filter(
          (node) => node.scrollWidth - node.clientWidth > 1,
        ).length
      return {
        page: root.scrollWidth - root.clientWidth,
        cards: wide('.insight-item, .insight-coverage'),
      }
    })
    expect(layout.page, `rolagem horizontal da página em ${where}`).toBeLessThanOrEqual(1)
    expect(layout.cards, `card de insight com rolagem lateral em ${where}`).toBe(0)

    await page.screenshot({
      path: `${OUT}/insights-${scenario.state}-${viewport.name}-${theme}.png`,
      fullPage: true,
    })
  }
}

const SCENARIOS = [
  { name: 'base completa', seed: seedBaseComplete },
  { name: 'moedas mistas', seed: seedMixed },
  { name: 'iene nativo', seed: seedYen },
]

for (const theme of THEMES) {
  for (const scenario of SCENARIOS) {
    test(`insights: ${scenario.name} (${theme})`, async ({ browser }) => {
      test.setTimeout(300_000)
      const context = await themedContext(browser, theme)
      const page = await context.newPage()
      page.setDefaultTimeout(20_000)
      await captureScenario(page, await scenario.seed(page), theme)
      await context.close()
    })
  }
}
