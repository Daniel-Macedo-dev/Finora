import { useEffect, useRef } from 'react'
import { Link } from 'react-router-dom'
import { ErrorState } from '../../components/states'
import { useNotifications } from './api'
import NotificationItem from './NotificationItem'

export default function NotificationPanel({ onClose }: { onClose: () => void }) {
  const panel = useRef<HTMLDivElement>(null)
  const notifications = useNotifications('ACTIVE', 0, 5)
  useEffect(() => {
    panel.current?.focus()
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onClose()
        return
      }
      if (event.key !== 'Tab' || !panel.current) return
      const focusable = Array.from(panel.current.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ))
      if (focusable.length === 0) {
        event.preventDefault()
        panel.current.focus()
        return
      }
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onClose])
  return (
    <div ref={panel} className="notification-panel" role="dialog" aria-modal="false"
      aria-labelledby="notification-panel-title" tabIndex={-1}>
      <div className="notification-panel-header">
        <h2 id="notification-panel-title">Notificações</h2>
        <button className="btn btn-ghost" onClick={onClose}>Fechar</button>
      </div>
      {notifications.isPending ? <p>Carregando notificações…</p> : notifications.isError ? (
        <ErrorState error={notifications.error} onRetry={() => notifications.refetch()} />
      ) : notifications.data.content.length === 0 ? (
        <p className="notification-empty">Tudo em dia por aqui.</p>
      ) : notifications.data.content.map((item) => <NotificationItem key={item.id} notification={item} />)}
      <Link className="notification-history-link" to="/notifications" onClick={onClose}>Ver histórico completo</Link>
    </div>
  )
}
