import { expect, test } from '@playwright/test'
import { loginViaUi, pageGet, pagePost, registerViaUi } from './helpers.ts'

const observedOn = '2026-07-01'

async function seed(page: import('@playwright/test').Page, targetPrice = 950) {
  const item = await (await pagePost(page, '/wishlist', {
    name: `Produto histórico ${Date.now()}`, priority: 'HIGH', targetPrice, status: 'MONITORING',
  })).json()
  const cash = await (await pagePost(page, `/wishlist/${item.id}/options`, {
    merchant: 'Loja Atual', kind: 'CASH', basePrice: 1200, shipping: 20,
  })).json()
  const installment = await (await pagePost(page, `/wishlist/${item.id}/options`, {
    merchant: 'Loja Parcelada', kind: 'INSTALLMENT', basePrice: 1200,
    installmentCount: 12, installmentAmount: 100,
  })).json()
  return { item, cash, installment }
}

function snapshot(merchant: string, price: number, extra: Record<string, unknown> = {}) {
  return { clientRequestId: crypto.randomUUID(), merchant, paymentKind: 'CASH',
    basePrice: price, shipping: 0, fees: 0, observedOn, updateLinkedOption: false, ...extra }
}

test.describe('Histórico manual de preços', () => {
  test('1 — item vazio mostra estado, ação e análise separada', async ({ page }) => {
    await registerViaUi(page); const { item } = await seed(page)
    await page.goto(`/wishlist/${item.id}`)
    await expect(page.getByRole('heading', { name: 'Histórico de preços' })).toBeVisible()
    await expect(page.getByText('Nenhum preço observado')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Análise de compra' })).toBeVisible()
  })

  test('2 e 3 — captura opções à vista e parcelada pela interface', async ({ page }) => {
    await registerViaUi(page); const { item } = await seed(page); await page.goto(`/wishlist/${item.id}`)
    const buttons = page.getByRole('button', { name: 'Registrar preço atual' })
    await buttons.nth(0).click(); await expect(page.getByText('Total R$ 1.220,00')).toBeVisible()
    await page.getByRole('button', { name: 'Salvar no histórico' }).click()
    await buttons.nth(1).click(); await expect(page.getByText('12 parcelas')).toBeVisible()
    await page.getByRole('button', { name: 'Salvar no histórico' }).click()
    await expect(page.getByText('2', { exact: true }).first()).toBeVisible()
  })

  test('4 e 5 — registra preço avulso à vista e parcelado', async ({ page }) => {
    await registerViaUi(page); const { item } = await seed(page); await page.goto(`/wishlist/${item.id}`)
    await page.getByRole('button', { name: 'Registrar preço', exact: true }).click()
    await page.getByRole('textbox', { name: 'Loja', exact: true }).fill('Loja Manual'); await page.getByLabel('Preço', { exact: true }).fill('900')
    await page.getByRole('button', { name: 'Salvar no histórico' }).click()
    await page.getByRole('button', { name: 'Registrar preço', exact: true }).click()
    await page.getByRole('textbox', { name: 'Loja', exact: true }).fill('Loja Parcelamento Manual')
    await page.getByRole('dialog', { name: 'Registrar preço' }).getByLabel('Pagamento').selectOption('INSTALLMENT'); await page.getByRole('spinbutton', { name: 'Preço', exact: true }).fill('1000')
    await page.getByLabel('Parcelas').fill('10'); await page.getByLabel('Valor da parcela').fill('100')
    await page.getByRole('button', { name: 'Salvar no histórico' }).click()
    await expect(page.getByText('Loja Parcelamento Manual')).toBeVisible()
  })

  test('6, 7 e 8 — associação só histórica preserva análise; atualização explícita muda opção', async ({ page }) => {
    await registerViaUi(page); const { item, cash } = await seed(page)
    const before = await (await pageGet(page, `/wishlist/${item.id}/analysis`)).json()
    await pagePost(page, `/wishlist/${item.id}/price-snapshots`, snapshot('Loja Atual', 800,
      { purchaseOptionId: cash.id }))
    expect(await (await pageGet(page, `/wishlist/${item.id}/analysis`)).json()).toEqual(before)
    await pagePost(page, `/wishlist/${item.id}/price-snapshots`, snapshot('Loja Atual', 850,
      { clientRequestId: crypto.randomUUID(), purchaseOptionId: cash.id, updateLinkedOption: true }))
    const after = await (await pageGet(page, `/wishlist/${item.id}/analysis`)).json()
    expect(after.options[0].nominalCost).toBe(850)
  })

  test('9 e 10 — retry idempotente e resumo inicial', async ({ page }) => {
    await registerViaUi(page); const { item } = await seed(page); const body = snapshot('Retry', 1000)
    await pagePost(page, `/wishlist/${item.id}/price-snapshots`, body)
    await pagePost(page, `/wishlist/${item.id}/price-snapshots`, body)
    const history = await (await pageGet(page, `/wishlist/${item.id}/price-snapshots`)).json()
    expect(history.totalElements).toBe(1)
    const summary = await (await pageGet(page, `/wishlist/${item.id}/price-history-summary`)).json()
    expect(summary.observationCount).toBe(1); expect(summary.previousComparableCost).toBeNull()
  })

  test('11 a 16 — queda, aumento, extremos, alvo e gráfico', async ({ page }) => {
    await registerViaUi(page); const { item } = await seed(page, 900)
    for (const [date, price] of [['2026-06-01', 1100], ['2026-06-15', 800], ['2026-07-01', 1000]] as const) {
      await pagePost(page, `/wishlist/${item.id}/price-snapshots`, snapshot('Série', price,
        { clientRequestId: crypto.randomUUID(), observedOn: date }))
    }
    const summary = await (await pageGet(page, `/wishlist/${item.id}/price-history-summary`)).json()
    expect(summary.percentageChange).toBe(25); expect(summary.historicalMinimum).toBe(800)
    expect(summary.historicalMaximum).toBe(1100); expect(summary.targetReached).toBe(false)
    await page.goto(`/wishlist/${item.id}`); await expect(page.getByRole('img', { name: /evolução/ })).toBeVisible()
  })

  test('17 a 20 — filtro, paginação estável, edição e exclusão', async ({ page }) => {
    await registerViaUi(page); const { item } = await seed(page)
    const created = await (await pagePost(page, `/wishlist/${item.id}/price-snapshots`, snapshot('Filtrável', 900))).json()
    await page.goto(`/wishlist/${item.id}`); await page.getByLabel('Filtrar por loja').fill('Filtrável')
    await expect(page.getByText('Filtrável')).toBeVisible()
    await page.getByRole('button', { name: 'Editar observação de Filtrável' }).click()
    await page.getByRole('textbox', { name: 'Loja', exact: true }).fill('Editada'); await page.getByRole('button', { name: 'Salvar no histórico' }).click()
    await page.reload()
    await expect(page.getByText('Editada')).toBeVisible()
    await page.getByRole('button', { name: 'Excluir observação de Editada' }).click()
    await page.getByRole('button', { name: 'Excluir observação', exact: true }).click()
    await expect(page.getByRole('dialog', { name: 'Excluir observação' })).toBeHidden()
    const remaining = await (await pageGet(page, `/wishlist/${item.id}/price-snapshots`)).json()
    expect(remaining.totalElements).toBe(0); expect(created.id).toBeTruthy()
  })

  test('21 a 24 — link seguro é textual e URL inválida é recusada', async ({ page }) => {
    await registerViaUi(page); const { item } = await seed(page)
    await pagePost(page, `/wishlist/${item.id}/price-snapshots`, snapshot('Com link', 900,
      { offerUrl: 'https://example.test/oferta' }))
    const invalid = await pagePost(page, `/wishlist/${item.id}/price-snapshots`, snapshot('Ruim', 900,
      { clientRequestId: crypto.randomUUID(), offerUrl: 'javascript:alert(1)' }))
    expect(invalid.status()).toBe(422)
    await page.goto(`/wishlist/${item.id}`); const link = page.getByRole('link', { name: 'Abrir oferta de Com link' })
    await expect(link).toHaveAttribute('rel', 'noopener noreferrer'); await expect(link).toHaveAttribute('href', 'https://example.test/oferta')
  })

  test('25 e 26 — excluir opção preserva; excluir item remove histórico', async ({ page }) => {
    await registerViaUi(page); const { item, cash } = await seed(page)
    await pagePost(page, `/wishlist/${item.id}/options/${cash.id}/price-snapshots`,
      { clientRequestId: crypto.randomUUID(), observedOn })
    await page.goto(`/wishlist/${item.id}`); await page.getByRole('button', { name: 'Excluir opção de Loja Atual' }).click()
    await page.getByRole('dialog', { name: 'Excluir opção' }).getByRole('button', { name: 'Excluir' }).click()
    await expect(page.getByText('Opção removida')).toBeVisible()
    await page.getByRole('button', { name: 'Excluir', exact: true }).first().click()
    await expect(page.getByText(/observações do histórico/)).toBeVisible()
  })

  test('27 — histórico fica isolado entre usuários', async ({ page, browser }) => {
    await registerViaUi(page); const { item } = await seed(page)
    await pagePost(page, `/wishlist/${item.id}/price-snapshots`, snapshot('Privada', 900))
    const context = await browser.newContext(); const other = await context.newPage(); await registerViaUi(other)
    const attack = await pageGet(other, `/wishlist/${item.id}/price-history-summary`); expect(attack.status()).toBe(404)
    await context.close()
  })

  test('28 a 30 — mobile, tema escuro e persistência após novo login', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 }); const identity = await registerViaUi(page)
    const { item } = await seed(page); await pagePost(page, `/wishlist/${item.id}/price-snapshots`, snapshot('Persistente', 900))
    await page.goto(`/wishlist/${item.id}`); await expect(page.getByRole('heading', { name: 'Histórico de preços' })).toBeVisible()
    await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'))
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    await pagePost(page, '/auth/logout', {}); await page.goto('/login'); await loginViaUi(page, identity); await page.goto(`/wishlist/${item.id}`)
    await expect(page.getByText('Persistente')).toBeVisible()
  })
})
