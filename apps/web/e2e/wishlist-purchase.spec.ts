import { expect, test } from '@playwright/test'
import { pageGet, pagePost, registerViaUi } from './helpers'

/**
 * Wishlist execution: a selected installment option becomes a real card
 * purchase exactly once, and the item flips to PURCHASED.
 */

test.describe('Cenário — Execução de compra da lista de desejos', () => {
  test('opção parcelada vira compra no cartão e não pode duplicar', async ({ page }) => {
    await registerViaUi(page)

    // Data setup through the authenticated browser session.
    const categories = await (await pageGet(page, '/categories?type=EXPENSE')).json()
    const categoryId = categories.find(
      (category: { name: string }) => category.name === 'Compras',
    ).id
    const card = await (
      await pagePost(page, '/credit-cards', {
        name: 'Cartão Desejos',
        brand: 'VISA',
        creditLimit: 3000,
        closingDay: 10,
        dueDay: 17,
      })
    ).json()
    const item = await (
      await pagePost(page, '/wishlist', {
        name: 'Cadeira ergonômica',
        priority: 'HIGH',
        categoryId,
      })
    ).json()
    const option = await (
      await pagePost(page, `/wishlist/${item.id}/options`, {
        merchant: 'Loja Conforto',
        kind: 'INSTALLMENT',
        basePrice: 1200,
        installmentCount: 12,
        installmentAmount: 100,
        creditCardId: card.id,
      })
    ).json()

    // Execute through the UI.
    await page.goto(`/wishlist/${item.id}`)
    await expect(page.getByRole('heading', { name: 'Cadeira ergonômica' })).toBeVisible()
    await page.getByRole('button', { name: 'Comprar' }).click()
    await expect(
      page.getByText('Compra parcelada em Loja Conforto', { exact: false }),
    ).toBeVisible()
    await page.getByRole('button', { name: 'Confirmar compra' }).click()
    await expect(page.getByRole('dialog')).toBeHidden()
    await expect(page.getByText('Comprado', { exact: true })).toBeVisible()

    // The card purchase exists with the exact schedule and consumed limit.
    const cards = await (await pageGet(page, '/credit-cards')).json()
    expect(cards[0].limit.usedLimit).toBe(1200)
    const purchases = await (
      await pageGet(page, `/credit-cards/${card.id}/purchases`)
    ).json()
    expect(purchases.content).toHaveLength(1)
    expect(purchases.content[0].installmentCount).toBe(12)
    expect(purchases.content[0].wishlistItemId).toBe(item.id)

    // A retry (double-submit, replay) cannot execute the item again.
    const retry = await pagePost(page, `/wishlist/${item.id}/purchase`, {
      optionId: option.id,
    })
    expect(retry.status()).toBe(422)
    expect((await retry.json()).code).toBe('WISHLIST_ALREADY_PURCHASED')
    const cardsAfter = await (await pageGet(page, '/credit-cards')).json()
    expect(cardsAfter[0].limit.usedLimit).toBe(1200)
  })
})
