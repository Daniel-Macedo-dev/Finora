import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, NetworkError, queryString } from './api'

afterEach(() => {
  vi.restoreAllMocks()
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
