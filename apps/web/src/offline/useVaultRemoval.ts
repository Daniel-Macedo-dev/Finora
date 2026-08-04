import { useCallback, useState } from 'react'
import { useVault } from './VaultProvider'

/**
 * The confirmation state shared by every screen that can delete the local copy.
 *
 * Signing out, turning offline access off and discarding an unreadable copy all
 * ran their own copy of this: the same three pieces of state and the same
 * try/catch, written out three times. The part that must not drift is the
 * catch — a removal that failed has to say so instead of being followed by a
 * cheerful navigation — so it lives in one place and the callers decide only
 * what happens afterwards.
 */
export function useVaultRemoval(failureMessage: string) {
  const vault = useVault()
  const [confirming, setConfirming] = useState(false)
  const [removing, setRemoving] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  const ask = useCallback(() => {
    setFailure(null)
    setConfirming(true)
  }, [])

  const dismiss = useCallback(() => {
    setConfirming(false)
    setFailure(null)
  }, [])

  /** Resolves true only once the encrypted record is actually gone. */
  const remove = useCallback(async (): Promise<boolean> => {
    setRemoving(true)
    setFailure(null)
    try {
      await vault.remove()
      setConfirming(false)
      return true
    } catch {
      // Kept open, and told the truth: the record is still on this device.
      setFailure(failureMessage)
      setConfirming(true)
      return false
    } finally {
      setRemoving(false)
    }
  }, [failureMessage, vault])

  return {
    risk: vault.destructiveRisk,
    /** True while the vault state is too unsettled to judge. */
    settling: vault.destructiveRisk === 'BUSY',
    confirming,
    removing,
    failure,
    ask,
    dismiss,
    remove,
  }
}
