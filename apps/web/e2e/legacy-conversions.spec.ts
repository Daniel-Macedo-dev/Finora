import { execSync } from 'node:child_process'
import { expect, test, type Page } from '@playwright/test'
import { apiSession, categoryId, pageGet, pagePost, registerViaUi } from './helpers.ts'

/**
 * Assisted legacy-credit conversion journeys: inventory, wizard with the
 * deterministic preview, single-count accounting, idempotency, reversal with
 * settlement guards, mixed batches, recurring mapping without backfill, user
 * isolation and the full mobile flow.
 *
 * Legacy CREDIT rows predate the card domain and can only be born in
 * migration V7, so tests forge them exactly like the migration did — flagging
 * a real transaction directly in the development PostgreSQL container.
 * All financial dates are fixed in late 2025, so invoice months (closing day
 * 10 → first invoice December 2025) never depend on the calendar.
 */

let pgContainer: string | null = null

/**
 * Resolves the PostgreSQL container: the docker-compose name locally, or the
 * generated service-container id on CI (discovered by image ancestry;
 * FINORA_PG_CONTAINER overrides both).
 */
function resolvePgContainer(): string {
  if (pgContainer) {
    return pgContainer
  }
  if (process.env.FINORA_PG_CONTAINER) {
    return (pgContainer = process.env.FINORA_PG_CONTAINER)
  }
  try {
    execSync('docker inspect finora-postgres', { stdio: 'ignore' })
    return (pgContainer = 'finora-postgres')
  } catch {
    const id = execSync('docker ps -q --filter "ancestor=postgres:16.6-alpine"')
      .toString()
      .trim()
      .split('\n')[0]
    if (!id) {
      throw new Error('Contêiner PostgreSQL não encontrado para forjar crédito legado')
    }
    return (pgContainer = id)
  }
}

function forgeLegacyCredit(transactionId: number) {
  execSync(
    `docker exec ${resolvePgContainer()} psql -U finora -d finora -c ` +
      `"UPDATE transactions SET payment_method = 'CREDIT', legacy_credit = TRUE WHERE id = ${transactionId}"`,
  )
}

async function seedLegacyTransaction(
  page: Page,
  amount: number,
  date = '2025-11-20',
  accountId?: number,
): Promise<number> {
  const compras = await categoryId(page.request, 'Compras', 'EXPENSE')
  const response = await pagePost(page, '/transactions', {
    type: 'EXPENSE',
    amount,
    description: `Compra antiga de ${amount}`,
    date,
    categoryId: compras,
    ...(accountId ? { accountId } : {}),
  })
  expect(response.ok()).toBeTruthy()
  const id = (await response.json()).id as number
  forgeLegacyCredit(id)
  return id
}

async function seedCard(page: Page, name: string, creditLimit: number): Promise<number> {
  const response = await pagePost(page, '/credit-cards', {
    name,
    brand: 'VISA',
    creditLimit,
    closingDay: 10,
    dueDay: 17,
  })
  expect(response.ok()).toBeTruthy()
  return (await response.json()).id as number
}

test.describe('Cenário — Conversão assistida de crédito legado', () => {
  test('converte pelo assistente e a despesa nunca conta em dobro', async ({ page }) => {
    await registerViaUi(page)
    const sourceId = await seedLegacyTransaction(page, 300)
    await seedCard(page, 'Cartão Roxo', 10000)

    // Before: November holds the expense.
    expect((await (await pageGet(page, '/dashboard?month=2025-11')).json()).expense).toBe(300)
    expect((await (await pageGet(page, '/dashboard?month=2025-12')).json()).expense).toBe(0)

    // Inventory shows the eligible source with its summary.
    await page.goto('/legacy-credit')
    await expect(page.getByText('Elegíveis para conversão')).toBeVisible()
    await expect(page.getByText('Compra antiga de 300')).toBeVisible()

    // Filters narrow by month.
    await page.getByLabel('Filtrar por mês').fill('2025-10')
    await expect(page.getByText('Nenhum crédito legado encontrado')).toBeVisible()
    await page.getByLabel('Filtrar por mês').fill('2025-11')
    await expect(page.getByText('Compra antiga de 300')).toBeVisible()

    // Wizard: source facts → parameters → deterministic schedule → impact →
    // explicit confirmation.
    await page.getByRole('button', { name: 'Converter', exact: true }).click()
    await expect(page.getByText('Valor histórico', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Avançar' }).click()

    await page.getByLabel('Cartão que receberá a compra').selectOption({ index: 1 })
    await page.getByRole('button', { name: 'Avançar' }).click()

    await expect(page.getByText('dezembro de 2025').first()).toBeVisible()
    await page.getByRole('button', { name: 'Avançar' }).click()

    await expect(page.getByText('Limite disponível antes')).toBeVisible()
    await page.getByRole('button', { name: 'Avançar' }).click()

    await page.getByRole('button', { name: 'Confirmar e criar compra real no cartão' }).click()

    // Success lands on the audit detail.
    await expect(page.getByText('Detalhe da conversão')).toBeVisible()
    await expect(page.getByText('Ativa')).toBeVisible()
    await page.getByRole('button', { name: 'Fechar', exact: true }).last().click()

    // The original stays visible as a protected audit record in its month.
    await page.goto('/transactions')
    await expect(page.getByRole('heading', { name: 'Transações' })).toBeVisible()

    // Accounting: the expense moved to the invoice month, exactly once.
    expect((await (await pageGet(page, '/dashboard?month=2025-11')).json()).expense).toBe(0)
    expect((await (await pageGet(page, '/dashboard?month=2025-12')).json()).expense).toBe(300)

    // The generated purchase is real, and the source is financially inactive.
    const transaction = await (await pageGet(page, `/transactions/${sourceId}`)).json()
    expect(transaction.financiallyActive).toBe(false)
    expect(transaction.legacyConversionStatus).toBe('ACTIVE')
    expect(transaction.generatedCardPurchaseId).not.toBeNull()

    // Idempotency: repeating the confirmed request returns the same conversion.
    const inventory = await (await pageGet(page, '/legacy-conversions')).json()
    const conversionId = inventory.page.content[0].conversionId as number
    const retry = await pagePost(page, '/legacy-conversions', {
      transactionId: sourceId,
      cardId: inventory.page.content[0].cardId,
      effectivePurchaseDate: '2025-11-20',
      installmentCount: 1,
      firstInvoiceMonth: '2025-12',
    })
    expect(retry.status()).toBe(201)
    expect((await retry.json()).id).toBe(conversionId)
    expect((await retry.json()).cardPurchaseId).toBe(transaction.generatedCardPurchaseId)
  })

  test('parcela em várias faturas e bloqueia limite insuficiente', async ({ page }) => {
    await registerViaUi(page)
    await seedLegacyTransaction(page, 300)
    const smallCard = await seedCard(page, 'Cartão Pequeno', 100)
    const bigCard = await seedCard(page, 'Cartão Grande', 10000)

    await page.goto('/legacy-credit')
    await page.getByRole('button', { name: 'Converter', exact: true }).click()
    await page.getByRole('button', { name: 'Avançar' }).click()

    // Insufficient limit: the preview reports a blocker and confirmation
    // stays impossible.
    await page.getByLabel('Cartão que receberá a compra').selectOption(String(smallCard))
    await page.getByRole('button', { name: 'Avançar' }).click()
    await expect(page.getByText(/limite/i).first()).toBeVisible()
    await page.getByRole('button', { name: 'Avançar' }).click()
    await page.getByRole('button', { name: 'Avançar' }).click()
    await expect(
      page.getByRole('button', { name: 'Confirmar e criar compra real no cartão' }),
    ).toBeDisabled()

    // Back to parameters: three installments on the big card.
    await page.getByRole('button', { name: 'Voltar' }).click()
    await page.getByRole('button', { name: 'Voltar' }).click()
    await page.getByRole('button', { name: 'Voltar' }).click()
    await page.getByLabel('Cartão que receberá a compra').selectOption(String(bigCard))
    await page.getByLabel('Número de parcelas').fill('3')
    await page.getByRole('button', { name: 'Avançar' }).click()

    // Cent-exact split straight from the backend: 100,00 + 100,00 + 100,00.
    await expect(page.getByText('1/3')).toBeVisible()
    await expect(page.getByText('dezembro de 2025').first()).toBeVisible()
    await expect(page.getByText('fevereiro de 2026').first()).toBeVisible()
    await page.getByRole('button', { name: 'Avançar' }).click()
    await page.getByRole('button', { name: 'Avançar' }).click()
    await page.getByRole('button', { name: 'Confirmar e criar compra real no cartão' }).click()
    await expect(page.getByText('Detalhe da conversão')).toBeVisible()
    await page.getByRole('button', { name: 'Fechar', exact: true }).last().click()

    // Invoice allocation: one installment per invoice month.
    const purchases = await (
      await pageGet(page, `/credit-cards/${bigCard}/purchases?page=0&size=10`)
    ).json()
    const installments = purchases.content[0].installments as Array<{ invoiceMonth: string }>
    expect(installments.map((entry) => entry.invoiceMonth)).toEqual([
      '2025-12',
      '2026-01',
      '2026-02',
    ])
  })

  test('estorna conversão não liquidada e bloqueia após pagamento concluído', async ({
    page,
  }) => {
    await registerViaUi(page)
    const accountResponse = await pagePost(page, '/accounts', {
      name: 'Conta Principal',
      type: 'CHECKING',
      openingBalance: 5000,
    })
    const accountId = (await accountResponse.json()).id as number
    const sourceId = await seedLegacyTransaction(page, 300)
    const cardId = await seedCard(page, 'Cartão Roxo', 10000)

    await pagePost(page, '/legacy-conversions', {
      transactionId: sourceId,
      cardId,
      effectivePurchaseDate: '2025-11-20',
      installmentCount: 1,
      firstInvoiceMonth: '2025-12',
    })

    // Reversal restores the source exactly once.
    await page.goto('/legacy-credit')
    await page.getByRole('button', { name: 'Ver conversão' }).click()
    const detail = page.getByRole('dialog', { name: 'Detalhe da conversão' })
    await expect(detail.getByText('Ativa')).toBeVisible()
    await detail.getByRole('button', { name: 'Estornar conversão' }).click()
    await detail.getByLabel('Motivo (opcional)').fill('Cartão errado')
    await detail.getByRole('button', { name: 'Estornar conversão' }).click()
    await expect(detail.getByText('Estornada', { exact: true })).toBeVisible()
    await expect(detail.getByText('Cartão errado')).toBeVisible()

    const restored = await (await pageGet(page, `/transactions/${sourceId}`)).json()
    expect(restored.financiallyActive).toBe(true)
    expect((await (await pageGet(page, '/dashboard?month=2025-11')).json()).expense).toBe(300)

    // Convert again, pay the invoice, and the reversal becomes blocked.
    const again = await pagePost(page, '/legacy-conversions', {
      transactionId: sourceId,
      cardId,
      effectivePurchaseDate: '2025-11-20',
      installmentCount: 1,
      firstInvoiceMonth: '2025-12',
    })
    expect(again.status()).toBe(201)
    const invoices = await (await pageGet(page, `/credit-cards/${cardId}/invoices`)).json()
    const openInvoice = invoices.find(
      (invoice: { outstandingAmount: number }) => invoice.outstandingAmount > 0,
    )
    const payment = await pagePost(
      page,
      `/credit-cards/${cardId}/invoices/${openInvoice.id}/payments`,
      { accountId, amount: 300, paidOn: '2025-12-17' },
    )
    expect(payment.ok()).toBeTruthy()

    await page.goto('/legacy-credit')
    await page.getByRole('button', { name: 'Ver conversão' }).click()
    const blocked = page.getByRole('dialog', { name: 'Detalhe da conversão' })
    await expect(blocked.getByText(/Estorno indisponível/)).toBeVisible()
    await expect(blocked.getByRole('button', { name: 'Estornar conversão' })).toBeDisabled()

    // Money moved exactly once through the invoice payment.
    expect((await (await pageGet(page, '/dashboard?month=2025-12')).json()).totalBalance).toBe(
      4700,
    )
  })

  test('lote independente: sucesso e falha convivem no mesmo resultado', async ({ page }) => {
    await registerViaUi(page)
    const firstId = await seedLegacyTransaction(page, 300, '2025-11-20')
    await seedLegacyTransaction(page, 200, '2025-11-21')
    // Both previews fit the limit alone; together the second must fail.
    const tightCard = await seedCard(page, 'Cartão Apertado', 350)

    await page.goto('/legacy-credit')
    await page.getByLabel('Selecionar Compra antiga de 300').check()
    await page.getByLabel('Selecionar Compra antiga de 200').check()
    await expect(page.getByText('2 transações selecionadas')).toBeVisible()
    await page.getByRole('button', { name: 'Converter selecionadas' }).click()

    const dialog = page.getByRole('dialog', { name: 'Converter em lote' })
    const cardSelects = dialog.getByLabel('Cartão', { exact: true })
    await cardSelects.nth(0).selectOption(String(tightCard))
    await cardSelects.nth(1).selectOption(String(tightCard))
    await dialog.getByRole('button', { name: 'Calcular faturas' }).click()
    await expect(dialog.getByText(/Primeira fatura: dezembro de 2025/).first()).toBeVisible()
    await dialog.getByRole('button', { name: 'Converter 2 transações' }).click()

    // Mixed outcome, in friendly labels; the failure never hides the success.
    await expect(dialog.getByText('Convertida', { exact: true })).toBeVisible()
    await expect(dialog.getByText('Falhou', { exact: true })).toBeVisible()
    await expect(dialog.getByRole('button', { name: /Tentar novamente 1 falha/ })).toBeVisible()

    // Only the first source converted; batch retries stay idempotent.
    const first = await (await pageGet(page, `/transactions/${firstId}`)).json()
    expect(first.financiallyActive).toBe(false)
    const inventory = await (await pageGet(page, '/legacy-conversions')).json()
    expect(inventory.summary.convertedCount).toBe(1)
    expect(inventory.summary.eligibleCount).toBe(1)
  })

  test('mapeia recorrente legado para cartão sem retroativos', async ({ page }) => {
    await registerViaUi(page)
    const cardId = await seedCard(page, 'Cartão Novo', 5000)
    const assinaturas = await categoryId(page.request, 'Assinaturas', 'EXPENSE')
    // Due day never today: past occurrences exist, none due exactly now.
    const today = new Date()
    const dueDay = today.getDate() === 1 ? 2 : 1
    const commitment = await pagePost(page, '/commitments', {
      description: 'Streaming antigo',
      amount: 39.9,
      categoryId: assinaturas,
      cadence: 'MONTHLY',
      dueDay,
      startDate: `2025-01-${String(dueDay).padStart(2, '0')}`,
      paymentMethod: 'CREDIT',
    })
    expect(commitment.ok()).toBeTruthy()

    await page.goto('/commitments')
    await page.getByRole('button', { name: /Crédito legado — migrar para cartão/ }).click()
    await expect(page.getByText(/Sem retroativos/)).toBeVisible()
    await page.getByLabel('Cartão de destino').selectOption(String(cardId))
    await page.getByLabel('Modo de execução').selectOption('AUTOMATIC')
    await page.getByRole('button', { name: 'Migrar para o cartão' }).click()

    // The definition now targets the card and is no longer "legacy".
    await expect(page.getByText(/Compra no cartão · Cartão Novo/)).toBeVisible()
    await expect(
      page.getByRole('button', { name: /Crédito legado — migrar para cartão/ }),
    ).toBeHidden()

    // No historical backfill: a year of past occurrences produces nothing.
    const processed = await (await pagePost(page, '/commitments/process-due', {})).json()
    expect(processed.materialized).toBe(0)
    const purchases = await (
      await pageGet(page, `/credit-cards/${cardId}/purchases?page=0&size=10`)
    ).json()
    expect(purchases.totalElements).toBe(0)
  })

  test('outro usuário nunca enxerga nem alcança as conversões', async ({ page, request }) => {
    await registerViaUi(page)
    const sourceId = await seedLegacyTransaction(page, 300)
    const cardId = await seedCard(page, 'Cartão Roxo', 10000)
    const converted = await pagePost(page, '/legacy-conversions', {
      transactionId: sourceId,
      cardId,
      effectivePurchaseDate: '2025-11-20',
      installmentCount: 1,
      firstInvoiceMonth: '2025-12',
    })
    const conversionId = (await converted.json()).id as number

    // A second, independent session sees an empty inventory and 404s.
    const intruder = await apiSession(request)
    const inventory = await (
      await request.get('http://localhost:8080/api/legacy-conversions')
    ).json()
    expect(inventory.page.totalElements).toBe(0)
    expect(inventory.summary.eligibleCount).toBe(0)

    const detail = await request.get(
      `http://localhost:8080/api/legacy-conversions/${conversionId}`,
    )
    expect(detail.status()).toBe(404)
    const reversal = await request.post(
      `http://localhost:8080/api/legacy-conversions/${conversionId}/reverse`,
      { headers: { 'X-XSRF-TOKEN': intruder.token }, data: {} },
    )
    expect(reversal.status()).toBe(404)

    // The owner's conversion is intact.
    const mine = await (await pageGet(page, `/legacy-conversions/${conversionId}`)).json()
    expect(mine.status).toBe('ACTIVE')
  })

  test('fluxo principal completo em 390px', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await registerViaUi(page)
    await seedLegacyTransaction(page, 300)
    await seedCard(page, 'Cartão Roxo', 10000)

    await page.goto('/legacy-credit')
    await expect(page.getByText('Elegíveis para conversão')).toBeVisible()
    await page.getByRole('button', { name: 'Converter', exact: true }).click()
    await expect(page.getByText('Valor histórico', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Avançar' }).click()
    await page.getByLabel('Cartão que receberá a compra').selectOption({ index: 1 })
    await page.getByRole('button', { name: 'Avançar' }).click()
    await expect(page.getByText('dezembro de 2025').first()).toBeVisible()
    await page.getByRole('button', { name: 'Avançar' }).click()
    await expect(page.getByText('Limite disponível antes')).toBeVisible()
    await page.getByRole('button', { name: 'Avançar' }).click()
    await page.getByRole('button', { name: 'Confirmar e criar compra real no cartão' }).click()
    await expect(page.getByText('Detalhe da conversão')).toBeVisible()
    await expect(page.getByText('Ativa')).toBeVisible()
  })
})
