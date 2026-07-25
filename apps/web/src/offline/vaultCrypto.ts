export const VAULT_SCHEMA_VERSION = 1
export const DATA_SCHEMA_VERSION = 1
export const PBKDF2_ITERATIONS = 310_000

export interface OfflineQuery {
  queryKey: readonly unknown[]
  data: unknown
  dataUpdatedAt: number
}

export interface VaultPayload {
  dataSchemaVersion: number
  owner: { id: number; displayName: string; email: string }
  preparedAt: string
  queries: OfflineQuery[]
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

async function deriveKey(password: string, salt: Uint8Array<ArrayBuffer>, iterations: number): Promise<CryptoKey> {
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
    false,
    ['encrypt', 'decrypt'],
  )
}

function validatePayload(value: unknown): VaultPayload {
  if (!value || typeof value !== 'object') throw new VaultError()
  const payload = value as Partial<VaultPayload>
  if (
    payload.dataSchemaVersion !== DATA_SCHEMA_VERSION ||
    !payload.owner || typeof payload.owner.id !== 'number' ||
    typeof payload.owner.displayName !== 'string' || typeof payload.owner.email !== 'string' ||
    typeof payload.preparedAt !== 'string' || !Array.isArray(payload.queries)
  ) throw new VaultError()
  return payload as VaultPayload
}

export async function encryptVault(
  payload: VaultPayload,
  password: string,
  createdAt = new Date().toISOString(),
): Promise<EncryptedVault> {
  if (password.length < 12) throw new VaultError()
  const salt = crypto.getRandomValues(new Uint8Array(16))
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const key = await deriveKey(password, salt, PBKDF2_ITERATIONS)
  const plaintext = new TextEncoder().encode(JSON.stringify(payload))
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, plaintext)
  const updatedAt = new Date().toISOString()
  return {
    vaultSchemaVersion: VAULT_SCHEMA_VERSION,
    dataSchemaVersion: DATA_SCHEMA_VERSION,
    kdf: 'PBKDF2-HMAC-SHA-256',
    iterations: PBKDF2_ITERATIONS,
    salt: bytesToBase64(salt),
    iv: bytesToBase64(iv),
    ciphertext: bytesToBase64(new Uint8Array(ciphertext)),
    createdAt,
    updatedAt,
  }
}

export async function decryptVault(vault: EncryptedVault, password: string): Promise<VaultPayload> {
  try {
    if (
      vault.vaultSchemaVersion !== VAULT_SCHEMA_VERSION ||
      vault.dataSchemaVersion !== DATA_SCHEMA_VERSION ||
      vault.kdf !== 'PBKDF2-HMAC-SHA-256' ||
      vault.iterations !== PBKDF2_ITERATIONS
    ) throw new VaultError()
    const salt = base64ToBytes(vault.salt)
    const iv = base64ToBytes(vault.iv)
    if (salt.byteLength !== 16 || iv.byteLength !== 12) throw new VaultError()
    const key = await deriveKey(password, salt, vault.iterations)
    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv },
      key,
      base64ToBytes(vault.ciphertext),
    )
    return validatePayload(JSON.parse(new TextDecoder().decode(plaintext)))
  } catch {
    throw new VaultError()
  }
}
