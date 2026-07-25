import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useRegisterSW } from 'virtual:pwa-register/react'

interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

export type InstallState = 'available' | 'installed' | 'dismissed' | 'unsupported'

interface PwaContextValue {
  installState: InstallState
  install(): Promise<boolean>
  updateAvailable: boolean
  applyUpdate(): Promise<void>
  serviceWorkerReady: boolean
  registrationError: boolean
}

const PwaContext = createContext<PwaContextValue | null>(null)

function isStandalone(): boolean {
  return window.matchMedia('(display-mode: standalone)').matches
}

export function PwaProvider({ children }: { children: ReactNode }) {
  const [installPrompt, setInstallPrompt] = useState<BeforeInstallPromptEvent | null>(null)
  const [installState, setInstallState] = useState<InstallState>(() =>
    isStandalone() ? 'installed' : 'unsupported',
  )
  const [serviceWorkerReady, setServiceWorkerReady] = useState(false)
  const [registrationError, setRegistrationError] = useState(false)
  const { needRefresh: [updateAvailable], updateServiceWorker } = useRegisterSW({
    immediate: true,
    onRegisteredSW: () => setServiceWorkerReady(true),
    onRegisterError: () => setRegistrationError(true),
  })

  useEffect(() => {
    const onPrompt = (event: Event) => {
      event.preventDefault()
      setInstallPrompt(event as BeforeInstallPromptEvent)
      setInstallState('available')
    }
    const onInstalled = () => {
      setInstallPrompt(null)
      setInstallState('installed')
    }
    window.addEventListener('beforeinstallprompt', onPrompt)
    window.addEventListener('appinstalled', onInstalled)
    return () => {
      window.removeEventListener('beforeinstallprompt', onPrompt)
      window.removeEventListener('appinstalled', onInstalled)
    }
  }, [])

  const value = useMemo<PwaContextValue>(() => ({
    installState,
    updateAvailable,
    serviceWorkerReady,
    registrationError,
    install: async () => {
      if (!installPrompt) return false
      await installPrompt.prompt()
      const choice = await installPrompt.userChoice
      setInstallPrompt(null)
      setInstallState(choice.outcome === 'accepted' ? 'installed' : 'dismissed')
      return choice.outcome === 'accepted'
    },
    applyUpdate: async () => {
      await updateServiceWorker(true)
    },
  }), [installPrompt, installState, registrationError, serviceWorkerReady, updateAvailable, updateServiceWorker])

  return <PwaContext.Provider value={value}>{children}</PwaContext.Provider>
}

export function usePwa(): PwaContextValue {
  const value = useContext(PwaContext)
  if (!value) throw new Error('usePwa must be used within PwaProvider')
  return value
}
