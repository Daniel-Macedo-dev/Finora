import { expect, test, type Page } from '@playwright/test'
import { registerViaUi } from './helpers.ts'

async function addWishlistItem(page: Page, name: string) {
  await page.goto('/wishlist')
  await page.getByRole('button', { name: 'Novo item' }).first().click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Nome do item').fill(name)
  await dialog.getByRole('button', { name: 'Adicionar item' }).click()
  await expect(dialog).toBeHidden()
  await page.getByRole('link', { name }).click()
}

const API = 'http://localhost:8080/api'

async function seedCash(page: Page, amount: number) {
  const token = await page.evaluate(() => {
    const match = document.cookie.match(/XSRF-TOKEN=([^;]+)/)
    return match ? decodeURIComponent(match[1]) : ''
  })
  await page.request.post(`${API}/accounts`, {
    headers: token ? { 'X-XSRF-TOKEN': token } : {},
    data: { name: 'Conta principal', type: 'CHECKING', openingBalance: amount },
  })
}

test.describe('Cenário — Planejar uma compra (autenticado)', () => {
  test.beforeEach(async ({ page }) => {
    await registerViaUi(page)
  })

  test('compara opções e recomenda a compra à vista mais barata', async ({ page }) => {
    // Give the user enough cash so a cash purchase stays safe (buffer defaults to 0).
    await seedCash(page, 10000)

    await addWishlistItem(page, 'Notebook para trabalho')
    const dialog = page.getByRole('dialog')

    await page.getByRole('button', { name: 'Nova opção' }).click()
    await dialog.getByLabel('Loja / vendedor').fill('Loja À Vista')
    await dialog.getByLabel('Preço à vista (R$)').fill('4500,00')
    await dialog.getByRole('button', { name: 'Adicionar opção' }).click()
    await expect(dialog).toBeHidden()

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
  })

  test('rejeita parcelas que não fecham com o total informado', async ({ page }) => {
    await addWishlistItem(page, 'Monitor')
    const dialog = page.getByRole('dialog')

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
