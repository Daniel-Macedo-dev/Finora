import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, NetworkError, OfflineMutationError, queryString } from './api'
import { setOfflineUnlocked } from '../offline/session'

afterEach(() => {
  setOfflineUnlocked(false)
  vi.restoreAllMocks()
})

describe('offline mutation boundary', () => {
  it.each([
    ['POST', () => api.post('/statement-imports', { file: 'x' })],
    ['PUT', () => api.put('/credit-cards/1', { name: 'x' })],
    ['PATCH', () => api.patch('/statement-imports/1', { included: false })],
    ['DELETE', () => api.delete('/credit-cards/1')],
  ])('blocks %s before fetch or CSRF bootstrap', async (_method, request) => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    setOfflineUnlocked(true)
    const error = await request().catch((caught: unknown) => caught)
    expect(error).toBeInstanceOf(OfflineMutationError)
    expect((error as Error).message).toBe(
      'Esta ação ainda exige conexão e não pode ser adicionada à fila offline.',
    )
    // Nothing is negotiated with the network — not even the CSRF bootstrap.
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('still blocks a raw call on a domain that supports queueing', async () => {
    // Supported domains queue through their hooks, never through the raw
    // client. A direct call is therefore always a mistake, and is refused
    // exactly like an unsupported one.
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    setOfflineUnlocked(true)
    await expect(api.post('/transactions', { amount: 1 })).rejects.toBeInstanceOf(
      OfflineMutationError,
    )
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('allows reads while offline', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    setOfflineUnlocked(true)
    await expect(api.get('/dashboard')).resolves.toEqual({ ok: true })
  })
})

function mockFetchResponse(status: number, body: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  )
}

describe('api error normalization', () => {
  it('turns a ProblemDetail response into an ApiError with code and fields', async () => {
    mockFetchResponse(422, {
      title: 'Regra de negócio violada',
      detail: 'Já existe um orçamento para essa categoria nesse mês.',
      code: 'BUDGET_ALREADY_EXISTS',
    })

    const error = await api.get('/budgets').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.status).toBe(422)
    expect(apiError.code).toBe('BUDGET_ALREADY_EXISTS')
    expect(apiError.message).toContain('orçamento')
  })

  it('collects field validation errors', async () => {
    mockFetchResponse(400, {
      title: 'Dados inválidos',
      detail: 'Um ou mais campos estão inválidos.',
      errors: [{ field: 'amount', message: 'O valor deve ser maior que zero.' }],
    })

    const error = (await api.post('/transactions', {}).catch((e: unknown) => e)) as ApiError
    expect(error.fieldErrors).toHaveLength(1)
    expect(error.fieldErrors[0].field).toBe('amount')
  })

  it('wraps connection failures in NetworkError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')))
    const error = await api.get('/dashboard').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(NetworkError)
  })

  it('returns parsed JSON on success', async () => {
    mockFetchResponse(200, { month: '2026-07' })
    await expect(api.get('/dashboard')).resolves.toEqual({ month: '2026-07' })
  })
})

describe('queryString', () => {
  it('skips empty values and keeps meaningful ones', () => {
    expect(
      queryString({ month: '2026-07', type: undefined, categoryId: null, search: '', page: 0 }),
    ).toBe('?month=2026-07&page=0')
  })

  it('returns an empty string when nothing applies', () => {
    expect(queryString({ a: undefined })).toBe('')
  })
})
