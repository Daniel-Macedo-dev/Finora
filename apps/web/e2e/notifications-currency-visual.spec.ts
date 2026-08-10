import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test'
import { pageGet, pagePost, registerViaUi, uniqueIdentity } from './helpers.ts'

/**
 * Visual QA for the notification states currency changed.
 *
 * <p>Capture only — not a regression test. Run explicitly with:
 *   $env:VISUAL_QA = "1"; npx playwright test e2e/notifications-currency-visual.spec.ts
 *
 * <p>4 states × 4 viewports × 2 themes = 32 frames. What each frame is supposed
 * to show is asserted before the shutter rather than judged from the thumbnail
 * afterwards: a dollar amount wearing a reais symbol, an invented centavo on a
 * yen value, two currencies quietly collapsed into one alert, or a severity
 * carried only by colour are all invisible at contact-sheet size and all fail
 * here instead.
 */
test.skip(!process.env.VISUAL_QA, 'Somente com VISUAL_QA=1')

const OUT = '../../qa-screenshots/notifications-currency'

const VIEWPORTS = [
  { name: '390x844', width: 390, height: 844 },
  { name: '768x1024', width: 768, height: 1024 },
  { name: '1280x800', width: 1280, height: 800 },
  { name: '1440x900', width: 1440, height: 900 },
]

const THEMES = ['light', 'dark'] as const
type Theme = (typeof THEMES)[number]

const iso = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`

const shiftDays = (days: number) => {
  const date = new Date()
  date.setDate(date.getDate() + days)
  return date
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

async function expenseCategoryId(page: Page) {
  const categories = await (await pageGet(page, '/categories?type=EXPENSE')).json()
  return categories.find((category: { name: string }) => category.name === 'Assinaturas').id
}

async function createAccount(page: Page, name: string, currency: string, balance: number) {
  const response = await pagePost(page, '/accounts', {
    name,
    type: 'CHECKING',
    openingBalance: balance,
    currency,
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return (await response.json()).id as number
}

async function commitment(
  page: Page,
  description: string,
  amount: number,
  currency: string,
  accountId: number,
  dueInDays: number,
) {
  const due = shiftDays(dueInDays)
  const start = dueInDays < 0 ? new Date(due) : new Date()
  if (dueInDays < 0) start.setMonth(start.getMonth() - 2)
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
}

async function sync(page: Page) {
  const response = await pagePost(page, '/notifications/sync', {})
  expect(response.ok(), await response.text()).toBeTruthy()
}

interface Scenario {
  state: 'brl' | 'estrangeira' | 'jpy' | 'mista'
  email: string
  /** Titles that must be on screen, one row each. */
  titles: string[]
  /** Money that must be legible, exactly as the currency renders it. */
  amounts: RegExp[]
  /** Whether a reais symbol is allowed to appear anywhere in the list. */
  allowsReais: boolean
  /** Whether a yen figure must be free of invented centavos. */
  yen: boolean
}

async function seedBrl(page: Page): Promise<Scenario> {
  const identity = uniqueIdentity('qa-notif-brl')
  await registerViaUi(page, identity)
  const account = await createAccount(page, 'Conta corrente', 'BRL', 4000)
  await commitment(page, 'Internet do escritório', 189.9, 'BRL', account, -2)
  await commitment(page, 'Plano de saúde', 1250, 'BRL', account, 3)
  await sync(page)
  return {
    state: 'brl',
    email: identity.email,
    titles: ['Internet do escritório', 'Plano de saúde'],
    amounts: [/R\$\s?189,90/, /R\$\s?1\.250,00/],
    allowsReais: true,
    yen: false,
  }
}

async function seedForeign(page: Page): Promise<Scenario> {
  const identity = uniqueIdentity('qa-notif-usd')
  await registerViaUi(page, identity)
  const account = await createAccount(page, 'Conta internacional', 'USD', 4000)
  await commitment(page, 'Assinatura de software', 82.4, 'USD', account, -2)
  await commitment(page, 'Hospedagem do site', 1200, 'USD', account, 3)
  await sync(page)
  return {
    state: 'estrangeira',
    email: identity.email,
    titles: ['Assinatura de software', 'Hospedagem do site'],
    amounts: [/US\$\s?82,40/, /US\$\s?1\.200,00/],
    allowsReais: false,
    yen: false,
  }
}

async function seedYen(page: Page): Promise<Scenario> {
  const identity = uniqueIdentity('qa-notif-jpy')
  await registerViaUi(page, identity)
  const account = await createAccount(page, 'Conta em ienes', 'JPY', 900000)
  await commitment(page, 'Aluguel do estúdio', 128000, 'JPY', account, -2)
  await commitment(page, 'Transporte mensal', 9800, 'JPY', account, 3)
  await sync(page)
  return {
    state: 'jpy',
    email: identity.email,
    titles: ['Aluguel do estúdio', 'Transporte mensal'],
    amounts: [/128\.000/, /9\.800/],
    allowsReais: false,
    yen: true,
  }
}

/**
 * Two currencies going negative at once — the state that used to produce no
 * alert at all, because the forecast's scalar fields go null the moment a
 * second currency exists.
 */
async function seedMixed(page: Page): Promise<Scenario> {
  const identity = uniqueIdentity('qa-notif-misto')
  await registerViaUi(page, identity)
  const brl = await createAccount(page, 'Conta em reais', 'BRL', 100)
  const usd = await createAccount(page, 'Conta em dólares', 'USD', 50)
  await commitment(page, 'Aluguel em reais', 500, 'BRL', brl, 3)
  await commitment(page, 'Aluguel em dólares', 300, 'USD', usd, 4)
  await sync(page)
  return {
    state: 'mista',
    email: identity.email,
    titles: ['Aluguel em reais', 'Aluguel em dólares'],
    // Each currency's own shortfall, and never their sum.
    amounts: [/-R\$\s?400,00/, /-US\$\s?250,00/],
    allowsReais: true,
    yen: false,
  }
}

async function captureScenario(page: Page, scenario: Scenario, theme: Theme): Promise<void> {
  const me = await (await page.request.get('http://localhost:8080/api/auth/me')).json()
  expect(me.email, 'usuário autenticado do cenário').toBe(scenario.email)

  for (const viewport of VIEWPORTS) {
    const where = `${scenario.state} @ ${viewport.name} / ${theme}`
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await page.goto('/notifications')
    // At mobile widths the shell's nav lives in a drawer whose overlay can
    // cover the list. Escape closes it, and is a no-op when already shut.
    await page.keyboard.press('Escape')
    await page.waitForLoadState('networkidle')

    // Route, theme and viewport are the ones this frame claims.
    await expect(page, where).toHaveURL(/\/notifications$/)
    await expect(page.locator('html'), where).toHaveAttribute('data-theme', theme)
    expect(await page.evaluate(() => window.innerWidth), where).toBe(viewport.width)

    const list = page.locator('.notification-item')
    await expect(list.first(), where).toBeVisible()

    // The semantic state: every title present, every amount legible in its own
    // currency, and the severity written as words rather than left to colour.
    for (const title of scenario.titles) {
      await expect(page.getByText(title, { exact: false }).first(), `${title} em ${where}`)
        .toBeVisible()
    }
    for (const amount of scenario.amounts) {
      await expect(list.getByText(amount).first(), `${amount} em ${where}`).toBeVisible()
    }
    await expect(
      page.locator('.notification-severity').first(),
      `severidade em texto em ${where}`,
    ).toHaveText(/Informativa|Atenção|Crítica/)

    // Foreign money must never wear a reais symbol.
    if (!scenario.allowsReais) {
      await expect(list.getByText(/R\$/), `símbolo de real em ${where}`).toHaveCount(0)
    }
    // Yen has no centavos, so a decimal on a yen figure is money that does not
    // exist. (JPY renders as "JP¥ 128.000" — a comma would be the defect.)
    if (scenario.yen) {
      await expect(
        list.getByText(/JP¥\s?[\d.]+,\d/),
        `centavo de iene em ${where}`,
      ).toHaveCount(0)
    }
    // Nothing anywhere may be the two currencies added together.
    if (scenario.state === 'mista') {
      await expect(list.getByText(/650,00/), `soma entre moedas em ${where}`).toHaveCount(0)
      await expect(
        page.locator('.notification-item', { hasText: 'Saldo projetado' }),
        `um risco por moeda em ${where}`,
      ).toHaveCount(2)
    }

    await expect(page.locator('.skeleton'), where).toHaveCount(0, { timeout: 15_000 })
    // Park the pointer outside the content so no hover state is photographed as
    // if it were the page's resting state.
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

    // Every action still has a readable accessible name, at every width.
    for (const name of [/Ver origem/, /Marcar como lida/, /Dispensar/]) {
      await expect(
        page.getByRole('link', { name }).or(page.getByRole('button', { name })).first(),
        `ação "${name}" em ${where}`,
      ).toBeVisible()
    }

    const layout = await page.evaluate(() => {
      const root = document.documentElement
      const overflowing = (selector: string) =>
        Array.from(document.querySelectorAll(selector)).filter(
          (node) => node.scrollWidth - node.clientWidth > 1,
        ).length
      const clipped = Array.from(document.querySelectorAll('.notification-copy')).filter(
        (node) => node.scrollHeight - node.clientHeight > 1,
      ).length
      return {
        page: root.scrollWidth - root.clientWidth,
        cards: overflowing('.notification-item, .notification-actions'),
        clipped,
      }
    })
    expect(layout.page, `rolagem horizontal da página em ${where}`).toBeLessThanOrEqual(1)
    expect(layout.cards, `item com rolagem lateral em ${where}`).toBe(0)
    expect(layout.clipped, `texto de notificação cortado em ${where}`).toBe(0)

    await page.screenshot({
      path: `${OUT}/${scenario.state}-${viewport.name}-${theme}.png`,
      fullPage: true,
    })
  }
}

const SCENARIOS = [
  { name: 'central em reais', seed: seedBrl },
  { name: 'moeda estrangeira', seed: seedForeign },
  { name: 'iene sem centavos', seed: seedYen },
  { name: 'risco de caixa em duas moedas', seed: seedMixed },
]

for (const theme of THEMES) {
  for (const scenario of SCENARIOS) {
    test(`notificações: ${scenario.name} (${theme})`, async ({ browser }) => {
      test.setTimeout(300_000)
      const context = await themedContext(browser, theme)
      const page = await context.newPage()
      page.setDefaultTimeout(20_000)
      await captureScenario(page, await scenario.seed(page), theme)
      await context.close()
    })
  }
}
