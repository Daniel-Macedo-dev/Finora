import type { VaultState } from './VaultProvider'

/**
 * How much the application actually knows about unsynchronized work, at the
 * moment something is about to delete the local encrypted copy.
 *
 * The decryption key lives only in memory, so the ordinary state after any
 * reload is a vault that exists, holds ciphertext, and cannot be read. In that
 * state the decrypted outbox is empty because there is nothing to decrypt it
 * with — not because there is nothing in it. Treating those two as the same
 * fact is exactly what lets a logout silently delete work the server has never
 * seen, and there is no way to tell them apart without either the password or a
 * plaintext marker in the record. The marker is not an option: it would put the
 * existence of pending work outside the authenticated ciphertext.
 *
 * So the unknown is named instead of guessed. Nothing here reads storage or
 * decrypts anything — the risk is derived from state the provider already
 * holds, which is why asking the question costs nothing and cannot leak what
 * the ciphertext is hiding.
 */
export type DestructiveRisk =
  /** No local copy exists; there is nothing to lose. */
  | 'NO_LOCAL_COPY'
  /** Readable, and its queue is known to be empty. */
  | 'KNOWN_SAFE'
  /** Readable, and it holds work the server has not accepted. */
  | 'KNOWN_PENDING'
  /** Exists, cannot be read, may hold anything. */
  | 'UNKNOWN_LOCKED'
  /** Exists, failed to decrypt or validate, may hold anything. */
  | 'UNKNOWN_CORRUPTED'
  /** Still settling. Nothing may be deleted on a state this uncertain. */
  | 'BUSY'

export function destructiveRiskOf(state: VaultState, pendingTotal: number): DestructiveRisk {
  switch (state) {
    case 'ABSENT':
      return 'NO_LOCAL_COPY'
    case 'LOADING':
    case 'UNLOCKING':
      return 'BUSY'
    case 'LOCKED':
      return 'UNKNOWN_LOCKED'
    case 'CORRUPTED':
      return 'UNKNOWN_CORRUPTED'
    case 'UNLOCKED_ONLINE':
    case 'UNLOCKED_OFFLINE':
      return pendingTotal > 0 ? 'KNOWN_PENDING' : 'KNOWN_SAFE'
  }
}

/**
 * Whether deleting the local copy could destroy work that exists nowhere else.
 *
 * Deliberately true for both unknowns. A locked vault whose queue happens to be
 * empty will be warned about for nothing; that false positive costs the user one
 * dialog, while the alternative costs them changes they cannot get back.
 */
export function mayDestroyUnsyncedWork(risk: DestructiveRisk): boolean {
  return risk === 'KNOWN_PENDING' || risk === 'UNKNOWN_LOCKED' || risk === 'UNKNOWN_CORRUPTED'
}

/** The two states whose contents cannot be inspected at all. */
export function isUnknownRisk(risk: DestructiveRisk): boolean {
  return risk === 'UNKNOWN_LOCKED' || risk === 'UNKNOWN_CORRUPTED'
}
