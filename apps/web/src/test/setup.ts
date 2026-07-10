import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Testing Library auto-cleanup relies on global afterEach; with Vitest
// globals disabled we register it explicitly.
afterEach(() => {
  cleanup()
})
