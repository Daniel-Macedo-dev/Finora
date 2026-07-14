import { expect, test, type Page } from '@playwright/test'
import { apiSession, pageGet, pagePost, registerViaUi } from './helpers.ts'

/**
 * Recurring automation lifecycle: definition creation, occurrence preview,
 * manual materialization, idempotent automatic processing, card targets,
 * failure + retry, skip/unskip, reschedule, reversal and user isolation.
 *
 * Occurrence dates are computed relative to "today" because dueness itself is
 * relative to the business date — the assertions derive from the same offsets,
 * so results do not depend on which calendar day the suite runs.
 */

function isoFromToday(days: number): string {
  const date = new Date()
  date.setDate(date.getDate() + days)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(
    date.getDate(),
  ).padStart(2, '0')}`
}

function brDate(iso: string): string {
  const [year, month, day] = iso.split('-')
  return `${day}/${month}/${year}`
}

async function createRecurringViaUi(
  page: Page,
  options: {
    description: string
    amount: string
    categoryLabel: string
    startDate: string
    target?: 'ACCOUNT_TRANSACTION' | 'CREDIT_CARD_PURCHASE'
    accountId?: number
    cardId?: number
    installments?: string
    automatic?: boolean
  },
) {
  await page.goto('/commitments')
  await page.getByRole('button', { name: 'Novo recorrente' }).first().click()
  await page.getByLabel('Descrição').fill(options.description)
  await page.getByLabel('Valor (R$)').fill(options.amount)
  await page.getByLabel('Categoria').selectOption({ label: options.categoryLabel })
  await page.getByLabel('Recorrência').selectOption('WEEKLY')
  await page.getByLabel('Início').fill(options.startDate)
  if (options.target) {
    await page.getByLabel('O que cada ocorrência vira').selectOption(options.target)
    if (options.target === 'ACCOUNT_TRANSACTION' && options.accountId) {
      await page.getByLabel('Conta', { exact: true }).selectOption(String(options.accountId))
    }
    if (options.target === 'CREDIT_CARD_PURCHASE' && options.cardId) {
      await page.getByLabel('Cartão').selectOption(String(options.cardId))
      if (options.installments) {
        await page.getByLabel('Parcelas').fill(options.installments)
      }
    }
    if (options.automatic) {
      await page.getByLabel('Execução').selectOption('AUTOMATIC')
    }
  }
  await page.getByRole('button', { name: 'Criar recorrente' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()
  await expect(page.getByText(options.description).first()).toBeVisible()
}

function occurrenceRow(page: Page, isoDate: string) {
  return page.getByRole('row').filter({ hasText: brDate(isoDate) })
}

async function openOccurrences(page: Page, description: string) {
  await page.getByRole('button', { name: `Ocorrências de ${description}` }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
}

async function accountBalance(page: Page, accountId: number): Promise<number> {
  const accounts = await (await pageGet(page, '/accounts')).json()
  return accounts.find((entry: { id: number }) => entry.id === accountId).currentBalance
}

test.describe('Cenário — Recorrentes em conta', () => {
  test('cria despesa recorrente, executa manualmente e estorna exatamente uma vez', async ({
    page,
  }) => {
    await registerViaUi(page)
    const account = await (
      await pagePost(page, '/accounts', {
        name: 'Conta Corrente',
        type: 'CHECKING',
        openingBalance: 1000,
      })
    ).json()

    const start = isoFromToday(-7)
    await createRecurringViaUi(page, {
      description: 'Assinatura de streaming',
      amount: '100,00',
      categoryLabel: 'Assinaturas (despesa)',
      startDate: start,
      target: 'ACCOUNT_TRANSACTION',
      accountId: account.id,
    })

    // Preview: past occurrence, today's anchor + future weekly repetitions.
    await openOccurrences(page, 'Assinatura de streaming')
    await expect(occurrenceRow(page, start)).toBeVisible()
    await expect(occurrenceRow(page, isoFromToday(7))).toBeVisible()
    await expect(occurrenceRow(page, isoFromToday(14))).toBeVisible()

    // Manual materialization creates one real transaction.
    await occurrenceRow(page, start).getByRole('button', { name: 'Executar' }).click()
    await page.getByRole('button', { name: 'Executar', exact: true }).last().click()
    await expect(occurrenceRow(page, start).getByText('Executada')).toBeVisible()
    await expect(
      occurrenceRow(page, start).getByRole('link', { name: 'Transação gerada' }),
    ).toBeVisible()
    expect(await accountBalance(page, account.id)).toBe(900)

    // A materialized occurrence only offers reversal — no double execution.
    await expect(
      occurrenceRow(page, start).getByRole('button', { name: 'Executar' }),
    ).toHaveCount(0)

    // Reversal restores the balance exactly once and is terminal.
    await occurrenceRow(page, start).getByRole('button', { name: 'Estornar' }).click()
    await page.getByRole('button', { name: 'Estornar', exact: true }).last().click()
    await expect(occurrenceRow(page, start).getByText('Estornada')).toBeVisible()
    expect(await accountBalance(page, account.id)).toBe(1000)
    await expect(
      occurrenceRow(page, start).getByRole('button', { name: 'Estornar' }),
    ).toHaveCount(0)
  })

  test('cria receita recorrente e o saldo sobe ao executar', async ({ page }) => {
    await registerViaUi(page)
    const account = await (
      await pagePost(page, '/accounts', {
        name: 'Conta Salário',
        type: 'CHECKING',
        openingBalance: 200,
      })
    ).json()

    const start = isoFromToday(-7)
    await createRecurringViaUi(page, {
      description: 'Salário quinzenal',
      amount: '1500,00',
      categoryLabel: 'Salário (receita)',
      startDate: start,
      target: 'ACCOUNT_TRANSACTION',
      accountId: account.id,
    })

    await openOccurrences(page, 'Salário quinzenal')
    await occurrenceRow(page, start).getByRole('button', { name: 'Executar' }).click()
    await page.getByRole('button', { name: 'Executar', exact: true }).last().click()
    await expect(occurrenceRow(page, start).getByText('Executada')).toBeVisible()
    expect(await accountBalance(page, account.id)).toBe(1700)
  })

  test('pula, reativa e reagenda mantendo a identidade da ocorrência', async ({ page }) => {
    await registerViaUi(page)
    await createRecurringViaUi(page, {
      description: 'Aporte planejado',
      amount: '50,00',
      categoryLabel: 'Assinaturas (despesa)',
      startDate: isoFromToday(0),
    })

    await openOccurrences(page, 'Aporte planejado')

    // Skip → badge + reactivation; nothing else changes.
    const nextWeek = isoFromToday(7)
    await occurrenceRow(page, nextWeek).getByRole('button', { name: 'Pular' }).click()
    await page.getByRole('button', { name: 'Pular', exact: true }).last().click()
    await expect(occurrenceRow(page, nextWeek).getByText('Pulada')).toBeVisible()
    await occurrenceRow(page, nextWeek).getByRole('button', { name: 'Reativar' }).click()
    await expect(occurrenceRow(page, nextWeek).getByText('Agendada')).toBeVisible()

    // Reschedule moves the effective date, keeping the original visible.
    const twoWeeks = isoFromToday(14)
    const moved = isoFromToday(16)
    await occurrenceRow(page, twoWeeks).getByRole('button', { name: 'Reagendar' }).click()
    await page.getByLabel('Nova data').fill(moved)
    await page.getByRole('button', { name: 'Reagendar', exact: true }).last().click()
    await expect(page.getByText(`(movida de ${brDate(twoWeeks)})`)).toBeVisible()
    await expect(occurrenceRow(page, moved)).toBeVisible()
  })

  test('processar vencidos executa tudo uma única vez, mesmo repetido', async ({ page }) => {
    await registerViaUi(page)
    const account = await (
      await pagePost(page, '/accounts', {
        name: 'Conta Automática',
        type: 'CHECKING',
        openingBalance: 1000,
      })
    ).json()

    // Weekly automatic expense started 14 days ago → 3 due occurrences.
    await createRecurringViaUi(page, {
      description: 'Plano de celular',
      amount: '50,00',
      categoryLabel: 'Assinaturas (despesa)',
      startDate: isoFromToday(-14),
      target: 'ACCOUNT_TRANSACTION',
      accountId: account.id,
      automatic: true,
    })

    await page.getByRole('button', { name: 'Processar vencidos' }).click()
    await expect(page.getByText('3 ocorrência(s) executada(s).')).toBeVisible()
    expect(await accountBalance(page, account.id)).toBe(850)

    // Idempotency: a second run finds nothing and changes nothing.
    await page.getByRole('button', { name: 'Processar vencidos' }).click()
    await expect(page.getByText('Nenhuma ocorrência automática pendente.')).toBeVisible()
    expect(await accountBalance(page, account.id)).toBe(850)
  })
})

test.describe('Cenário — Recorrentes no cartão', () => {
  test('gera compra parcelada na fatura correta; sem limite falha e permite retry', async ({
    page,
  }) => {
    await registerViaUi(page)
    const card = await (
      await pagePost(page, '/credit-cards', {
        name: 'Cartão Recorrente',
        brand: 'VISA',
        creditLimit: 500,
        closingDay: 10,
        dueDay: 17,
      })
    ).json()

    const start = isoFromToday(-7)
    await createRecurringViaUi(page, {
      description: 'Academia',
      amount: '300,00',
      categoryLabel: 'Assinaturas (despesa)',
      startDate: start,
      target: 'CREDIT_CARD_PURCHASE',
      cardId: card.id,
      installments: '2',
    })

    await openOccurrences(page, 'Academia')
    await occurrenceRow(page, start).getByRole('button', { name: 'Executar' }).click()
    await page.getByRole('button', { name: 'Executar', exact: true }).last().click()
    await expect(occurrenceRow(page, start).getByText('Executada')).toBeVisible()
    await expect(
      occurrenceRow(page, start).getByRole('link', { name: 'Compra no cartão' }),
    ).toBeVisible()

    // The purchase went through the card domain: 2 installments of 150 across
    // two consecutive invoices, limit consumed exactly once.
    const invoices = await (await pageGet(page, `/credit-cards/${card.id}/invoices`)).json()
    expect(invoices).toHaveLength(2)
    expect(invoices.map((invoice: { invoiceTotal: number }) => invoice.invoiceTotal)).toEqual([
      150, 150,
    ])
    const cards = await (await pageGet(page, '/credit-cards')).json()
    expect(cards[0].limit.usedLimit).toBe(300)

    // The generated purchase is inspectable in the card area.
    await occurrenceRow(page, start).getByRole('link', { name: 'Compra no cartão' }).click()
    await expect(page.getByRole('heading', { name: 'Cartão Recorrente' })).toBeVisible()
    await expect(page.getByText('Academia').first()).toBeVisible()

    // Insufficient limit: the occurrence fails visibly and can be retried.
    await createRecurringViaUi(page, {
      description: 'Assinatura corporativa',
      amount: '450,00',
      categoryLabel: 'Assinaturas (despesa)',
      startDate: start,
      target: 'CREDIT_CARD_PURCHASE',
      cardId: card.id,
    })
    await openOccurrences(page, 'Assinatura corporativa')
    await occurrenceRow(page, start).getByRole('button', { name: 'Executar' }).click()
    await page.getByRole('button', { name: 'Executar', exact: true }).last().click()
    await expect(occurrenceRow(page, start).getByText('Falhou')).toBeVisible()
    await expect(
      occurrenceRow(page, start).getByRole('button', { name: 'Tentar de novo' }),
    ).toBeVisible()

    // No partial artifacts: card usage unchanged after the failure.
    const cardsAfterFailure = await (await pageGet(page, '/credit-cards')).json()
    expect(cardsAfterFailure[0].limit.usedLimit).toBe(300)

    // Resolve the cause (raise the limit), then retry succeeds.
    const csrf = await page.context().cookies()
    const token = csrf.find((cookie) => cookie.name === 'XSRF-TOKEN')!.value
    await page.request.put(`http://localhost:8080/api/credit-cards/${card.id}`, {
      headers: { 'X-XSRF-TOKEN': decodeURIComponent(token) },
      data: {
        name: 'Cartão Recorrente',
        brand: 'VISA',
        creditLimit: 2000,
        closingDay: 10,
        dueDay: 17,
      },
    })
    await occurrenceRow(page, start).getByRole('button', { name: 'Tentar de novo' }).click()
    await expect(occurrenceRow(page, start).getByText('Executada')).toBeVisible()
    const cardsAfterRetry = await (await pageGet(page, '/credit-cards')).json()
    expect(cardsAfterRetry[0].limit.usedLimit).toBe(750)
  })
})

test.describe('Cenário — Isolamento entre usuários', () => {
  test('usuário B não enxerga nem executa recorrentes de A', async ({ page, request }) => {
    await registerViaUi(page)
    await createRecurringViaUi(page, {
      description: 'Recorrente privado',
      amount: '80,00',
      categoryLabel: 'Assinaturas (despesa)',
      startDate: isoFromToday(-7),
    })
    const commitments = await (await pageGet(page, '/commitments')).json()
    const commitmentId = commitments[0].id

    const intruder = await apiSession(request)
    const base = 'http://localhost:8080/api'
    const headers = { 'X-XSRF-TOKEN': intruder.token }
    const from = isoFromToday(-30)
    const to = isoFromToday(30)

    expect((await request.get(`${base}/commitments/${commitmentId}`)).status()).toBe(404)
    expect(
      (
        await request.get(
          `${base}/commitments/${commitmentId}/occurrences?from=${from}&to=${to}`,
        )
      ).status(),
    ).toBe(404)
    expect(
      (
        await request.post(
          `${base}/commitments/${commitmentId}/occurrences/${isoFromToday(-7)}/materialize`,
          { headers },
        )
      ).status(),
    ).toBe(404)

    // B's own world is empty: no leaked definitions or forecast events.
    expect(await (await request.get(`${base}/commitments`)).json()).toEqual([])
    const forecast = await (await request.get(`${base}/forecast?days=30`)).json()
    expect(forecast.events).toEqual([])
    expect(forecast.openingBalance).toBe(0)
  })
})
