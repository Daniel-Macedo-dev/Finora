import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from './api'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  // jsdom cookie jar: reset between tests.
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('CSRF integration in the API client', () => {
  it('envia o header X-XSRF-TOKEN em mutações usando o cookie', async () => {
    document.cookie = 'XSRF-TOKEN=abc-123'
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { ok: true }))
    vi.stubGlobal('fetch', fetchMock)

    await api.post('/transactions', { amount: 1 })

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('abc-123')
    expect(init.credentials).toBe('same-origin')
  })

  it('faz o bootstrap do token antes da primeira mutação quando não há cookie', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/auth/csrf')) {
        document.cookie = 'XSRF-TOKEN=novo-token'
        return new Response(null, { status: 204 })
      }
      return jsonResponse(200, { ok: true })
    })
    vi.stubGlobal('fetch', fetchMock)

    await api.post('/goals', { name: 'x' })

    const urls = fetchMock.mock.calls.map((call) => String(call[0]))
    expect(urls[0]).toContain('/auth/csrf')
    const mutation = fetchMock.mock.calls[1] as unknown as [string, RequestInit]
    expect((mutation[1].headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('novo-token')
  })

  it('não envia header CSRF em leituras', async () => {
    document.cookie = 'XSRF-TOKEN=abc-123'
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, {}))
    vi.stubGlobal('fetch', fetchMock)

    await api.get('/dashboard')

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('expõe 401 como isUnauthenticated para o tratamento global', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse(401, { code: 'AUTH_UNAUTHENTICATED' })),
    )
    const error = (await api.get('/dashboard').catch((e: unknown) => e)) as ApiError
    expect(error.isUnauthenticated).toBe(true)
    expect(error.code).toBe('AUTH_UNAUTHENTICATED')
  })
})
