import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test'
import { pagePost, registerViaUi, uniqueIdentity } from './helpers.ts'

/**
 * Visual QA for the three statement-import states currency changed.
 *
 * <p>Capture only — not a regression test. Run explicitly with:
 *   $env:VISUAL_QA = "1"; npx playwright test e2e/statement-import-currency-visual.spec.ts
 *
 * <p>3 states × 4 viewports × 2 themes = 24 frames. What each frame is supposed
 * to show is asserted before the shutter rather than judged from the thumbnail
 * afterwards: a dollar amount wearing a reais symbol, an invented centavo on a
 * yen value, an acknowledgement that arrived pre-checked, or an explanation
 * clipped out of its panel are all invisible at contact-sheet size and all fail
 * here instead.
 */
test.skip(!process.env.VISUAL_QA, 'Somente com VISUAL_QA=1')

const OUT = '../../qa-screenshots/statement-import-currency'

const VIEWPORTS = [
  { name: '390x844', width: 390, height: 844 },
  { name: '768x1024', width: 768, height: 1024 },
  { name: '1280x800', width: 1280, height: 800 },
  { name: '1440x900', width: 1440, height: 900 },
]

const THEMES = ['light', 'dark'] as const
type Theme = (typeof THEMES)[number]

const CSV_USD = '05/06/2026;Assinatura internacional;-120,50\n06/06/2026;Reembolso;80,00\n'
const CSV_JPY = '05/06/2026;Ramen fracionado;-100,50\n06/06/2026;Ramen inteiro;-1200\n'

/** Synthetic OFX that deliberately declares no currency. */
const OFX_NO_CURDEF =
  'OFXHEADER:100\nDATA:OFXSGML\n\n<OFX>\n<BANKMSGSRSV1><STMTTRNRS><STMTRS>\n'
  + '<BANKACCTFROM><BANKID>0260<ACCTID>12345-678<ACCTTYPE>CHECKING</BANKACCTFROM>\n'
  + '<BANKTRANLIST>\n'
  + '<STMTTRN>\n<TRNTYPE>DEBIT\n<DTPOSTED>20260605\n<TRNAMT>-25.90\n'
  + '<FITID>FIT-QA-1\n<NAME>Assinatura mensal\n</STMTTRN>\n'
  + '<STMTTRN>\n<TRNTYPE>CREDIT\n<DTPOSTED>20260606\n<TRNAMT>340.00\n'
  + '<FITID>FIT-QA-2\n<NAME>Reembolso de viagem\n</STMTTRN>\n'
  + '</BANKTRANLIST>\n</STMTRS></STMTTRNRS></BANKMSGSRSV1>\n</OFX>\n'

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

async function createAccount(page: Page, name: string, currency: string, balance = 1000) {
  const response = await pagePost(page, '/accounts', {
    name,
    type: 'CHECKING',
    openingBalance: balance,
    currency,
  })
  expect(response.ok()).toBeTruthy()
}

async function selectAccount(page: Page, accountName: string) {
  const select = page.getByLabel('Conta de destino')
  const value = await select
    .locator('option', { hasText: accountName })
    .first()
    .getAttribute('value')
  await select.selectOption(value as string)
}

async function upload(page: Page, accountName: string, filename: string, content: string) {
  await page.goto('/statement-imports')
  await page.getByRole('button', { name: 'Importar extrato' }).first().click()
  await selectAccount(page, accountName)
  await page.getByLabel('Arquivo do extrato').setInputFiles({
    name: filename,
    mimeType: 'application/octet-stream',
    buffer: Buffer.from(content, 'utf-8'),
  })
  await page.getByRole('button', { name: 'Enviar extrato' }).click()
}

/**
 * Assigns a category to every row, so each frame shows the state that actually
 * matters: everything ready, with only the currency question outstanding.
 */
async function assignAllCategories(page: Page) {
  await expect(page.getByText('Prontos para importar')).toBeVisible()
  const selects = page.locator('select[aria-label^="Categoria de"]')
  for (let index = 0; index < (await selects.count()); index += 1) {
    const select = selects.nth(index)
    if ((await select.inputValue()) === '') {
      const label = await select.getAttribute('aria-label')
      const saved = page.waitForResponse(
        (response) =>
          response.request().method() === 'PATCH'
          && /\/statement-imports\/\d+\/items\/\d+$/.test(response.url()),
      )
      await select.selectOption({ index: 1 })
      await saved
      await expect(page.getByLabel(label as string, { exact: true })).not.toHaveValue('')
    }
  }
}

async function mapCsv(page: Page) {
  await expect(page.getByText('Aguardando mapeamento')).toBeVisible()
  await page.getByLabel('Coluna de data').selectOption('0')
  await page.getByLabel('Coluna de descrição').selectOption('1')
  await page.getByLabel('Coluna de valor').selectOption('2')
  await page.getByRole('button', { name: 'Testar mapeamento' }).click()
  await page.getByRole('button', { name: 'Processar arquivo' }).click()
  await expect(page.getByText('Prontos para importar')).toBeVisible()
}

interface Scenario {
  state: 'csv-usd' | 'ofx-sem-moeda' | 'jpy-edicao'
  email: string
  filename: string
  /** Currency every amount in the frame must be denominated in. */
  currency: 'USD' | 'JPY'
  /** Provenance label that must appear, proving the frame is the right state. */
  sourceLabel: string
  /** Copy that must be present — the explanation the state exists to give. */
  present: RegExp[]
  /** Copy that must be absent, e.g. a claim the state cannot support. */
  absent: RegExp[]
  /** Whether the acknowledgement control must exist, exactly once. */
  acknowledgement: boolean
  /**
   * Reopens the state after a viewport change. The open batch is component
   * state rather than a route, so every frame has to navigate back into it.
   */
  reopen: (page: Page) => Promise<void>
}

/** A foreign CSV: values in dollars, denomination explained, nothing converted. */
async function seedCsvUsd(page: Page): Promise<Scenario> {
  const identity = await registerViaUi(page, uniqueIdentity('qa-csv-usd'))
  await createAccount(page, 'Conta internacional', 'USD')
  await upload(page, 'Conta internacional', 'extrato-internacional.csv', CSV_USD)
  await mapCsv(page)
  await assignAllCategories(page)
  return {
    state: 'csv-usd',
    email: identity.email,
    filename: 'extrato-internacional.csv',
    currency: 'USD',
    sourceLabel: 'Moeda da conta de destino',
    present: [
      /serão importados em USD — Dólar americano/,
      /Nenhuma conversão será realizada/,
    ],
    absent: [/não declarou uma moeda/, /antes de o Finora registrar/],
    acknowledgement: false,
    reopen: openFirstBatch,
  }
}

/** An OFX that declared nothing: the assumption, stated, with its consent. */
async function seedOfxAssumed(page: Page): Promise<Scenario> {
  const identity = await registerViaUi(page, uniqueIdentity('qa-ofx-assumed'))
  await createAccount(page, 'Conta internacional', 'USD')
  await upload(page, 'Conta internacional', 'extrato-sem-moeda.ofx', OFX_NO_CURDEF)
  await assignAllCategories(page)
  return {
    state: 'ofx-sem-moeda',
    email: identity.email,
    filename: 'extrato-sem-moeda.ofx',
    currency: 'USD',
    sourceLabel: 'Moeda da conta (arquivo não declarou)',
    present: [
      /não declarou uma moeda/,
      /O Finora usará USD — Dólar americano/,
      /Nenhuma conversão será realizada/,
      /Confirme acima que os valores devem ser interpretados em USD/,
    ],
    // This frame is about a file, not about Finora's own history.
    absent: [/antes de o Finora registrar/],
    acknowledgement: true,
    reopen: openFirstBatch,
  }
}

/** A zero-decimal currency being edited, with its precision refusal visible. */
async function seedJpyEditing(page: Page): Promise<Scenario> {
  const identity = await registerViaUi(page, uniqueIdentity('qa-jpy'))
  await createAccount(page, 'Conta iene', 'JPY', 90000)
  await upload(page, 'Conta iene', 'extrato-iene.csv', CSV_JPY)
  await mapCsv(page)
  await assignAllCategories(page)
  return {
    state: 'jpy-edicao',
    email: identity.email,
    filename: 'extrato-iene.csv',
    currency: 'JPY',
    sourceLabel: 'Moeda da conta de destino',
    present: [/JPY não aceita centavos/, /JPY não usa centavos/],
    absent: [/não declarou uma moeda/],
    acknowledgement: false,
    async reopen(page) {
      await openFirstBatch(page)
      // The editor, showing the refusal of a fractional yen amount: the
      // validation a thumbnail cannot confirm is actually rendered.
      await page.getByRole('button', { name: /Editar Ramen fracionado/ }).click()
      const amount = page.getByLabel('Valor (JPY)')
      await expect(amount).toBeVisible()
      await amount.fill('100,50')
      await page.getByRole('button', { name: 'Salvar alterações' }).click()
      await expect(page.getByRole('alert')).toContainText(/não aceita centavos/)
    },
  }
}

async function openFirstBatch(page: Page) {
  await page.goto('/statement-imports')
  // At mobile widths the shell's nav lives in a drawer whose overlay can cover
  // the history table. Escape closes it, and is a no-op when it is already shut.
  await page.keyboard.press('Escape')
  await page.getByRole('table').getByRole('button', { name: 'Abrir' }).first().click()
  await expect(
    page.getByRole('region', { name: 'Detalhe da importação de extrato' }),
  ).toBeVisible()
}

async function captureScenario(page: Page, scenario: Scenario, theme: Theme): Promise<void> {
  const me = await (await page.request.get('http://localhost:8080/api/auth/me')).json()
  expect(me.email, 'usuário autenticado do cenário').toBe(scenario.email)

  for (const viewport of VIEWPORTS) {
    const where = `${scenario.state} @ ${viewport.name} / ${theme}`
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await scenario.reopen(page)
    await page.waitForLoadState('networkidle')

    // Route, batch, theme and viewport are all the ones this frame claims.
    await expect(page, where).toHaveURL(/\/statement-imports$/)
    await expect(page.locator('html'), where).toHaveAttribute('data-theme', theme)
    expect(await page.evaluate(() => window.innerWidth), where).toBe(viewport.width)
    const detail = page.getByRole('region', { name: 'Detalhe da importação de extrato' })
    await expect(detail, where).toBeVisible()
    await expect(detail.locator('.si-filename').first(), where)
      .toHaveText(scenario.filename)

    // The currency and its provenance, in text.
    await expect(detail.getByText(scenario.sourceLabel), where).toBeVisible()
    await expect(
      page.getByRole('columnheader', { name: `Valor (${scenario.currency})` }),
      where,
    ).toBeVisible()
    for (const copy of scenario.present) {
      await expect(detail.getByText(copy).first(), `${copy} em ${where}`).toBeVisible()
    }
    for (const copy of scenario.absent) {
      await expect(detail.getByText(copy), `${copy} em ${where}`).toHaveCount(0)
    }

    // The acknowledgement exists only where it can change an outcome, and it
    // never arrives already granted.
    const acknowledgement = page.locator('#si-currency-ack')
    await expect(acknowledgement, where).toHaveCount(scenario.acknowledgement ? 1 : 0)
    if (scenario.acknowledgement) {
      await expect(acknowledgement, where).not.toBeChecked()
      await expect(page.getByRole('button', { name: /Importar \d+ lançamento/ }), where)
        .toBeDisabled()
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

    // A foreign amount wearing a reais symbol is the defect a thumbnail hides.
    await expect(detail.getByText(/R\$/), `símbolo de real em ${where}`).toHaveCount(0)
    // Yen has no centavos, so an invented ",00" on a valid figure would be money
    // that is not real. The one exception is the row whose entire problem is
    // that it carries centavos: rounding *that* to 101 would show a different
    // amount from the one the message says to correct.
    if (scenario.currency === 'JPY') {
      const centavos = /JP¥\s?[\d.]+,\d/
      const rows = detail.locator('.si-items tbody tr')
      await expect(
        rows.filter({ hasNotText: 'Inválido' }).getByText(centavos),
        `centavo de iene em linha válida em ${where}`,
      ).toHaveCount(0)
      await expect(
        detail.locator('.stat-card, .si-confirm-summary').getByText(centavos),
        `centavo de iene em total em ${where}`,
      ).toHaveCount(0)
      await expect(
        rows.filter({ hasText: 'Inválido' }).getByText(/JP¥\s?100,50/),
        `valor fracionado arredondado em ${where}`,
      ).toBeVisible()
    }
    // An item's own amount is always a positive figure, so a zero there would
    // be an absent value dressed up as money. (A pending *total* of zero is a
    // real total and is deliberately not covered by this.)
    await expect(
      detail.locator('.si-items .money').getByText(/^-?(US\$|JP¥|R\$)\s?0(,00)?$/),
      `valor de lançamento como zero em ${where}`,
    ).toHaveCount(0)

    const layout = await page.evaluate(() => {
      const root = document.documentElement
      const overflowing = (selector: string) =>
        Array.from(document.querySelectorAll(selector)).filter(
          (node) => node.scrollWidth - node.clientWidth > 1,
        ).length
      const clipped = Array.from(
        document.querySelectorAll('.si-currency-notice, .si-warning, .field-error'),
      ).filter((node) => node.scrollHeight - node.clientHeight > 1).length
      return {
        page: root.scrollWidth - root.clientWidth,
        cards: overflowing('.si-currency-notice, .si-batch-header, .si-confirm, .dialog'),
        clipped,
      }
    })
    expect(layout.page, `rolagem horizontal da página em ${where}`).toBeLessThanOrEqual(1)
    expect(layout.cards, `card/diálogo com rolagem lateral em ${where}`).toBe(0)
    expect(layout.clipped, `explicação ou erro cortado em ${where}`).toBe(0)

    await page.screenshot({
      path: `${OUT}/${scenario.state}-${viewport.name}-${theme}.png`,
      fullPage: true,
    })
  }
}

const SCENARIOS = [
  { name: 'CSV em dólar', seed: seedCsvUsd },
  { name: 'OFX sem moeda declarada', seed: seedOfxAssumed },
  { name: 'edição em iene', seed: seedJpyEditing },
]

for (const theme of THEMES) {
  for (const scenario of SCENARIOS) {
    test(`importação de extrato: ${scenario.name} (${theme})`, async ({ browser }) => {
      test.setTimeout(300_000)
      const context = await themedContext(browser, theme)
      const page = await context.newPage()
      page.setDefaultTimeout(20_000)
      await captureScenario(page, await scenario.seed(page), theme)
      await context.close()
    })
  }
}
