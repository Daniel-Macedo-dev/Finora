import { expect, test } from '@playwright/test'
import { registerViaUi, uniqueIdentity } from './helpers.ts'

test.use({ viewport: { width: 390, height: 844 } })

test.describe('Cenário — Navegação e autenticação mobile', () => {
  test('registra, navega e sai da conta em tela pequena', async ({ page }) => {
    const identity = uniqueIdentity('mobile')
    await page.goto('/register')
    await page.getByLabel('Nome').fill(identity.displayName)
    await page.getByLabel('E-mail').fill(identity.email)
    await page.getByLabel('Senha', { exact: true }).fill(identity.password)
    await page.getByLabel('Confirmar senha').fill(identity.password)
    await page.getByRole('button', { name: 'Criar conta' }).click()
    await expect(page.getByRole('heading', { name: 'Visão geral' })).toBeVisible()

    await page.getByRole('button', { name: 'Abrir menu' }).click()
    await page.getByRole('link', { name: 'Transações', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Transações' })).toBeVisible()

    await page.getByRole('button', { name: 'Nova transação' }).first().click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Valor (R$)').fill('49,90')
    await dialog.getByLabel('Descrição').fill('Lanche mobile')
    await dialog.getByLabel('Categoria').selectOption({ label: 'Alimentação' })
    await dialog.getByRole('button', { name: 'Adicionar transação' }).click()
    await expect(dialog).toBeHidden()
    await expect(page.getByRole('cell', { name: 'Lanche mobile', exact: true })).toBeVisible()

    await page.getByRole('button', { name: 'Abrir menu' }).click()
    await expect(page.getByText(identity.displayName)).toBeVisible()
    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible()
  })

  test('usuário não autenticado é enviado ao login', async ({ page }) => {
    await page.goto('/dashboard')
    await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible()
  })

  test('login mobile leva ao dashboard', async ({ page }) => {
    const identity = await registerViaUi(page)
    await page.getByRole('button', { name: 'Abrir menu' }).click()
    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible()

    await page.getByLabel('E-mail').fill(identity.email)
    await page.getByLabel('Senha', { exact: true }).fill(identity.password)
    await page.getByRole('button', { name: 'Entrar' }).click()
    await expect(page.getByRole('heading', { name: 'Visão geral' })).toBeVisible()
  })
})
