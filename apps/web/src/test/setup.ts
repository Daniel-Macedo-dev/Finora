import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Testing Library auto-cleanup relies on global afterEach; with Vitest
// globals disabled we register it explicitly.
afterEach(() => {
  cleanup()
})

// jsdom does not implement the native <dialog> modal API that Dialog.tsx
// relies on; a minimal open/close shim keeps dialog content testable.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}
