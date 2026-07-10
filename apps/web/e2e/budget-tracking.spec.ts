import { expect, test } from '@playwright/test'
import { categoryId, createTransaction, resetData } from './helpers.ts'

test.describe('Cenário 2 — Acompanhamento de orçamento', () => {
  test.beforeEach(async ({ request }) => {
    await resetData(request)
  })

  test('mostra consumo, restante e estado de atenção', async ({ page, request }) => {
    const foodId = await categoryId(request, 'Alimentação', 'EXPENSE')
    const today = new Date()
    const monthKey = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`
    await createTransaction(request, {
      type: 'EXPENSE',
      amount: 700,
      description: 'Mercado do mês',
      date: `${monthKey}-05`,
      categoryId: foodId,
    })

    await page.goto('/budgets')
    await page.getByRole('button', { name: 'Novo orçamento' }).first().click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Categoria').selectOption({ label: 'Alimentação' })
    await dialog.getByLabel('Limite mensal (R$)').fill('800,00')
    await dialog.getByRole('button', { name: 'Criar orçamento' }).click()
    await expect(dialog).toBeHidden()

    // 700 of 800 = 87.5% -> warning state
    const budgetRow = page.getByRole('listitem').filter({ hasText: 'Alimentação' })
    await expect(budgetRow.getByText('R$ 700,00 de R$ 800,00')).toBeVisible()
    await expect(budgetRow.getByText('Restam R$ 100,00')).toBeVisible()
    await expect(budgetRow.getByText('Perto do limite')).toBeVisible()
  })

  test('não oferece a mesma categoria duas vezes no mesmo mês', async ({ page }) => {
    await page.goto('/budgets')
    await page.getByRole('button', { name: 'Novo orçamento' }).first().click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Categoria').selectOption({ label: 'Transporte' })
    await dialog.getByLabel('Limite mensal (R$)').fill('300,00')
    await dialog.getByRole('button', { name: 'Criar orçamento' }).click()
    await expect(dialog).toBeHidden()

    // The category with a budget disappears from the options (duplicate
    // prevention in the UI; the API also rejects it with 422).
    await page.getByRole('button', { name: 'Novo orçamento' }).first().click()
    await expect(dialog).toBeVisible()
    await expect(
      dialog.getByLabel('Categoria').locator('option', { hasText: 'Transporte' }),
    ).toHaveCount(0)
  })
})
