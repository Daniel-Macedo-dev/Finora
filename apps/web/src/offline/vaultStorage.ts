import type { EncryptedVault } from './vaultCrypto'

const DB_NAME = 'finora-offline-vault'
const STORE_NAME = 'vault'
const RECORD_KEY = 'single-vault'

export class VaultStorageError extends Error {
  constructor(message = 'Não foi possível acessar o armazenamento seguro deste navegador.') {
    super(message)
    this.name = 'VaultStorageError'
  }
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (!('indexedDB' in window)) {
      reject(new VaultStorageError())
      return
    }
    const request = indexedDB.open(DB_NAME, 1)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME)
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(new VaultStorageError())
    request.onblocked = () => reject(new VaultStorageError())
  })
}

async function transaction<T>(
  mode: IDBTransactionMode,
  operation: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  const db = await openDatabase()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, mode)
    const request = operation(tx.objectStore(STORE_NAME))
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(new VaultStorageError())
    tx.onabort = () => reject(new VaultStorageError())
    tx.oncomplete = () => db.close()
  })
}

export async function loadVault(): Promise<EncryptedVault | null> {
  const value = await transaction<EncryptedVault | undefined>('readonly', (store) => store.get(RECORD_KEY))
  return value ?? null
}

export async function saveVault(vault: EncryptedVault): Promise<void> {
  await transaction<IDBValidKey>('readwrite', (store) => store.put(vault, RECORD_KEY))
}

export async function deleteVault(): Promise<void> {
  await transaction<undefined>('readwrite', (store) => store.delete(RECORD_KEY))
}

export function encryptedSize(vault: EncryptedVault): number {
  return new Blob([JSON.stringify(vault)]).size
}
