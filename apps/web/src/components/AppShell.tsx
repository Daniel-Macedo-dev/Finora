import { Suspense, useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { LoadingCards } from './states'
import Dialog from './Dialog'
import {
  LayoutDashboard,
  ArrowLeftRight,
  FileUp,
  CreditCard,
  PiggyBank,
  CalendarClock,
  ChartSpline,
  History,
  Target,
  Heart,
  Settings,
  Menu,
  X,
  UserRound,
  LogOut,
  WifiOff,
  RefreshCw,
} from 'lucide-react'
import { useCurrentUser, useLogout } from '../features/auth/api'
import NotificationBell from '../features/notifications/NotificationBell'
import './AppShell.css'
import { useConnection } from '../offline/connection'
import { usePwa } from '../pwa/PwaProvider'
import { useVault } from '../offline/VaultProvider'
import OfflineUnavailable from '../offline/OfflineUnavailable'
import { UNSUPPORTED_OFFLINE_MESSAGE } from '../offline/outbox/useOutbox'
import SyncIndicator from '../features/offline-sync/SyncIndicator'
import { useReplayTriggers } from '../features/offline-sync/useReplayTriggers'

const NAV_ITEMS = [
  { to: '/dashboard', label: 'Visão geral', icon: LayoutDashboard },
  { to: '/transactions', label: 'Transações', icon: ArrowLeftRight },
  { to: '/statement-imports', label: 'Importar extrato', icon: FileUp },
  { to: '/credit-cards', label: 'Cartões', icon: CreditCard },
  { to: '/legacy-credit', label: 'Crédito legado', icon: History },
  { to: '/budgets', label: 'Orçamentos', icon: PiggyBank },
  { to: '/commitments', label: 'Recorrentes', icon: CalendarClock },
  { to: '/forecast', label: 'Previsão', icon: ChartSpline },
  { to: '/goals', label: 'Metas', icon: Target },
  { to: '/wishlist', label: 'Lista de desejos', icon: Heart },
  { to: '/settings', label: 'Configurações', icon: Settings },
]

function BrandMark() {
  return (
    <span className="brand">
      <span className="brand-mark" aria-hidden="true">
        F
      </span>
      <span className="brand-name">Finora</span>
    </span>
  )
}

function UserPanel() {
  const currentUser = useCurrentUser()
  const vault = useVault()
  const logout = useLogout()
  const navigate = useNavigate()
  const [confirmingLogout, setConfirmingLogout] = useState(false)

  const user = currentUser.data ?? vault.owner
  if (!user) {
    return null
  }

  function signOut() {
    logout.mutate(undefined, {
      // Local cleanup runs whichever way the server call went: a failed
      // logout request must not leave decrypted data behind on the device.
      onSettled: () => {
        void vault.remove().finally(() => navigate('/login', { replace: true }))
      },
    })
  }

  /**
   * Logging out deletes the local encrypted copy — including anything the
   * server has never seen. That is not a decision to make on the user's behalf
   * behind a generic "Sair", so unsynchronized work turns it into a question.
   */
  function handleLogout() {
    if (vault.hasPendingWork) {
      setConfirmingLogout(true)
      return
    }
    signOut()
  }

  return (
    <div className="user-panel">
      <NavLink to="/profile" className="user-identity" title="Abrir perfil">
        <span className="user-avatar" aria-hidden="true">
          <UserRound size={16} />
        </span>
        <span className="user-meta">
          <span className="user-name">{user.displayName}</span>
          <span className="user-email">{user.email}</span>
        </span>
      </NavLink>
      <button
        type="button"
        className="btn btn-ghost btn-icon"
        onClick={handleLogout}
        disabled={logout.isPending}
        aria-label="Sair da conta"
        title="Sair"
      >
        <LogOut size={16} aria-hidden="true" />
      </button>

      <Dialog
        open={confirmingLogout}
        title="Sair com alterações offline pendentes"
        onClose={() => setConfirmingLogout(false)}
      >
        <p style={{ color: 'var(--ink-secondary)' }}>
          Há {vault.counts.total} alteração(ões) offline que ainda não chegaram ao servidor
          {vault.counts.conflicts > 0 && `, sendo ${vault.counts.conflicts} em conflito`}
          {vault.counts.permanent > 0 && ` e ${vault.counts.permanent} com falha`}. Sair da conta
          apaga a cópia local criptografada deste dispositivo, e essas alterações não existem em
          nenhum outro lugar.
        </p>
        <div className="form-footer">
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => setConfirmingLogout(false)}
          >
            Cancelar
          </button>
          <NavLink
            to="/offline-sync"
            className="btn btn-primary"
            onClick={() => setConfirmingLogout(false)}
          >
            Sincronizar agora
          </NavLink>
          <button
            type="button"
            className="btn btn-danger"
            onClick={() => {
              setConfirmingLogout(false)
              signOut()
            }}
          >
            Descartar alterações e sair
          </button>
        </div>
      </Dialog>
    </div>
  )
}

export default function AppShell() {
  const [menuOpen, setMenuOpen] = useState(false)
  const location = useLocation()
  const connection = useConnection()
  const pwa = usePwa()
  const vault = useVault()
  useReplayTriggers()
  const offlineUnlocked = vault.state === 'UNLOCKED_OFFLINE'
  const offlineRouteSupported = new Set(['/dashboard', '/transactions', '/credit-cards', '/budgets', '/commitments', '/forecast', '/goals', '/wishlist', '/settings', '/notifications', '/offline-sync']).has(location.pathname)
  /** Routes whose domains can be queued while offline. */
  const offlineWritableRoute =
    location.pathname === '/transactions'
    || location.pathname === '/budgets'
    || location.pathname === '/goals'
    || location.pathname === '/offline-sync'
    || location.pathname.startsWith('/wishlist')

  // Close the mobile drawer on navigation.
  useEffect(() => {
    setMenuOpen(false)
  }, [location.pathname])

  useEffect(() => {
    if (!menuOpen) {
      return
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setMenuOpen(false)
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [menuOpen])

  // Offline mode is no longer read-only, but only the allowlisted domains can
  // be queued. On those routes everything is enabled except controls marked as
  // online-only; everywhere else the whole page stays blocked, so a workflow
  // that was never designed for replay cannot be started by accident. The API
  // client's refusal of unsafe requests remains the real boundary — this is the
  // part that explains it to the user before they try.
  useEffect(() => {
    if (!offlineUnlocked) return
    const selector = offlineWritableRoute
      ? "#main-content [data-offline-blocked='true'] button, " +
        "#main-content button[data-offline-blocked='true']"
      : "#main-content button:not([data-offline-allowed='true'])"
    const disableMutations = () => {
      document.querySelectorAll<HTMLButtonElement>(selector).forEach((button) => {
        if (!button.disabled) {
          button.dataset.offlineDisabled = 'true'
          button.disabled = true
          button.title = UNSUPPORTED_OFFLINE_MESSAGE
        }
      })
    }
    disableMutations()
    const observer = new MutationObserver(disableMutations)
    const main = document.getElementById('main-content')
    if (main) observer.observe(main, { childList: true, subtree: true })
    return () => {
      observer.disconnect()
      document.querySelectorAll<HTMLButtonElement>("#main-content button[data-offline-disabled='true']").forEach((button) => {
        button.disabled = false
        delete button.dataset.offlineDisabled
        button.removeAttribute('title')
      })
    }
  }, [location.pathname, offlineUnlocked, offlineWritableRoute])

  const navLinks = NAV_ITEMS.map(({ to, label, icon: Icon }) => (
    <NavLink key={to} to={to} className="nav-link">
      <Icon size={18} aria-hidden="true" />
      <span>{label}</span>
    </NavLink>
  ))

  return (
    <div className={`app-shell ${offlineUnlocked ? 'offline-readonly' : ''}`}>
      <a className="skip-link" href="#main-content">
        Pular para o conteúdo
      </a>
      {connection.state !== 'ONLINE' && (
        <div className="connection-banner" role="status" aria-live="polite">
          {connection.state === 'OFFLINE' ? <WifiOff size={18} aria-hidden="true" /> : <RefreshCw size={18} aria-hidden="true" />}
          <span>{connection.state === 'OFFLINE' ? 'Sem conexão. O acesso offline é somente leitura.' : 'Reconectando ao Finora…'}</span>
          <button type="button" className="btn btn-secondary" onClick={connection.retry}>Tentar novamente</button>
        </div>
      )}
      {pwa.updateAvailable && (
        <div className="update-banner" role="status">
          <span>Uma nova versão do Finora está disponível.</span>
          <button type="button" className="btn btn-primary" onClick={() => void pwa.applyUpdate()}>
            Atualizar agora
          </button>
        </div>
      )}
      <div className="shell-notification-bell">
        <SyncIndicator />
        <NotificationBell />
      </div>

      <header className="mobile-topbar">
        <BrandMark />
        <div className="mobile-topbar-actions"><button
          type="button"
          className="btn btn-ghost btn-icon"
          aria-expanded={menuOpen}
          aria-controls="mobile-nav"
          onClick={() => setMenuOpen((open) => !open)}
        >
          {menuOpen ? <X aria-hidden="true" /> : <Menu aria-hidden="true" />}
          <span className="visually-hidden">{menuOpen ? 'Fechar menu' : 'Abrir menu'}</span>
        </button></div>
      </header>

      {menuOpen && (
        <div
          className="mobile-backdrop"
          onClick={() => setMenuOpen(false)}
          aria-hidden="true"
        />
      )}

      <nav
        id="mobile-nav"
        className={`sidebar ${menuOpen ? 'sidebar-open' : ''}`}
        aria-label="Navegação principal"
      >
        <div className="sidebar-brand">
          <BrandMark />
        </div>
        {navLinks}
        <div className="sidebar-footer">
          <UserPanel />
        </div>
      </nav>

      <main id="main-content" className="app-main">
        {offlineUnlocked && (
          <div className="offline-context" role="status">
            <strong>Modo somente leitura</strong>
            <span>Dados salvos em {vault.updatedAt ? new Date(vault.updatedAt).toLocaleString('pt-BR') : 'data desconhecida'}.</span>
            <span>Os valores podem estar desatualizados.</span>
            <button data-offline-allowed="true" type="button" className="btn btn-secondary" onClick={vault.lock}>Bloquear dados offline</button>
          </div>
        )}
        {offlineUnlocked && !offlineRouteSupported ? <OfflineUnavailable /> : (
          <Suspense fallback={<LoadingCards count={3} height={120} />}>
            <Outlet />
          </Suspense>
        )}
      </main>
    </div>
  )
}
