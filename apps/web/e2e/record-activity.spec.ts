import { expect, test } from '@playwright/test'
import { resetData } from './helpers.ts'

test.describe('Cenário 1 — Registrar atividade financeira', () => {
  test.beforeEach(async ({ request }) => {
    await resetData(request)
  })

  test('registra receita e despesa e reflete na lista e no dashboard', async ({ page }) => {
    await page.goto('/transactions')

    // Income
    await page.getByRole('button', { name: 'Nova transação' }).first().click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('button', { name: 'Receita' }).click()
    await dialog.getByLabel('Valor (R$)').fill('5000,00')
    await dialog.getByLabel('Descrição').fill('Salário do mês')
    await dialog.getByLabel('Categoria').selectOption({ label: 'Salário' })
    await dialog.getByRole('button', { name: 'Adicionar transação' }).click()
    await expect(dialog).toBeHidden()
    await expect(page.getByRole('cell', { name: 'Salário do mês', exact: true })).toBeVisible()

    // Expense
    await page.getByRole('button', { name: 'Nova transação' }).first().click()
    await dialog.getByLabel('Valor (R$)').fill('320,50')
    await dialog.getByLabel('Descrição').fill('Supermercado semanal')
    await dialog.getByLabel('Categoria').selectOption({ label: 'Alimentação' })
    await dialog.getByRole('button', { name: 'Adicionar transação' }).click()
    await expect(dialog).toBeHidden()
    await expect(page.getByRole('cell', { name: 'Supermercado semanal', exact: true })).toBeVisible()

    // Dashboard aggregates
    await page.goto('/dashboard')
    const stats = page.getByRole('region', { name: 'Indicadores do mês' })
    await expect(stats.getByText('R$ 5.000,00').first()).toBeVisible()
    await expect(stats.getByText('R$ 320,50').first()).toBeVisible()
    await expect(stats.getByText('R$ 4.679,50').first()).toBeVisible()
  })

  test('valida campos obrigatórios sem fechar o formulário', async ({ page }) => {
    await page.goto('/transactions')
    await page.getByRole('button', { name: 'Nova transação' }).first().click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('button', { name: 'Adicionar transação' }).click()
    await expect(dialog.getByText('Informe um valor maior que zero.')).toBeVisible()
    await expect(dialog).toBeVisible()
  })
})
