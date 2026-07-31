import { expect, test } from '@playwright/test'
import { registerViaUi } from './helpers.ts'

test.skip(!process.env.VISUAL_QA, 'Somente com VISUAL_QA=1')

const OUT = '../../qa-screenshots'
const PASSWORD = 'senha-offline-visual'
const VIEWPORTS = [
  { name: 'mobile-390', width: 390, height: 844 },
  { name: 'tablet-768', width: 768, height: 1024 },
  { name: 'desktop-1280', width: 1280, height: 800 },
  { name: 'desktop-1440', width: 1440, height: 900 },
]

test('estados offline em todos os viewports e temas', async ({ page, context }) => {
  test.setTimeout(180_000)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  const navigate = (path: string) => page.evaluate((nextPath) => {
    history.pushState({}, '', nextPath)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, path)
  await registerViaUi(page)
  await page.goto('/settings')
  await page.getByLabel('Senha offline (mínimo de 12 caracteres)').fill(PASSWORD)
  await page.getByLabel('Confirmar senha offline').fill(PASSWORD)
  await page.getByRole('button', { name: 'Ativar acesso offline neste dispositivo' }).click()
  await expect(page.getByText('Acesso offline ativado neste dispositivo.')).toBeVisible({ timeout: 20_000 })

  await context.setOffline(true)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Desbloquear dados offline' })).toBeVisible()

  for (const theme of ['light', 'dark'] as const) {
    await page.evaluate((value) => { localStorage.setItem('finora.theme', value); document.documentElement.dataset.theme = value }, theme)
    for (const viewport of VIEWPORTS) {
      await page.setViewportSize(viewport)
      await page.screenshot({ path: `${OUT}/${viewport.name}/offline-locked-${theme}.png`, fullPage: true })
    }
  }

  await page.getByLabel('Senha offline').fill('senha-incorreta-visual')
  await page.getByRole('button', { name: 'Desbloquear', exact: true }).click()
  await expect(page.getByRole('alert')).toBeVisible()
  for (const theme of ['light', 'dark'] as const) {
    await page.evaluate((value) => { localStorage.setItem('finora.theme', value); document.documentElement.dataset.theme = value }, theme)
    for (const viewport of VIEWPORTS) {
      await page.setViewportSize(viewport)
      await page.screenshot({ path: `${OUT}/${viewport.name}/offline-wrong-password-${theme}.png`, fullPage: true })
    }
  }

  await page.getByLabel('Senha offline').fill(PASSWORD)
  await page.getByRole('button', { name: 'Desbloquear', exact: true }).click()
  await expect(page.getByText(/^Modo offline/)).toBeVisible()
  for (const theme of ['light', 'dark'] as const) {
    await page.evaluate((value) => { localStorage.setItem('finora.theme', value); document.documentElement.dataset.theme = value }, theme)
    for (const viewport of VIEWPORTS) {
      await page.setViewportSize(viewport)
      await navigate('/dashboard')
      await page.screenshot({ path: `${OUT}/${viewport.name}/offline-dashboard-${theme}.png`, fullPage: true })
      await navigate('/statement-imports')
      await page.screenshot({ path: `${OUT}/${viewport.name}/offline-unavailable-${theme}.png`, fullPage: true })
      await navigate('/settings')
      await page.screenshot({ path: `${OUT}/${viewport.name}/offline-settings-${theme}.png`, fullPage: true })
    }
  }
})
