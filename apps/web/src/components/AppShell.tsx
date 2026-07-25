import { Suspense, useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { LoadingCards } from './states'
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

  const user = currentUser.data ?? vault.owner
  if (!user) {
    return null
  }

  function handleLogout() {
    logout.mutate(undefined, {
      onSettled: () => navigate('/login', { replace: true }),
    })
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
    </div>
  )
}

export default function AppShell() {
  const [menuOpen, setMenuOpen] = useState(false)
  const location = useLocation()
  const connection = useConnection()
  const pwa = usePwa()
  const vault = useVault()
  const offlineUnlocked = vault.state === 'UNLOCKED_OFFLINE'
  const offlineRouteSupported = new Set(['/dashboard', '/transactions', '/credit-cards', '/budgets', '/commitments', '/forecast', '/goals', '/wishlist', '/settings', '/notifications']).has(location.pathname)

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

  useEffect(() => {
    if (!offlineUnlocked) return
    const disableMutations = () => {
      document.querySelectorAll<HTMLButtonElement>("#main-content button:not([data-offline-allowed='true'])").forEach((button) => {
        if (!button.disabled) {
          button.dataset.offlineDisabled = 'true'
          button.disabled = true
          button.title = 'Esta ação precisa de conexão. O modo offline do Finora é somente leitura.'
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
  }, [location.pathname, offlineUnlocked])

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
      <div className="shell-notification-bell"><NotificationBell /></div>

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
