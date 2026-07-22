import { api } from '../../lib/api'
import { formatBRL } from '../../lib/format'
import { claimBrowserNotifications } from './api'

export type BrowserPermissionState = NotificationPermission | 'unsupported'

export function browserPermission(): BrowserPermissionState {
  return 'Notification' in window ? Notification.permission : 'unsupported'
}

export async function requestBrowserPermission(): Promise<BrowserPermissionState> {
  if (!('Notification' in window)) return 'unsupported'
  return Notification.requestPermission()
}

export async function deliverBrowserClaims(navigate: (route: string) => void) {
  if (browserPermission() !== 'granted') return
  const claims = await claimBrowserNotifications()
  for (const claim of claims) {
    const body = claim.amount == null ? 'Abra o Finora para ver os detalhes.' : formatBRL(claim.amount)
    try {
      const alert = new Notification(claim.title, {
        body,
        tag: `${claim.sourceKey}:${claim.revision}`,
      })
      alert.onclick = () => {
        window.focus()
        if (claim.route.startsWith('/') && !claim.route.startsWith('//')) navigate(claim.route)
        void api.post(`/notifications/${claim.id}/read`)
        alert.close()
      }
    } catch {
      // The durable in-app notification remains available.
    }
  }
}
