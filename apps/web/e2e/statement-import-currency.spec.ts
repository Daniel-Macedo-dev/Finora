import { expect, test, type Page } from '@playwright/test'
import { apiSession, categoryId, pageGet, pagePost, registerViaUi } from './helpers.ts'

/**
 * Statement-import currency journeys.
 *
 * <p>Two invariants run through all of them: an imported transaction is
 * denominated in its destination account's currency, and nothing is ever
 * converted — Finora holds no exchange rates, so a foreign statement is read in
 * the account's currency or refused, never reinterpreted.
 *
 * <p>Every fixture is synthetic with fixed 2026-06 dates. No real bank file,
 * no account number, no calendar dependence.
 */

/** Synthetic OFX. A null declaration omits CURDEF entirely. */
function ofx(declared: string | null, rows: Array<[string, string, string]>): string {
  const transactions = rows
    .map(
      ([amount, fitId, name]) =>
        `<STMTTRN>\n<TRNTYPE>${amount.startsWith('-') ? 'DEBIT' : 'CREDIT'}\n`
        + `<DTPOSTED>20260605\n<TRNAMT>${amount}\n<FITID>${fitId}\n<NAME>${name}\n</STMTTRN>\n`,
    )
    .join('')
  return (
    'OFXHEADER:100\nDATA:OFXSGML\n\n<OFX>\n<BANKMSGSRSV1><STMTTRNRS><STMTRS>\n'
    + (declared === null ? '' : `<CURDEF>${declared}\n`)
    + '<BANKACCTFROM><BANKID>0260<ACCTID>12345-678<ACCTTYPE>CHECKING</BANKACCTFROM>\n'
    + '<BANKTRANLIST>\n'
    + transactions
    + '</BANKTRANLIST>\n</STMTRS></STMTTRNRS></BANKMSGSRSV1>\n</OFX>\n'
  )
}

const OFX_DTD_ATTACK =
  '<?xml version="1.0"?>\n'
  + '<!DOCTYPE OFX [<!ENTITY moeda SYSTEM "file:///etc/passwd">]>\n'
  + '<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>&moeda;</CURDEF>'
  + '</STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>'

const CSV_TWO_ROWS =
  '05/06/2026;Assinatura mensal;-120,50\n' + '06/06/2026;Reembolso recebido;80,00\n'

/* ---------- helpers ---------- */

async function createAccount(
  page: Page,
  name: string,
  currency: string,
  openingBalance = 1000,
): Promise<number> {
  const response = await pagePost(page, '/accounts', {
    name,
    type: 'CHECKING',
    openingBalance,
    currency,
  })
  expect(response.ok()).toBeTruthy()
  return (await response.json()).id as number
}

/** Picks a destination account by name; options also name type and currency. */
async function selectAccount(page: Page, label: string, accountName: string) {
  const select = page.getByLabel(label)
  const value = await select
    .locator('option', { hasText: accountName })
    .first()
    .getAttribute('value')
  expect(value).toBeTruthy()
  await select.selectOption(value as string)
}

async function uploadStatement(
  page: Page,
  accountName: string,
  filename: string,
  content: string,
) {
  await page.goto('/statement-imports')
  await page.getByRole('button', { name: 'Importar extrato' }).first().click()
  await selectAccount(page, 'Conta de destino', accountName)
  await page.getByLabel('Arquivo do extrato').setInputFiles({
    name: filename,
    mimeType: 'application/octet-stream',
    buffer: Buffer.from(content, 'utf-8'),
  })
  await page.getByRole('button', { name: 'Enviar extrato' }).click()
}

/** Maps the three-column synthetic CSV and runs the authoritative parse. */
async function mapCsvColumns(page: Page, expectedValid: string) {
  await expect(page.getByText('Aguardando mapeamento')).toBeVisible()
  await page.getByLabel('Coluna de data').selectOption('0')
  await page.getByLabel('Coluna de descrição').selectOption('1')
  await page.getByLabel('Coluna de valor').selectOption('2')
  await page.getByRole('button', { name: 'Testar mapeamento' }).click()
  await expect(page.getByText(expectedValid)).toBeVisible()
  await page.getByRole('button', { name: 'Processar arquivo' }).click()
  await expect(page.getByText('Prontos para importar')).toBeVisible()
}

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

/** Checks the acknowledgement a currency assumption requires. */
async function acknowledgeCurrency(page: Page, currency: string) {
  const acknowledgement = page.getByRole('checkbox', {
    name: new RegExp(`interpretados em ${currency}`),
  })
  await expect(acknowledgement).not.toBeChecked()
  await acknowledgement.check()
}

async function confirmImport(page: Page) {
  await page.getByRole('button', { name: /Importar \d+ lançamento/ }).click()
  await page.getByRole('button', { name: 'Criar transações' }).click()
  await expect(page.getByText('Resultado da operação')).toBeVisible()
}

/** Currency codes of every transaction the owner has, newest page. */
async function transactionCurrencies(page: Page): Promise<string[]> {
  const body = await (await pageGet(page, '/transactions')).json()
  return (body.content as Array<{ currency: string }>).map((entry) => entry.currency)
}

/* ---------- CSV: the account is the denomination contract ---------- */

test('BRL CSV import still reads as reais end to end', async ({ page }) => {
  await registerViaUi(page)
  await createAccount(page, 'Conta corrente', 'BRL')

  await uploadStatement(page, 'Conta corrente', 'extrato.csv', CSV_TWO_ROWS)
  await expect(
    page.getByText(/serão importados em BRL — Real brasileiro/),
  ).toBeVisible()
  await mapCsvColumns(page, '2 de 2')

  // Values render as reais, and no acknowledgement is asked for: choosing the
  // account is itself the denomination decision for a CSV.
  await expect(page.getByRole('columnheader', { name: 'Valor (BRL)' })).toBeVisible()
  await expect(page.getByRole('checkbox', { name: /interpretados em/ })).toHaveCount(0)

  await assignAllCategories(page)
  await confirmImport(page)
  expect(await transactionCurrencies(page)).toEqual(['BRL', 'BRL'])
})

test('USD CSV states the destination currency, imports as dollars and never shows R$', async ({
  page,
}) => {
  await registerViaUi(page)
  const accountId = await createAccount(page, 'Conta internacional', 'USD')

  await uploadStatement(page, 'Conta internacional', 'extrato.csv', CSV_TWO_ROWS)

  // Before the mapping is confirmed the denomination is already explicit.
  await expect(
    page.getByText(/serão importados em USD — Dólar americano/),
  ).toBeVisible()
  await expect(page.getByText(/Nenhuma conversão será realizada/)).toBeVisible()
  await mapCsvColumns(page, '2 de 2')

  await expect(page.getByRole('columnheader', { name: 'Valor (USD)' })).toBeVisible()
  const table = page.locator('.si-items')
  await expect(table).toContainText('US$')
  await expect(table).not.toContainText('R$')

  await assignAllCategories(page)
  await confirmImport(page)

  expect(await transactionCurrencies(page)).toEqual(['USD', 'USD'])
  // The imported movements hit the USD account, unconverted.
  const account = await (await pageGet(page, `/accounts/${accountId}`)).json()
  expect(account.currency).toBe('USD')
  expect(account.currentBalance).toBeCloseTo(1000 - 120.5 + 80, 2)

  // The imported rows stay visible afterwards, still in dollars.
  const imported = page.getByRole('row', { name: /Assinatura mensal/ })
  await expect(imported).toContainText('Importado')
  await expect(imported).toContainText('US$')
  await expect(imported).not.toContainText('R$ 120,50')
})

test('JPY CSV renders whole yen and refuses a fractional row until it is corrected', async ({
  page,
}) => {
  await registerViaUi(page)
  await createAccount(page, 'Conta iene', 'JPY', 90000)

  await uploadStatement(
    page,
    'Conta iene',
    'extrato.csv',
    '05/06/2026;Ramen fracionado;-100,50\n06/06/2026;Ramen inteiro;-1200\n',
  )
  await expect(page.getByText(/em JPY — Iene japonês/)).toBeVisible()
  // One of the two rows carries centavos yen does not have, so the mapping
  // step already reports it as invalid instead of promising it.
  await mapCsvColumns(page, '1 de 2')

  // Yen has no centavos: the whole row shows none, and the fractional row is
  // invalid rather than silently rounded to 101.
  const table = page.locator('.si-items')
  await expect(table).toContainText('JP¥ 1.200')
  await expect(table).not.toContainText('1.200,00')
  const fractional = page.getByRole('row', { name: /Ramen fracionado/ })
  await expect(fractional).toContainText('Inválido')
  await expect(fractional).toContainText(/não aceita centavos/)

  // The fractional row cannot be confirmed: only one of the two is ready, so
  // the row has to be corrected before it can become money.
  await expect(page.getByText('Prontos para importar')).toBeVisible()
  await expect(page.locator('.stat-card', { hasText: 'Prontos para importar' })).toContainText(
    '1',
  )

  // The editor refuses a fractional yen amount rather than rounding it.
  await page.getByRole('button', { name: /Editar Ramen fracionado/ }).click()
  const amount = page.getByLabel('Valor (JPY)')
  await expect(amount).toBeVisible()
  await amount.fill('100,50')
  await page.getByRole('button', { name: 'Salvar alterações' }).click()
  await expect(page.getByRole('alert')).toContainText(/não aceita centavos/)

  // A whole amount restores the row through the ordinary validation path.
  await amount.fill('101')
  await page.getByRole('button', { name: 'Salvar alterações' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()
  await expect(page.getByRole('row', { name: /Ramen fracionado/ })).toContainText('Pronto')

  await assignAllCategories(page)
  await confirmImport(page)
  expect(await transactionCurrencies(page)).toEqual(['JPY', 'JPY'])
})

/* ---------- OFX that declares its currency ---------- */

test('OFX declaring the account currency imports without asking anything', async ({ page }) => {
  await registerViaUi(page)
  await createAccount(page, 'Conta internacional', 'USD')

  await uploadStatement(
    page,
    'Conta internacional',
    'extrato.ofx',
    ofx('USD', [['-25.90', 'FIT-USD-1', 'Assinatura mensal']]),
  )

  const notice = page.getByRole('region', { name: 'Moeda da importação' })
  await expect(notice).toContainText(/O arquivo declara USD/)
  await expect(notice).toContainText(/sem conversão/)
  // A declaration Finora believed needs no confirmation.
  await expect(page.getByRole('checkbox', { name: /interpretados em/ })).toHaveCount(0)

  await assignAllCategories(page)
  await confirmImport(page)
  expect(await transactionCurrencies(page)).toEqual(['USD'])
})

test('OFX whose declaration disagrees with the account is refused with no side effect', async ({
  page,
}) => {
  await registerViaUi(page)
  await createAccount(page, 'Conta corrente', 'BRL')

  await uploadStatement(
    page,
    'Conta corrente',
    'extrato.ofx',
    ofx('EUR', [['-25.90', 'FIT-EUR-1', 'Assinatura mensal']]),
  )

  const error = page.getByRole('alert')
  await expect(error).toContainText('EUR')
  await expect(error).toContainText('BRL')
  // Conversion is never offered, because it does not exist.
  await expect(error).not.toContainText(/converter automaticamente/)

  // No batch and no transaction were created.
  await page.getByRole('button', { name: 'Cancelar' }).click()
  await expect(page.getByText(/Nenhuma importação/)).toBeVisible()
  expect(await transactionCurrencies(page)).toEqual([])
})

test('OFX declaring a currency outside the catalogue is refused', async ({ page }) => {
  await registerViaUi(page)
  await createAccount(page, 'Conta corrente', 'BRL')

  await uploadStatement(
    page,
    'Conta corrente',
    'extrato.ofx',
    ofx('CNY', [['-25.90', 'FIT-CNY-1', 'Assinatura mensal']]),
  )

  const error = page.getByRole('alert')
  await expect(error).toContainText('CNY')
  await expect(error).toContainText(/Moeda não suportada/)
  // Never silently remapped: the upload simply does not happen.
  await page.getByRole('button', { name: 'Cancelar' }).click()
  await expect(page.getByText(/Nenhuma importação/)).toBeVisible()
  expect(await transactionCurrencies(page)).toEqual([])
})

/* ---------- OFX that declares nothing ---------- */

test('OFX without a declaration previews, blocks confirmation, then imports exactly once', async ({
  page,
}) => {
  await registerViaUi(page)
  await createAccount(page, 'Conta internacional', 'USD')

  await uploadStatement(
    page,
    'Conta internacional',
    'extrato.ofx',
    ofx(null, [['-25.90', 'FIT-ASSUMED-1', 'Assinatura mensal']]),
  )

  // A missing declaration is not an upload error: the preview exists.
  await expect(page.getByText('Prontos para importar')).toBeVisible()
  await expect(page.getByText(/não declarou uma moeda/)).toBeVisible()
  await expect(page.getByText(/O Finora usará USD — Dólar americano/)).toBeVisible()
  await assignAllCategories(page)

  // Confirmation is blocked, and the block explains itself.
  const confirmButton = page.getByRole('button', { name: /Importar 1 lançamento/ })
  await expect(confirmButton).toBeDisabled()
  await expect(
    page.getByText(/Confirme acima que os valores devem ser interpretados em USD/),
  ).toBeVisible()
  expect(await transactionCurrencies(page)).toEqual([])

  await acknowledgeCurrency(page, 'USD')
  await expect(confirmButton).toBeEnabled()
  await confirmImport(page)
  expect(await transactionCurrencies(page)).toEqual(['USD'])

  // Repeating the confirmation creates nothing: consent is not identity.
  await page.reload()
  await expect(page.getByText('Concluída')).toBeVisible()
  expect(await transactionCurrencies(page)).toEqual(['USD'])
})

/* ---------- destination-account changes ---------- */

test('changing the account of an assumed-currency batch moves the assumption and clears consent', async ({
  page,
}) => {
  await registerViaUi(page)
  await createAccount(page, 'Conta internacional', 'USD')
  await createAccount(page, 'Conta euro', 'EUR')

  await uploadStatement(
    page,
    'Conta internacional',
    'extrato.ofx',
    ofx(null, [['-25.90', 'FIT-MOVE-1', 'Assinatura mensal']]),
  )
  await assignAllCategories(page)
  await acknowledgeCurrency(page, 'USD')
  await expect(page.getByRole('button', { name: /Importar 1 lançamento/ })).toBeEnabled()

  // A different destination is a different assumption.
  await selectAccount(page, 'Conta de destino', 'Conta euro')
  await expect(page.getByText(/O Finora usará EUR — Euro/)).toBeVisible()
  await expect(
    page.getByRole('checkbox', { name: /interpretados em EUR/ }),
  ).not.toBeChecked()
  await expect(page.getByRole('button', { name: /Importar 1 lançamento/ })).toBeDisabled()

  await acknowledgeCurrency(page, 'EUR')
  await confirmImport(page)
  expect(await transactionCurrencies(page)).toEqual(['EUR'])
})

test('a file-declared batch cannot be moved to an account of another currency', async ({
  page,
}) => {
  await registerViaUi(page)
  await createAccount(page, 'Conta internacional', 'USD')
  await createAccount(page, 'Outra conta USD', 'USD')
  await createAccount(page, 'Conta corrente', 'BRL')

  await uploadStatement(
    page,
    'Conta internacional',
    'extrato.ofx',
    ofx('USD', [['-25.90', 'FIT-FIXED-1', 'Assinatura mensal']]),
  )
  await expect(page.getByText(/só contas em USD podem receber este extrato/)).toBeVisible()

  // Same currency is fine.
  await selectAccount(page, 'Conta de destino', 'Outra conta USD')
  await expect(page.getByLabel('Conta de destino')).toContainText('Outra conta USD')

  // Another currency is refused, and the previous destination survives.
  await selectAccount(page, 'Conta de destino', 'Conta corrente')
  const error = page.getByRole('alert')
  await expect(error).toContainText('USD')
  await expect(error).toContainText('BRL')
  await page.getByRole('button', { name: 'Voltar ao histórico' }).click()
  await page.getByRole('button', { name: 'Abrir' }).first().click()
  await expect(page.getByRole('region', { name: 'Confirmação da importação' })).toContainText(
    'Outra conta USD',
  )
  await expect(page.getByRole('columnheader', { name: 'Valor (USD)' })).toBeVisible()
})

/* ---------- review tools and undo keep working in a foreign currency ---------- */

test('duplicate review, categories and undo all work on a foreign-currency import', async ({
  page,
}) => {
  await registerViaUi(page)
  const accountId = await createAccount(page, 'Conta internacional', 'USD')
  const foodCategory = await categoryId(page.request, 'Alimentação', 'EXPENSE')

  // A manual USD transaction the statement will look like.
  await pagePost(page, '/transactions', {
    type: 'EXPENSE',
    amount: 25.9,
    description: 'Assinatura mensal',
    date: '2026-06-04',
    categoryId: foodCategory,
    accountId,
    currency: 'USD',
  })

  await uploadStatement(
    page,
    'Conta internacional',
    'extrato.ofx',
    ofx('USD', [
      ['-25.90', 'FIT-DUP-1', 'Assinatura mensal'],
      ['-42.00', 'FIT-DUP-2', 'Farmacia central'],
    ]),
  )

  // Duplicate review compares both sides in dollars.
  await page.getByRole('button', { name: /Possível duplicata/ }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('US$')
  await expect(dialog).not.toContainText('R$')
  await dialog.getByRole('button', { name: /Importar mesmo assim/ }).click()

  await assignAllCategories(page)
  await confirmImport(page)
  expect((await transactionCurrencies(page)).filter((code) => code === 'USD')).toHaveLength(3)

  // Undo removes the import's effect, in the same currency.
  await page.getByRole('button', { name: 'Desfazer importação', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Desfazer importação' }).click()
  await expect(
    page
      .getByRole('region', { name: 'Resultado por lançamento' })
      .getByText('Desfeito', { exact: true })
      .first(),
  ).toBeVisible()
  // Only the manual USD transaction survives.
  expect(await transactionCurrencies(page)).toEqual(['USD'])
  const account = await (await pageGet(page, `/accounts/${accountId}`)).json()
  expect(account.currency).toBe('USD')
})

/* ---------- isolation and parser security ---------- */

test('another owner account is unreachable and never leaks its currency', async ({
  page,
  request,
}) => {
  const stranger = await apiSession(request)
  const csrf = await request.get('http://localhost:8080/api/auth/csrf')
  expect(csrf.status()).toBe(204)
  const strangerAccount = await request.post('http://localhost:8080/api/accounts', {
    headers: { 'X-XSRF-TOKEN': stranger.token },
    data: { name: 'Conta secreta', type: 'CHECKING', openingBalance: 500, currency: 'EUR' },
  })
  expect(strangerAccount.ok()).toBeTruthy()
  const strangerAccountId = (await strangerAccount.json()).id as number

  await registerViaUi(page)
  await createAccount(page, 'Conta corrente', 'BRL')

  // Uploading a EUR-declaring file into someone else's EUR account must read as
  // missing — not as a currency comparison of any kind.
  const form = new FormData()
  form.append(
    'file',
    new Blob([ofx('EUR', [['-1.00', 'FIT-X', 'Sonda']])], { type: 'application/octet-stream' }),
    'extrato.ofx',
  )
  const probe = await page.evaluate(
    async ([accountId]) => {
      const token = document.cookie
        .split('; ')
        .find((entry) => entry.startsWith('XSRF-TOKEN='))
        ?.slice('XSRF-TOKEN='.length)
      const body = new FormData()
      body.append(
        'file',
        new Blob(['OFXHEADER:100\nDATA:OFXSGML\n\n<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS>\n'
          + '<CURDEF>EUR\n<BANKTRANLIST>\n<STMTTRN>\n<TRNTYPE>DEBIT\n<DTPOSTED>20260605\n'
          + '<TRNAMT>-1.00\n<FITID>FIT-X\n<NAME>Sonda\n</STMTTRN>\n'
          + '</BANKTRANLIST>\n</STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>\n']),
        'extrato.ofx',
      )
      body.append('accountId', String(accountId))
      const response = await fetch('http://localhost:8080/api/statement-imports', {
        method: 'POST',
        credentials: 'include',
        headers: { 'X-XSRF-TOKEN': decodeURIComponent(token ?? '') },
        body,
      })
      return { status: response.status, text: await response.text() }
    },
    [strangerAccountId],
  )

  expect(probe.status).toBe(404)
  expect(probe.text).not.toContain('EUR')
  expect(probe.text).not.toContain('Conta secreta')
  expect(form).toBeTruthy()
})

test('a malicious OFX declaring its currency through an entity is still rejected', async ({
  page,
}) => {
  await registerViaUi(page)
  await createAccount(page, 'Conta corrente', 'BRL')

  await uploadStatement(page, 'Conta corrente', 'ataque.ofx', OFX_DTD_ATTACK)

  await expect(page.getByRole('alert')).toContainText(/rejeitado por segurança/)
  await page.getByRole('button', { name: 'Cancelar' }).click()
  await expect(page.getByText(/Nenhuma importação/)).toBeVisible()
})
