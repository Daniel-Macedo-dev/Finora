import { expect, test, type Page } from '@playwright/test'
import { apiSession, categoryId, pageGet, pagePost, pagePut, registerViaUi } from './helpers.ts'

/**
 * Statement-import journeys: CSV mapping with Brazilian formats, OFX in both
 * variants, duplicate detection with explicit decisions, deterministic
 * category rules, idempotent confirmation, partial failure with retry,
 * audited undo, user isolation and the full mobile flow.
 *
 * Every statement is a synthetic in-memory fixture with fixed 2026-06 dates —
 * no real banking data, no calendar dependence.
 */

const API = 'http://localhost:8080/api'

const CSV_SIGNED =
  'Data;Descrição;Valor\n' +
  '05/06/2026;Mercado Central;-120,50\n' +
  '06/06/2026;Pix recebido;80,00\n'

const CSV_DEBIT_CREDIT =
  'Data;Histórico;Débito;Crédito\n' +
  '05/06/2026;Conta de luz;95,30;\n' +
  '06/06/2026;Depósito poupança;;150,00\n'

const OFX_SGML =
  'OFXHEADER:100\nDATA:OFXSGML\n\n<OFX>\n<BANKMSGSRSV1><STMTTRNRS><STMTRS>\n<BANKTRANLIST>\n' +
  '<STMTTRN>\n<TRNTYPE>DEBIT\n<DTPOSTED>20260605\n<TRNAMT>-25.90\n<FITID>FIT-E2E-1\n<NAME>Padaria Sao Joao\n</STMTTRN>\n' +
  '<STMTTRN>\n<TRNTYPE>CREDIT\n<DTPOSTED>20260606\n<TRNAMT>5200.00\n<FITID>FIT-E2E-2\n<NAME>Salario de junho\n</STMTTRN>\n' +
  '</BANKTRANLIST>\n</STMTRS></STMTTRNRS></BANKMSGSRSV1>\n</OFX>\n'

const OFX_XML =
  '<?xml version="1.0" encoding="UTF-8"?>\n' +
  '<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>' +
  '<STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20260610</DTPOSTED><TRNAMT>-42.00</TRNAMT>' +
  '<FITID>FIT-XML-1</FITID><NAME>Farmacia Central</NAME></STMTTRN>' +
  '<STMTTRN><TRNTYPE>CREDIT</TRNTYPE><DTPOSTED>20260611</DTPOSTED><TRNAMT>300.00</TRNAMT>' +
  '<FITID>FIT-XML-2</FITID><NAME>Reembolso</NAME></STMTTRN>' +
  '</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>'

const OFX_MALFORMED = 'isto não é um arquivo OFX de verdade'

const OFX_CARD =
  '<OFX><CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS>' +
  '<CCACCTFROM><ACCTID>4111111111111111</ACCTID></CCACCTFROM>' +
  '</CCSTMTRS></CCSTMTTRNRS></CREDITCARDMSGSRSV1></OFX>'

async function createAccount(
  page: Page,
  name = 'Conta Corrente',
  type: 'CHECKING' | 'SAVINGS' = 'CHECKING',
  openingBalance = 1000,
): Promise<number> {
  const response = await pagePost(page, '/accounts', { name, type, openingBalance })
  expect(response.ok()).toBeTruthy()
  return (await response.json()).id as number
}

/** Opens the upload dialog and sends an in-memory statement file. */
async function uploadStatement(
  page: Page,
  accountName: string,
  filename: string,
  content: string,
) {
  await page.goto('/statement-imports')
  await page
    .getByRole('button', { name: 'Importar extrato' })
    .first()
    .click()
  await page.getByLabel('Conta de destino').selectOption({ label: accountName })
  await page.getByLabel('Arquivo do extrato').setInputFiles({
    name: filename,
    mimeType: 'application/octet-stream',
    buffer: Buffer.from(content, 'utf-8'),
  })
  await page.getByRole('button', { name: 'Enviar extrato' }).click()
}

/** Assigns every empty row category select to its first available option. */
async function assignAllCategories(page: Page) {
  const selects = page.locator('select[aria-label^="Categoria de"]')
  const count = await selects.count()
  for (let index = 0; index < count; index += 1) {
    const select = selects.nth(index)
    if ((await select.inputValue()) === '') {
      const label = await select.getAttribute('aria-label')
      const saved = page.waitForResponse((response) =>
        response.request().method() === 'PATCH' && /\/statement-imports\/\d+\/items\/\d+$/.test(response.url()),
      )
      await select.selectOption({ index: 1 })
      await saved
      // Each assignment refetches the batch; resolve a fresh node and wait for persistence.
      await expect(page.getByLabel(label!, { exact: true })).not.toHaveValue('')
    }
  }
}

async function confirmImport(page: Page) {
  await page.getByRole('button', { name: /Importar \d+ lançamento/ }).click()
  await page.getByRole('button', { name: 'Criar transações' }).click()
  await expect(page.getByText('Resultado da operação')).toBeVisible()
}

test('full CSV journey: mapping, categories, accounting effect and idempotent reupload', async ({
  page,
}) => {
  await registerViaUi(page)
  const accountId = await createAccount(page)
  const foodCategory = await categoryId(page.request, 'Alimentação', 'EXPENSE')
  await pagePost(page, '/budgets', {
    month: '2026-06',
    categoryId: foodCategory,
    limitAmount: 500,
  })

  await uploadStatement(page, 'Conta Corrente', 'extrato-junho.csv', CSV_SIGNED)

  // The CSV waits for explicit mapping; the raw rows are visible.
  await expect(page.getByText('Aguardando mapeamento')).toBeVisible()
  await expect(page.getByText('Mercado Central').first()).toBeVisible()

  await page.getByLabel('Coluna de data').selectOption('0')
  await page.getByLabel('Coluna de descrição').selectOption('1')
  await page.getByLabel('Coluna de valor').selectOption('2')
  await page.getByRole('button', { name: 'Testar mapeamento' }).click()
  await expect(page.getByText(/2 de 2/)).toBeVisible()
  await page.getByRole('button', { name: 'Processar arquivo' }).click()

  // Preview: nothing imported yet, categories pending.
  await expect(page.getByText('Prontos para importar')).toBeVisible()
  const before = await (await pageGet(page, `/accounts/${accountId}`)).json()
  expect(before.currentBalance).toBe(1000)

  await assignAllCategories(page)
  await confirmImport(page)

  // Real transactions exist exactly once: balance, budget and list agree.
  const account = await (await pageGet(page, `/accounts/${accountId}`)).json()
  expect(account.currentBalance).toBeCloseTo(1000 - 120.5 + 80, 2)
  const budgets = await (await pageGet(page, '/budgets?month=2026-06')).json()
  expect(budgets.budgets[0].consumedAmount).toBeCloseTo(120.5, 2)

  // Reuploading the same file surfaces exact duplicates and imports nothing.
  await page.getByRole('button', { name: 'Voltar ao histórico' }).click()
  await uploadStatement(page, 'Conta Corrente', 'extrato-junho.csv', CSV_SIGNED)
  await page.getByLabel('Coluna de data').selectOption('0')
  await page.getByLabel('Coluna de descrição').selectOption('1')
  await page.getByLabel('Coluna de valor').selectOption('2')
  await page.getByRole('button', { name: 'Testar mapeamento' }).click()
  await expect(page.getByText(/2 de 2/)).toBeVisible()
  await page.getByRole('button', { name: 'Processar arquivo' }).click()

  await expect(page.getByText('Este arquivo já foi enviado para esta conta antes.', { exact: false })).toBeVisible()
  // CSV rows carry no bank identifier, so the reupload surfaces content
  // matches as possible duplicates that demand an explicit decision.
  await expect(page.getByRole('button', { name: /Possível duplicata/ }).first()).toBeVisible()
  await expect(page.getByText('2 exigem decisão')).toBeVisible()
  const after = await (await pageGet(page, `/accounts/${accountId}`)).json()
  expect(after.currentBalance).toBeCloseTo(1000 - 120.5 + 80, 2)
})

test('debit and credit columns map into income and expense rows', async ({ page }) => {
  await registerViaUi(page)
  await createAccount(page, 'Poupança', 'SAVINGS')

  await uploadStatement(page, 'Poupança', 'debito-credito.csv', CSV_DEBIT_CREDIT)
  await expect(page.getByText('Aguardando mapeamento')).toBeVisible()

  await page.getByRole('radio', { name: /Colunas separadas de débito e crédito/ }).check()
  await page.getByLabel('Coluna de data').selectOption('0')
  await page.getByLabel('Coluna de descrição').selectOption('1')
  await page.getByLabel('Coluna de débito (saídas)').selectOption('2')
  await page.getByLabel('Coluna de crédito (entradas)').selectOption('3')
  await page.getByRole('button', { name: 'Testar mapeamento' }).click()
  await expect(page.getByText(/2 de 2/)).toBeVisible()
  await page.getByRole('button', { name: 'Processar arquivo' }).click()

  // The debit landed as expense (−95,30) and the credit as income (+150,00).
  await expect(page.getByText('-R$ 95,30').first()).toBeVisible()
  await expect(page.getByText('R$ 150,00').first()).toBeVisible()
})

test('possible duplicate against a manual transaction requires the explicit override', async ({
  page,
}) => {
  await registerViaUi(page)
  const accountId = await createAccount(page)
  const foodCategory = await categoryId(page.request, 'Alimentação', 'EXPENSE')
  await pagePost(page, '/transactions', {
    type: 'EXPENSE',
    amount: 25.9,
    description: 'Padaria São João',
    date: '2026-06-04',
    categoryId: foodCategory,
    accountId,
  })

  await uploadStatement(page, 'Conta Corrente', 'extrato.ofx', OFX_SGML)
  await expect(page.getByRole('button', { name: /Possível duplicata/ })).toBeVisible()

  // Side-by-side review, then the explicit decision.
  await page.getByRole('button', { name: /Possível duplicata/ }).click()
  await expect(page.getByText('Transação existente no Finora')).toBeVisible()
  await page.getByRole('button', { name: 'Importar mesmo assim' }).click()

  await assignAllCategories(page)
  await confirmImport(page)

  // Manual + income + overridden expense — the override worked exactly once.
  const transactions = await (await pageGet(page, '/transactions?month=2026-06')).json()
  expect(transactions.totalElements).toBe(3)
})

test('a category correction saved as a rule is reused deterministically', async ({ page }) => {
  await registerViaUi(page)
  await createAccount(page, 'Conta Corrente')
  await createAccount(page, 'Poupança', 'SAVINGS')

  await uploadStatement(page, 'Conta Corrente', 'extrato.ofx', OFX_SGML)
  await expect(page.getByRole('button', { name: /Editar Padaria/ })).toBeVisible()

  // Correct the category and save it as a deterministic rule.
  await page.getByRole('button', { name: /Editar Padaria/ }).click()
  await page.getByLabel('Categoria', { exact: true }).selectOption({ label: 'Alimentação' })
  await page.getByRole('checkbox', { name: /Criar uma regra/ }).check()
  await page.getByRole('button', { name: 'Salvar alterações' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()

  // The next statement (other account, same description) gets the suggestion.
  await page.getByRole('button', { name: 'Voltar ao histórico' }).click()
  await uploadStatement(page, 'Poupança', 'extrato-2.ofx', OFX_SGML)
  await expect(page.getByText(/Sugerida pela regra/).first()).toBeVisible()
})

test('OFX XML parses; malformed and card statements are blocked safely', async ({ page }) => {
  await registerViaUi(page)
  await createAccount(page)

  await uploadStatement(page, 'Conta Corrente', 'extrato-xml.ofx', OFX_XML)
  await expect(page.getByText('Farmacia Central')).toBeVisible()
  await expect(page.getByText('Reembolso')).toBeVisible()

  // Malformed OFX: a safe Portuguese message, never a stack trace.
  await page.getByRole('button', { name: 'Voltar ao histórico' }).click()
  await uploadStatement(page, 'Conta Corrente', 'quebrado.ofx', OFX_MALFORMED)
  const malformedError = page.getByRole('alert')
  await expect(malformedError).toBeVisible()
  await expect(malformedError).not.toContainText(/Exception|Stack|SAX|org\./)
  await page.getByRole('button', { name: 'Cancelar' }).click()

  // A card statement is redirected to the Cartões area.
  await uploadStatement(page, 'Conta Corrente', 'fatura.ofx', OFX_CARD)
  await expect(page.getByRole('alert')).toContainText('Cartões')
})

test('partial failure keeps the batch retryable until every row imports', async ({ page }) => {
  await registerViaUi(page)
  await createAccount(page)
  const foodCategory = await categoryId(page.request, 'Alimentação', 'EXPENSE')

  await uploadStatement(page, 'Conta Corrente', 'extrato.ofx', OFX_SGML)
  await expect(page.getByText('Prontos para importar')).toBeVisible()
  await assignAllCategories(page)

  // Sabotage one row: its category is deactivated after selection.
  await pagePut(page, `/categories/${foodCategory}`, {
    name: 'Alimentação',
    type: 'EXPENSE',
    active: false,
  })
  await confirmImport(page)
  await expect(page.getByText(/1 lançamento falhou/)).toBeVisible()
  await expect(page.getByText('Lançamento importado.').first()).toBeVisible()

  // Fix the cause and retry: only the failed row is imported again.
  await pagePut(page, `/categories/${foodCategory}`, {
    name: 'Alimentação',
    type: 'EXPENSE',
    active: true,
  })
  await page.getByRole('button', { name: 'Tentar novamente os lançamentos com falha' }).click()
  await expect(page.getByText(/1 lançamento falhou/)).toBeHidden()

  const transactions = await (await pageGet(page, '/transactions?month=2026-06')).json()
  expect(transactions.totalElements).toBe(2)
})

test('undo removes the financial effect once and preserves the audit ledger', async ({
  page,
}) => {
  await registerViaUi(page)
  const accountId = await createAccount(page)

  await uploadStatement(page, 'Conta Corrente', 'extrato.ofx', OFX_SGML)
  await expect(page.getByText('Prontos para importar')).toBeVisible()
  await assignAllCategories(page)
  await confirmImport(page)

  const imported = await (await pageGet(page, `/accounts/${accountId}`)).json()
  expect(imported.currentBalance).toBeCloseTo(1000 - 25.9 + 5200, 2)

  // Item undo, confirmed in a dialog that explains the financial effect.
  await page.getByRole('button', { name: /Desfazer importação de Padaria/ }).click()
  await expect(page.getByText(/efeito financeiro desaparece/)).toBeVisible()
  await page.getByRole('dialog').getByRole('button', { name: 'Desfazer lançamento' }).click()
  await expect(page.getByText('Importação desfeita.').first()).toBeVisible()
  const afterItem = await (await pageGet(page, `/accounts/${accountId}`)).json()
  expect(afterItem.currentBalance).toBeCloseTo(1000 + 5200, 2)

  // Batch undo finishes the rest; the ledger survives as UNDONE rows. The
  // trigger and the dialog's own confirm button share the exact same
  // accessible name ("Desfazer importação"), and the dialog is a native
  // <dialog> whose close transition can momentarily leave neither button
  // matched — so "the trigger becomes hidden" is not a safe completion
  // signal here. The per-item result text only renders once the mutation's
  // onSuccess callback has run with the server's response, so wait on that
  // instead.
  await page.getByRole('button', { name: 'Desfazer importação', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Desfazer importação' }).click()
  await expect(
    page.getByRole('region', { name: 'Resultado por lançamento' }).getByText('Desfeito', { exact: true }),
  ).toBeVisible()
  const afterBatch = await (await pageGet(page, `/accounts/${accountId}`)).json()
  expect(afterBatch.currentBalance).toBe(1000)
  await expect(
    page.getByRole('button', { name: 'Desfazer importação', exact: true }),
  ).toBeHidden()
})

test('another user cannot read the batch nor use the account', async ({ page, request }) => {
  await registerViaUi(page)
  const accountId = await createAccount(page)
  await uploadStatement(page, 'Conta Corrente', 'extrato.ofx', OFX_SGML)
  await expect(page.getByText('Prontos para importar')).toBeVisible()
  const history = await (await pageGet(page, '/statement-imports')).json()
  const batchId = history.content[0].id as number

  const attacker = await apiSession(request)
  const detail = await request.get(`${API}/statement-imports/${batchId}`)
  expect(detail.status()).toBe(404)

  const upload = await request.post(`${API}/statement-imports`, {
    headers: { 'X-XSRF-TOKEN': attacker.token },
    multipart: {
      file: {
        name: 'ataque.ofx',
        mimeType: 'application/octet-stream',
        buffer: Buffer.from(OFX_SGML, 'utf-8'),
      },
      accountId: String(accountId),
    },
  })
  expect(upload.status()).toBe(404)

  // The victim's batch remains intact.
  const stillThere = await (await pageGet(page, `/statement-imports/${batchId}`)).json()
  expect(stillThere.items.length).toBe(2)
})

test('the primary CSV flow completes at 390px', async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } })
  const page = await context.newPage()
  await registerViaUi(page)
  await createAccount(page)

  // Navigation through the mobile drawer.
  await page.goto('/dashboard')
  await page.getByRole('button', { name: 'Abrir menu' }).click()
  await page.getByRole('link', { name: 'Importar extrato' }).click()
  await page.getByRole('button', { name: 'Importar extrato' }).first().click()
  await page.getByLabel('Conta de destino').selectOption({ label: 'Conta Corrente' })
  await page.getByLabel('Arquivo do extrato').setInputFiles({
    name: 'extrato-mobile.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from(CSV_SIGNED, 'utf-8'),
  })
  await page.getByRole('button', { name: 'Enviar extrato' }).click()

  await page.getByLabel('Coluna de data').selectOption('0')
  await page.getByLabel('Coluna de descrição').selectOption('1')
  await page.getByLabel('Coluna de valor').selectOption('2')
  await page.getByRole('button', { name: 'Testar mapeamento' }).click()
  await expect(page.getByText(/2 de 2/)).toBeVisible()
  await page.getByRole('button', { name: 'Processar arquivo' }).click()

  await expect(page.getByText('Prontos para importar')).toBeVisible()
  await assignAllCategories(page)
  await confirmImport(page)
  await expect(page.getByText('Lançamento importado.').first()).toBeVisible()

  // The page never scrolls horizontally on mobile. document.body.scrollWidth
  // is the right signal here, not document.documentElement.scrollWidth: the
  // wide item table legitimately scrolls inside its own .table-wrap
  // (overflow-x: auto) and never widens the body, but Chromium's root
  // <html> element reports a scrollWidth that includes that contained
  // table's intrinsic width regardless — a measurement quirk of the root
  // element, not an actual page-level scrollbar.
  const overflow = await page.evaluate(() => {
    const limit = document.documentElement.clientWidth
    const excess = document.body.scrollWidth - limit
    if (excess <= 0) {
      return { excess, offenders: [] as string[] }
    }
    // The culprit chain: elements wider than the viewport whose own overflow
    // is visible (a scroll container legitimately exceeds and is excluded).
    const offenders: string[] = []
    for (const element of Array.from(document.querySelectorAll('*'))) {
      const style = getComputedStyle(element)
      if (element.scrollWidth > limit + 1 && style.overflowX === 'visible') {
        offenders.push(
          `${element.tagName}.${String(element.className)} scrollWidth=${element.scrollWidth}`,
        )
      }
    }
    return { excess, offenders: offenders.slice(0, 12) }
  })
  expect(overflow, JSON.stringify(overflow.offenders)).toMatchObject({ excess: 0 })

  await context.close()
})
