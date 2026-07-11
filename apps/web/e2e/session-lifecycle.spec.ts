import { expect, test } from '@playwright/test'
import { registerViaUi } from './helpers.ts'

test.describe('Cenário — Ciclo de vida da sessão', () => {
  test('sessão expirada leva ao login e permite reentrar', async ({ page, context }) => {
    const identity = await registerViaUi(page)

    // Simulate expiration by dropping the session cookie the server issued.
    await context.clearCookies({ name: 'FINORA_SESSION' })

    // A protected navigation now resolves to login.
    await page.goto('/transactions')
    await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible()

    // Logging back in restores access and returns to the originally requested page.
    await page.getByLabel('E-mail').fill(identity.email)
    await page.getByLabel('Senha', { exact: true }).fill(identity.password)
    await page.getByRole('button', { name: 'Entrar' }).click()
    await expect(page.getByRole('heading', { name: 'Transações' })).toBeVisible()
  })

  test('troca de senha mantém a sessão atual e permite login com a nova senha', async ({ page }) => {
    const identity = await registerViaUi(page)

    await page.goto('/profile')
    await page.getByLabel('Senha atual').fill(identity.password)
    await page.getByLabel('Nova senha', { exact: true }).fill('senha-nova-forte-9')
    await page.getByLabel('Confirmar nova senha').fill('senha-nova-forte-9')
    await page.getByRole('button', { name: 'Alterar senha' }).click()
    await expect(page.getByText('Senha alterada com sucesso.')).toBeVisible()

    // Current session still works.
    await page.goto('/dashboard')
    await expect(page.getByRole('heading', { name: 'Visão geral' })).toBeVisible()

    // Logout and log in with the new password.
    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible()
    await page.getByLabel('E-mail').fill(identity.email)
    await page.getByLabel('Senha', { exact: true }).fill('senha-nova-forte-9')
    await page.getByRole('button', { name: 'Entrar' }).click()
    await expect(page.getByRole('heading', { name: 'Visão geral' })).toBeVisible()
  })
})
