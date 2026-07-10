import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@fontsource-variable/inter/index.css'
import './index.css'
import App from './App'
import { ApiError } from './lib/api'
import { watchSystemTheme } from './lib/theme'

const queryClient = new QueryClient({
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
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
