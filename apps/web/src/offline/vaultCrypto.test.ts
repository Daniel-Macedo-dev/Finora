import { describe, expect, it } from 'vitest'
import { DATA_SCHEMA_VERSION, VaultError, decryptVault, encryptVault, type VaultPayload } from './vaultCrypto'

const payload: VaultPayload = {
  dataSchemaVersion: DATA_SCHEMA_VERSION,
  owner: { id: 42, displayName: 'Usuária Ç', email: 'synthetic@example.test' },
  preparedAt: '2026-07-25T12:00:00.000Z',
  queries: [{ queryKey: ['dashboard', '2026-07'], data: { balance: 987654.32 }, dataUpdatedAt: 1 }],
}

describe('offline vault cryptography', () => {
  it('encrypts and decrypts Unicode content without plaintext metadata', async () => {
    const vault = await encryptVault(payload, 'senha-local-segura')
    expect(JSON.stringify(vault)).not.toContain('987654.32')
    expect(JSON.stringify(vault)).not.toContain('synthetic@example.test')
    await expect(decryptVault(vault, 'senha-local-segura')).resolves.toEqual(payload)
  })

  it('fails closed for a wrong password', async () => {
    const vault = await encryptVault(payload, 'senha-local-segura')
    await expect(decryptVault(vault, 'senha-local-errada')).rejects.toBeInstanceOf(VaultError)
  })

  it('fails closed for tampered ciphertext or IV', async () => {
    const vault = await encryptVault(payload, 'senha-local-segura')
    await expect(decryptVault({ ...vault, ciphertext: `${vault.ciphertext.slice(0, -2)}AA` }, 'senha-local-segura')).rejects.toBeInstanceOf(VaultError)
    await expect(decryptVault({ ...vault, iv: `${vault.iv.slice(0, -2)}AA` }, 'senha-local-segura')).rejects.toBeInstanceOf(VaultError)
  })

  it('uses a fresh IV and salt on every write', async () => {
    const first = await encryptVault(payload, 'senha-local-segura')
    const second = await encryptVault(payload, 'senha-local-segura')
    expect(second.iv).not.toBe(first.iv)
    expect(second.salt).not.toBe(first.salt)
    expect(second.ciphertext).not.toBe(first.ciphertext)
  })

  it('rejects unsupported and corrupted metadata', async () => {
    const vault = await encryptVault(payload, 'senha-local-segura')
    await expect(decryptVault({ ...vault, vaultSchemaVersion: 999 }, 'senha-local-segura')).rejects.toBeInstanceOf(VaultError)
    await expect(decryptVault({ ...vault, salt: 'broken' }, 'senha-local-segura')).rejects.toBeInstanceOf(VaultError)
  })

  it('supports an empty bounded query payload', async () => {
    const empty = { ...payload, queries: [] }
    const vault = await encryptVault(empty, 'senha-local-segura')
    await expect(decryptVault(vault, 'senha-local-segura')).resolves.toEqual(empty)
  })
})
