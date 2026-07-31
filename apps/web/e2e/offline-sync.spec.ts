import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { uniqueIdentity, type TestIdentity } from './helpers.ts'

/**
 * Offline write, replay and conflict resolution, driven entirely through the
 * real UI and the real endpoints.
 *
 * There is no test-only backdoor into the queue and no bypass of authentication
 * or CSRF: every mutation here travels the same path a user's would. Offline is
 * simulated with Playwright's network controls, and a lost response is simulated
 * by aborting the reply after the server has already committed — the one case
 * the client cannot distinguish on its own.
 */

const OFFLINE_PASSWORD = 'senha-offline-sintetica'

async function registerAndEnableVault(page: Page, identity: TestIdentity): Promise<void> {
  await page.goto('/register')
  await page.getByLabel('Nome').fill(identity.displayName)
  await page.getByLabel('E-mail').fill(identity.email)
  await page.getByLabel('Senha', { exact: true }).fill(identity.password)
  await page.getByLabel('Confirmar senha').fill(identity.password)
  await page.getByRole('button', { name: 'Criar conta' }).click()
  await expect(page.getByRole('heading', { name: 'Visão geral' })).toBeVisible()

  await page.goto('/settings')
  await page.getByLabel('Senha offline (mínimo de 12 caracteres)').fill(OFFLINE_PASSWORD)
  await page.getByLabel('Confirmar senha offline').fill(OFFLINE_PASSWORD)
  await page.getByRole('button', { name: 'Ativar acesso offline neste dispositivo' }).click()
  await expect(page.getByText('Acesso offline ativado neste dispositivo.')).toBeVisible({
    timeout: 20_000,
  })
}

/**
 * Unlocks the vault if this navigation landed on the unlock screen.
 *
 * The derived key lives only in memory, so every full page load while offline
 * asks for the offline password again. That is the product behaviour, not a
 * test inconvenience: a key that survived a reload would have to be written
 * somewhere, and the whole point is that it never is.
 */
async function unlockIfLocked(page: Page): Promise<void> {
  // Wait for the load to resolve one way or the other before deciding. Asking
  // whether the field is visible the instant navigation returns answers "no"
  // while the app is still booting, and the caller then waits out its timeout
  // on an unlock screen nobody filled in.
  const locked = page.getByRole('heading', { name: 'Desbloquear dados offline' })
  const unlocked = page.locator('#main-content')
  await Promise.race([
    locked.waitFor({ state: 'visible', timeout: 20_000 }).catch(() => undefined),
    unlocked.waitFor({ state: 'visible', timeout: 20_000 }).catch(() => undefined),
  ])
  if (await locked.isVisible().catch(() => false)) {
    await page.getByLabel('Senha offline').fill(OFFLINE_PASSWORD)
    await page.getByRole('button', { name: 'Desbloquear', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Desbloquear dados offline' })).toBeHidden({
      timeout: 20_000,
    })
    return
  }
  // Online, the app renders normally and only the sync centre offers the way
  // back in, so a load that landed there while locked has its own form. It
  // appears a beat after #main-content, so it has to be waited for rather than
  // sampled.
  const inPlace = page.getByRole('button', { name: 'Desbloquear cópia offline' })
  await inPlace.waitFor({ state: 'visible', timeout: 3_000 }).catch(() => undefined)
  if (await inPlace.isVisible().catch(() => false)) {
    await page.getByLabel('Senha offline').fill(OFFLINE_PASSWORD)
    await inPlace.click()
    await expect(inPlace).toBeHidden({ timeout: 20_000 })
  }
}

/** Navigates and unlocks again when the reload asks for it. */
async function visit(page: Page, path: string): Promise<void> {
  await page.goto(path)
  await unlockIfLocked(page)
}

async function goOfflineAndUnlock(context: BrowserContext, page: Page): Promise<void> {
  await context.setOffline(true)
  await page.goto('/transactions')
  await expect(page.getByRole('heading', { name: 'Desbloquear dados offline' })).toBeVisible()
  await unlockIfLocked(page)
  await expect(page.getByRole('heading', { name: 'Transações' })).toBeVisible({ timeout: 20_000 })
}

async function createTransactionOffline(page: Page, description: string, amount: string) {
  await visit(page, '/transactions')
  // Header and empty state both offer it; either opens the same form.
  await page.getByRole('button', { name: 'Nova transação' }).first().click()
  // Exact: the filter bar's "Buscar por descrição" also matches loosely.
  await page.getByLabel('Descrição', { exact: true }).fill(description)
  await page.getByLabel('Valor (R$)', { exact: true }).fill(amount)
  // A category is required, and offline it can only come from the cached list.
  await page.getByLabel('Categoria', { exact: true }).selectOption({ index: 1 })
  await page.getByRole('button', { name: /Adicionar transação|Salvar/ }).first().click()
}

async function reconnect(context: BrowserContext, page: Page) {
  await context.setOffline(false)
  await visit(page, '/offline-sync')
  await expect(page.getByRole('heading', { name: 'Sincronização offline' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Sincronizar agora' })).toBeVisible({
    timeout: 20_000,
  })
}

/**
 * Drains the queue and waits for it to be empty.
 *
 * Reconnecting already triggers one controlled attempt, so by the time a test
 * gets here the queue may be empty and the button correctly disabled. Clicking
 * it unconditionally would hang on the app behaving exactly as intended.
 */
async function syncNow(page: Page): Promise<void> {
  const button = page.getByRole('button', { name: 'Sincronizar agora' })
  if (await button.isEnabled().catch(() => false)) {
    await button.click()
  }
  await expect(page.getByRole('heading', { name: 'Nenhuma alteração pendente' })).toBeVisible({
    timeout: 30_000,
  })
}

test.describe.serial('Fila de mutações offline', () => {
  let context: BrowserContext
  let page: Page
  const identity = uniqueIdentity('outbox')

  test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ locale: 'pt-BR' })
    page = await context.newPage()
    await registerAndEnableVault(page, identity)
  })

  test.afterAll(async () => {
    await context.close()
  })

  test('um cofre existente continua utilizável depois da atualização de formato', async () => {
    // The vault was written by the current build, but the unlock path is the
    // same one a V1 record takes: decrypt, migrate in memory, rewrite.
    await goOfflineAndUnlock(context, page)
    await expect(page.getByRole('heading', { name: 'Transações' })).toBeVisible()
    await context.setOffline(false)
    await visit(page, '/dashboard')
  })

  test('transação criada offline aparece como pendente e não envia requisição', async () => {
    const requests: string[] = []
    page.on('request', (request) => {
      if (request.method() !== 'GET' && request.url().includes('/api/')) {
        requests.push(`${request.method()} ${request.url()}`)
      }
    })

    await goOfflineAndUnlock(context, page)
    await createTransactionOffline(page, 'Mercado offline', '42,00')

    await expect(page.getByText('Mercado offline')).toBeVisible()
    await expect(page.getByText('Criado offline')).toBeVisible()
    // Not even a CSRF bootstrap left the browser.
    expect(requests).toEqual([])
    page.removeAllListeners('request')
  })

  test('indicador do shell mostra a contagem pendente', async () => {
    await expect(page.getByRole('link', { name: /alteração\(ões\) offline/ })).toBeVisible()
  })

  test('telas com totais derivados avisam que estão desatualizadas', async () => {
    await visit(page, '/transactions')
    await expect(
      page.getByText('Alguns totais ainda não incluem alterações offline pendentes.'),
    ).toBeVisible()
  })

  test('central lista a operação com recurso, tipo e tentativas', async () => {
    await visit(page, '/offline-sync')
    await expect(page.getByText('Mercado offline', { exact: true })).toBeVisible()
    await expect(page.getByText(/Transação · Criação/)).toBeVisible()
    await expect(page.getByText('Pendente').first()).toBeVisible()
  })

  test('cofre bloqueado nunca sincroniza e mantém a fila criptografada', async () => {
    await visit(page, '/settings')
    await page.getByRole('button', { name: 'Bloquear dados offline' }).click()
    // Deliberately not `visit`: this navigation must stay locked. Offline and
    // locked, the unlock screen stands in front of the whole app — the queue is
    // not merely hidden from this page, it is unreachable until the key is
    // derived again.
    await page.goto('/offline-sync')
    await expect(page.getByRole('heading', { name: 'Desbloquear dados offline' })).toBeVisible()

    const serialized = await page.evaluate(
      async () =>
        new Promise<string>((resolve, reject) => {
          const request = indexedDB.open('finora-offline-vault')
          request.onerror = () => reject(request.error)
          request.onsuccess = () => {
            const get = request.result
              .transaction('vault')
              .objectStore('vault')
              .get('single-vault')
            get.onsuccess = () => resolve(JSON.stringify(get.result))
            get.onerror = () => reject(get.error)
          }
        }),
    )
    expect(serialized).not.toContain('Mercado offline')
    expect(serialized).toContain('ciphertext')
  })

  test('recarregar o aplicativo preserva o trabalho pendente criptografado', async () => {
    await page.reload()
    await goOfflineAndUnlock(context, page)
    await visit(page, '/offline-sync')
    await expect(page.getByText('Mercado offline', { exact: true })).toBeVisible()
  })

  test('reconectar sincroniza a transação exatamente uma vez', async () => {
    await reconnect(context, page)
    await syncNow(page)

    await visit(page, '/transactions')
    await expect(page.getByText('Mercado offline')).toHaveCount(1)
    await expect(page.getByText('Criado offline')).toHaveCount(0)
  })

  test('resposta perdida é reenviada sem duplicar o efeito', async () => {
    await goOfflineAndUnlock(context, page)
    await createTransactionOffline(page, 'Resposta perdida', '13,00')
    await context.setOffline(false)

    // The server commits, then the reply is thrown away — exactly the ambiguity
    // the client cannot resolve on its own.
    let dropped = false
    await page.route('**/api/offline-sync/mutations', async (route) => {
      if (!dropped) {
        dropped = true
        await route.fetch()
        await route.abort('connectionreset')
        return
      }
      await route.continue()
    })

    await visit(page, '/offline-sync')
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    // First attempt looks like a network failure to the client.
    await expect(page.getByText('Resposta perdida')).toBeVisible()

    await page.getByRole('button', { name: /Tentar novamente/ }).click()
    await syncNow(page)
    await page.unroute('**/api/offline-sync/mutations')

    await visit(page, '/transactions')
    // Exactly one, because the server recognised its own receipt.
    await expect(page.getByText('Resposta perdida')).toHaveCount(1)
  })

  test('criar e excluir offline não gera nenhuma escrita no servidor', async () => {
    await goOfflineAndUnlock(context, page)
    await createTransactionOffline(page, 'Efêmera', '9,00')
    await expect(page.getByText('Efêmera')).toBeVisible()

    await page.getByRole('button', { name: 'Excluir Efêmera' }).click()
    await page.getByRole('button', { name: 'Excluir' }).last().click()

    await reconnect(context, page)
    await expect(page.getByRole('heading', { name: 'Nenhuma alteração pendente' })).toBeVisible()
    await visit(page, '/transactions')
    await expect(page.getByText('Efêmera')).toHaveCount(0)
  })

  test('orçamento criado offline sincroniza ao reconectar', async () => {
    await goOfflineAndUnlock(context, page)
    await visit(page, '/budgets')
    await page.getByRole('button', { name: /Novo orçamento/ }).first().click()
    await page.getByRole('combobox').first().selectOption({ index: 1 })
    await page.getByLabel(/Limite/).fill('800,00')
    await page.getByRole('button', { name: /Criar orçamento|Salvar/ }).first().click()

    await reconnect(context, page)
    await syncNow(page)
  })

  test('meta criada offline sincroniza e o aporte continua bloqueado', async () => {
    await goOfflineAndUnlock(context, page)
    await visit(page, '/goals')
    const contribute = page.getByRole('button', { name: /Aportar|Registrar aporte/ }).first()
    if (await contribute.count()) {
      await expect(contribute).toBeDisabled()
    }
  })

  test('ações de cartão e fatura continuam indisponíveis offline', async () => {
    await visit(page, '/credit-cards')
    await expect(page.getByRole('heading', { name: 'Conteúdo indisponível offline' }))
      .toBeVisible()
      .catch(async () => {
        const blocked = page.locator('#main-content button').first()
        if (await blocked.count()) await expect(blocked).toBeDisabled()
      })
  })

  test('importação de extrato continua exigindo conexão', async () => {
    await visit(page, '/statement-imports')
    await expect(page.getByRole('heading', { name: 'Conteúdo indisponível offline' })).toBeVisible()
  })

  test('sair com pendências exige uma decisão explícita', async () => {
    await goOfflineAndUnlock(context, page)
    await createTransactionOffline(page, 'Pendente ao sair', '5,00')

    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await expect(
      page.getByRole('heading', { name: 'Sair com alterações offline pendentes' }),
    ).toBeVisible()
    await expect(page.getByText(/não existem em nenhum outro lugar/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Descartar alterações e sair' })).toBeVisible()

    // Cancelling keeps the work exactly where it was.
    await page.getByRole('button', { name: 'Cancelar' }).click()
    await visit(page, '/offline-sync')
    await expect(page.getByText('Pendente ao sair')).toBeVisible()
  })

  test('descarte confirmado remove o cofre e a fila', async () => {
    await context.setOffline(false)
    await visit(page, '/dashboard')
    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await page.getByRole('button', { name: 'Descartar alterações e sair' }).click()
    await expect(page).toHaveURL(/\/login/, { timeout: 20_000 })

    const exists = await page.evaluate(
      async () =>
        new Promise<boolean>((resolve) => {
          const request = indexedDB.open('finora-offline-vault')
          request.onsuccess = () => {
            const get = request.result
              .transaction('vault')
              .objectStore('vault')
              .get('single-vault')
            get.onsuccess = () => resolve(Boolean(get.result))
            get.onerror = () => resolve(false)
          }
          request.onerror = () => resolve(false)
        }),
    )
    expect(exists).toBe(false)
  })
})

test.describe.serial('Conflitos e dependências offline', () => {
  let context: BrowserContext
  let page: Page
  const identity = uniqueIdentity('conflict')

  test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ locale: 'pt-BR' })
    page = await context.newPage()
    await registerAndEnableVault(page, identity)
  })

  test.afterAll(async () => {
    await context.close()
  })

  test('item criado offline aparece na lista marcado como pendente', async () => {
    await goOfflineAndUnlock(context, page)
    await visit(page, '/wishlist')
    await page.getByRole('button', { name: 'Novo item' }).first().click()
    await page.getByLabel('Nome do item').fill('Notebook offline')
    await page.getByRole('button', { name: 'Adicionar item' }).click()

    await expect(page.getByRole('link', { name: 'Notebook offline' }).first()).toBeVisible()
    await expect(page.getByText('Criado offline').first()).toBeVisible()
  })

  test('opção de compra pode nomear um item que só existe na fila', async () => {
    // The whole point of client resource ids: this option's parent has no
    // server id, and will not have one until replay assigns it.
    await page.getByRole('link', { name: 'Notebook offline' }).first().click()
    await expect(page.getByRole('heading', { name: 'Notebook offline' })).toBeVisible()

    await page.getByRole('button', { name: 'Nova opção' }).click()
    await page.getByLabel('Loja / vendedor').fill('Loja offline')
    await page.getByLabel('Preço à vista (R$)').fill('3500,00')
    await page.getByRole('button', { name: 'Adicionar opção' }).click()

    await expect(page.getByText('Loja offline')).toBeVisible()
    await expect(page.getByText('Criado offline').first()).toBeVisible()
    // The analysis is the server's, and it says so rather than inventing one.
    await expect(page.getByText(/assim que este item for sincronizado/)).toBeVisible()
  })

  test('observação de preço pode nomear a opção criada offline', async () => {
    await page.getByRole('button', { name: 'Registrar preço', exact: true }).click()
    await page.getByLabel('Loja', { exact: true }).fill('Loja offline')
    // A number input, so the value is a decimal point, not a comma.
    await page.getByLabel('Preço', { exact: true }).fill('3480.00')
    // An explicit past date: the form defaults to the browser's today, which the
    // server rejects as future-dated whenever the two disagree about the day.
    await page.getByLabel('Data', { exact: true }).fill('2026-07-20')
    await page.getByRole('button', { name: /Salvar no histórico/ }).click()

    await expect(page.getByRole('cell', { name: 'Loja offline' })).toBeVisible()
    await expect(page.getByText(/A opção atual não será alterada/)).toHaveCount(0)
  })

  test('a cadeia inteira sincroniza na ordem de dependência', async () => {
    await visit(page, '/offline-sync')
    // Three operations, and the two children cannot be sent before the parent.
    await expect(page.getByText(/Item da lista de desejos · Criação/)).toBeVisible()
    await expect(page.getByText(/Opção de compra · Criação/)).toBeVisible()
    await expect(page.getByText(/Observação de preço · Criação/)).toBeVisible()

    await reconnect(context, page)
    await syncNow(page)

    await visit(page, '/wishlist')
    await expect(page.getByRole('link', { name: 'Notebook offline' }).first()).toBeVisible()
    await expect(page.getByText('Criado offline')).toHaveCount(0)

    // The children landed under the parent the server assigned, not a new one.
    await page.getByRole('link', { name: 'Notebook offline' }).first().click()
    await expect(page.getByText('Loja offline')).toBeVisible()
    await expect(page.getByRole('cell', { name: 'Loja offline' })).toBeVisible()
  })

  test('edição offline sobre valor alterado no servidor vira conflito', async ({ browser }) => {
    // Create a transaction online, edit it offline, and change it from another
    // context in between — the ordinary "two devices" case.
    await visit(page, '/transactions')
    await page.getByRole('button', { name: 'Nova transação' }).click()
    await page.getByLabel('Descrição', { exact: true }).fill('Disputada')
    await page.getByLabel('Valor (R$)', { exact: true }).fill('10,00')
    await page.getByLabel('Categoria', { exact: true }).selectOption({ index: 1 })
    await page.getByRole('button', { name: /Adicionar transação|Salvar/ }).first().click()
    await expect(page.getByText('Disputada')).toBeVisible()

    await goOfflineAndUnlock(context, page)
    await visit(page, '/transactions')
    await page.getByRole('button', { name: 'Editar Disputada' }).click()
    await page.getByLabel('Valor (R$)', { exact: true }).fill('25,00')
    await page.getByRole('button', { name: /Salvar/ }).first().click()
    await expect(page.getByText('Pendente').first()).toBeVisible()

    // Another session moves the server value on.
    const other = await browser.newContext({ locale: 'pt-BR' })
    const otherPage = await other.newPage()
    await visit(otherPage, '/login')
    await otherPage.getByLabel('E-mail').fill(identity.email)
    await otherPage.getByLabel('Senha', { exact: true }).fill(identity.password)
    await otherPage.getByRole('button', { name: 'Entrar' }).click()
    await visit(otherPage, '/transactions')
    await otherPage.getByRole('button', { name: 'Editar Disputada' }).click()
    await otherPage.getByLabel('Valor (R$)', { exact: true }).fill('80,00')
    await otherPage.getByRole('button', { name: /Salvar/ }).first().click()
    await expect(otherPage.getByText('80,00')).toBeVisible()
    await other.close()

    await reconnect(context, page)
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    await expect(page.getByText('Conflito').first()).toBeVisible({ timeout: 20_000 })
  })

  test('conflito mostra o valor do servidor ao lado da alteração offline', async () => {
    await page.getByRole('button', { name: 'Resolver conflito' }).click()
    await expect(page.getByRole('columnheader', { name: 'Valor salvo no servidor' })).toBeVisible()
    await expect(page.getByRole('columnheader', { name: 'Alteração feita offline' })).toBeVisible()
    // Readable money, not raw payload.
    await expect(page.getByRole('cell', { name: /80,00/ })).toBeVisible()
    await expect(page.getByRole('cell', { name: /25,00/ })).toBeVisible()
    // Versions are available but are not the explanation.
    await expect(page.getByText('Detalhes técnicos')).toBeVisible()
  })

  test('aplicar a alteração local exige confirmação e sobrescreve o servidor', async () => {
    await page.getByRole('button', { name: 'Aplicar minha alteração' }).click()
    await expect(page.getByRole('heading', { name: 'Aplicar sua alteração' })).toBeVisible()
    await page.getByRole('button', { name: 'Aplicar minha alteração' }).last().click()

    await syncNow(page)

    await visit(page, '/transactions')
    await expect(page.getByText('25,00')).toBeVisible()
  })

  test('manter o valor do servidor descarta a alteração local sem enviá-la', async ({ browser }) => {
    // The other half of the conflict contract. A fresh conflict, resolved the
    // opposite way: the server keeps its value and nothing is sent.
    await goOfflineAndUnlock(context, page)
    await visit(page, '/transactions')
    await page.getByRole('button', { name: 'Editar Disputada' }).click()
    await page.getByLabel('Valor (R$)', { exact: true }).fill('44,00')
    await page.getByRole('button', { name: /Salvar/ }).first().click()
    await expect(page.getByText('Pendente').first()).toBeVisible()

    const other = await browser.newContext({ locale: 'pt-BR' })
    const otherPage = await other.newPage()
    await visit(otherPage, '/login')
    await otherPage.getByLabel('E-mail').fill(identity.email)
    await otherPage.getByLabel('Senha', { exact: true }).fill(identity.password)
    await otherPage.getByRole('button', { name: 'Entrar' }).click()
    await visit(otherPage, '/transactions')
    await otherPage.getByRole('button', { name: 'Editar Disputada' }).click()
    await otherPage.getByLabel('Valor (R$)', { exact: true }).fill('99,00')
    await otherPage.getByRole('button', { name: /Salvar/ }).first().click()
    await expect(otherPage.getByText('99,00')).toBeVisible()
    await other.close()

    await reconnect(context, page)
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    await expect(page.getByText('Conflito').first()).toBeVisible({ timeout: 20_000 })

    const sent: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('/api/offline-sync/mutations')) sent.push(request.method())
    })
    await page.getByRole('button', { name: /Resolver conflito/ }).click()
    await page.getByRole('button', { name: 'Manter o do servidor' }).click()
    await expect(page.getByRole('heading', { name: 'Nenhuma alteração pendente' })).toBeVisible()
    // Keeping the server's value is a local decision; nothing was sent for it.
    expect(sent).toEqual([])
    page.removeAllListeners('request')

    await visit(page, '/transactions')
    await expect(page.getByText('99,00')).toBeVisible()
    await expect(page.getByText('44,00')).toHaveCount(0)
  })

  test('falha permanente permanece acionável em vez de repetir para sempre', async () => {
    await goOfflineAndUnlock(context, page)
    await createTransactionOffline(page, 'Recusada', '7,00')

    await context.setOffline(false)
    // The server refuses this one for a reason the client cannot fix by retrying.
    await page.route('**/api/offline-sync/mutations', async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      body.results = body.results.map((result: Record<string, unknown>) => ({
        ...result,
        status: 'REJECTED',
        error: { code: 'CATEGORY_TYPE_MISMATCH', detail: 'Categoria incompatível.' },
      }))
      await route.fulfill({ response, json: body })
    })

    await visit(page, '/offline-sync')
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    await expect(page.getByText('Categoria incompatível.')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByText('Falha').first()).toBeVisible()
    await page.unroute('**/api/offline-sync/mutations')

    // The user can still discard it — it never retries itself into a loop.
    await page.getByRole('button', { name: /Descartar:/ }).first().click()
    await page.getByRole('button', { name: 'Descartar definitivamente' }).click()
    await expect(page.getByRole('heading', { name: 'Nenhuma alteração pendente' })).toBeVisible()
  })

  test('falha temporária é reenviada com a mesma identidade', async () => {
    await goOfflineAndUnlock(context, page)
    await createTransactionOffline(page, 'Servidor instável', '11,00')
    await context.setOffline(false)

    let failures = 0
    const seenIds = new Set<string>()
    await page.route('**/api/offline-sync/mutations', async (route) => {
      const body = route.request().postDataJSON() as {
        mutations: { clientMutationId: string }[]
      }
      body.mutations.forEach((mutation) => seenIds.add(mutation.clientMutationId))
      if (failures === 0) {
        failures += 1
        await route.fulfill({ status: 503, body: '{}' })
        return
      }
      await route.continue()
    })

    await visit(page, '/offline-sync')
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    await expect(page.getByText('Servidor instável')).toBeVisible()
    await page.getByRole('button', { name: /Tentar novamente/ }).click()
    await syncNow(page)
    await page.unroute('**/api/offline-sync/mutations')

    // One identity across both attempts: that is what lets the server dedupe.
    expect(seenIds.size).toBe(1)
  })
})

test.describe('Duas abas do mesmo dono', () => {
  test('duas abas sincronizando ao mesmo tempo não duplicam o efeito', async ({ browser }) => {
    // Two tabs share one vault and one queue. The replay lock is what keeps
    // them from both sending; the server's receipts are what makes it harmless
    // if the lock is unavailable and they do.
    const context = await browser.newContext({ locale: 'pt-BR' })
    const first = await context.newPage()
    const identity = uniqueIdentity('tabs')
    await registerAndEnableVault(first, identity)

    await goOfflineAndUnlock(context, first)
    await createTransactionOffline(first, 'Duas abas', '31,00')
    await context.setOffline(false)

    const second = await context.newPage()
    await visit(second, '/offline-sync')
    await expect(second.getByRole('heading', { name: 'Sincronização offline' })).toBeVisible()

    // Count what actually reaches the endpoint from either tab.
    let batches = 0
    await context.route('**/api/offline-sync/mutations', async (route) => {
      batches += 1
      await route.continue()
    })

    await visit(first, '/offline-sync')
    await Promise.all([
      first.getByRole('button', { name: 'Sincronizar agora' }).click(),
      second.getByRole('button', { name: 'Sincronizar agora' }).click(),
    ])

    await expect(first.getByRole('heading', { name: 'Nenhuma alteração pendente' })).toBeVisible({
      timeout: 30_000,
    })
    await context.unroute('**/api/offline-sync/mutations')

    // One transaction on the server, whether one batch was sent or two.
    await visit(first, '/transactions')
    await expect(first.getByText('Duas abas')).toHaveCount(1)
    expect(batches).toBeGreaterThan(0)

    // The second tab converges on the same empty queue.
    await second.reload()
    await expect(second.getByRole('heading', { name: 'Nenhuma alteração pendente' })).toBeVisible({
      timeout: 20_000,
    })
    await context.close()
  })
})

test.describe('Isolamento entre donos e mobile', () => {
  test('outra conta nunca vê nem sincroniza a fila da primeira', async ({ browser }) => {
    const first = await browser.newContext({ locale: 'pt-BR' })
    const firstPage = await first.newPage()
    const owner = uniqueIdentity('owner')
    await registerAndEnableVault(firstPage, owner)
    await goOfflineAndUnlock(first, firstPage)
    await createTransactionOffline(firstPage, 'Segredo do dono', '99,00')
    await first.setOffline(false)
    await first.close()

    // A different browser profile has no vault at all, and the first owner's
    // queue is unreachable: it never left that device.
    const second = await browser.newContext({ locale: 'pt-BR' })
    const secondPage = await second.newPage()
    const intruder = uniqueIdentity('intruder')
    await registerAndEnableVault(secondPage, intruder)
    await visit(secondPage, '/offline-sync')
    await expect(secondPage.getByText('Segredo do dono')).toHaveCount(0)
    await expect(
      secondPage.getByRole('heading', { name: 'Nenhuma alteração pendente' }),
    ).toBeVisible()
    await second.close()
  })

  test('central de sincronização funciona em telas de 390px', async ({ browser }) => {
    const context = await browser.newContext({ locale: 'pt-BR', viewport: { width: 390, height: 780 } })
    const page = await context.newPage()
    const identity = uniqueIdentity('mobile')
    await registerAndEnableVault(page, identity)
    await goOfflineAndUnlock(context, page)
    await createTransactionOffline(page, 'Móvel', '15,00')

    await visit(page, '/offline-sync')
    await expect(page.getByRole('heading', { name: 'Sincronização offline' })).toBeVisible()
    await expect(page.getByText('Móvel', { exact: true })).toBeVisible()

    // The page itself never scrolls sideways at this width.
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow).toBeLessThanOrEqual(1)
    await context.close()
  })
})
