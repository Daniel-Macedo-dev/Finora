import { expect, test } from '@playwright/test'
import { pageGet, pagePost, registerViaUi } from './helpers.ts'

/** The full card journey at 390px: create, buy, inspect the invoice and pay. */

test.use({ viewport: { width: 390, height: 844 } })

test.describe('Cenário — Cartões no celular (390px)', () => {
  test('cria cartão, registra compra, abre a fatura e paga pelo menu mobile', async ({
    page,
  }) => {
    await registerViaUi(page)
    const account = await (
      await pagePost(page, '/accounts', {
        name: 'Conta Mobile',
        type: 'CHECKING',
        openingBalance: 1000,
      })
    ).json()

    // Reach the cards area through the mobile navigation drawer.
    await page.getByRole('button', { name: 'Abrir menu' }).click()
    await page.getByRole('link', { name: 'Cartões' }).click()
    await expect(page.getByRole('heading', { name: 'Cartões' })).toBeVisible()

    await page.getByRole('button', { name: 'Adicionar cartão' }).first().click()
    await page.getByLabel('Nome do cartão').fill('Cartão Mobile')
    await page.getByLabel('Limite (R$)').fill('800,00')
    await page.getByLabel('Dia de fechamento').fill('10')
    await page.getByLabel('Dia de vencimento').fill('17')
    await page.getByRole('button', { name: 'Adicionar cartão' }).last().click()

    await page.getByRole('link', { name: /Cartão Mobile/ }).click()
    await page.getByRole('button', { name: 'Nova compra' }).first().click()
    await page.getByLabel('Descrição').fill('Fone bluetooth')
    await page.getByLabel('Categoria').selectOption({ label: 'Compras' })
    await page.getByLabel('Data da compra').fill('2031-03-05')
    await page.getByLabel('Valor total (R$)').fill('240,00')
    await page.getByLabel('Parcelas').fill('2')
    await page.getByRole('button', { name: 'Registrar compra' }).click()
    await expect(page.getByRole('dialog')).toBeHidden()

    await page.getByRole('link', { name: 'março de 2031' }).click()
    await expect(page.getByRole('heading', { name: /Fatura de março de 2031/ })).toBeVisible()
    await page.getByRole('button', { name: 'Pagar fatura' }).click()
    await page.getByLabel('Conta', { exact: true }).selectOption(String(account.id))
    await page.getByRole('button', { name: 'Confirmar pagamento' }).click()
    await expect(page.getByRole('dialog')).toBeHidden()
    await expect(page.getByText('Paga', { exact: true })).toBeVisible()

    const accounts = await (await pageGet(page, '/accounts')).json()
    expect(
      accounts.find((entry: { id: number }) => entry.id === account.id).currentBalance,
    ).toBe(880)
  })
})
