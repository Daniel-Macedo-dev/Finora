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
} from 'lucide-react'
import { useCurrentUser, useLogout } from '../features/auth/api'
import NotificationBell from '../features/notifications/NotificationBell'
import './AppShell.css'

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
  const logout = useLogout()
  const navigate = useNavigate()

  const user = currentUser.data
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

  const navLinks = NAV_ITEMS.map(({ to, label, icon: Icon }) => (
    <NavLink key={to} to={to} className="nav-link">
      <Icon size={18} aria-hidden="true" />
      <span>{label}</span>
    </NavLink>
  ))

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        Pular para o conteúdo
      </a>
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
        <Suspense fallback={<LoadingCards count={3} height={120} />}>
          <Outlet />
        </Suspense>
      </main>
    </div>
  )
}
