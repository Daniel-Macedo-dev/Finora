import { describe, expect, it } from 'vitest'
import {
  createVault,
  DATA_SCHEMA_VERSION,
  emptyPayload,
  needsMigration,
  PBKDF2_ITERATIONS,
  resealVault,
  reopenVault,
  unlockVault,
  VAULT_SCHEMA_VERSION,
  VaultError,
  type EncryptedVault,
  type VaultPayload,
} from './vaultCrypto'
import { DEFAULT_SYNC_PREFERENCES, type OutboxEntry } from './outbox/types'

const owner = { id: 42, displayName: 'Usuária Ç', email: 'synthetic@example.test' }
const password = 'senha-local-segura'

const queuedEntry: OutboxEntry = {
  clientMutationId: '11111111-1111-1111-1111-111111111111',
  resourceType: 'TRANSACTION',
  operation: 'CREATE',
  target: { clientResourceId: '22222222-2222-2222-2222-222222222222' },
  clientResourceId: '22222222-2222-2222-2222-222222222222',
  baseVersion: null,
  payload: { amount: 987654.32, description: 'Compra sigilosa' },
  dependencies: [],
  status: 'PENDING',
  createdAt: '2026-07-25T12:00:00.000Z',
  updatedAt: '2026-07-25T12:00:00.000Z',
  attemptCount: 0,
  nextAttemptAt: null,
  lastError: null,
  conflict: null,
  label: 'Compra sigilosa',
}

const payload: VaultPayload = {
  ...emptyPayload(owner, '2026-07-25T12:00:00.000Z', [
    { queryKey: ['dashboard', '2026-07'], data: { balance: 987654.32 }, dataUpdatedAt: 1 },
  ]),
  outbox: [queuedEntry],
}

/** A record exactly as the read-only stage wrote it, encrypted with V1's shape. */
async function makeV1Vault(): Promise<EncryptedVault> {
  return sealLegacy(
    {
      dataSchemaVersion: 1,
      owner,
      preparedAt: '2026-07-20T09:00:00.000Z',
      queries: [{ queryKey: ['goals'], data: [{ id: 7 }], dataUpdatedAt: 5 }],
    },
    1,
  )
}

/**
 * A record from the outbox stage: envelope V2, payload V2. This is the case
 * that a version check on the envelope alone would wrongly call up to date.
 */
async function makeV2Vault(
  overrides: Partial<Record<string, unknown>> = {},
): Promise<EncryptedVault> {
  return sealLegacy(
    {
      dataSchemaVersion: 2,
      owner,
      preparedAt: '2026-07-25T12:00:00.000Z',
      queries: [
        { queryKey: ['accounts'], data: [{ id: 1, currentBalance: 800 }], dataUpdatedAt: 5 },
        { queryKey: ['dashboard', '2026-07'], data: { income: 5000 }, dataUpdatedAt: 6 },
      ],
      outbox: [queuedEntry],
      resourceMappings: [],
      syncHistory: [],
      syncPreferences: { ...DEFAULT_SYNC_PREFERENCES },
      ...overrides,
    },
    2,
  )
}

async function sealLegacy(
  legacyPayload: Record<string, unknown>,
  vaultSchemaVersion: number,
): Promise<EncryptedVault> {
  const salt = crypto.getRandomValues(new Uint8Array(16))
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const material = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(password),
    'PBKDF2',
    false,
    ['deriveKey'],
  )
  const key = await crypto.subtle.deriveKey(
    { name: 'PBKDF2', hash: 'SHA-256', salt, iterations: PBKDF2_ITERATIONS },
    material,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  )
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv },
    key,
    new TextEncoder().encode(JSON.stringify(legacyPayload)),
  )
  const base64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes))
  return {
    vaultSchemaVersion,
    dataSchemaVersion: legacyPayload.dataSchemaVersion as number,
    kdf: 'PBKDF2-HMAC-SHA-256',
    iterations: PBKDF2_ITERATIONS,
    salt: base64(salt),
    iv: base64(iv),
    ciphertext: base64(new Uint8Array(ciphertext)),
    createdAt: '2026-07-20T09:00:00.000Z',
    updatedAt: '2026-07-20T09:00:00.000Z',
  }
}

describe('offline vault cryptography', () => {
  it('keeps queued mutations out of the encrypted record metadata', async () => {
    const { encrypted } = await createVault(payload, password)
    const serialized = JSON.stringify(encrypted)
    expect(serialized).not.toContain('987654.32')
    expect(serialized).not.toContain('Compra sigilosa')
    expect(serialized).not.toContain('synthetic@example.test')
    expect(serialized).not.toContain(queuedEntry.clientMutationId)
    expect(serialized).not.toContain(queuedEntry.clientResourceId)
  })

  it('round-trips the whole payload including the outbox', async () => {
    const { encrypted } = await createVault(payload, password)
    const { payload: decrypted } = await unlockVault(encrypted, password)
    expect(decrypted).toEqual(payload)
  })

  it('fails closed for a wrong password', async () => {
    const { encrypted } = await createVault(payload, password)
    await expect(unlockVault(encrypted, 'senha-local-errada')).rejects.toBeInstanceOf(VaultError)
  })

  it('fails closed for tampered ciphertext or IV', async () => {
    const { encrypted } = await createVault(payload, password)
    await expect(
      unlockVault({ ...encrypted, ciphertext: `${encrypted.ciphertext.slice(0, -2)}AA` }, password),
    ).rejects.toBeInstanceOf(VaultError)
    await expect(
      unlockVault({ ...encrypted, iv: `${encrypted.iv.slice(0, -2)}AA` }, password),
    ).rejects.toBeInstanceOf(VaultError)
  })

  it('uses a fresh IV for every rewrite of an unlocked vault', async () => {
    const { encrypted, session } = await createVault(payload, password)
    const rewritten = await resealVault({ ...payload, outbox: [] }, session)
    const again = await resealVault(payload, session)
    expect(rewritten.iv).not.toBe(encrypted.iv)
    expect(again.iv).not.toBe(rewritten.iv)
    // The salt is stable — it is part of the identity of the derived key.
    expect(rewritten.salt).toBe(encrypted.salt)
  })

  it('re-seals without the password once unlocked', async () => {
    const { encrypted, session } = await createVault(payload, password)
    const updated = await resealVault({ ...payload, outbox: [] }, session)
    const { payload: decrypted } = await unlockVault(updated, password)
    expect(decrypted.outbox).toEqual([])
    expect(updated.createdAt).toBe(encrypted.createdAt)
  })

  it('reopens a record another tab rewrote, without the password', async () => {
    const { session } = await createVault(payload, password)
    // What a second tab sees: the same stored record, changed by the first.
    const rewritten = await resealVault({ ...payload, outbox: [] }, session)
    const reopened = await reopenVault(rewritten, session)
    expect(reopened.outbox).toEqual([])
    expect(reopened.owner).toEqual(payload.owner)
  })

  it('fails closed when reopening with a key that does not open the record', async () => {
    const { encrypted } = await createVault(payload, password)
    const other = await createVault(payload, 'outra-senha-offline-longa')
    await expect(reopenVault(encrypted, other.session)).rejects.toBeInstanceOf(VaultError)
    await expect(
      reopenVault({ ...encrypted, vaultSchemaVersion: VAULT_SCHEMA_VERSION + 1 }, other.session),
    ).rejects.toBeInstanceOf(VaultError)
  })

  it('fails closed for an unknown future schema', async () => {
    const { encrypted } = await createVault(payload, password)
    await expect(
      unlockVault({ ...encrypted, vaultSchemaVersion: VAULT_SCHEMA_VERSION + 1 }, password),
    ).rejects.toBeInstanceOf(VaultError)
    await expect(unlockVault({ ...encrypted, salt: 'broken' }, password)).rejects.toBeInstanceOf(
      VaultError,
    )
  })

  it('migrates a read-only V1 vault instead of rejecting it', async () => {
    const legacy = await makeV1Vault()
    expect(needsMigration(legacy)).toBe(true)

    const { payload: migrated, session } = await unlockVault(legacy, password)
    expect(migrated.dataSchemaVersion).toBe(DATA_SCHEMA_VERSION)
    expect(migrated.owner).toEqual(owner)
    expect(migrated.preparedAt).toBe('2026-07-20T09:00:00.000Z')
    // V1 → V2 → V3 in one pass: the queue appears, and the cached goal is
    // labelled with the currency it always had.
    expect(migrated.queries).toEqual([
      { queryKey: ['goals'], data: [{ id: 7, currency: 'BRL' }], dataUpdatedAt: 5 },
    ])
    expect(migrated.outbox).toEqual([])
    expect(migrated.resourceMappings).toEqual([])
    expect(migrated.syncHistory).toEqual([])
    expect(migrated.syncPreferences).toEqual(DEFAULT_SYNC_PREFERENCES)

    // The upgraded record is written with the same key, and no longer migrates.
    const upgraded = await resealVault(migrated, session)
    expect(needsMigration(upgraded)).toBe(false)
    await expect(unlockVault(upgraded, password)).resolves.toBeDefined()
  })

  it('upgrades a V2 payload even though its envelope is already current', async () => {
    const stored = await makeV2Vault()
    // The exact case an envelope-only check would call up to date.
    expect(stored.vaultSchemaVersion).toBe(VAULT_SCHEMA_VERSION)
    expect(needsMigration(stored)).toBe(true)

    const { payload: migrated, session } = await unlockVault(stored, password)
    expect(migrated.dataSchemaVersion).toBe(3)
    expect(migrated.queries.map((entry) => entry.queryKey[0])).toEqual(['accounts'])
    expect(migrated.queries[0].data).toEqual([{ id: 1, currentBalance: 800, currency: 'BRL' }])

    const upgraded = await resealVault(migrated, session)
    expect(needsMigration(upgraded)).toBe(false)
    // Fresh IV on every write, and identity carried across.
    expect(upgraded.iv).not.toBe(stored.iv)
    expect(upgraded.createdAt).toBe(stored.createdAt)
  })

  it('carries a queued mutation across the upgrade byte for byte', async () => {
    const stored = await makeV2Vault()
    const { payload: migrated } = await unlockVault(stored, password)

    expect(migrated.outbox).toHaveLength(1)
    // The canonical request is what the server hashed into a receipt. Adding a
    // currency to it would change that hash and turn a legitimate resend into
    // a reused idempotency key.
    expect(migrated.outbox[0]).toEqual(queuedEntry)
    expect(migrated.outbox[0].payload).not.toHaveProperty('currency')
    expect(migrated.outbox[0].clientMutationId).toBe(queuedEntry.clientMutationId)
    expect(migrated.outbox[0].clientResourceId).toBe(queuedEntry.clientResourceId)
    expect(migrated.outbox[0].status).toBe('PENDING')
    expect(migrated.outbox[0].attemptCount).toBe(0)
  })

  it('is a no-op for a record already at the current data schema', async () => {
    const { encrypted } = await createVault(payload, password)
    expect(needsMigration(encrypted)).toBe(false)

    const { payload: opened } = await unlockVault(encrypted, password)
    expect(opened).toEqual(payload)
  })

  it('fails closed on a data version this build does not understand', async () => {
    const future = await makeV2Vault({ dataSchemaVersion: 4 })
    // Refusing is the safe answer: guessing at a newer shape could silently
    // drop queued work that exists nowhere else.
    await expect(unlockVault(future, password)).rejects.toBeInstanceOf(VaultError)
  })

  it('keeps no migration metadata outside the ciphertext', async () => {
    const stored = await makeV2Vault()
    const { payload: migrated, session } = await unlockVault(stored, password)
    const upgraded = await resealVault(migrated, session)

    const serialized = JSON.stringify(upgraded)
    expect(serialized).not.toContain('BRL')
    expect(serialized).not.toContain('accounts')
    expect(serialized).not.toContain(queuedEntry.clientMutationId)
  })

  it('leaves the original record readable when the upgrade is never written', async () => {
    const legacy = await makeV1Vault()
    await unlockVault(legacy, password)
    // Nothing was persisted, so the V1 record is still exactly what it was.
    const { payload: again } = await unlockVault(legacy, password)
    expect(again.queries).toHaveLength(1)
    expect(again.outbox).toEqual([])
  })
})
