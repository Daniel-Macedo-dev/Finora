import { useState, type FormEvent } from 'react'
import { LockKeyhole, Trash2 } from 'lucide-react'
import { useVault } from './VaultProvider'
import './offline.css'

export default function OfflineUnlock() {
  const vault = useVault()
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)

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
        <button type="button" className="btn btn-danger" onClick={() => { if (window.confirm('Excluir a cópia offline deste dispositivo? Os dados do servidor não serão alterados.')) void vault.remove() }}><Trash2 size={16} aria-hidden="true" /> Excluir cópia local</button>
      </section>
    </main>
  )
}
