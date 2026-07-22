import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import NotificationBell from './NotificationBell'
import NotificationsPage from './NotificationsPage'
import { browserPermission, deliverBrowserClaims, requestBrowserPermission } from './browserNotifications'

const item = {
  id: 7, sourceKey: 'COMMITMENT:2:2026-07-21', type: 'RECURRING_OVERDUE',
  severity: 'CRITICAL', eventDate: '2026-07-21', title: 'Conta vencida', amount: 120,
  resourceType: 'COMMITMENT', resourceId: 2, route: '/commitments', revision: 1,
  unread: true, dismissed: false, snoozed: false, snoozedUntil: null,
  firstSeenAt: '2026-07-21T12:00:00Z', lastSeenAt: '2026-07-21T12:00:00Z', resolvedAt: null,
}

function response(body: unknown, status = 200) {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

function renderWithClient(ui: ReactNode, handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => handler(String(input), init)))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter>{ui}</MemoryRouter></QueryClientProvider>)
}

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('notification center', () => {
  it('caps the badge at 99+ and exposes the real unread count in its accessible name', async () => {
    renderWithClient(<NotificationBell />, (url) => {
      if (url.includes('unread-count')) return response({ count: 123 })
      if (url.includes('browser-claims')) return response([])
      return response({ created: 0 })
    })
    const bell = await screen.findByRole('button', { name: 'Notificações: 123 não lidas' })
    expect(screen.getByText('99+')).toBeInTheDocument()
    fireEvent.click(bell)
    expect(await screen.findByRole('dialog', { name: 'Notificações' })).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(bell).toHaveFocus())
    expect(screen.queryByRole('dialog', { name: 'Notificações' })).not.toBeInTheDocument()
  })

  it('renders history, filters, source navigation and lifecycle actions', async () => {
    document.cookie = 'XSRF-TOKEN=test-token; path=/'
    renderWithClient(<NotificationsPage />, (url, init) => {
      if (url.includes('unread-count')) return response({ count: 1 })
      if (init?.method === 'POST') return response(item)
      return response({ content: [item], page: 0, size: 20, totalElements: 1, totalPages: 1 })
    })
    expect(await screen.findByText('Conta vencida')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver origem' })).toHaveAttribute('href', '/commitments')
    fireEvent.click(screen.getByRole('button', { name: 'Não lidas' }))
    await waitFor(() => expect(String(vi.mocked(fetch).mock.calls.at(-1)?.[0])).toContain('filter=UNREAD'))
    fireEvent.click(await screen.findByRole('button', { name: 'Marcar como lida' }))
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).includes('/7/read'))).toBe(true))
  })

  it('never requests permission automatically and reports unsupported browsers', async () => {
    expect(browserPermission()).toBe('unsupported')
    expect(await requestBrowserPermission()).toBe('unsupported')
  })

  it('claims only after permission is granted and keeps hidden amounts private', async () => {
    const shown: Array<{ title: string; body?: string }> = []
    class FakeNotification {
      static permission: NotificationPermission = 'granted'
      onclick: (() => void) | null = null
      constructor(title: string, options?: NotificationOptions) { shown.push({ title, body: options?.body }) }
      close() { /* no-op */ }
      static requestPermission = vi.fn(async () => 'granted' as NotificationPermission)
    }
    vi.stubGlobal('Notification', FakeNotification)
    vi.stubGlobal('fetch', vi.fn(async () => response([{ id: 7, sourceKey: item.sourceKey, revision: 1, title: item.title, amount: null, route: item.route }])))
    await deliverBrowserClaims(vi.fn())
    expect(shown).toEqual([{ title: 'Conta vencida', body: 'Abra o Finora para ver os detalhes.' }])
    expect(FakeNotification.requestPermission).not.toHaveBeenCalled()
  })
})
