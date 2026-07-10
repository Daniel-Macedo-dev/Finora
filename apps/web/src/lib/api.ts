/**
 * Central API client. All server communication goes through here so base URL,
 * error normalization and JSON handling live in one place.
 */

const BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '/api'

export interface FieldError {
  field: string
  message: string
}

/** Normalized API error carrying the backend's ProblemDetail payload. */
export class ApiError extends Error {
  readonly status: number
  readonly title: string
  readonly code?: string
  readonly fieldErrors: FieldError[]

  constructor(
    status: number,
    title: string,
    detail: string,
    code?: string,
    fieldErrors: FieldError[] = [],
  ) {
    super(detail)
    this.name = 'ApiError'
    this.status = status
    this.title = title
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

/** Error thrown when the API cannot be reached at all. */
export class NetworkError extends Error {
  constructor() {
    super('Não foi possível conectar ao servidor. Verifique se a API está em execução.')
    this.name = 'NetworkError'
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  let title = 'Erro inesperado'
  let detail = 'Algo deu errado ao falar com o servidor.'
  let code: string | undefined
  let fieldErrors: FieldError[] = []
  try {
    const body = await response.json()
    if (typeof body.title === 'string') title = body.title
    if (typeof body.detail === 'string') detail = body.detail
    if (typeof body.code === 'string') code = body.code
    if (Array.isArray(body.errors)) fieldErrors = body.errors
  } catch {
    // non-JSON error body; keep defaults
  }
  return new ApiError(response.status, title, detail, code, fieldErrors)
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      headers: { Accept: 'application/json', ...init?.headers },
      ...init,
    })
  } catch {
    throw new NetworkError()
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

function jsonInit(method: string, body: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body: unknown) => request<T>(path, jsonInit('POST', body)),
  put: <T>(path: string, body: unknown) => request<T>(path, jsonInit('PUT', body)),
  delete: (path: string) => request<void>(path, { method: 'DELETE' }),
}

/** Pagination envelope matching the backend's PageResponse. */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** Builds a query string skipping null/undefined/empty values. */
export function queryString(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value))
    }
  }
  const raw = search.toString()
  return raw ? `?${raw}` : ''
}
