import { defineConfig } from '@playwright/test'

/**
 * E2E suite. Expects the API (localhost:8080, with PostgreSQL) to be running;
 * the Vite dev server is started automatically when not already up.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 30_000,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    locale: 'pt-BR',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 60_000,
  },
})
