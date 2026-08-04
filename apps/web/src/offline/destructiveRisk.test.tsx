import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { destructiveRiskOf, mayDestroyUnsyncedWork } from './destructiveRisk'
import type { VaultCounts, VaultState } from './VaultProvider'

/**
 * The guard around every path that deletes the local encrypted copy.
 *
 * The case that matters most is the boring one: a locked vault whose queue is
 * genuinely empty still has to warn, because the application cannot tell that
 * vault apart from one holding the only copy of a week of offline work. A test
 * that only proved the warning appears when work exists would pass just as
 * happily against the bug this replaced.
 */

const EMPTY: VaultCounts = {
  total: 0,
  pending: 0,
  blocked: 0,
  syncing: 0,
  conflicts: 0,
  retryable: 0,
  permanent: 0,
}

const PENDING: VaultCounts = { ...EMPTY, total: 3, pending: 2, conflicts: 1 }

const logoutMutate = vi.fn()
const removeVault = vi.fn()
const navigate = vi.fn()

let vaultState: VaultState = 'LOCKED'
let vaultCounts: VaultCounts = EMPTY

function vaultStub() {
  return {
    state: vaultState,
    counts: vaultCounts,
    destructiveRisk: destructiveRiskOf(vaultState, vaultCounts.total),
    hasPendingWork: vaultCounts.total > 0,
    owner: { id: 1, displayName: 'Dona', email: 'dona@finora.test' },
    remove: removeVault,
    lock: vi.fn(),
    error: null,
    updatedAt: null,
    size: null,
    entries: [],
    autoSync: true,
    lastSyncAt: null,
    replaying: false,
    setAutoSync: vi.fn(),
    unlock: vi.fn(),
    enable: vi.fn(),
    refresh: vi.fn(),
  }
}

vi.mock('./VaultProvider', () => ({
  useVault: () => vaultStub(),
  useOptionalVault: () => vaultStub(),
}))

vi.mock('../features/auth/api', () => ({
  useCurrentUser: () => ({ data: { id: 1, displayName: 'Dona', email: 'dona@finora.test' } }),
  useLogout: () => ({ mutate: logoutMutate, isPending: false }),
}))

vi.mock('../pwa/PwaProvider', () => ({
  usePwa: () => ({ installState: 'unsupported', serviceWorkerReady: true, registrationError: null }),
}))

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigate }
})

const { UserPanel } = await import('../components/AppShell')
const OfflineSettings = (await import('../features/settings/OfflineSettings')).default

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <UserPanel />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function renderSettings() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <OfflineSettings />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Signs out the way the shell does: server call first, then local cleanup. */
function completeLogout() {
  logoutMutate.mockImplementation((_vars: unknown, options?: { onSettled?: () => void }) =>
    options?.onSettled?.(),
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  vaultState = 'LOCKED'
  vaultCounts = EMPTY
  removeVault.mockResolvedValue(undefined)
  completeLogout()
})

describe('destructiveRiskOf', () => {
  it('separates knowing the queue is empty from being unable to read it', () => {
    expect(destructiveRiskOf('UNLOCKED_ONLINE', 0)).toBe('KNOWN_SAFE')
    expect(destructiveRiskOf('LOCKED', 0)).toBe('UNKNOWN_LOCKED')
    expect(mayDestroyUnsyncedWork(destructiveRiskOf('UNLOCKED_ONLINE', 0))).toBe(false)
    expect(mayDestroyUnsyncedWork(destructiveRiskOf('LOCKED', 0))).toBe(true)
  })

  it('maps every vault state to a decision', () => {
    expect(destructiveRiskOf('ABSENT', 0)).toBe('NO_LOCAL_COPY')
    expect(destructiveRiskOf('LOADING', 0)).toBe('BUSY')
    expect(destructiveRiskOf('UNLOCKING', 0)).toBe('BUSY')
    expect(destructiveRiskOf('CORRUPTED', 0)).toBe('UNKNOWN_CORRUPTED')
    expect(destructiveRiskOf('UNLOCKED_OFFLINE', 2)).toBe('KNOWN_PENDING')
  })
})

describe('sair da conta', () => {
  it('sai direto quando não existe cópia local', async () => {
    vaultState = 'ABSENT'
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(logoutMutate).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/login', { replace: true }))
  })

  it('sai direto quando a fila descriptografada está comprovadamente vazia', async () => {
    vaultState = 'UNLOCKED_ONLINE'
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(removeVault).toHaveBeenCalledTimes(1)
  })

  it('mostra as contagens exatas quando a fila é legível e tem pendências', async () => {
    vaultState = 'UNLOCKED_ONLINE'
    vaultCounts = PENDING
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))

    expect(
      screen.getByRole('heading', { name: 'Sair com alterações offline pendentes' }),
    ).toBeInTheDocument()
    expect(screen.getByText(/Há 3 alteração\(ões\) offline/)).toBeInTheDocument()
    expect(screen.getByText(/sendo 1 em conflito/)).toBeInTheDocument()
    expect(removeVault).not.toHaveBeenCalled()
  })

  it('avisa da incerteza quando o cofre está bloqueado', async () => {
    vaultState = 'LOCKED'
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))

    expect(
      screen.getByRole('heading', { name: 'A cópia offline está bloqueada' }),
    ).toBeInTheDocument()
    expect(screen.getByText(/pode conter alterações que ainda não foram enviadas/)).toBeInTheDocument()
    expect(screen.getByText(/não pode verificar isso agora/)).toBeInTheDocument()
  })

  it('avisa igual quando o cofre bloqueado está de fato vazio', async () => {
    // The false positive is the policy, not an accident: `counts` is empty here
    // precisely because nothing could be decrypted to count.
    vaultState = 'LOCKED'
    vaultCounts = EMPTY
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))

    expect(
      screen.getByRole('heading', { name: 'A cópia offline está bloqueada' }),
    ).toBeInTheDocument()
    // And it must not invent a number it does not have.
    expect(screen.queryByText(/Há 0 alteração/)).not.toBeInTheDocument()
    expect(screen.queryByText(/alteração\(ões\) offline que ainda não chegaram/)).not.toBeInTheDocument()
  })

  it('avisa que o conteúdo não pôde ser verificado quando o cofre está ilegível', async () => {
    vaultState = 'CORRUPTED'
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))

    expect(
      screen.getByRole('heading', { name: 'A cópia offline não pôde ser verificada' }),
    ).toBeInTheDocument()
    expect(screen.getByText(/não pôde ser lida nem validada/)).toBeInTheDocument()
  })

  it.each(['LOADING', 'UNLOCKING'] as const)('impede sair enquanto o estado é %s', async (state) => {
    vaultState = state
    renderPanel()
    const button = screen.getByRole('button', { name: 'Sair da conta' })

    expect(button).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent(/Verificando a cópia offline/)
    expect(logoutMutate).not.toHaveBeenCalled()
  })

  it('cancelar não sai nem apaga nada', async () => {
    vaultState = 'LOCKED'
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(logoutMutate).not.toHaveBeenCalled()
    expect(removeVault).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
  })

  it('desbloquear e verificar leva à central sem apagar', async () => {
    vaultState = 'LOCKED'
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))
    await userEvent.click(screen.getByRole('button', { name: 'Desbloquear e verificar' }))

    expect(navigate).toHaveBeenCalledWith('/offline-sync')
    expect(removeVault).not.toHaveBeenCalled()
    expect(logoutMutate).not.toHaveBeenCalled()
  })

  it('o primeiro passo destrutivo ainda não apaga', async () => {
    vaultState = 'LOCKED'
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))
    await userEvent.click(screen.getByRole('button', { name: 'Descartar cópia e sair' }))

    expect(screen.getByRole('heading', { name: 'Excluir a cópia offline e sair' })).toBeInTheDocument()
    expect(screen.getByText('Essa ação não pode ser desfeita.')).toBeInTheDocument()
    expect(removeVault).not.toHaveBeenCalled()
    expect(logoutMutate).not.toHaveBeenCalled()
  })

  it('só a confirmação final apaga a cópia', async () => {
    vaultState = 'LOCKED'
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))
    await userEvent.click(screen.getByRole('button', { name: 'Descartar cópia e sair' }))
    await userEvent.click(screen.getByRole('button', { name: 'Excluir e sair definitivamente' }))

    expect(logoutMutate).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(removeVault).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/login', { replace: true }))
  })

  it('voltar da confirmação final não apaga', async () => {
    vaultState = 'LOCKED'
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))
    await userEvent.click(screen.getByRole('button', { name: 'Descartar cópia e sair' }))
    await userEvent.click(screen.getByRole('button', { name: 'Voltar' }))

    expect(
      screen.getByRole('heading', { name: 'A cópia offline está bloqueada' }),
    ).toBeInTheDocument()
    expect(removeVault).not.toHaveBeenCalled()
  })

  it('a limpeza local acontece mesmo quando o logout do servidor falha', async () => {
    vaultState = 'LOCKED'
    // A request that failed reaches onError and then onSettled, never onSuccess.
    // Hanging the cleanup off onSuccess would leave a confirmed sign-out with
    // its decrypted data intact whenever the network happened to be down.
    logoutMutate.mockImplementation(
      (_vars: unknown, options?: { onSuccess?: () => void; onSettled?: () => void }) => {
        options?.onSettled?.()
      },
    )
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))
    await userEvent.click(screen.getByRole('button', { name: 'Descartar cópia e sair' }))
    await userEvent.click(screen.getByRole('button', { name: 'Excluir e sair definitivamente' }))

    await waitFor(() => expect(removeVault).toHaveBeenCalledTimes(1))
  })

  it('uma exclusão local que falhou é relatada, não comemorada', async () => {
    vaultState = 'LOCKED'
    removeVault.mockRejectedValue(new Error('IndexedDB indisponível'))
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))
    await userEvent.click(screen.getByRole('button', { name: 'Descartar cópia e sair' }))
    await userEvent.click(screen.getByRole('button', { name: 'Excluir e sair definitivamente' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /não pôde ser excluída/,
    )
    expect(navigate).not.toHaveBeenCalledWith('/login', { replace: true })
  })

  it('nunca descreve os dados do servidor como apagados', async () => {
    vaultState = 'LOCKED'
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))

    expect(
      screen.getByText('Os dados já enviados ao servidor não são apagados.'),
    ).toBeInTheDocument()
  })

  it('o foco inicial fica no cancelar, não na ação destrutiva', async () => {
    vaultState = 'LOCKED'
    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Sair da conta' }))

    expect(screen.getByRole('button', { name: 'Cancelar' })).toHaveFocus()
    // Every action reads as a distinct sentence, so a screen reader user is
    // never choosing between two buttons announced the same way.
    const names = screen
      .getAllByRole('button')
      .map((button) => button.getAttribute('aria-label') ?? button.textContent?.trim())
    expect(new Set(names).size).toBe(names.length)
  })
})

describe('desativar o acesso offline', () => {
  it('usa a mesma proteção do cofre bloqueado', async () => {
    vaultState = 'LOCKED'
    renderSettings()
    await userEvent.click(screen.getByRole('button', { name: 'Desativar e excluir cópia local' }))

    expect(
      screen.getByRole('heading', { name: 'A cópia offline está bloqueada' }),
    ).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Excluir cópia mesmo assim' }))
    expect(removeVault).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Excluir cópia definitivamente' }))
    await waitFor(() => expect(removeVault).toHaveBeenCalledTimes(1))
  })

  it('não afirma pendências quando a cópia legível está vazia', async () => {
    vaultState = 'UNLOCKED_ONLINE'
    renderSettings()
    await userEvent.click(screen.getByRole('button', { name: 'Desativar e excluir cópia local' }))

    expect(screen.getByText(/Não há alterações pendentes registradas nela/)).toBeInTheDocument()
    expect(screen.queryByText('Essa ação não pode ser desfeita.')).not.toBeInTheDocument()
  })

  it('não relata contagem zero enquanto a cópia está bloqueada', () => {
    vaultState = 'LOCKED'
    renderSettings()
    expect(screen.getByText('Desconhecido (cópia bloqueada)')).toBeInTheDocument()
  })
})
