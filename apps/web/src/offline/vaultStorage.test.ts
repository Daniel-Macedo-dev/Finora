import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it } from 'vitest'
import { deleteVault, encryptedSize, loadVault, saveVault } from './vaultStorage'
import type { EncryptedVault } from './vaultCrypto'

const vault: EncryptedVault = {
  vaultSchemaVersion: 1,
  dataSchemaVersion: 1,
  kdf: 'PBKDF2-HMAC-SHA-256',
  iterations: 310000,
  salt: 'salt', iv: 'iv', ciphertext: 'ciphertext',
  createdAt: '2026-07-25T12:00:00.000Z', updatedAt: '2026-07-25T12:00:00.000Z',
}

describe('offline vault storage', () => {
  beforeEach(async () => { await deleteVault() })

  it('creates, atomically replaces, and deletes the single vault record', async () => {
    expect(await loadVault()).toBeNull()
    await saveVault(vault)
    expect(await loadVault()).toEqual(vault)
    const replacement = { ...vault, ciphertext: 'replacement' }
    await saveVault(replacement)
    expect(await loadVault()).toEqual(replacement)
    await deleteVault()
    expect(await loadVault()).toBeNull()
  })

  it('reports approximate encrypted size', () => {
    expect(encryptedSize(vault)).toBeGreaterThan(vault.ciphertext.length)
  })
})
