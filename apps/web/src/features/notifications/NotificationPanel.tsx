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
    const close = (event: KeyboardEvent) => event.key === 'Escape' && onClose()
    document.addEventListener('keydown', close)
    return () => document.removeEventListener('keydown', close)
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
