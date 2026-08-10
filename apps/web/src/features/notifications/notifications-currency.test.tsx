import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import NotificationsPage from './NotificationsPage'
import { deliverBrowserClaims } from './browserNotifications'
import type { CurrencyCode } from '../../lib/money'

/**
 * A notification amount is only meaningful together with its currency.
 *
 * <p>Until now every one of them was printed as reais, because the formatter
 * only knew how. A dollar subscription and a yen subscription both arrived
 * wearing `R$` — not an ugly symbol, a wrong number. These tests hold the
 * inbox and the browser body to the currency the server actually sent, and
 * hold the private delivery to saying nothing at all.
 */

const baseNotification = {
  id: 7,
  sourceKey: 'COMMITMENT:2:2026-07-21',
  type: 'RECURRING_OVERDUE',
  severity: 'CRITICAL' as const,
  eventDate: '2026-07-21',
  title: 'Assinatura vencida',
  resourceType: 'COMMITMENT',
  resourceId: 2,
  route: '/commitments',
  revision: 1,
  unread: true,
  dismissed: false,
  snoozed: false,
  snoozedUntil: null,
  firstSeenAt: '2026-07-21T12:00:00Z',
  lastSeenAt: '2026-07-21T12:00:00Z',
  resolvedAt: null,
}

function response(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

function renderWithClient(ui: ReactNode, handler: (url: string) => Response) {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => handler(String(input))))
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Renders the inbox holding exactly one notification of the given money. */
async function renderInbox(amount: number | null, currency: CurrencyCode) {
  const item = { ...baseNotification, amount, currency }
  renderWithClient(<NotificationsPage />, (url) => {
    if (url.includes('unread-count')) return response({ count: 1 })
    if (url.includes('browser-claims')) return response([])
    return response({ content: [item], page: 0, size: 20, totalElements: 1, totalPages: 1 })
  })
  const heading = await screen.findByText('Assinatura vencida')
  // The date and the amount share the line under the heading.
  return heading.parentElement!.querySelector('p')!.textContent ?? ''
}

/** Delivers one claim through a fake Notification API and returns what it drew. */
async function deliverOne(
  claim: Partial<{ amount: number | null; currency: CurrencyCode | null; route: string }>,
) {
  const shown: Array<{ title: string; body?: string; tag?: string }> = []
  const instances: FakeNotification[] = []
  const navigate = vi.fn()
  class FakeNotification {
    static permission: NotificationPermission = 'granted'
    onclick: (() => void) | null = null
    constructor(title: string, options?: NotificationOptions) {
      shown.push({ title, body: options?.body, tag: options?.tag })
      instances.push(this)
    }
    close() {
      /* no-op */
    }
    static requestPermission = vi.fn(async () => 'granted' as NotificationPermission)
  }
  vi.stubGlobal('Notification', FakeNotification)
  const payload = [
    {
      id: 7,
      revision: 3,
      title: 'Assinatura vencida',
      amount: null,
      currency: null,
      route: '/commitments',
      ...claim,
    },
  ]
  vi.stubGlobal('fetch', vi.fn(async () => response(payload)))
  await deliverBrowserClaims(navigate)
  return { shown, instances, navigate, requestPermission: FakeNotification.requestPermission }
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('notification amounts in the currency the server sent', () => {
  it('renders reais exactly as it always did', async () => {
    const line = await renderInbox(120, 'BRL')
    expect(line).toContain('R$')
    expect(line).toContain('120,00')
  })

  it('never dresses dollars as reais', async () => {
    const line = await renderInbox(80, 'USD')
    expect(line).not.toContain('R$')
    expect(line).toMatch(/US\$|USD/)
    expect(line).toContain('80,00')
  })

  it('does not invent centavos for a whole number of yen', async () => {
    const line = await renderInbox(3000, 'JPY')
    expect(line).not.toContain('R$')
    expect(line).toMatch(/JP¥|JPY|¥/)
    expect(line).toContain('3.000')
    // A yen has no fractional part, so no ",00" may appear beside it.
    expect(line).not.toContain('3.000,00')
  })

  it('shows only the date when the row carries no amount at all', async () => {
    const line = await renderInbox(null, 'USD')
    expect(line).not.toContain('·')
    expect(line).not.toMatch(/US\$|USD|R\$/)
  })
})

describe('browser delivery', () => {
  it('formats a disclosed dollar amount as dollars', async () => {
    const { shown } = await deliverOne({ amount: 80, currency: 'USD' })
    expect(shown).toHaveLength(1)
    expect(shown[0].body).not.toContain('R$')
    expect(shown[0].body).toMatch(/US\$|USD/)
    expect(shown[0].body).toContain('80,00')
  })

  it('formats a disclosed yen amount without centavos', async () => {
    const { shown } = await deliverOne({ amount: 3000, currency: 'JPY' })
    expect(shown[0].body).not.toContain('R$')
    expect(shown[0].body).toContain('3.000')
    expect(shown[0].body).not.toContain('3.000,00')
  })

  it('says nothing about the money when the owner asked for it to stay private', async () => {
    const { shown } = await deliverOne({ amount: null, currency: null })
    expect(shown[0].body).toBe('Abra o Finora para ver os detalhes.')
    // No denomination anywhere in the payload the operating system draws:
    // knowing the withheld sum is in dollars is most of what hiding it protects.
    const drawn = JSON.stringify(shown[0])
    for (const code of ['USD', 'JPY', 'BRL', 'US$', 'JP¥', 'R$']) {
      expect(drawn).not.toContain(code)
    }
  })

  it('withholds the amount when a currency somehow arrives without it', async () => {
    // Defence in depth: the server pairs them, and if it ever did not, the
    // page still refuses to render a figure it cannot denominate.
    const { shown } = await deliverOne({ amount: 80, currency: null })
    expect(shown[0].body).toBe('Abra o Finora para ver os detalhes.')
    expect(shown[0].body).not.toContain('80')
  })

  it('de-duplicates on the notification id and revision, never on a source key', async () => {
    const { shown } = await deliverOne({ amount: null, currency: null })
    expect(shown[0].tag).toBe('7:3')
  })

  it('never asks for permission on its own', async () => {
    const { requestPermission } = await deliverOne({ amount: 80, currency: 'USD' })
    expect(requestPermission).not.toHaveBeenCalled()
  })

  it('follows an internal route on click but refuses anything protocol-relative', async () => {
    vi.stubGlobal('focus', vi.fn())

    const internal = await deliverOne({ route: '/commitments' })
    internal.instances[0].onclick?.()
    expect(internal.navigate).toHaveBeenCalledWith('/commitments')

    const external = await deliverOne({ route: '//example.com' })
    external.instances[0].onclick?.()
    expect(external.navigate).not.toHaveBeenCalled()
  })
})

/* ---------- no BRL-only helper survives in this feature ---------- */

describe('currency helpers', () => {
  it('imports no BRL-only formatter anywhere in the notification feature', async () => {
    const modules = {
      ...import.meta.glob('./*.tsx', { query: '?raw', import: 'default' }),
      ...import.meta.glob('./*.ts', { query: '?raw', import: 'default' }),
    }
    const offenders: string[] = []
    const scanned: string[] = []
    for (const [path, load] of Object.entries(modules)) {
      if (path.includes('.test.')) {
        continue
      }
      scanned.push(path)
      if (/\bformatBRL\b/.test((await load()) as string)) {
        offenders.push(path)
      }
    }
    expect(offenders).toEqual([])
    // A glob that matched nothing would pass this test forever without ever
    // looking at a single file, which is the one way a guard can rot silently.
    expect(scanned).toContain('./NotificationItem.tsx')
    expect(scanned).toContain('./browserNotifications.ts')
  })
})
