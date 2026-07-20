import { expect, type APIRequestContext, type Page } from '@playwright/test'

const API = 'http://localhost:8080/api'

let userCounter = 0

export interface TestIdentity {
  email: string
  password: string
  displayName: string
}

/** A unique identity per call — E2E isolation comes from distinct users, not
 * from destructive global cleanup (which no longer exists post-auth). */
export function uniqueIdentity(prefix = 'user'): TestIdentity {
  userCounter += 1
  const stamp = `${Date.now()}-${userCounter}`
  return {
    email: `${prefix}-${stamp}@finora.test`,
    password: 'senha-de-teste-123',
    displayName: `Pessoa ${prefix} ${userCounter}`,
  }
}

/** Registers a user through the UI and lands on the authenticated dashboard. */
export async function registerViaUi(page: Page, identity = uniqueIdentity()): Promise<TestIdentity> {
  await page.goto('/register')
  await page.getByLabel('Nome').fill(identity.displayName)
  await page.getByLabel('E-mail').fill(identity.email)
  await page.getByLabel('Senha', { exact: true }).fill(identity.password)
  await page.getByLabel('Confirmar senha').fill(identity.password)
  await page.getByRole('button', { name: 'Criar conta' }).click()
  await expect(page.getByRole('heading', { name: 'Visão geral' })).toBeVisible()
  return identity
}

export async function loginViaUi(page: Page, identity: TestIdentity): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('E-mail').fill(identity.email)
  await page.getByLabel('Senha', { exact: true }).fill(identity.password)
  await page.getByRole('button', { name: 'Entrar' }).click()
  await expect(page.getByRole('heading', { name: 'Visão geral' })).toBeVisible()
}

export async function logoutViaUi(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Sair da conta' }).click()
  await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible()
}

/* ---------- API-backed session for direct requests ---------- */

/**
 * Registers a user via the API and returns a request context that carries that
 * user's session cookie + CSRF token — used for direct cross-user attack
 * assertions that must bypass the UI.
 */
export async function apiSession(request: APIRequestContext, identity = uniqueIdentity()) {
  // Bootstrap CSRF token, then register.
  const csrf = await request.get(`${API}/auth/csrf`)
  const token = csrfTokenFrom(csrf.headers()['set-cookie'])
  const register = await request.post(`${API}/auth/register`, {
    headers: { 'X-XSRF-TOKEN': token },
    data: {
      displayName: identity.displayName,
      email: identity.email,
      password: identity.password,
    },
  })
  if (!register.ok()) {
    throw new Error(`Falha ao registrar usuário de teste: ${await register.text()}`)
  }
  const id = (await register.json()).id as number
  return { identity, id, token }
}

function csrfTokenFrom(setCookie: string | undefined): string {
  if (!setCookie) {
    throw new Error('resposta /auth/csrf não trouxe Set-Cookie')
  }
  const match = setCookie.match(/XSRF-TOKEN=([^;]+)/)
  if (!match) {
    throw new Error('cookie XSRF-TOKEN ausente')
  }
  return decodeURIComponent(match[1])
}

/* ---------- browser-session API calls ---------- */

/**
 * POSTs through the page's own browser context (session + CSRF cookies),
 * for fast test-data setup that the UI flow under test doesn't cover.
 */
export async function pagePost(page: Page, path: string, data: unknown) {
  const cookies = await page.context().cookies()
  const token = cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')?.value
  if (!token) {
    throw new Error('sessão do navegador sem cookie XSRF-TOKEN')
  }
  return page.request.post(`${API}${path}`, {
    headers: { 'X-XSRF-TOKEN': decodeURIComponent(token) },
    data,
  })
}

export async function pagePut(page: Page, path: string, data: unknown) {
  const cookies = await page.context().cookies()
  const token = cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')?.value
  if (!token) {
    throw new Error('sessão do navegador sem cookie XSRF-TOKEN')
  }
  return page.request.put(`${API}${path}`, {
    headers: { 'X-XSRF-TOKEN': decodeURIComponent(token) },
    data,
  })
}

export async function pageGet(page: Page, path: string) {
  return page.request.get(`${API}${path}`)
}

export async function categoryId(
  request: APIRequestContext,
  name: string,
  type: 'INCOME' | 'EXPENSE',
): Promise<number> {
  const categories = await (await request.get(`${API}/categories?type=${type}`)).json()
  const category = (categories as Array<{ id: number; name: string }>).find(
    (entry) => entry.name === name,
  )
  if (!category) {
    throw new Error(`Categoria padrão não encontrada: ${name}`)
  }
  return category.id
}
