import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import CommitmentForm from './CommitmentForm'
import { OCCURRENCE_STATUS_LABELS } from './types'

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

const CATEGORIES = [
  { id: 1, name: 'Assinaturas', type: 'EXPENSE', active: true, isDefault: true },
  { id: 2, name: 'Salário', type: 'INCOME', active: true, isDefault: true },
]
const ACCOUNTS = [
  { id: 10, name: 'Conta Principal', type: 'CHECKING', openingBalance: 0, currentBalance: 100, archived: false, displayOrder: 0 },
]
const CARDS = [
  { id: 20, name: 'Cartão Roxo', archived: false },
]

function mockApi() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/categories')) {
        return jsonResponse(CATEGORIES)
      }
      if (url.includes('/accounts')) {
        return jsonResponse(ACCOUNTS)
      }
      if (url.includes('/credit-cards')) {
        return jsonResponse(CARDS)
      }
      return jsonResponse([])
    }),
  )
}

function renderForm(onSubmit = vi.fn()) {
  mockApi()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <CommitmentForm
        editing={null}
        busy={false}
        submitError={null}
        onSubmit={onSubmit}
        onCancel={() => undefined}
      />
    </QueryClientProvider>,
  )
  return onSubmit
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('CommitmentForm', () => {
  it('starts projection-only: no target selectors, no execution mode', async () => {
    renderForm()
    await waitFor(() =>
      expect(screen.getByLabelText('O que cada ocorrência vira')).toBeInTheDocument(),
    )
    expect(screen.queryByLabelText('Conta')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Cartão')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Execução')).not.toBeInTheDocument()
    expect(
      screen.getByText(/nada é lançado automaticamente/i),
    ).toBeInTheDocument()
  })

  it('shows account fields for the account target, without generic credit', async () => {
    renderForm()
    await waitFor(() =>
      expect(screen.getByLabelText('O que cada ocorrência vira')).toBeInTheDocument(),
    )
    await userEvent.selectOptions(
      screen.getByLabelText('O que cada ocorrência vira'),
      'ACCOUNT_TRANSACTION',
    )
    expect(screen.getByLabelText('Conta')).toBeInTheDocument()
    expect(screen.getByLabelText('Execução')).toBeInTheDocument()
    const paymentSelect = screen.getByLabelText('Forma de pagamento (opcional)')
    expect(paymentSelect).toBeInTheDocument()
    expect(paymentSelect.querySelector('option[value="CREDIT"]')).toBeNull()
  })

  it('shows card and installments for the card target', async () => {
    renderForm()
    await waitFor(() =>
      expect(screen.getByLabelText('O que cada ocorrência vira')).toBeInTheDocument(),
    )
    await userEvent.selectOptions(
      screen.getByLabelText('O que cada ocorrência vira'),
      'CREDIT_CARD_PURCHASE',
    )
    expect(screen.getByLabelText('Cartão')).toBeInTheDocument()
    expect(screen.getByLabelText('Parcelas')).toBeInTheDocument()
  })

  it('explains the weekly anchor and hides the monthly due day', async () => {
    renderForm()
    await waitFor(() => expect(screen.getByLabelText('Recorrência')).toBeInTheDocument())
    expect(screen.getByLabelText('Dia de vencimento')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('Recorrência'), 'WEEKLY')
    expect(screen.queryByLabelText('Dia de vencimento')).not.toBeInTheDocument()
    expect(screen.getByText(/repete a cada 7 dias/i)).toBeInTheDocument()
  })

  it('blocks submitting an account target without an account', async () => {
    const onSubmit = renderForm()
    await waitFor(() =>
      expect(screen.getByLabelText('O que cada ocorrência vira')).toBeInTheDocument(),
    )
    await userEvent.type(screen.getByLabelText('Descrição'), 'Internet')
    await userEvent.type(screen.getByLabelText('Valor (R$)'), '99,90')
    await userEvent.selectOptions(screen.getByLabelText('Categoria'), '1')
    await userEvent.type(screen.getByLabelText('Dia de vencimento'), '10')
    await userEvent.selectOptions(
      screen.getByLabelText('O que cada ocorrência vira'),
      'ACCOUNT_TRANSACTION',
    )
    await userEvent.click(screen.getByRole('button', { name: 'Criar recorrente' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Escolha a conta de destino.')
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('submits a complete automatic account definition', async () => {
    const onSubmit = renderForm()
    await waitFor(() =>
      expect(screen.getByLabelText('O que cada ocorrência vira')).toBeInTheDocument(),
    )
    await userEvent.type(screen.getByLabelText('Descrição'), 'Internet')
    await userEvent.type(screen.getByLabelText('Valor (R$)'), '99,90')
    await userEvent.selectOptions(screen.getByLabelText('Categoria'), '1')
    await userEvent.type(screen.getByLabelText('Dia de vencimento'), '10')
    await userEvent.selectOptions(
      screen.getByLabelText('O que cada ocorrência vira'),
      'ACCOUNT_TRANSACTION',
    )
    await userEvent.selectOptions(screen.getByLabelText('Conta'), '10')
    await userEvent.selectOptions(screen.getByLabelText('Execução'), 'AUTOMATIC')
    await userEvent.click(screen.getByRole('button', { name: 'Criar recorrente' }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        description: 'Internet',
        amount: 99.9,
        targetKind: 'ACCOUNT_TRANSACTION',
        executionMode: 'AUTOMATIC',
        accountId: 10,
        creditCardId: null,
        installmentCount: 1,
      }),
    )
  })
})

describe('occurrence action invalidation', () => {
  it('refreshes definitions, transactions, accounts, cards, budgets and forecast after materializing', async () => {
    mockApi()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidated: string[] = []
    vi.spyOn(queryClient, 'invalidateQueries').mockImplementation(async (filters) => {
      const key = (filters?.queryKey ?? []) as unknown[]
      invalidated.push(String(key[0]))
    })

    const { renderHook, waitFor: waitForHook } = await import('@testing-library/react')
    const { useOccurrenceAction } = await import('./api')
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => useOccurrenceAction(1), { wrapper })
    result.current.mutate({ date: '2026-07-14', action: 'materialize' })
    await waitForHook(() => expect(result.current.isSuccess).toBe(true))

    for (const key of [
      'commitments',
      'dashboard',
      'forecast',
      'transactions',
      'accounts',
      'credit-cards',
      'budgets',
    ]) {
      expect(invalidated).toContain(key)
    }
  })
})

describe('occurrence status labels', () => {
  it('covers every lifecycle status in pt-BR', () => {
    expect(OCCURRENCE_STATUS_LABELS).toEqual({
      SCHEDULED: 'Agendada',
      MATERIALIZED: 'Executada',
      SKIPPED: 'Pulada',
      FAILED: 'Falhou',
      REVERSED: 'Estornada',
    })
  })
})
