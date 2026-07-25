import { defineConfig } from '@playwright/test'

/**
 * E2E suite. Expects the API (localhost:8080, with PostgreSQL) to be running;
 * the production preview is built and started automatically so manifest and
 * Service Worker behavior are exercised exactly as shipped.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 30_000,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:4173',
    locale: 'pt-BR',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npm run build && npm run preview -- --host 127.0.0.1',
    url: 'http://localhost:4173',
    reuseExistingServer: true,
    timeout: 60_000,
  },
})
