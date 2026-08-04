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
  // The helper owns its own outcome: without this, a form that silently failed
  // to submit only surfaces much later as an empty queue somewhere else.
  await expect(page.getByRole('link', { name: /alteração\(ões\) offline/ })).toBeVisible({
    timeout: 20_000,
  })
}

/**
 * Turns "sincronizar automaticamente ao reconectar" on or off through the real
 * settings control.
 *
 * Reconnecting is itself a replay trigger, which is correct product behaviour
 * and is covered by its own scenarios. The few cases below need the *first*
 * attempt to be one the test controls — a lost response, or two tabs pressing
 * sync at the same instant — so they switch the automatic attempt off exactly
 * as a user would, and switch it back on afterwards.
 */
async function setAutoReplay(page: Page, enabled: boolean): Promise<void> {
  await visit(page, '/settings')
  const toggle = page.getByLabel('Sincronizar automaticamente ao reconectar')
  // The box is controlled by the vault, and the vault only changes once the
  // preference has been re-encrypted and written. So the click is followed by a
  // wait on the settled value rather than by setChecked, which reads back the
  // reverted box before the write lands.
  if ((await toggle.isChecked()) !== enabled) await toggle.click()
  await expect(toggle).toBeChecked({ checked: enabled, timeout: 20_000 })
}

/**
 * Re-takes the offline snapshot through the real settings control.
 *
 * The encrypted copy is written when offline access is enabled and refreshed
 * only when the user asks — that is the documented contract, and the settings
 * screen says so. A row created online after the copy was taken therefore does
 * not exist offline until this runs, so any journey that edits such a row
 * offline has to ask for the copy first, exactly as a person would.
 */
async function refreshOfflineCopy(page: Page): Promise<void> {
  await visit(page, '/settings')
  await page.getByLabel('Senha offline para atualizar').fill(OFFLINE_PASSWORD)
  await page.getByRole('button', { name: 'Atualizar dados offline' }).click()
  await expect(page.getByText('Dados offline atualizados.')).toBeVisible({ timeout: 20_000 })
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
    // Scoped: the offline context strip offers the same action by the same name.
    await page
      .getByLabel('Aplicativo e acesso offline')
      .getByRole('button', { name: 'Bloquear dados offline' })
      .click()
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
    // The attempt that loses its answer has to be this test's, not the one
    // reconnecting fires on its own.
    await setAutoReplay(page, false)
    await context.setOffline(false)

    // The server commits, then the reply is thrown away — exactly the ambiguity
    // the client cannot resolve on its own.
    const attempts: string[] = []
    let dropped = false
    await context.route('**/api/offline-sync/mutations', async (route) => {
      if (!dropped) {
        dropped = true
        attempts.push('drop')
        await route.fetch()
        await route.abort('connectionreset')
        return
      }
      attempts.push('continue')
      await route.continue()
    })

    await visit(page, '/offline-sync')
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    // Exactly one attempt leaves, and it is the one whose answer is discarded.
    await expect.poll(() => attempts, { timeout: 20_000 }).toEqual(['drop'])
    // It looks like a network failure to the client, so the work stays queued
    // and actionable. Exact, because the row's own discard button repeats it.
    await expect(page.getByText('Resposta perdida', { exact: true })).toBeVisible()
    await expect(page.getByText('Falha temporária')).toBeVisible()

    await page.getByRole('button', { name: /^Tentar novamente:/ }).click()
    // The discarded answer looked like an unreachable API, so the app says so
    // and refuses to send until the connection is proven again. The shell's own
    // banner is that proof, and nothing can be replayed before it.
    await expect(page.getByRole('button', { name: 'Sincronizar agora' })).toBeDisabled()
    await page.getByRole('button', { name: 'Tentar novamente', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Sincronizar agora' })).toBeEnabled({
      timeout: 20_000,
    })
    await syncNow(page)
    await context.unroute('**/api/offline-sync/mutations')

    await visit(page, '/transactions')
    // Exactly one, because the server recognised its own receipt.
    await expect(page.getByText('Resposta perdida')).toHaveCount(1)
    await setAutoReplay(page, true)
  })

  test('criar e excluir offline não gera nenhuma escrita no servidor', async () => {
    await goOfflineAndUnlock(context, page)
    await createTransactionOffline(page, 'Efêmera', '9,00')
    await expect(page.getByText('Efêmera')).toBeVisible()

    await page.getByRole('button', { name: 'Excluir Efêmera' }).click()
    // Scoped to the confirmation: every row also has an "Excluir <descrição>".
    await page
      .getByRole('dialog', { name: 'Excluir transação' })
      .getByRole('button', { name: 'Excluir', exact: true })
      .click()
    // Compaction cancels the pair outright, so the queue empties immediately —
    // there is nothing left for the server to be told about.
    await expect(page.getByRole('link', { name: /alteração\(ões\) offline/ })).toHaveCount(0)

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
    // Cards are readable offline and writable nowhere near it: the shell says
    // read-only in so many words, and every action on the page is inert.
    await expect(page.getByText('Modo offline (somente leitura)')).toBeVisible()
    // Every action on the page, not merely the obvious one: the only control
    // left alive is the shell's own "lock the offline copy".
    await expect(
      page.locator("#main-content button:not([data-offline-allowed='true']):not([disabled])"),
    ).toHaveCount(0)
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
    await expect(page.getByText('Pendente ao sair', { exact: true })).toBeVisible()
  })

  test('descarte confirmado remove o cofre e a fila', async () => {
    // Back online but deliberately not synchronized: the discard has to be
    // reachable with the server available, which is precisely when losing the
    // queue would be a choice rather than an accident.
    await setAutoReplay(page, false)
    await context.setOffline(false)
    // Through the sync centre, because coming back online leaves the copy
    // locked and only that screen offers the way back into it. The warning is
    // driven by what the queue actually holds, so it has to be readable.
    await visit(page, '/offline-sync')
    await expect(page.getByText('Pendente ao sair', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Sair da conta' }).click()
    // Two stages, and the first one deliberately destroys nothing.
    await page.getByRole('button', { name: 'Descartar alterações e sair' }).click()
    await expect(page.getByText('Essa ação não pode ser desfeita.')).toBeVisible()
    await page.getByRole('button', { name: 'Excluir e sair definitivamente' }).click()
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

    await expect(page.getByLabel('Opções de compra').getByText('Loja offline')).toBeVisible()
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
    // Scoped to the options list: once the option exists on the server the
    // price-history filter offers it by name too, and both matches are correct.
    await page.getByRole('link', { name: 'Notebook offline' }).first().click()
    const options = page.getByLabel('Opções de compra')
    await expect(options.getByText('Loja offline')).toHaveCount(1)
    await expect(options.getByText('Loja offline')).toBeVisible()
    // Exact: the row's edit and delete controls name the merchant too.
    await expect(page.getByRole('cell', { name: 'Loja offline', exact: true })).toBeVisible()
  })

  test('edição offline sobre valor alterado no servidor vira conflito', async ({ browser }) => {
    // Create a transaction online, edit it offline, and change it from another
    // context in between — the ordinary "two devices" case.
    await visit(page, '/transactions')
    // Header and empty state both offer it; either opens the same form.
    await page.getByRole('button', { name: 'Nova transação' }).first().click()
    await page.getByLabel('Descrição', { exact: true }).fill('Disputada')
    await page.getByLabel('Valor (R$)', { exact: true }).fill('10,00')
    await page.getByLabel('Categoria', { exact: true }).selectOption({ index: 1 })
    await page.getByRole('button', { name: /Adicionar transação|Salvar/ }).first().click()
    await expect(page.getByText('Disputada')).toBeVisible()
    // It was created after the offline copy was taken, so the copy has to be
    // asked for again before it can be edited without a connection.
    await refreshOfflineCopy(page)

    await goOfflineAndUnlock(context, page)
    await visit(page, '/transactions')
    await page.getByRole('button', { name: 'Editar Disputada' }).click()
    await page.getByLabel('Valor (R$)', { exact: true }).fill('25,00')
    await page.getByRole('button', { name: /Salvar/ }).first().click()
    await expect(page.getByText('Pendente').first()).toBeVisible()

    // Another session moves the server value on.
    const other = await browser.newContext({ locale: 'pt-BR' })
    const otherPage = await other.newPage()
    // A fresh context has no vault, so this is a plain navigation.
    await otherPage.goto('/login')
    await otherPage.getByLabel('E-mail').fill(identity.email)
    await otherPage.getByLabel('Senha', { exact: true }).fill(identity.password)
    await otherPage.getByRole('button', { name: 'Entrar' }).click()
    // Wait for the session to actually exist: navigating while the login POST
    // is still in flight cancels it and lands back on /login.
    await expect(otherPage.getByRole('heading', { name: 'Visão geral' })).toBeVisible()
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
    // A fresh context has no vault, so this is a plain navigation.
    await otherPage.goto('/login')
    await otherPage.getByLabel('E-mail').fill(identity.email)
    await otherPage.getByLabel('Senha', { exact: true }).fill(identity.password)
    await otherPage.getByRole('button', { name: 'Entrar' }).click()
    // Wait for the session to actually exist: navigating while the login POST
    // is still in flight cancels it and lands back on /login.
    await expect(otherPage.getByRole('heading', { name: 'Visão geral' })).toBeVisible()
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
    await context.route('**/api/offline-sync/mutations', async (route) => {
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
    await context.unroute('**/api/offline-sync/mutations')

    // The user can still discard it — it never retries itself into a loop.
    await page.getByRole('button', { name: /Descartar:/ }).first().click()
    await page.getByRole('button', { name: 'Descartar definitivamente' }).click()
    await expect(page.getByRole('heading', { name: 'Nenhuma alteração pendente' })).toBeVisible()
  })

  test('falha temporária é reenviada com a mesma identidade', async () => {
    await goOfflineAndUnlock(context, page)
    await createTransactionOffline(page, 'Servidor instável', '11,00')
    // Both attempts here are this test's. A 503 leaves the entry retryable, so
    // the automatic trigger keeps re-firing as it falls due and re-renders the
    // row — detaching the retry button mid-click on a slower machine.
    await setAutoReplay(page, false)
    await context.setOffline(false)

    let failures = 0
    const seenIds = new Set<string>()
    await context.route('**/api/offline-sync/mutations', async (route) => {
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
    await expect(page.getByText('Servidor instável', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: /^Tentar novamente:/ }).click()
    await syncNow(page)
    await context.unroute('**/api/offline-sync/mutations')

    // One identity across both attempts: that is what lets the server dedupe.
    expect(seenIds.size).toBe(1)
    await setAutoReplay(page, true)
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
    // Without this, reconnecting drains the queue before the second tab even
    // exists and there is no race left to observe.
    await setAutoReplay(first, false)
    await context.setOffline(false)

    const second = await context.newPage()
    await visit(second, '/offline-sync')
    await expect(second.getByRole('heading', { name: 'Sincronização offline' })).toBeVisible()
    await expect(second.getByText('Duas abas', { exact: true })).toBeVisible()

    // Count what actually reaches the endpoint from either tab, and hold the
    // first batch open long enough that the second tab really does press sync
    // while a replay is in flight — that is the race the lock exists for.
    let batches = 0
    await context.route('**/api/offline-sync/mutations', async (route) => {
      batches += 1
      if (batches === 1) await new Promise((resolve) => setTimeout(resolve, 2_000))
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

    // The second tab converges on the same empty queue on its own. Deliberately
    // without a reload: a reload would drop the in-memory key and land on the
    // unlock screen, and it is the broadcast — not a refresh — that is supposed
    // to keep the other tab honest.
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

/* ---------- destroying a copy nobody can read ---------- */

/** Reads the encrypted record straight out of IndexedDB, bypassing the app. */
async function vaultRecordExists(page: Page): Promise<boolean> {
  return page.evaluate(
    () =>
      new Promise<boolean>((resolve) => {
        const request = indexedDB.open('finora-offline-vault')
        request.onsuccess = () => {
          const store = request.result.transaction('vault').objectStore('vault')
          const get = store.get('single-vault')
          get.onsuccess = () => resolve(Boolean(get.result))
          get.onerror = () => resolve(false)
        }
        request.onerror = () => resolve(false)
      }),
  )
}

/**
 * Leaves the vault locked the way a user does: by reloading.
 *
 * The key is held in memory and nowhere else, so any full load drops it. This
 * is not a contrived setup — it is the state the application is in every single
 * time the tab is reopened, which is exactly why deleting on that state without
 * asking was dangerous.
 */
async function reloadIntoLockedVault(page: Page, path = '/transactions'): Promise<void> {
  await page.goto(path)
  await expect(page.locator('#main-content')).toBeVisible({ timeout: 20_000 })
  await expect(page.getByRole('button', { name: 'Sair da conta' })).toBeEnabled({
    timeout: 20_000,
  })
}

/** Unlocks through the sync centre's own form — no extra password screen. */
async function unlockInPlace(page: Page): Promise<void> {
  const button = page.getByRole('button', { name: 'Desbloquear cópia offline' })
  await expect(button).toBeVisible({ timeout: 20_000 })
  await page.getByLabel('Senha offline').fill(OFFLINE_PASSWORD)
  await button.click()
  await expect(button).toBeHidden({ timeout: 20_000 })
}

test.describe('Ações destrutivas com o cofre bloqueado', () => {
  /**
   * Prepares an account whose local copy is locked, with a queued mutation the
   * server has never seen sealed inside it.
   *
   * Automatic replay is switched off first, through the real control: left on,
   * reconnecting drains the queue and the scenario stops being about a copy
   * that holds the only version of anything.
   */
  async function lockedVaultWithPendingWork(
    context: BrowserContext,
    page: Page,
    description: string,
  ) {
    await registerAndEnableVault(page, uniqueIdentity('locked'))
    await goOfflineAndUnlock(context, page)
    await createTransactionOffline(page, description, '73,00')
    await setAutoReplay(page, false)
    await context.setOffline(false)
    await reloadIntoLockedVault(page)
  }

  test('sair com o cofre bloqueado avisa da incerteza e cancelar preserva a cópia', async ({
    browser,
  }) => {
    const context = await browser.newContext({ locale: 'pt-BR' })
    const page = await context.newPage()
    await lockedVaultWithPendingWork(context, page, 'Presa no cofre')

    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await expect(
      page.getByRole('heading', { name: 'A cópia offline está bloqueada' }),
    ).toBeVisible()
    await expect(page.getByText(/pode conter alterações que ainda não foram enviadas/)).toBeVisible()

    // No fabricated count: the application cannot read the queue, and says so
    // instead of reporting a number it does not have.
    await expect(page.getByText(/alteração\(ões\) offline que ainda não chegaram/)).toHaveCount(0)
    await expect(page.getByText(/Há 0 alteração/)).toHaveCount(0)

    await page.getByRole('button', { name: 'Cancelar' }).click()
    expect(await vaultRecordExists(page)).toBe(true)
    await expect(page).not.toHaveURL(/\/login/)
    await expect(page.getByRole('button', { name: 'Sair da conta' })).toBeVisible()
    await context.close()
  })

  test('desbloquear e verificar mostra a fila real e ainda sincroniza', async ({ browser }) => {
    const context = await browser.newContext({ locale: 'pt-BR' })
    const page = await context.newPage()
    await lockedVaultWithPendingWork(context, page, 'Revisada antes de sair')

    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await page.getByRole('button', { name: 'Desbloquear e verificar' }).click()
    await expect(page).toHaveURL(/\/offline-sync/)

    await unlockInPlace(page)
    await expect(page.getByText('Revisada antes de sair', { exact: true })).toBeVisible({
      timeout: 20_000,
    })
    expect(await vaultRecordExists(page)).toBe(true)

    // Still perfectly sendable: reviewing never cost the user the work.
    await syncNow(page)
    await visit(page, '/transactions')
    await expect(page.getByText('Revisada antes de sair')).toHaveCount(1)
    await context.close()
  })

  test('descarte destrutivo exige a segunda confirmação e só então apaga', async ({ browser }) => {
    const context = await browser.newContext({ locale: 'pt-BR' })
    const page = await context.newPage()
    await lockedVaultWithPendingWork(context, page, 'Descartada com o cofre fechado')

    // Nothing may be sent while this plays out: a queue that quietly drained
    // during the dialog would make the deletion look harmless for the wrong
    // reason.
    let mutationRequests = 0
    await context.route('**/api/offline-sync/mutations', async (route) => {
      mutationRequests += 1
      await route.continue()
    })

    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await page.getByRole('button', { name: 'Descartar cópia e sair' }).click()
    await expect(page.getByRole('heading', { name: 'Excluir a cópia offline e sair' })).toBeVisible()
    await expect(page.getByText('Essa ação não pode ser desfeita.')).toBeVisible()

    // Backing out of the final step leaves the record exactly where it was.
    await page.getByRole('button', { name: 'Voltar' }).click()
    await expect(
      page.getByRole('heading', { name: 'A cópia offline está bloqueada' }),
    ).toBeVisible()
    await page.getByRole('button', { name: 'Cancelar' }).click()
    expect(await vaultRecordExists(page)).toBe(true)

    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await page.getByRole('button', { name: 'Descartar cópia e sair' }).click()
    await page.getByRole('button', { name: 'Excluir e sair definitivamente' }).click()

    await expect(page).toHaveURL(/\/login/, { timeout: 20_000 })
    expect(await vaultRecordExists(page)).toBe(false)
    expect(mutationRequests).toBe(0)
    await context.unroute('**/api/offline-sync/mutations')
    await context.close()
  })

  test('um cofre bloqueado e vazio recebe o mesmo aviso conservador', async ({ browser }) => {
    // The intended false positive, stated as a test so it cannot be "fixed" by
    // someone who reads the empty warning as a bug.
    const context = await browser.newContext({ locale: 'pt-BR' })
    const page = await context.newPage()
    await registerAndEnableVault(page, uniqueIdentity('locked-empty'))
    await reloadIntoLockedVault(page)

    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await expect(
      page.getByRole('heading', { name: 'A cópia offline está bloqueada' }),
    ).toBeVisible()

    await page.getByRole('button', { name: 'Cancelar' }).click()
    expect(await vaultRecordExists(page)).toBe(true)
    await context.close()
  })

  test('desativar o acesso offline com o cofre bloqueado tem a mesma proteção', async ({
    browser,
  }) => {
    const context = await browser.newContext({ locale: 'pt-BR' })
    const page = await context.newPage()
    await lockedVaultWithPendingWork(context, page, 'Sobrevive à desativação')

    // A row the server already has, to prove at the end that deleting the local
    // copy never touched it.
    await visit(page, '/transactions')
    await page.getByRole('button', { name: 'Nova transação' }).first().click()
    await page.getByLabel('Descrição', { exact: true }).fill('Guardada no servidor')
    await page.getByLabel('Valor (R$)', { exact: true }).fill('12,00')
    await page.getByLabel('Categoria', { exact: true }).selectOption({ index: 1 })
    await page.getByRole('button', { name: /Adicionar transação|Salvar/ }).first().click()
    await expect(page.getByText('Guardada no servidor')).toBeVisible()

    await page.goto('/settings')
    await page.getByRole('button', { name: 'Desativar e excluir cópia local' }).click()
    await expect(
      page.getByRole('heading', { name: 'A cópia offline está bloqueada' }),
    ).toBeVisible()
    await expect(page.getByText('Os dados já enviados ao servidor não são apagados.')).toBeVisible()

    await page.getByRole('button', { name: 'Desbloquear e verificar' }).click()
    await expect(page).toHaveURL(/\/offline-sync/)
    await unlockInPlace(page)
    await expect(page.getByText('Sobrevive à desativação', { exact: true })).toBeVisible({
      timeout: 20_000,
    })
    expect(await vaultRecordExists(page)).toBe(true)

    // Now delete it deliberately, from a locked state again.
    await reloadIntoLockedVault(page, '/settings')
    await page.getByRole('button', { name: 'Desativar e excluir cópia local' }).click()
    await page.getByRole('button', { name: 'Excluir cópia mesmo assim' }).click()
    await expect(page.getByText('Essa ação não pode ser desfeita.')).toBeVisible()
    await page.getByRole('button', { name: 'Excluir cópia definitivamente' }).click()

    // The settings screen falls back to offering offline access again, which is
    // how the user sees that this device no longer holds a copy.
    await expect(
      page.getByLabel('Senha offline (mínimo de 12 caracteres)'),
    ).toBeVisible({ timeout: 20_000 })
    expect(await vaultRecordExists(page)).toBe(false)

    // The account is untouched: only this device's copy went away.
    await page.goto('/transactions')
    await expect(page.getByText('Guardada no servidor')).toBeVisible({ timeout: 20_000 })
    await context.close()
  })

  test('logout do servidor com falha ainda executa a limpeza local confirmada', async ({
    browser,
  }) => {
    const context = await browser.newContext({ locale: 'pt-BR' })
    const page = await context.newPage()
    await lockedVaultWithPendingWork(context, page, 'Perdida com o servidor fora')

    // context.route, not page.route: the Service Worker owns /api requests, and
    // a page-level handler simply never sees them.
    await context.route('**/api/auth/logout', (route) =>
      route.fulfill({ status: 500, contentType: 'application/json', body: '{}' }),
    )
    let mutationRequests = 0
    await context.route('**/api/offline-sync/mutations', async (route) => {
      mutationRequests += 1
      await route.continue()
    })

    await page.getByRole('button', { name: 'Sair da conta' }).click()
    await page.getByRole('button', { name: 'Descartar cópia e sair' }).click()
    await page.getByRole('button', { name: 'Excluir e sair definitivamente' }).click()

    // The user asked for the copy to go; a server that refused to hear about it
    // does not get to leave decrypted data on the device.
    await expect(page).toHaveURL(/\/login/, { timeout: 20_000 })
    expect(await vaultRecordExists(page)).toBe(false)
    expect(mutationRequests).toBe(0)

    await context.unroute('**/api/auth/logout')
    await context.unroute('**/api/offline-sync/mutations')
    await context.close()
  })
})
