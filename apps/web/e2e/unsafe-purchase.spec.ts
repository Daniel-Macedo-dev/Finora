import { expect, test } from '@playwright/test'
import { createAccount, resetData, resetSettings, seedThreeMonthHistory } from './helpers.ts'

test.describe('Cenário 4 — Compra insegura', () => {
  test.beforeEach(async ({ request }) => {
    await resetData(request)
  })

  test('recomenda aguardar quando nenhuma opção respeita a reserva mínima', async ({
    page,
    request,
  }) => {
    await createAccount(request, 'Conta principal', 1000)
    await seedThreeMonthHistory(request, 5000, 4000) // sobra média 1000/mês
    await resetSettings(request, { minimumCashBuffer: 500 })

    await page.goto('/wishlist')
    await page.getByRole('button', { name: 'Novo item' }).first().click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Nome do item').fill('Console de videogame')
    await dialog.getByRole('button', { name: 'Adicionar item' }).click()
    await expect(dialog).toBeHidden()

    await page.getByRole('link', { name: 'Console de videogame' }).click()

    // 3500 + buffer 500 - caixa 1000 = 3000 faltando -> 3 meses de sobra média
    await page.getByRole('button', { name: 'Nova opção' }).click()
    await dialog.getByLabel('Loja / vendedor').fill('Loja Cara')
    await dialog.getByLabel('Preço à vista (R$)').fill('3500,00')
    await dialog.getByRole('button', { name: 'Adicionar opção' }).click()
    await expect(dialog).toBeHidden()

    const analysis = page.getByRole('region', { name: 'Análise de compra' })
    await expect(analysis.getByText('Aguardar')).toBeVisible()
    await expect(analysis.getByText('R$ 3.000,00').first()).toBeVisible()
    await expect(analysis.getByText(/3 meses/)).toBeVisible()
    await expect(analysis.getByText('Arriscada')).toBeVisible()
  })
})
