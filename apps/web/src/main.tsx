import { StrictMode, Suspense } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@fontsource-variable/inter/index.css'
import './index.css'
import App from './App'
import { ApiError } from './lib/api'
import { AUTH_ME_KEY } from './features/auth/api'
import { LoadingCards } from './components/states'
import { watchSystemTheme } from './lib/theme'
import { PwaProvider } from './pwa/PwaProvider'
import { ConnectionProvider } from './offline/connection'
import { VaultProvider } from './offline/VaultProvider'

const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error) => {
      // A 401 on any protected query means the server session ended: reset the
      // authenticated-user state so RequireAuth redirects to login. A network
      // failure is NOT treated as logged-out.
      if (error instanceof ApiError && error.isUnauthenticated) {
        queryClient.setQueryData(AUTH_ME_KEY, null)
      }
    },
  }),
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      // Client/validation errors will not change on retry; network glitches might.
      retry: (failureCount, error) => {
        if (error instanceof ApiError && error.status < 500) {
          return false
        }
        return failureCount < 2
      },
    },
  },
})

watchSystemTheme()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ConnectionProvider>
        <VaultProvider>
          <PwaProvider>
            <BrowserRouter>
              <Suspense fallback={<LoadingCards count={3} height={120} />}>
                <App />
              </Suspense>
            </BrowserRouter>
          </PwaProvider>
        </VaultProvider>
      </ConnectionProvider>
    </QueryClientProvider>
  </StrictMode>,
)
