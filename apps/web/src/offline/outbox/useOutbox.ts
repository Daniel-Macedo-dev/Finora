import { useOptionalVault } from '../VaultProvider'
import type { QueueRequest } from './queue'
import { newId } from './types'

/** What a domain hook gets back instead of a server response while offline. */
export interface QueuedMutation {
  queued: true
  clientResourceId: string
  clientMutationId: string | null
}

export function isQueued(value: unknown): value is QueuedMutation {
  return !!value && typeof value === 'object' && (value as QueuedMutation).queued === true
}

/**
 * The seam between a domain mutation hook and the encrypted queue.
 *
 * Deliberately opt-in per hook rather than built into the API client. If the
 * client queued every unsafe request automatically, enabling offline mode would
 * silently enable it for statement imports, invoice payments and recurring
 * reversals too — workflows whose correctness depends on server state at the
 * moment they run. The allowlist has to be a decision someone made per hook,
 * and the client's blanket refusal stays as the backstop for everything else.
 */
export function useOfflineOutbox() {
  const vault = useOptionalVault()
  const enabled = vault?.state === 'UNLOCKED_OFFLINE'

  return {
    /** True only while the app is offline with the vault unlocked. */
    enabled,
    /** Fresh identity for a resource being created offline. */
    newResourceId: newId,
    async enqueue(request: QueueRequest): Promise<QueuedMutation> {
      if (!vault) throw new Error('A cópia offline não está disponível.')
      const entry = await vault.queue(request)
      return {
        queued: true,
        clientResourceId: request.clientResourceId,
        clientMutationId: entry?.clientMutationId ?? null,
      }
    },
  }
}

/** The message every unsupported control shows while offline. */
export const UNSUPPORTED_OFFLINE_MESSAGE =
  'Esta ação ainda exige conexão e não pode ser adicionada à fila offline.'
