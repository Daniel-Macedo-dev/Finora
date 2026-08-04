import { useState, type FormEvent } from 'react'
import { LockKeyhole, Trash2 } from 'lucide-react'
import { useVault } from './VaultProvider'
import VaultDeletionDialog from './VaultDeletionDialog'
import { useVaultRemoval } from './useVaultRemoval'
import './offline.css'

export default function OfflineUnlock() {
  const vault = useVault()
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const removal = useVaultRemoval(
    'A cópia offline não pôde ser excluída deste dispositivo. Tente novamente.',
  )

  async function submit(event: FormEvent) {
    event.preventDefault()
    try { await vault.unlock(password, false) } catch { setPassword('') }
  }

  return (
    <main className="offline-unlock">
      <section className="card offline-unlock-card" aria-labelledby="offline-unlock-title">
        <LockKeyhole size={32} aria-hidden="true" />
        <h1 id="offline-unlock-title">Desbloquear dados offline</h1>
        <p>Você está sem conexão. Use a senha offline criada neste dispositivo. Isso não autentica uma sessão no servidor.</p>
        <form onSubmit={(event) => void submit(event)}>
          <label htmlFor="offline-password">Senha offline</label>
          <div className="offline-password-row">
            <input id="offline-password" className="input" type={showPassword ? 'text' : 'password'} autoComplete="off" value={password} onChange={(event) => setPassword(event.target.value)} aria-describedby={vault.error ? 'offline-unlock-error' : undefined} />
            <button type="button" className="btn btn-secondary" aria-pressed={showPassword} onClick={() => setShowPassword((value) => !value)}>{showPassword ? 'Ocultar' : 'Mostrar'}</button>
          </div>
          {vault.error && <p id="offline-unlock-error" role="alert" className="field-error">{vault.error}</p>}
          <button type="submit" className="btn btn-primary" disabled={vault.state === 'UNLOCKING' || password.length === 0}>{vault.state === 'UNLOCKING' ? 'Desbloqueando…' : 'Desbloquear'}</button>
        </form>
        {/* This screen is reached with the copy unreadable by definition, so the
            deletion offered here is exactly the case that must never be a single
            click: the recovery path out of a forgotten password, and also the
            fastest way to destroy work nobody can currently see. */}
        <button
          type="button"
          className="btn btn-danger"
          disabled={removal.settling || removal.removing}
          onClick={removal.ask}
        >
          <Trash2 size={16} aria-hidden="true" /> Excluir cópia local
        </button>
      </section>
      <VaultDeletionDialog
        open={removal.confirming}
        risk={removal.risk}
        counts={vault.counts}
        intent="DISABLE_OFFLINE"
        busy={removal.removing}
        failure={removal.failure}
        onCancel={removal.dismiss}
        // No connection here, so there is nowhere to review: the unlock form is
        // already on this screen and closing the dialog returns to it.
        onReview={removal.dismiss}
        onConfirm={() => void removal.remove()}
      />
    </main>
  )
}
