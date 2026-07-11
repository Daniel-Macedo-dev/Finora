import { expect, test, type Page } from '@playwright/test'
import { apiSession, pageGet, pagePost, registerViaUi } from './helpers'

/**
 * Credit-card lifecycle: card creation, one-time and installment purchases,
 * invoice assignment, full/partial payments, reversal, isolation and legacy
 * credit behavior. Purchases use fixed 2031 dates so derived invoice months
 * and statuses never depend on the real calendar.
 */

async function createCardViaUi(page: Page, name: string, limit: string) {
  await page.goto('/credit-cards')
  await page.getByRole('button', { name: 'Adicionar cartão' }).first().click()
  await page.getByLabel('Nome do cartão').fill(name)
  await page.getByLabel('Limite (R$)').fill(limit)
  await page.getByLabel('Dia de fechamento').fill('10')
  await page.getByLabel('Dia de vencimento').fill('17')
  await page.getByRole('button', { name: 'Adicionar cartão' }).last().click()
  await expect(page.getByRole('link', { name: new RegExp(name) })).toBeVisible()
}

async function createPurchaseViaUi(
  page: Page,
  options: { description: string; date: string; amount: string; installments: string },
) {
  await page.getByRole('button', { name: 'Nova compra' }).first().click()
  await page.getByLabel('Descrição').fill(options.description)
  await page.getByLabel('Categoria').selectOption({ label: 'Compras' })
  await page.getByLabel('Data da compra').fill(options.date)
  await page.getByLabel('Valor total (R$)').fill(options.amount)
  await page.getByLabel('Parcelas').fill(options.installments)
  await page.getByRole('button', { name: 'Registrar compra' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()
}

test.describe('Cenário — Cartões de crédito', () => {
  test('cria cartão, registra compras e mantém o limite exato', async ({ page }) => {
    await registerViaUi(page)
    await createCardViaUi(page, 'Cartão Principal', '5000,00')

    // Empty invoice state and full available limit.
    await page.getByRole('link', { name: /Cartão Principal/ }).click()
    await expect(page.getByRole('heading', { name: 'Cartão Principal' })).toBeVisible()
    await expect(page.getByText('Nenhuma fatura ainda')).toBeVisible()
    await expect(page.getByText('Disponível')).toContainText('R$ 5.000,00')

    // One-time purchase before the closing day → March 2031 invoice.
    await createPurchaseViaUi(page, {
      description: 'Fone de ouvido',
      date: '2031-03-05',
      amount: '350,00',
      installments: '1',
    })
    await expect(page.getByRole('link', { name: 'março de 2031' })).toBeVisible()
    await expect(page.getByText('Usado')).toContainText('R$ 350,00')

    // Uneven installment purchase: exact cent split across three months.
    await createPurchaseViaUi(page, {
      description: 'Notebook',
      date: '2031-03-06',
      amount: '1000,01',
      installments: '3',
    })
    await expect(page.getByRole('link', { name: 'abril de 2031' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'maio de 2031' })).toBeVisible()
    await expect(page.getByText('Usado')).toContainText('R$ 1.350,01')

    // The bank account is never touched by purchases (there is none yet).
    const cards = await (await pageGet(page, '/credit-cards')).json()
    expect(cards[0].limit.availableLimit).toBe(3649.99)

    // March invoice = 350.00 + 333.33 (installment 1/3, remainder on the last).
    await page.getByRole('link', { name: 'março de 2031' }).click()
    await expect(page.getByRole('heading', { name: /Fatura de março de 2031/ })).toBeVisible()
    await expect(page.getByText('Total da fatura').locator('..')).toContainText('R$ 683,33')
    await expect(page.getByText('1/3')).toBeVisible()
  })

  test('paga fatura integral e parcialmente, com estorno exato', async ({ page }) => {
    await registerViaUi(page)
    // Cash for the payment comes from a real account created via the API.
    const account = await (
      await pagePost(page, '/accounts', {
        name: 'Conta Pagadora',
        type: 'CHECKING',
        openingBalance: 4000,
      })
    ).json()

    await createCardViaUi(page, 'Cartão Fatura', '5000,00')
    await page.getByRole('link', { name: /Cartão Fatura/ }).click()
    await createPurchaseViaUi(page, {
      description: 'Geladeira',
      date: '2031-03-05',
      amount: '1000,00',
      installments: '2',
    })

    // Full payment of the March invoice (R$ 500,00).
    await page.getByRole('link', { name: 'março de 2031' }).click()
    await page.getByRole('button', { name: 'Pagar fatura' }).click()
    await expect(
      page.getByText('O pagamento reduz o saldo da conta, mas não registra uma nova despesa', {
        exact: false,
      }),
    ).toBeVisible()
    await page.getByLabel('Conta', { exact: true }).selectOption(String(account.id))
    await page.getByRole('button', { name: 'Confirmar pagamento' }).click()
    await expect(page.getByRole('dialog')).toBeHidden()
    await expect(page.getByText('Paga', { exact: true })).toBeVisible()

    // Balance drops exactly once; no new expense appears anywhere.
    let accounts = await (await pageGet(page, '/accounts')).json()
    expect(accounts.find((a: { id: number }) => a.id === account.id).currentBalance).toBe(3500)

    // Partial then completing payment on the April invoice.
    await page.getByRole('link', { name: /Cartão Fatura/ }).click()
    await page.getByRole('link', { name: 'abril de 2031' }).click()
    await page.getByRole('button', { name: 'Pagar fatura' }).click()
    await page.getByLabel('Conta', { exact: true }).selectOption(String(account.id))
    await page.getByRole('button', { name: 'Valor parcial' }).click()
    await page.getByLabel('Valor parcial (R$)').fill('200,00')
    await page.getByRole('button', { name: 'Confirmar pagamento' }).click()
    await expect(page.getByRole('dialog')).toBeHidden()
    await expect(page.getByText('Parcialmente paga')).toBeVisible()
    await expect(page.getByText('Em aberto').last().locator('..')).toContainText('R$ 300,00')

    await page.getByRole('button', { name: 'Pagar fatura' }).click()
    await page.getByLabel('Conta', { exact: true }).selectOption(String(account.id))
    await page.getByRole('button', { name: 'Confirmar pagamento' }).click()
    await expect(page.getByRole('dialog')).toBeHidden()
    await expect(page.getByText('Paga', { exact: true })).toBeVisible()

    accounts = await (await pageGet(page, '/accounts')).json()
    expect(accounts.find((a: { id: number }) => a.id === account.id).currentBalance).toBe(3000)

    // Reversal restores account, outstanding and limit exactly once.
    await page
      .getByRole('button', { name: /Estornar pagamento/ })
      .last()
      .click()
    await page.getByRole('button', { name: 'Estornar', exact: true }).click()
    await expect(page.getByText('Estornado').first()).toBeVisible()
    accounts = await (await pageGet(page, '/accounts')).json()
    expect(accounts.find((a: { id: number }) => a.id === account.id).currentBalance).toBe(3300)
    const cards = await (await pageGet(page, '/credit-cards')).json()
    expect(cards[0].limit.usedLimit).toBe(300)
  })

  test('usuário B não vê nem alcança cartões, faturas ou pagamentos de A', async ({
    page,
    request,
  }) => {
    await registerViaUi(page)
    await createCardViaUi(page, 'Cartão Secreto', '2000,00')
    await page.getByRole('link', { name: /Cartão Secreto/ }).click()
    await createPurchaseViaUi(page, {
      description: 'Compra privada',
      date: '2031-03-05',
      amount: '100,00',
      installments: '1',
    })
    const cards = await (await pageGet(page, '/credit-cards')).json()
    const cardId = cards[0].id
    const invoices = await (await pageGet(page, `/credit-cards/${cardId}/invoices`)).json()
    const invoiceId = invoices[0].id

    // User B, in a separate API session, probes A's known ids directly.
    const intruder = await apiSession(request)
    const headers = { 'X-XSRF-TOKEN': intruder.token }
    const base = 'http://localhost:8080/api'
    expect((await request.get(`${base}/credit-cards`)).ok()).toBeTruthy()
    expect(await (await request.get(`${base}/credit-cards`)).json()).toEqual([])
    expect((await request.get(`${base}/credit-cards/${cardId}`)).status()).toBe(404)
    expect(
      (await request.get(`${base}/credit-cards/${cardId}/invoices/${invoiceId}`)).status(),
    ).toBe(404)
    expect(
      (
        await request.post(`${base}/credit-cards/${cardId}/invoices/${invoiceId}/payments`, {
          headers,
          data: { accountId: 1, amount: 10, paidOn: '2031-03-15' },
        })
      ).status(),
    ).toBe(404)
  })

  test('crédito genérico é rejeitado e direcionado para a área de cartões', async ({ page }) => {
    await registerViaUi(page)

    // The transaction form no longer offers "Crédito" and points to the cards area.
    await page.goto('/transactions')
    await page.getByRole('button', { name: 'Nova transação' }).first().click()
    const methods = page.getByLabel('Forma de pagamento (opcional)')
    await expect(methods.locator('option', { hasText: 'Crédito' })).toHaveCount(0)
    await expect(page.getByText('Registre na área de Cartões')).toBeVisible()
    await page.getByRole('button', { name: 'Cancelar' }).click()

    // The API enforces the same rule.
    const response = await pagePost(page, '/transactions', {
      type: 'EXPENSE',
      amount: 50,
      description: 'Crédito genérico',
      date: '2031-03-05',
      categoryId: (await (await pageGet(page, '/categories?type=EXPENSE')).json())[0].id,
      paymentMethod: 'CREDIT',
    })
    expect(response.status()).toBe(422)
    expect((await response.json()).code).toBe('USE_CREDIT_CARD_PURCHASE')
  })
})
