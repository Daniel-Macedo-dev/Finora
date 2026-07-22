import { Bell } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { deliverBrowserClaims } from './browserNotifications'
import { useSyncNotifications, useUnreadCount } from './api'
import NotificationPanel from './NotificationPanel'
import './notifications.css'

export default function NotificationBell() {
  const [open, setOpen] = useState(false)
  const unread = useUnreadCount()
  const { mutate: syncNotifications } = useSyncNotifications()
  const navigate = useNavigate()
  const bell = useRef<HTMLButtonElement>(null)
  const synchronizing = useRef(false)
  const synchronize = useCallback(() => {
    if (document.hidden || synchronizing.current) return
    synchronizing.current = true
    syncNotifications(undefined, {
      onSuccess: () => void deliverBrowserClaims(navigate),
      onSettled: () => { synchronizing.current = false },
    })
  }, [navigate, syncNotifications])
  const closePanel = useCallback(() => {
    setOpen(false)
    window.requestAnimationFrame(() => bell.current?.focus())
  }, [])
  useEffect(() => {
    synchronize()
    const interval = window.setInterval(synchronize, 5 * 60_000)
    const onFocus = () => synchronize()
    window.addEventListener('focus', onFocus)
    return () => { window.clearInterval(interval); window.removeEventListener('focus', onFocus) }
  }, [synchronize])
  const count = unread.data?.count ?? 0
  return (
    <div className="notification-bell-wrap">
      <button ref={bell} type="button" className="btn btn-ghost btn-icon notification-bell"
        aria-label={count ? `Notificações: ${count} não lidas` : 'Notificações: nenhuma não lida'}
        aria-expanded={open} onClick={() => setOpen((value) => !value)}>
        <Bell size={20} aria-hidden="true" />
        {count > 0 && <span className="notification-badge" aria-hidden="true">{count > 99 ? '99+' : count}</span>}
      </button>
      {open && <NotificationPanel onClose={closePanel} />}
    </div>
  )
}
