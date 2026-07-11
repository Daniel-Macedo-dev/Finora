import { expect, test } from '@playwright/test'
import { registerViaUi } from './helpers.ts'

test.describe('Cenário — Acompanhamento de orçamento (autenticado)', () => {
  test.beforeEach(async ({ page }) => {
    await registerViaUi(page)
  })

  async function createExpense(page: import('@playwright/test').Page, amount: string) {
    await page.goto('/transactions')
    await page.getByRole('button', { name: 'Nova transação' }).first().click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Valor (R$)').fill(amount)
    await dialog.getByLabel('Descrição').fill('Mercado do mês')
    await dialog.getByLabel('Categoria').selectOption({ label: 'Alimentação' })
    await dialog.getByRole('button', { name: 'Adicionar transação' }).click()
    await expect(dialog).toBeHidden()
  }

  test('mostra consumo, restante e estado de atenção', async ({ page }) => {
    await createExpense(page, '700,00')

    await page.goto('/budgets')
    await page.getByRole('button', { name: 'Novo orçamento' }).first().click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Categoria').selectOption({ label: 'Alimentação' })
    await dialog.getByLabel('Limite mensal (R$)').fill('800,00')
    await dialog.getByRole('button', { name: 'Criar orçamento' }).click()
    await expect(dialog).toBeHidden()

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

    await page.getByRole('button', { name: 'Novo orçamento' }).first().click()
    await expect(dialog).toBeVisible()
    await expect(
      dialog.getByLabel('Categoria').locator('option', { hasText: 'Transporte' }),
    ).toHaveCount(0)
  })
})
