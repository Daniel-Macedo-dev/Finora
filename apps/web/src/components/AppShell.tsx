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
import VaultDeletionDialog from '../offline/VaultDeletionDialog'
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

/** Exported so the destructive sign-out path can be tested on its own. */
export function UserPanel() {
  const currentUser = useCurrentUser()
  const vault = useVault()
  const logout = useLogout()
  const navigate = useNavigate()
  const [confirmingLogout, setConfirmingLogout] = useState(false)
  const [removing, setRemoving] = useState(false)
  const [removalFailed, setRemovalFailed] = useState<string | null>(null)

  const risk = vault.destructiveRisk
  const settling = risk === 'BUSY'

  const user = currentUser.data ?? vault.owner
  if (!user) {
    return null
  }

  /**
   * Ends the session and deletes the local copy, in that order.
   *
   * The deletion runs whichever way the server call went — a logout the user
   * confirmed must not leave decrypted data on the device because the network
   * happened to be down — but it is still allowed to fail loudly. Navigating to
   * the login screen while the encrypted record is demonstrably still there
   * would report a deletion that did not happen.
   */
  function signOut() {
    setRemoving(true)
    setRemovalFailed(null)
    logout.mutate(undefined, {
      onSettled: () => {
        void vault
          .remove()
          .then(() => {
            setConfirmingLogout(false)
            navigate('/login', { replace: true })
          })
          .catch(() => {
            setRemovalFailed(
              'Sua sessão foi encerrada, mas a cópia offline deste dispositivo não pôde ser '
              + 'excluída. Ela continua bloqueada aqui. Tente novamente.',
            )
            setConfirmingLogout(true)
          })
          .finally(() => setRemoving(false))
      },
    })
  }

  /**
   * Signing out deletes the local encrypted copy — including anything the
   * server has never seen. Whether there is anything to lose is a question the
   * application can only answer while the copy is readable, so everything else
   * goes through the warning: an unreadable copy is treated as one that may
   * hold unique work, because assuming the opposite is unrecoverable.
   */
  function handleLogout() {
    if (settling) return
    if (risk === 'NO_LOCAL_COPY' || risk === 'KNOWN_SAFE') {
      signOut()
      return
    }
    setRemovalFailed(null)
    setConfirmingLogout(true)
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
      {/* Disabled only while the vault state is still settling: deleting a copy
          the application has not finished looking at is a guess, and the one
          guess that cannot be taken back. */}
      <button
        type="button"
        className="btn btn-ghost btn-icon"
        onClick={handleLogout}
        disabled={logout.isPending || removing || settling}
        aria-label="Sair da conta"
        aria-describedby={settling ? 'logout-settling' : undefined}
        title="Sair"
      >
        <LogOut size={16} aria-hidden="true" />
      </button>
      {settling && (
        <span id="logout-settling" role="status" className="visually-hidden">
          Verificando a cópia offline deste dispositivo antes de permitir sair.
        </span>
      )}

      <VaultDeletionDialog
        open={confirmingLogout}
        risk={risk}
        counts={vault.counts}
        intent="LOGOUT"
        busy={removing}
        failure={removalFailed}
        onCancel={() => {
          setConfirmingLogout(false)
          setRemovalFailed(null)
        }}
        onReview={() => {
          setConfirmingLogout(false)
          navigate('/offline-sync')
        }}
        onConfirm={signOut}
      />
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
  const offlineRouteSupported =
    new Set(['/dashboard', '/transactions', '/credit-cards', '/budgets', '/commitments', '/forecast', '/goals', '/wishlist', '/settings', '/notifications', '/offline-sync']).has(location.pathname)
    // A wishlist item's own page is where purchase options and price
    // observations are added, so it has to be reachable offline for the
    // dependency chain to exist at all. Its detail query is part of the bounded
    // offline dataset for that reason; an exact-match list quietly excluded it.
    || /^\/wishlist\/[^/]+$/.test(location.pathname)
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
    // Coalesced into a frame, and the observer is detached while the sweep
    // writes: without this, every keystroke in a filter or refetch of a long
    // list re-scans the whole subtree, and the sweep's own disabled/title
    // writes feed straight back into the observer that scheduled it.
    let scheduled = 0
    const main = document.getElementById('main-content')
    const observe = () => main && observer.observe(main, { childList: true, subtree: true })
    const observer = new MutationObserver(() => {
      if (scheduled) return
      scheduled = requestAnimationFrame(() => {
        scheduled = 0
        observer.disconnect()
        disableMutations()
        observe()
      })
    })
    observe()
    return () => {
      if (scheduled) cancelAnimationFrame(scheduled)
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
    <div className={`app-shell ${offlineUnlocked ? 'offline-unlocked' : ''}`}>
      <a className="skip-link" href="#main-content">
        Pular para o conteúdo
      </a>
      {connection.state !== 'ONLINE' && (
        <div className="connection-banner" role="status" aria-live="polite">
          {connection.state === 'OFFLINE' ? <WifiOff size={18} aria-hidden="true" /> : <RefreshCw size={18} aria-hidden="true" />}
          <span>{connection.state === 'OFFLINE' ? 'Sem conexão. Algumas alterações podem ser feitas e ficam na fila até a conexão voltar.' : 'Reconectando ao Finora…'}</span>
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
            <strong>{offlineWritableRoute ? 'Modo offline' : 'Modo offline (somente leitura)'}</strong>
            <span>Dados salvos em {vault.updatedAt ? new Date(vault.updatedAt).toLocaleString('pt-BR') : 'data desconhecida'}.</span>
            <span>
              {offlineWritableRoute
                ? 'As alterações desta tela ficam na fila até a conexão voltar.'
                : 'Os valores podem estar desatualizados.'}
            </span>
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
