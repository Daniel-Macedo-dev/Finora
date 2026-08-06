import { migrateQueriesToV3 } from './dataMigration'
import {
  DEFAULT_SYNC_PREFERENCES,
  type OutboxEntry,
  type ResourceMapping,
  type SyncHistoryEntry,
  type SyncPreferences,
} from './outbox/types'

/**
 * The encryption envelope. Unchanged at 2: this stage touches none of PBKDF2,
 * the iteration count, AES-GCM, the salt or IV format, or the IndexedDB record.
 */
export const VAULT_SCHEMA_VERSION = 2

/**
 * The plaintext payload's shape, inside the ciphertext.
 *
 * V3 is the multi-currency shape: every cached resource names the currency its
 * amounts are denominated in. A copy prepared before that is still perfectly
 * good data — it is simply BRL that never said so — and is upgraded in memory
 * on unlock rather than being thrown away.
 */
export const DATA_SCHEMA_VERSION = 3
export const PBKDF2_ITERATIONS = 310_000

export interface OfflineQuery {
  queryKey: readonly unknown[]
  data: unknown
  dataUpdatedAt: number
}

export interface VaultOwner {
  id: number
  displayName: string
  email: string
}

/**
 * Everything the vault holds, all of it inside authenticated ciphertext.
 *
 * The outbox is what changed the security calculus for this stage. The
 * read-only vault held a copy of data the server already had, so losing it cost
 * nothing but convenience. A queued mutation is the only copy of work the
 * server has never seen.
 */
export interface VaultPayload {
  dataSchemaVersion: number
  owner: VaultOwner
  preparedAt: string
  queries: OfflineQuery[]
  outbox: OutboxEntry[]
  resourceMappings: ResourceMapping[]
  syncHistory: SyncHistoryEntry[]
  syncPreferences: SyncPreferences
}

export interface EncryptedVault {
  vaultSchemaVersion: number
  dataSchemaVersion: number
  kdf: 'PBKDF2-HMAC-SHA-256'
  iterations: number
  salt: string
  iv: string
  ciphertext: string
  createdAt: string
  updatedAt: string
}

/**
 * An unlocked vault's key material, held only in memory.
 *
 * Queueing a mutation has to rewrite the vault, and asking for the offline
 * password on every entry is not a usable product — so the derived key outlives
 * the unlock. The password does not: it is used for derivation and then
 * dropped. Locking, logging out and the inactivity timer all discard this
 * object, and nothing ever writes it to storage.
 */
export interface VaultSession {
  readonly key: CryptoKey
  readonly salt: Uint8Array<ArrayBuffer>
  readonly iterations: number
  readonly createdAt: string
}

export class VaultError extends Error {
  constructor() {
    super('Não foi possível desbloquear os dados. Verifique a senha ou exclua a cópia local.')
    this.name = 'VaultError'
  }
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

function base64ToBytes(value: string): Uint8Array<ArrayBuffer> {
  try {
    const binary = atob(value)
    return Uint8Array.from(binary, (character) => character.charCodeAt(0))
  } catch {
    throw new VaultError()
  }
}

async function deriveKey(
  password: string,
  salt: Uint8Array<ArrayBuffer>,
  iterations: number,
): Promise<CryptoKey> {
  const material = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(password),
    'PBKDF2',
    false,
    ['deriveKey'],
  )
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', hash: 'SHA-256', salt, iterations },
    material,
    { name: 'AES-GCM', length: 256 },
    // Not extractable: the key cannot be read back out and persisted, even by
    // our own code.
    false,
    ['encrypt', 'decrypt'],
  )
}

function isOwner(value: unknown): value is VaultOwner {
  const owner = value as Partial<VaultOwner> | undefined
  return (
    !!owner &&
    typeof owner.id === 'number' &&
    typeof owner.displayName === 'string' &&
    typeof owner.email === 'string'
  )
}

/**
 * Accepts the current schema and the previous one, and nothing else.
 *
 * An unknown higher schema means this browser is running older code against a
 * vault newer code wrote. Guessing at its contents could silently drop queued
 * mutations, so it fails closed and leaves the ciphertext untouched.
 */
function validatePayload(value: unknown): VaultPayload {
  if (!value || typeof value !== 'object') throw new VaultError()
  const payload = value as Partial<VaultPayload>
  if (
    !isOwner(payload.owner) ||
    typeof payload.preparedAt !== 'string' ||
    !Array.isArray(payload.queries)
  ) {
    throw new VaultError()
  }
  // Deterministic chain: V1 → V2 → V3. Each step is a whole shape, so a very
  // old vault lands on the current one without special cases per pair.
  let current = payload
  if (current.dataSchemaVersion === 1) {
    current = migrateV1(current)
  }
  if (current.dataSchemaVersion === 2) {
    current = migrateV2(current)
  }
  // Anything else — a future version this build does not understand — fails
  // closed. Guessing at its contents could silently drop queued mutations,
  // which are the only copy of work the server has never seen.
  if (current.dataSchemaVersion !== DATA_SCHEMA_VERSION) throw new VaultError()
  if (
    !Array.isArray(current.outbox) ||
    !Array.isArray(current.resourceMappings) ||
    !Array.isArray(current.syncHistory) ||
    !current.syncPreferences ||
    typeof current.syncPreferences.autoSync !== 'boolean'
  ) {
    throw new VaultError()
  }
  return current as VaultPayload
}

/**
 * Upgrades a read-only vault in memory.
 *
 * A V1 vault is perfectly valid data — it simply predates the outbox. Refusing
 * it would force the user to delete and rebuild a vault that has nothing wrong
 * with it, so it is carried forward with its owner, timestamp and cached
 * queries intact and an empty queue alongside.
 */
function migrateV1(payload: Partial<VaultPayload>): Partial<VaultPayload> {
  return {
    dataSchemaVersion: 2,
    owner: payload.owner as VaultOwner,
    preparedAt: payload.preparedAt as string,
    queries: payload.queries as OfflineQuery[],
    outbox: [],
    resourceMappings: [],
    syncHistory: [],
    syncPreferences: { ...DEFAULT_SYNC_PREFERENCES },
  }
}

/**
 * Labels a pre-multi-currency copy with the currency it always had.
 *
 * The cached queries are migrated per known shape — see `dataMigration` — and
 * the derived ones that cannot be repaired are dropped rather than guessed at.
 *
 * The outbox is deliberately untouched. A queued mutation's payload is the
 * canonical request the server already hashed into a receipt; adding a field to
 * it would change that hash, and a resend whose response was lost would come
 * back as a reused idempotency key. Its currency is interpreted as BRL at read
 * time instead, by whatever displays or projects it.
 */
function migrateV2(payload: Partial<VaultPayload>): Partial<VaultPayload> {
  return {
    ...payload,
    dataSchemaVersion: 3,
    queries: migrateQueriesToV3((payload.queries ?? []) as OfflineQuery[]),
  }
}

/**
 * True when the stored record's envelope or payload predates the current shape.
 *
 * Both versions matter. The envelope has been V2 since the outbox landed, so
 * checking it alone would decide that a V2 record holding a V2 payload needs
 * nothing — and the pre-multi-currency data inside it would never be upgraded.
 */
export function needsMigration(vault: EncryptedVault): boolean {
  return (
    vault.vaultSchemaVersion < VAULT_SCHEMA_VERSION ||
    vault.dataSchemaVersion < DATA_SCHEMA_VERSION
  )
}

async function seal(
  payload: VaultPayload,
  session: VaultSession,
  createdAt: string,
): Promise<EncryptedVault> {
  // A fresh IV for every write. Reusing one under the same AES-GCM key is a
  // catastrophic failure, and this vault is rewritten on every queued mutation.
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const plaintext = new TextEncoder().encode(JSON.stringify(payload))
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, session.key, plaintext)
  return {
    vaultSchemaVersion: VAULT_SCHEMA_VERSION,
    dataSchemaVersion: DATA_SCHEMA_VERSION,
    kdf: 'PBKDF2-HMAC-SHA-256',
    iterations: session.iterations,
    salt: bytesToBase64(session.salt),
    iv: bytesToBase64(iv),
    ciphertext: bytesToBase64(new Uint8Array(ciphertext)),
    createdAt,
    updatedAt: new Date().toISOString(),
  }
}

/** Creates a brand-new vault and the in-memory session that can rewrite it. */
export async function createVault(
  payload: VaultPayload,
  password: string,
  createdAt = new Date().toISOString(),
): Promise<{ encrypted: EncryptedVault; session: VaultSession }> {
  if (password.length < 12) throw new VaultError()
  const salt = crypto.getRandomValues(new Uint8Array(16))
  const session: VaultSession = {
    key: await deriveKey(password, salt, PBKDF2_ITERATIONS),
    salt,
    iterations: PBKDF2_ITERATIONS,
    createdAt,
  }
  return { encrypted: await seal(payload, session, createdAt), session }
}

/**
 * Decrypts and returns both the payload and the session that can re-seal it.
 *
 * The payload comes back already migrated when the record was V1; the caller
 * decides when to persist that upgrade, so a failed write leaves the original
 * ciphertext in place and nothing is lost.
 */
export async function unlockVault(
  vault: EncryptedVault,
  password: string,
): Promise<{ payload: VaultPayload; session: VaultSession }> {
  try {
    if (
      vault.vaultSchemaVersion > VAULT_SCHEMA_VERSION ||
      vault.vaultSchemaVersion < 1 ||
      vault.kdf !== 'PBKDF2-HMAC-SHA-256' ||
      vault.iterations !== PBKDF2_ITERATIONS
    ) {
      throw new VaultError()
    }
    const salt = base64ToBytes(vault.salt)
    const iv = base64ToBytes(vault.iv)
    if (salt.byteLength !== 16 || iv.byteLength !== 12) throw new VaultError()
    const key = await deriveKey(password, salt, vault.iterations)
    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv },
      key,
      base64ToBytes(vault.ciphertext),
    )
    const payload = validatePayload(JSON.parse(new TextDecoder().decode(plaintext)))
    return {
      payload,
      session: { key, salt, iterations: vault.iterations, createdAt: vault.createdAt },
    }
  } catch {
    // One generic failure for a wrong password, a tampered record and an
    // unreadable schema alike: telling them apart would leak whether a guess
    // was close.
    throw new VaultError()
  }
}

/** Re-seals an unlocked vault without ever needing the password again. */
export function resealVault(payload: VaultPayload, session: VaultSession): Promise<EncryptedVault> {
  return seal(payload, session, session.createdAt)
}

/**
 * Reads a record with a key this tab already holds.
 *
 * Two tabs share one stored vault but each keeps its own decrypted copy in
 * memory, so a queue drained in one leaves the other showing work that is
 * already gone. This is how the second one catches up without asking for the
 * offline password again — the same authenticated decryption as `unlockVault`,
 * minus the derivation, and it still fails closed on a record this key cannot
 * open or a schema it does not understand.
 */
export async function reopenVault(
  vault: EncryptedVault,
  session: VaultSession,
): Promise<VaultPayload> {
  try {
    if (vault.vaultSchemaVersion > VAULT_SCHEMA_VERSION || vault.vaultSchemaVersion < 1) {
      throw new VaultError()
    }
    const iv = base64ToBytes(vault.iv)
    if (iv.byteLength !== 12) throw new VaultError()
    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv },
      session.key,
      base64ToBytes(vault.ciphertext),
    )
    return validatePayload(JSON.parse(new TextDecoder().decode(plaintext)))
  } catch {
    throw new VaultError()
  }
}

/** An empty payload at the current data schema, for a freshly enabled vault. */
export function emptyPayload(
  owner: VaultOwner,
  preparedAt: string,
  queries: OfflineQuery[],
): VaultPayload {
  return {
    dataSchemaVersion: DATA_SCHEMA_VERSION,
    owner,
    preparedAt,
    queries,
    outbox: [],
    resourceMappings: [],
    syncHistory: [],
    syncPreferences: { ...DEFAULT_SYNC_PREFERENCES },
  }
}
