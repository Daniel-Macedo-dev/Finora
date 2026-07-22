import { Link } from 'react-router-dom'
import { formatBRL, formatDate } from '../../lib/format'
import { useDismissNotification, useReadNotification, useRestoreNotification, useSnoozeNotification, useUnreadNotification } from './api'
import type { FinoraNotification } from './types'

const severityLabel = { INFO: 'Informativa', WARNING: 'Atenção', CRITICAL: 'Crítica' }

export default function NotificationItem({ notification }: { notification: FinoraNotification }) {
  const read = useReadNotification()
  const unread = useUnreadNotification()
  const dismiss = useDismissNotification()
  const restore = useRestoreNotification()
  const snooze = useSnoozeNotification()
  const busy = read.isPending || unread.isPending || dismiss.isPending || restore.isPending || snooze.isPending
  return (
    <article className={`notification-item severity-${notification.severity.toLowerCase()} ${notification.unread ? 'is-unread' : ''}`}>
      <div className="notification-copy">
        <span className="notification-severity">{severityLabel[notification.severity]}</span>
        <h3>{notification.title}</h3>
        <p>{formatDate(notification.eventDate)}{notification.amount != null ? ` · ${formatBRL(notification.amount)}` : ''}</p>
      </div>
      <div className="notification-actions">
        <Link className="btn btn-secondary" to={notification.route} onClick={() => read.mutate(notification.id)}>Ver origem</Link>
        <button className="btn btn-ghost" disabled={busy} onClick={() => (notification.unread ? read : unread).mutate(notification.id)}>
          {notification.unread ? 'Marcar como lida' : 'Marcar como não lida'}
        </button>
        {notification.dismissed ? (
          <button className="btn btn-ghost" disabled={busy || notification.resolvedAt != null} onClick={() => restore.mutate(notification.id)}>Restaurar</button>
        ) : (
          <button className="btn btn-ghost" disabled={busy} onClick={() => dismiss.mutate(notification.id)}>Dispensar</button>
        )}
        {notification.resolvedAt == null && (
          <button className="btn btn-ghost" disabled={busy} aria-label={`Adiar ${notification.title} por um dia`}
            onClick={() => snooze.mutate({ id: notification.id, until: new Date(Date.now() + 86_400_000).toISOString() })}>
            Adiar 1 dia
          </button>
        )}
      </div>
    </article>
  )
}
