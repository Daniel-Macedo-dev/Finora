import { useState } from 'react'
import PageHeader from '../../components/PageHeader'
import { ErrorState, LoadingCards } from '../../components/states'
import { useNotifications, useReadAllNotifications, useUnreadCount } from './api'
import NotificationItem from './NotificationItem'
import type { NotificationFilter } from './types'
import './notifications.css'

const filters: Array<[NotificationFilter, string]> = [
  ['ACTIVE', 'Ativas'], ['UNREAD', 'Não lidas'], ['SNOOZED', 'Adiadas'],
  ['DISMISSED', 'Dispensadas'], ['RESOLVED', 'Resolvidas'], ['ALL', 'Todas'],
]

export default function NotificationsPage() {
  const [filter, setFilter] = useState<NotificationFilter>('ACTIVE')
  const [page, setPage] = useState(0)
  const notifications = useNotifications(filter, page, 20)
  const unread = useUnreadCount()
  const readAll = useReadAllNotifications()
  return (
    <>
      <PageHeader title="Notificações" description={`${unread.data?.count ?? 0} não lidas. O histórico não altera seus registros financeiros.`} />
      <div className="notification-toolbar">
        <div className="notification-filters" role="group" aria-label="Filtrar notificações">
          {filters.map(([value, label]) => (
            <button key={value} className={`btn ${filter === value ? 'btn-primary' : 'btn-secondary'}`}
              aria-pressed={filter === value} onClick={() => { setFilter(value); setPage(0) }}>{label}</button>
          ))}
        </div>
        <button className="btn btn-secondary" disabled={readAll.isPending} onClick={() => readAll.mutate()}>Marcar todas como lidas</button>
      </div>
      {notifications.isPending ? <LoadingCards count={3} height={120} /> : notifications.isError ? (
        <ErrorState error={notifications.error} onRetry={() => notifications.refetch()} />
      ) : notifications.data.content.length === 0 ? (
        <section className="card notification-empty"><h2>Nenhuma notificação</h2><p>Não há itens neste filtro.</p></section>
      ) : (
        <section className="notification-list" aria-label="Histórico de notificações">
          {notifications.data.content.map((item) => <NotificationItem key={item.id} notification={item} />)}
        </section>
      )}
      {notifications.data && notifications.data.totalPages > 1 && (
        <nav className="notification-pagination" aria-label="Paginação das notificações">
          <button className="btn btn-secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Anterior</button>
          <span>Página {page + 1} de {notifications.data.totalPages}</span>
          <button className="btn btn-secondary" disabled={page + 1 >= notifications.data.totalPages} onClick={() => setPage((p) => p + 1)}>Próxima</button>
        </nav>
      )}
    </>
  )
}
