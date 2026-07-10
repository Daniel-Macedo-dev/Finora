import { expect, test } from '@playwright/test'
import { createAccount, resetData, resetSettings, seedThreeMonthHistory } from './helpers.ts'

test.describe('Cenário 3 — Planejar uma compra', () => {
  test.beforeEach(async ({ request }) => {
    await resetData(request)
  })

  test('compara opções e recomenda a compra à vista mais barata', async ({ page, request }) => {
    await createAccount(request, 'Conta principal', 10000)
    await seedThreeMonthHistory(request, 6000, 4000) // sobra média 2000/mês
    await resetSettings(request, { minimumCashBuffer: 2000 })

    await page.goto('/wishlist')
    await page.getByRole('button', { name: 'Novo item' }).first().click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Nome do item').fill('Notebook para trabalho')
    await dialog.getByRole('button', { name: 'Adicionar item' }).click()
    await expect(dialog).toBeHidden()

    await page.getByRole('link', { name: 'Notebook para trabalho' }).click()

    // Cash option: 4500 total
    await page.getByRole('button', { name: 'Nova opção' }).click()
    await dialog.getByLabel('Loja / vendedor').fill('Loja À Vista')
    await dialog.getByLabel('Preço à vista (R$)').fill('4500,00')
    await dialog.getByRole('button', { name: 'Adicionar opção' }).click()
    await expect(dialog).toBeHidden()

    // Installment option: 10x500 = 5000
    await page.getByRole('button', { name: 'Nova opção' }).click()
    await dialog.getByLabel('Loja / vendedor').fill('Loja Parcelada')
    await dialog.getByRole('button', { name: 'Parcelado' }).click()
    await dialog.getByLabel('Preço total parcelado (R$)').fill('5000,00')
    await dialog.getByLabel('Nº de parcelas').fill('10')
    await dialog.getByLabel('Valor da parcela (R$)').fill('500,00')
    await dialog.getByRole('button', { name: 'Adicionar opção' }).click()
    await expect(dialog).toBeHidden()

    const analysis = page.getByRole('region', { name: 'Análise de compra' })
    await expect(analysis.getByText('Comprar à vista', { exact: true })).toBeVisible()
    await expect(analysis.getByText(/menor valor presente/)).toBeVisible()
    await expect(analysis.getByText('Recomendada')).toBeVisible()
    await expect(analysis.getByText('Premissas usadas')).toBeVisible()
    await expect(analysis.getByText('R$ 10.000,00')).toBeVisible() // caixa disponível
  })

  test('rejeita parcelas que não fecham com o total informado', async ({ page }) => {
    await page.goto('/wishlist')
    await page.getByRole('button', { name: 'Novo item' }).first().click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Nome do item').fill('Monitor')
    await dialog.getByRole('button', { name: 'Adicionar item' }).click()
    await expect(dialog).toBeHidden()

    await page.getByRole('link', { name: 'Monitor' }).click()
    await page.getByRole('button', { name: 'Nova opção' }).click()
    await dialog.getByLabel('Loja / vendedor').fill('Loja X')
    await dialog.getByRole('button', { name: 'Parcelado' }).click()
    await dialog.getByLabel('Preço total parcelado (R$)').fill('2000,00')
    await dialog.getByLabel('Nº de parcelas').fill('10')
    await dialog.getByLabel('Valor da parcela (R$)').fill('180,00')
    await dialog.getByRole('button', { name: 'Adicionar opção' }).click()
    await expect(dialog.getByText(/não correspondem ao preço total/)).toBeVisible()
    await expect(dialog).toBeVisible()
  })
})
