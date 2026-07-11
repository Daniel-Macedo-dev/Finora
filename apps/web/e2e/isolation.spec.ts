import { expect, request as playwrightRequest, test, type Page } from '@playwright/test'
import { apiSession, registerViaUi } from './helpers.ts'

const API = 'http://localhost:8080/api'

async function addIncome(page: Page, amount: string, description: string) {
  await page.goto('/transactions')
  await page.getByRole('button', { name: 'Nova transação' }).first().click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: 'Receita' }).click()
  await dialog.getByLabel('Valor (R$)').fill(amount)
  await dialog.getByLabel('Descrição').fill(description)
  await dialog.getByLabel('Categoria').selectOption({ label: 'Salário' })
  await dialog.getByRole('button', { name: 'Adicionar transação' }).click()
  await expect(dialog).toBeHidden()
}

test.describe('Cenário — Isolamento entre usuários', () => {
  test('usuário B não vê os dados de A e começa em estado vazio', async ({ page }) => {
    // User A records income.
    await registerViaUi(page)
    await addIncome(page, '9000,00', 'Salário da Alice')
    await page.goto('/dashboard')
    await expect(
      page.getByRole('region', { name: 'Indicadores do mês' }).getByText('R$ 9.000,00').first(),
    ).toBeVisible()

    // Logout, register user B.
    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible()
    await registerViaUi(page)

    // B's dashboard is empty and never mentions A's data.
    await page.goto('/dashboard')
    await expect(page.getByText('Sem dados por enquanto')).toBeVisible()
    await expect(page.getByText('Salário da Alice')).toHaveCount(0)

    await page.goto('/transactions')
    await expect(page.getByText('Nenhuma transação encontrada')).toBeVisible()
  })

  test('usuário B não acessa um recurso de A por ID direto na API', async ({ page }) => {
    // User A (UI) creates an account and a wishlist item; capture their ids via API.
    const alice = await registerViaUi(page)
    const aliceCtx = await playwrightRequest.newContext()
    // Re-login through the API context to share the session cookie jar.
    const csrf = await aliceCtx.get(`${API}/auth/csrf`)
    const token = decodeURIComponent(
      (csrf.headers()['set-cookie'].match(/XSRF-TOKEN=([^;]+)/) ?? [])[1] ?? '',
    )
    await aliceCtx.post(`${API}/auth/login`, {
      headers: { 'X-XSRF-TOKEN': token },
      data: { email: alice.email, password: alice.password },
    })
    const itemResponse = await aliceCtx.post(`${API}/wishlist`, {
      headers: { 'X-XSRF-TOKEN': token },
      data: { name: 'Item privado da Alice', priority: 'HIGH' },
    })
    const aliceItemId = (await itemResponse.json()).id as number

    // User B, via their own API session, cannot read A's item or run analysis.
    const bruno = await apiSession(await playwrightRequest.newContext())
    const brunoCtx = await playwrightRequest.newContext()
    const brunoCsrf = await brunoCtx.get(`${API}/auth/csrf`)
    const brunoToken = decodeURIComponent(
      (brunoCsrf.headers()['set-cookie'].match(/XSRF-TOKEN=([^;]+)/) ?? [])[1] ?? '',
    )
    await brunoCtx.post(`${API}/auth/login`, {
      headers: { 'X-XSRF-TOKEN': brunoToken },
      data: { email: bruno.identity.email, password: bruno.identity.password },
    })

    const read = await brunoCtx.get(`${API}/wishlist/${aliceItemId}`)
    expect(read.status()).toBe(404)
    const analysis = await brunoCtx.get(`${API}/wishlist/${aliceItemId}/analysis`)
    expect(analysis.status()).toBe(404)

    await aliceCtx.dispose()
    await brunoCtx.dispose()
  })

  test('planejamento de compra de A é inacessível para B', async ({ page }) => {
    // A creates a wishlist item with an option.
    await registerViaUi(page)
    await page.goto('/wishlist')
    await page.getByRole('button', { name: 'Novo item' }).first().click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Nome do item').fill('Câmera da Alice')
    await dialog.getByRole('button', { name: 'Adicionar item' }).click()
    await expect(dialog).toBeHidden()

    // B logs in fresh and sees an empty wishlist.
    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await registerViaUi(page)
    await page.goto('/wishlist')
    await expect(page.getByText('Câmera da Alice')).toHaveCount(0)
    await expect(page.getByText('Sua lista de desejos está vazia')).toBeVisible()
  })
})
