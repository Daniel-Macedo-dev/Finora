import type { APIRequestContext } from '@playwright/test'

const API = 'http://localhost:8080/api'

interface Page<T> {
  content: T[]
}

/**
 * Clears user-created data through the public API so each scenario starts
 * from a deterministic state. Default categories and settings stay.
 */
export async function resetData(request: APIRequestContext): Promise<void> {
  // Transactions first: accounts/categories with history refuse deletion.
  for (;;) {
    const page = (await (
      await request.get(`${API}/transactions?size=100`)
    ).json()) as Page<{ id: number }>
    if (page.content.length === 0) {
      break
    }
    for (const transaction of page.content) {
      await request.delete(`${API}/transactions/${transaction.id}`)
    }
  }
  const wishlist = (await (await request.get(`${API}/wishlist`)).json()) as Array<{ id: number }>
  for (const item of wishlist) {
    await request.delete(`${API}/wishlist/${item.id}`)
  }
  const goals = (await (await request.get(`${API}/goals`)).json()) as Array<{ id: number }>
  for (const goal of goals) {
    await request.delete(`${API}/goals/${goal.id}`)
  }
  const commitments = (await (await request.get(`${API}/commitments`)).json()) as Array<{
    id: number
  }>
  for (const commitment of commitments) {
    await request.delete(`${API}/commitments/${commitment.id}`)
  }
  const accounts = (await (await request.get(`${API}/accounts`)).json()) as Array<{ id: number }>
  for (const account of accounts) {
    await request.delete(`${API}/accounts/${account.id}`)
  }
  // Budgets of the current and neighbour months.
  const now = new Date()
  for (let delta = -3; delta <= 1; delta++) {
    const date = new Date(now.getFullYear(), now.getMonth() + delta, 1)
    const month = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
    const summary = (await (await request.get(`${API}/budgets?month=${month}`)).json()) as {
      budgets: Array<{ id: number }>
    }
    for (const budget of summary.budgets) {
      await request.delete(`${API}/budgets/${budget.id}`)
    }
  }
  await resetSettings(request)
}

export async function resetSettings(
  request: APIRequestContext,
  overrides: Partial<{
    minimumCashBuffer: number
    maxInstallmentCommitmentRatio: number
    monthlyOpportunityRate: number
    budgetWarningThreshold: number
  }> = {},
): Promise<void> {
  await request.put(`${API}/settings`, {
    data: {
      minimumCashBuffer: 0,
      maxInstallmentCommitmentRatio: 0.3,
      monthlyOpportunityRate: 0,
      budgetWarningThreshold: 0.8,
      ...overrides,
    },
  })
}

export async function categoryId(
  request: APIRequestContext,
  name: string,
  type: 'INCOME' | 'EXPENSE',
): Promise<number> {
  const categories = (await (await request.get(`${API}/categories?type=${type}`)).json()) as Array<{
    id: number
    name: string
  }>
  const category = categories.find((entry) => entry.name === name)
  if (!category) {
    throw new Error(`Categoria padrão não encontrada: ${name}`)
  }
  return category.id
}

export async function createTransaction(
  request: APIRequestContext,
  data: {
    type: 'INCOME' | 'EXPENSE'
    amount: number
    description: string
    date: string
    categoryId: number
    accountId?: number
  },
): Promise<void> {
  const response = await request.post(`${API}/transactions`, { data })
  if (!response.ok()) {
    throw new Error(`Falha ao criar transação de fixture: ${await response.text()}`)
  }
}

export async function createAccount(
  request: APIRequestContext,
  name: string,
  openingBalance: number,
): Promise<number> {
  const response = await request.post(`${API}/accounts`, {
    data: { name, type: 'CHECKING', openingBalance },
  })
  if (!response.ok()) {
    throw new Error(`Falha ao criar conta de fixture: ${await response.text()}`)
  }
  return ((await response.json()) as { id: number }).id
}

/** Months of history (income/expense) covering the analysis window. */
export async function seedThreeMonthHistory(
  request: APIRequestContext,
  income: number,
  expense: number,
): Promise<void> {
  const incomeCat = await categoryId(request, 'Salário', 'INCOME')
  const expenseCat = await categoryId(request, 'Outros', 'EXPENSE')
  const now = new Date()
  for (let i = 1; i <= 3; i++) {
    const month = new Date(now.getFullYear(), now.getMonth() - i, 1)
    const key = `${month.getFullYear()}-${String(month.getMonth() + 1).padStart(2, '0')}`
    await createTransaction(request, {
      type: 'INCOME',
      amount: income,
      description: `Salário ${key}`,
      date: `${key}-05`,
      categoryId: incomeCat,
    })
    await createTransaction(request, {
      type: 'EXPENSE',
      amount: expense,
      description: `Gastos ${key}`,
      date: `${key}-20`,
      categoryId: expenseCat,
    })
  }
}
