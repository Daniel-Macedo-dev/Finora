/**
 * Central API client. All server communication goes through here so base URL,
 * session credentials, CSRF handling and error normalization live in one place.
 *
 * Authentication model: HttpOnly session cookie (FINORA_SESSION) managed by
 * the browser + CSRF double-submit (XSRF-TOKEN cookie echoed back in the
 * X-XSRF-TOKEN header on unsafe methods). Nothing auth-related ever touches
 * localStorage.
 */

const BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '/api'

const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

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

  get isUnauthenticated(): boolean {
    return this.status === 401
  }
}

/** Error thrown when the API cannot be reached at all. */
export class NetworkError extends Error {
  constructor() {
    super('Não foi possível conectar ao servidor. Verifique se a API está em execução.')
    this.name = 'NetworkError'
  }
}

function readCookie(name: string): string | null {
  const prefix = `${name}=`
  for (const part of document.cookie.split('; ')) {
    if (part.startsWith(prefix)) {
      return decodeURIComponent(part.slice(prefix.length))
    }
  }
  return null
}

let csrfBootstrap: Promise<void> | null = null

/** Ensures the XSRF-TOKEN cookie exists before the first unsafe request. */
async function ensureCsrfToken(): Promise<void> {
  if (readCookie('XSRF-TOKEN')) {
    return
  }
  csrfBootstrap ??= fetch(`${BASE_URL}/auth/csrf`, { credentials: 'same-origin' })
    .then(() => undefined)
    .catch(() => undefined)
    .finally(() => {
      csrfBootstrap = null
    })
  await csrfBootstrap
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
  const method = (init?.method ?? 'GET').toUpperCase()
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...(init?.headers as Record<string, string>),
  }
  if (UNSAFE_METHODS.has(method)) {
    await ensureCsrfToken()
    const token = readCookie('XSRF-TOKEN')
    if (token) {
      headers['X-XSRF-TOKEN'] = token
    }
  }
  let response: Response
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      credentials: 'same-origin',
      ...init,
      headers,
    })
  } catch {
    throw new NetworkError()
  }
  if (!response.ok) {
    // Mutations are never replayed automatically: a CSRF/session failure on a
    // financial write must surface instead of risking a duplicate write.
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
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, body !== undefined ? jsonInit('POST', body) : { method: 'POST' }),
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
