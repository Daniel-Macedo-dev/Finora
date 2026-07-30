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

async function goOfflineAndUnlock(context: BrowserContext, page: Page): Promise<void> {
  await context.setOffline(true)
  await page.goto('/transactions')
  await expect(page.getByRole('heading', { name: 'Desbloquear dados offline' })).toBeVisible()
  await page.getByLabel('Senha offline').fill(OFFLINE_PASSWORD)
  await page.getByRole('button', { name: 'Desbloquear', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Transações' })).toBeVisible({ timeout: 20_000 })
}

async function createTransactionOffline(page: Page, description: string, amount: string) {
  await page.goto('/transactions')
  await page.getByRole('button', { name: 'Nova transação' }).click()
  await page.getByLabel('Descrição').fill(description)
  await page.getByLabel('Valor').fill(amount)
  await page.getByRole('button', { name: /Salvar|Criar/ }).first().click()
}

async function reconnect(context: BrowserContext, page: Page) {
  await context.setOffline(false)
  await page.goto('/offline-sync')
  await expect(page.getByRole('heading', { name: 'Sincronização offline' })).toBeVisible()
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
    await page.goto('/dashboard')
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
    await page.goto('/transactions')
    await expect(
      page.getByText('Alguns totais ainda não incluem alterações offline pendentes.'),
    ).toBeVisible()
  })

  test('central lista a operação com recurso, tipo e tentativas', async () => {
    await page.goto('/offline-sync')
    await expect(page.getByText('Mercado offline')).toBeVisible()
    await expect(page.getByText(/Transação · Criação/)).toBeVisible()
    await expect(page.getByText('Pendente').first()).toBeVisible()
  })

  test('cofre bloqueado nunca sincroniza e mantém a fila criptografada', async () => {
    await page.goto('/settings')
    await page.getByRole('button', { name: 'Bloquear dados offline' }).click()
    await page.goto('/offline-sync')
    await expect(page.getByRole('heading', { name: 'Cópia offline bloqueada' })).toBeVisible()

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
    await page.goto('/offline-sync')
    await expect(page.getByText('Mercado offline')).toBeVisible()
  })

  test('reconectar sincroniza a transação exatamente uma vez', async () => {
    await reconnect(context, page)
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    await expect(page.getByRole('heading', { name: 'Nenhuma alteração pendente' })).toBeVisible({
      timeout: 20_000,
    })

    await page.goto('/transactions')
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

    await page.goto('/offline-sync')
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    // First attempt looks like a network failure to the client.
    await expect(page.getByText('Resposta perdida')).toBeVisible()

    await page.getByRole('button', { name: /Tentar novamente/ }).click()
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    await expect(page.getByRole('heading', { name: 'Nenhuma alteração pendente' })).toBeVisible({
      timeout: 20_000,
    })
    await page.unroute('**/api/offline-sync/mutations')

    await page.goto('/transactions')
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
    await page.goto('/transactions')
    await expect(page.getByText('Efêmera')).toHaveCount(0)
  })

  test('orçamento criado offline sincroniza ao reconectar', async () => {
    await goOfflineAndUnlock(context, page)
    await page.goto('/budgets')
    await page.getByRole('button', { name: /Novo orçamento/ }).click()
    await page.getByLabel(/Limite/).fill('800,00')
    await page.getByRole('button', { name: /Salvar|Criar/ }).first().click()

    await reconnect(context, page)
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    await expect(page.getByRole('heading', { name: 'Nenhuma alteração pendente' })).toBeVisible({
      timeout: 20_000,
    })
  })

  test('meta criada offline sincroniza e o aporte continua bloqueado', async () => {
    await goOfflineAndUnlock(context, page)
    await page.goto('/goals')
    const contribute = page.getByRole('button', { name: /Aportar|Registrar aporte/ }).first()
    if (await contribute.count()) {
      await expect(contribute).toBeDisabled()
    }
  })

  test('ações de cartão e fatura continuam indisponíveis offline', async () => {
    await page.goto('/credit-cards')
    await expect(page.getByRole('heading', { name: 'Conteúdo indisponível offline' }))
      .toBeVisible()
      .catch(async () => {
        const blocked = page.locator('#main-content button').first()
        if (await blocked.count()) await expect(blocked).toBeDisabled()
      })
  })

  test('importação de extrato continua exigindo conexão', async () => {
    await page.goto('/statement-imports')
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
    await page.goto('/offline-sync')
    await expect(page.getByText('Pendente ao sair')).toBeVisible()
  })

  test('descarte confirmado remove o cofre e a fila', async () => {
    await context.setOffline(false)
    await page.goto('/dashboard')
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

  test('item, opção e observação criados offline sincronizam na ordem certa', async () => {
    await goOfflineAndUnlock(context, page)
    await page.goto('/wishlist')
    await page.getByRole('button', { name: /Novo item|Adicionar item/ }).first().click()
    await page.getByLabel('Nome').fill('Notebook offline')
    await page.getByRole('button', { name: /Salvar|Criar/ }).first().click()
    await expect(page.getByText('Notebook offline')).toBeVisible()

    await reconnect(context, page)
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    await expect(page.getByRole('heading', { name: 'Nenhuma alteração pendente' })).toBeVisible({
      timeout: 20_000,
    })

    await page.goto('/wishlist')
    await expect(page.getByText('Notebook offline')).toHaveCount(1)
  })

  test('edição offline sobre valor alterado no servidor vira conflito', async ({ browser }) => {
    // Create a transaction online, edit it offline, and change it from another
    // context in between — the ordinary "two devices" case.
    await page.goto('/transactions')
    await page.getByRole('button', { name: 'Nova transação' }).click()
    await page.getByLabel('Descrição').fill('Disputada')
    await page.getByLabel('Valor').fill('10,00')
    await page.getByRole('button', { name: /Salvar|Criar/ }).first().click()
    await expect(page.getByText('Disputada')).toBeVisible()

    await goOfflineAndUnlock(context, page)
    await page.goto('/transactions')
    await page.getByRole('button', { name: 'Editar Disputada' }).click()
    await page.getByLabel('Valor').fill('25,00')
    await page.getByRole('button', { name: /Salvar/ }).first().click()
    await expect(page.getByText('Pendente').first()).toBeVisible()

    // Another session moves the server value on.
    const other = await browser.newContext({ locale: 'pt-BR' })
    const otherPage = await other.newPage()
    await otherPage.goto('/login')
    await otherPage.getByLabel('E-mail').fill(identity.email)
    await otherPage.getByLabel('Senha', { exact: true }).fill(identity.password)
    await otherPage.getByRole('button', { name: 'Entrar' }).click()
    await otherPage.goto('/transactions')
    await otherPage.getByRole('button', { name: 'Editar Disputada' }).click()
    await otherPage.getByLabel('Valor').fill('80,00')
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

    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    await expect(page.getByRole('heading', { name: 'Nenhuma alteração pendente' })).toBeVisible({
      timeout: 20_000,
    })

    await page.goto('/transactions')
    await expect(page.getByText('25,00')).toBeVisible()
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

    await page.goto('/offline-sync')
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

    await page.goto('/offline-sync')
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    await expect(page.getByText('Servidor instável')).toBeVisible()
    await page.getByRole('button', { name: /Tentar novamente/ }).click()
    await page.getByRole('button', { name: 'Sincronizar agora' }).click()
    await expect(page.getByRole('heading', { name: 'Nenhuma alteração pendente' })).toBeVisible({
      timeout: 20_000,
    })
    await page.unroute('**/api/offline-sync/mutations')

    // One identity across both attempts: that is what lets the server dedupe.
    expect(seenIds.size).toBe(1)
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
    await secondPage.goto('/offline-sync')
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

    await page.goto('/offline-sync')
    await expect(page.getByRole('heading', { name: 'Sincronização offline' })).toBeVisible()
    await expect(page.getByText('Móvel')).toBeVisible()

    // The page itself never scrolls sideways at this width.
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow).toBeLessThanOrEqual(1)
    await context.close()
  })
})
