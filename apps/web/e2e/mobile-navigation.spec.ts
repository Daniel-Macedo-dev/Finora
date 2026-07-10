import { expect, test } from '@playwright/test'
import { resetData } from './helpers.ts'

test.use({ viewport: { width: 390, height: 844 } })

test.describe('Cenário 5 — Navegação mobile', () => {
  test.beforeEach(async ({ request }) => {
    await resetData(request)
  })

  test('navega pelas páginas principais e cria uma transação no celular', async ({ page }) => {
    await page.goto('/dashboard')

    // Drawer navigation
    await page.getByRole('button', { name: 'Abrir menu' }).click()
    await page.getByRole('link', { name: 'Transações' }).click()
    await expect(page.getByRole('heading', { name: 'Transações' })).toBeVisible()

    // Create a transaction on a small screen
    await page.getByRole('button', { name: 'Nova transação' }).first().click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Valor (R$)').fill('49,90')
    await dialog.getByLabel('Descrição').fill('Lanche mobile')
    await dialog.getByLabel('Categoria').selectOption({ label: 'Alimentação' })
    await dialog.getByRole('button', { name: 'Adicionar transação' }).click()
    await expect(dialog).toBeHidden()
    await expect(page.getByRole('cell', { name: 'Lanche mobile', exact: true })).toBeVisible()

    // Other core routes remain reachable
    await page.getByRole('button', { name: 'Abrir menu' }).click()
    await page.getByRole('link', { name: 'Orçamentos' }).click()
    await expect(page.getByRole('heading', { name: 'Orçamentos' })).toBeVisible()

    await page.getByRole('button', { name: 'Abrir menu' }).click()
    await page.getByRole('link', { name: 'Lista de desejos' }).click()
    await expect(page.getByRole('heading', { name: 'Lista de desejos' })).toBeVisible()
  })
})
