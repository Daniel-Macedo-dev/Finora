import { expect, test, type Page } from '@playwright/test'
import { apiSession, loginViaUi, logoutViaUi, pageGet, pagePost, pagePut, registerViaUi } from './helpers.ts'

async function seedOverdue(page: Page, title = 'Internet do escritório') {
  const categories = await (await pageGet(page, '/categories?type=EXPENSE')).json()
  const categoryId = categories.find((category: { name: string }) => category.name === 'Assinaturas').id
  const account = await (await pagePost(page, '/accounts', { name: `Conta ${Date.now()}`, type: 'CHECKING', openingBalance: 1000 })).json()
  const today = new Date()
  const due = new Date(today)
  due.setDate(due.getDate() - 2)
  const start = new Date(due)
  start.setMonth(start.getMonth() - 2)
  const iso = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
  await pagePost(page, '/commitments', {
    description: title, amount: 120, categoryId, cadence: 'MONTHLY',
    dueDay: due.getDate(), startDate: iso(start), targetKind: 'ACCOUNT_TRANSACTION',
    accountId: account.id,
  })
  await pagePost(page, '/notifications/sync', {})
  const history = await (await pageGet(page, '/notifications?filter=ALL')).json()
  return history.content[0] as { id: number; route: string }
}

async function registerAndSeed(page: Page, title?: string) {
  const identity = await registerViaUi(page)
  const notification = await seedOverdue(page, title)
  await page.goto('/notifications')
  return { identity, notification }
}

test('empty notification center', async ({ page }) => {
  await registerViaUi(page)
  await page.goto('/notifications')
  await expect(page.getByText('Nenhuma notificação')).toBeVisible()
})

test('recurring overdue appears in history', async ({ page }) => {
  await registerAndSeed(page)
  await expect(page.getByText('Internet do escritório', { exact: false })).toBeVisible()
})

test('unread badge announces its count', async ({ page }) => {
  await registerAndSeed(page)
  await expect(page.getByRole('button', { name: /Notificações: 1 não lidas/ })).toBeVisible()
})

test('compact panel opens and closes with Escape', async ({ page }) => {
  await registerAndSeed(page)
  await page.getByRole('button', { name: /Notificações:/ }).click()
  await expect(page.getByRole('dialog', { name: 'Notificações' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: 'Notificações' })).toBeHidden()
})

test('compact panel links to full history', async ({ page }) => {
  await registerAndSeed(page)
  await page.getByRole('button', { name: /Notificações:/ }).click()
  await page.getByRole('link', { name: 'Ver histórico completo' }).click()
  await expect(page).toHaveURL(/\/notifications$/)
})

test('marks a notification read', async ({ page }) => {
  await registerAndSeed(page)
  await page.getByRole('button', { name: 'Marcar como lida' }).click()
  await expect(page.getByRole('button', { name: 'Marcar como não lida' })).toBeVisible()
})

test('marks a notification unread again', async ({ page }) => {
  await registerAndSeed(page)
  await page.getByRole('button', { name: 'Marcar como lida' }).click()
  await page.getByRole('button', { name: 'Marcar como não lida' }).click()
  await expect(page.getByRole('button', { name: 'Marcar como lida' })).toBeVisible()
})

test('read all clears the unread summary', async ({ page }) => {
  await registerAndSeed(page)
  await page.getByRole('button', { name: 'Marcar todas como lidas' }).click()
  await expect(page.getByText(/^0 não lidas/)).toBeVisible()
})

test('dismiss hides an active notification', async ({ page }) => {
  await registerAndSeed(page)
  await page.getByRole('button', { name: 'Dispensar' }).click()
  await expect(page.getByText('Nenhuma notificação')).toBeVisible()
})

test('restore returns a dismissed notification', async ({ page }) => {
  await registerAndSeed(page)
  await page.getByRole('button', { name: 'Dispensar' }).click()
  await page.getByRole('button', { name: 'Dispensadas' }).click()
  await page.getByRole('button', { name: 'Restaurar' }).click()
  await page.getByRole('button', { name: 'Ativas' }).click()
  await expect(page.getByText('Internet do escritório', { exact: false })).toBeVisible()
})

test('snooze moves a notification to the snoozed filter', async ({ page }) => {
  await registerAndSeed(page)
  await page.getByRole('button', { name: /Adiar .* por um dia/ }).click()
  await page.getByRole('button', { name: 'Adiadas' }).click()
  await expect(page.getByText('Internet do escritório', { exact: false })).toBeVisible()
})

test('unread filter shows current unread revisions', async ({ page }) => {
  await registerAndSeed(page)
  await page.getByRole('button', { name: 'Não lidas' }).click()
  await expect(page.getByText('Internet do escritório', { exact: false })).toBeVisible()
})

test('all filter retains dismissed history', async ({ page }) => {
  await registerAndSeed(page)
  await page.getByRole('button', { name: 'Dispensar' }).click()
  await page.getByRole('button', { name: 'Todas', exact: true }).click()
  await expect(page.getByText('Internet do escritório', { exact: false })).toBeVisible()
})

test('source navigation uses the trusted route', async ({ page }) => {
  await registerAndSeed(page)
  await page.getByRole('link', { name: 'Ver origem' }).click()
  await expect(page).toHaveURL(/\/commitments$/)
})

test('preference filtering resolves disabled recurring notifications', async ({ page }) => {
  await registerAndSeed(page)
  const preferences = await (await pageGet(page, '/notification-preferences')).json()
  await pagePut(page, '/notification-preferences', { ...preferences, recurringDueEnabled: false })
  await pagePost(page, '/notifications/sync', {})
  await page.reload()
  await expect(page.getByText('Nenhuma notificação')).toBeVisible()
})

test('lead-day preference validates in settings', async ({ page }) => {
  await registerViaUi(page)
  await page.goto('/settings')
  const lead = page.getByLabel('Dias de antecedência')
  await lead.fill('15')
  await expect(page.getByRole('button', { name: 'Salvar notificações' })).toBeDisabled()
})

test('browser permission denied is described', async ({ page }) => {
  await page.addInitScript(() => {
    class DeniedNotification { static permission = 'denied'; static requestPermission = async () => 'denied' }
    Object.defineProperty(window, 'Notification', { value: DeniedNotification })
  })
  await registerViaUi(page)
  await page.goto('/settings')
  await expect(page.getByText('Permissão negada no navegador.')).toBeVisible()
})

test('browser permission is requested only from the explicit button', async ({ page }) => {
  await page.addInitScript(() => {
    ;(window as Window & { permissionRequests?: number }).permissionRequests = 0
    class StubNotification {
      static permission = 'default'
      static requestPermission = async () => { (window as Window & { permissionRequests?: number }).permissionRequests = 1; return 'granted' }
    }
    Object.defineProperty(window, 'Notification', { value: StubNotification })
  })
  await registerViaUi(page)
  await page.goto('/settings')
  await expect.poll(() => page.evaluate(() => (window as Window & { permissionRequests?: number }).permissionRequests)).toBe(0)
  await page.getByRole('button', { name: 'Permitir alertas neste navegador' }).click()
  await expect.poll(() => page.evaluate(() => (window as Window & { permissionRequests?: number }).permissionRequests)).toBe(1)
})

test('cross-user history is isolated', async ({ page, request }) => {
  const { notification } = await registerAndSeed(page)
  const other = await apiSession(request)
  const foreign = await request.post(`http://localhost:8080/api/notifications/${notification.id}/read`, {
    headers: { 'X-XSRF-TOKEN': other.token },
  })
  expect(foreign.status()).toBe(404)
})

test('mobile notification access and logout-login persistence', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const { identity } = await registerAndSeed(page, 'Título longo de notificação que precisa caber no celular')
  await expect(page.getByRole('button', { name: /Notificações:/ })).toBeVisible()
  await page.getByRole('button', { name: 'Abrir menu' }).click()
  await logoutViaUi(page)
  await loginViaUi(page, identity)
  await page.goto('/notifications')
  await expect(page.getByText('Título longo de notificação', { exact: false })).toBeVisible()
})
