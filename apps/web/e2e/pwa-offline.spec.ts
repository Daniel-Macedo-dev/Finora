import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { uniqueIdentity } from './helpers.ts'

test.describe.serial('PWA e acesso offline seguro', () => {
  let context: BrowserContext
  let page: Page
  const password = 'senha-offline-sintetica'
  const knownValue = '7654.32'
  const identity = uniqueIdentity('pwa')

  test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ locale: 'pt-BR' })
    page = await context.newPage()
  })
  test.afterAll(async () => { await context.close() })

  test('manifesto de produção é válido e instalável', async () => {
    const response = await page.request.get('/manifest.webmanifest')
    expect(response.ok()).toBeTruthy()
    const manifest = await response.json()
    expect(manifest).toMatchObject({ name: 'Finora', display: 'standalone', lang: 'pt-BR' })
    expect(manifest.icons).toEqual(expect.arrayContaining([expect.objectContaining({ sizes: '192x192' }), expect.objectContaining({ sizes: '512x512' })]))
  })

  test('Service Worker registra no build de produção', async () => {
    await page.goto('/login')
    await expect.poll(() => page.evaluate(async () => Boolean(await navigator.serviceWorker.ready))).toBe(true)
  })

  test('Cache Storage contém apenas shell e ativos estáticos', async () => {
    const urls = await page.evaluate(async () => (await Promise.all((await caches.keys()).map(async (name) => (await caches.open(name)).keys()))).flat().map((request) => request.url))
    expect(urls.some((url) => url.includes('/api/'))).toBe(false)
    expect(urls.some((url) => url.includes('/index.html') || url.includes('/assets/'))).toBe(true)
  })

  test('acesso offline começa desativado', async () => {
    await page.goto('/register')
    await page.getByLabel('Nome').fill(identity.displayName)
    await page.getByLabel('E-mail').fill(identity.email)
    await page.getByLabel('Senha', { exact: true }).fill(identity.password)
    await page.getByLabel('Confirmar senha').fill(identity.password)
    const responsePromise = page.waitForResponse((response) => response.url().includes('/api/auth/register'))
    await page.getByRole('button', { name: 'Criar conta' }).click()
    const response = await responsePromise
    expect(response.status(), await response.text()).toBe(201)
    await expect(page.getByRole('heading', { name: 'Visão geral' })).toBeVisible()
    await page.goto('/settings')
    await expect(page.getByRole('button', { name: 'Ativar acesso offline neste dispositivo' })).toBeVisible()
  })

  test('usuário ativa cofre com senha local separada', async () => {
    await page.getByLabel('Senha offline (mínimo de 12 caracteres)').fill(password)
    await page.getByLabel('Confirmar senha offline').fill(password)
    await page.getByRole('button', { name: 'Ativar acesso offline neste dispositivo' }).click()
    await expect(page.getByText('Acesso offline ativado neste dispositivo.')).toBeVisible({ timeout: 20_000 })
  })

  test('IndexedDB não expõe identidade ou valor financeiro em texto claro', async () => {
    const serialized = await page.evaluate(async () => new Promise<string>((resolve, reject) => {
      const request = indexedDB.open('finora-offline-vault')
      request.onerror = () => reject(request.error)
      request.onsuccess = () => {
        const get = request.result.transaction('vault').objectStore('vault').get('single-vault')
        get.onsuccess = () => resolve(JSON.stringify(get.result))
        get.onerror = () => reject(get.error)
      }
    }))
    expect(serialized).not.toContain(identity.email)
    expect(serialized).not.toContain(knownValue)
    expect(serialized).toContain('ciphertext')
  })

  test('shell reabre depois que a rede é desligada', async () => {
    await context.setOffline(true)
    await page.reload()
    await expect(page.getByRole('heading', { name: 'Desbloquear dados offline' })).toBeVisible()
  })

  test('senha errada falha fechada sem revelar dados', async () => {
    await page.getByLabel('Senha offline').fill('senha-totalmente-errada')
    await page.getByRole('button', { name: 'Desbloquear', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('Não foi possível desbloquear')
    await expect(page.getByText(knownValue)).toHaveCount(0)
  })

  test('senha correta abre os dados salvos', async () => {
    await page.getByLabel('Senha offline').fill(password)
    await page.getByRole('button', { name: 'Desbloquear', exact: true }).click()
    await expect(page.getByText(/^Modo offline/)).toBeVisible()
    await expect(page.getByText(/Dados salvos em/)).toBeVisible()
  })

  test('controles não suportados ficam indisponíveis offline', async () => {
    // The dashboard queues nothing, so every one of its controls stays out of
    // reach — the routes that do queue are covered in offline-sync.spec.ts.
    const blocked = page.locator('.app-main button:not([data-offline-allowed="true"])').first()
    if (await blocked.count()) await expect(blocked).toBeDisabled()
  })

  test('rota não preparada explica como recuperar acesso', async () => {
    await page.getByRole('link', { name: 'Importar extrato' }).click()
    await expect(page.getByRole('heading', { name: 'Conteúdo indisponível offline' })).toBeVisible()
    await expect(page.getByText('Conecte-se e atualize os dados offline.')).toBeVisible()
  })

  test('reconexão revalida a sessão e logout remove o cofre', async () => {
    await context.setOffline(false)
    await page.goto('/dashboard')
    await expect(page.getByText(/^Modo offline/)).toHaveCount(0, { timeout: 20_000 })

    // Reconnecting reloads, which drops the in-memory key, so the copy is
    // locked by the time this runs — its queue is unreadable and signing out
    // says so rather than deleting on the assumption that it is empty.
    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await expect(page.getByRole('heading', { name: 'A cópia offline está bloqueada' })).toBeVisible()
    await page.getByRole('button', { name: 'Descartar cópia e sair' }).click()
    await page.getByRole('button', { name: 'Excluir e sair definitivamente' }).click()
    await expect(page).toHaveURL(/\/login/, { timeout: 20_000 })
    const exists = await page.evaluate(async () => new Promise<boolean>((resolve) => {
      const request = indexedDB.open('finora-offline-vault')
      request.onsuccess = () => {
        const get = request.result.transaction('vault').objectStore('vault').get('single-vault')
        get.onsuccess = () => resolve(Boolean(get.result))
      }
    }))
    expect(exists).toBe(false)
  })
})
