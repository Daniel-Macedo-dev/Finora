import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import LoginPage from './LoginPage'
import RequireAuth from './RequireAuth'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const AUTHENTICATED_USER = {
  id: 1,
  displayName: 'Usuária Teste',
  email: 'user@test.dev',
  createdAt: '2026-07-01T00:00:00Z',
}

function mockApi(routes: Record<string, () => Response | Promise<Response>>) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      for (const [suffix, handler] of Object.entries(routes)) {
        if (url.includes(suffix)) {
          return handler()
        }
      }
      return jsonResponse(404, {})
    }),
  )
}

function renderWithProviders(ui: React.ReactElement, initialPath = '/') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=token-de-teste'
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('LoginPage', () => {
  it('valida campos vazios sem chamar a API', async () => {
    const user = userEvent.setup()
    mockApi({ '/auth/me': () => jsonResponse(401, {}) })
    renderWithProviders(<LoginPage />)

    await user.click(screen.getByRole('button', { name: 'Entrar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Informe e-mail e senha.')
  })

  it('mostra erro genérico para credenciais inválidas', async () => {
    const user = userEvent.setup()
    mockApi({
      '/auth/me': () => jsonResponse(401, {}),
      '/auth/login': () =>
        jsonResponse(401, { code: 'AUTH_INVALID_CREDENTIALS', detail: 'E-mail ou senha inválidos.' }),
    })
    renderWithProviders(<LoginPage />)

    await user.type(screen.getByLabelText('E-mail'), 'user@test.dev')
    await user.type(screen.getByLabelText('Senha'), 'senha-errada')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('E-mail ou senha inválidos.')
  })

  it('anuncia sessão expirada quando redirecionado com o estado', async () => {
    mockApi({ '/auth/me': () => jsonResponse(401, {}) })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter
          initialEntries={[{ pathname: '/login', state: { sessionExpired: true } }]}
        >
          <LoginPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(await screen.findByRole('status')).toHaveTextContent('Sua sessão expirou')
  })

  it('alterna a visibilidade da senha de forma acessível', async () => {
    const user = userEvent.setup()
    mockApi({ '/auth/me': () => jsonResponse(401, {}) })
    renderWithProviders(<LoginPage />)

    const senha = screen.getByLabelText('Senha')
    expect(senha).toHaveAttribute('type', 'password')
    await user.click(screen.getByRole('button', { name: 'Mostrar senha' }))
    expect(senha).toHaveAttribute('type', 'text')
    await user.click(screen.getByRole('button', { name: 'Ocultar senha' }))
    expect(senha).toHaveAttribute('type', 'password')
  })
})

describe('RequireAuth', () => {
  function protectedApp() {
    return (
      <Routes>
        <Route path="/login" element={<p>Página de login</p>} />
        <Route
          path="/dashboard"
          element={
            <RequireAuth>
              <p>Conteúdo protegido</p>
            </RequireAuth>
          }
        />
      </Routes>
    )
  }

  it('redireciona usuário não autenticado para o login', async () => {
    mockApi({ '/auth/me': () => jsonResponse(401, {}) })
    renderWithProviders(protectedApp(), '/dashboard')

    expect(await screen.findByText('Página de login')).toBeInTheDocument()
    expect(screen.queryByText('Conteúdo protegido')).not.toBeInTheDocument()
  })

  it('renderiza o conteúdo para usuário autenticado', async () => {
    mockApi({ '/auth/me': () => jsonResponse(200, AUTHENTICATED_USER) })
    renderWithProviders(protectedApp(), '/dashboard')

    expect(await screen.findByText('Conteúdo protegido')).toBeInTheDocument()
  })

  it('falha de rede mostra retry em vez de redirecionar para login', async () => {
    mockApi({
      '/auth/me': () => {
        throw new TypeError('fetch failed')
      },
    })
    renderWithProviders(protectedApp(), '/dashboard')

    expect(
      await screen.findByRole('button', { name: 'Tentar novamente' }),
    ).toBeInTheDocument()
    expect(screen.queryByText('Página de login')).not.toBeInTheDocument()
  })

  it('não expõe conteúdo protegido durante a hidratação', async () => {
    let resolveMe: (response: Response) => void = () => {}
    mockApi({
      '/auth/me': () => new Promise<Response>((resolve) => (resolveMe = resolve)),
    })
    renderWithProviders(protectedApp(), '/dashboard')

    expect(screen.queryByText('Conteúdo protegido')).not.toBeInTheDocument()
    resolveMe(jsonResponse(200, AUTHENTICATED_USER))
    await waitFor(() =>
      expect(screen.getByText('Conteúdo protegido')).toBeInTheDocument(),
    )
  })
})
