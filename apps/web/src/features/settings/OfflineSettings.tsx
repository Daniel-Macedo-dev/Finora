import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useCurrentUser } from '../auth/api'
import { useVault } from '../../offline/VaultProvider'
import VaultDeletionDialog from '../../offline/VaultDeletionDialog'
import { usePwa } from '../../pwa/PwaProvider'

export default function OfflineSettings() {
  const currentUser = useCurrentUser()
  const vault = useVault()
  const pwa = usePwa()
  const navigate = useNavigate()
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [feedback, setFeedback] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [confirmingRemoval, setConfirmingRemoval] = useState(false)
  const [removing, setRemoving] = useState(false)
  const [removalFailed, setRemovalFailed] = useState<string | null>(null)

  const settling = vault.destructiveRisk === 'BUSY'

  async function removeVault() {
    setRemoving(true)
    setRemovalFailed(null)
    try {
      await vault.remove()
      setConfirmingRemoval(false)
      // No success message on purpose: removing the vault clears the query
      // cache, which remounts this screen and would throw the message away
      // mid-sentence. The status list above says "Desativada" and the enable
      // form comes back — both survive the remount, which a toast does not.
    } catch {
      // Never reported as done when the record is still there.
      setRemovalFailed(
        'A cópia offline não pôde ser excluída deste dispositivo. Ela continua bloqueada aqui. '
        + 'Tente novamente.',
      )
    } finally {
      setRemoving(false)
    }
  }

  async function enable(event: FormEvent) {
    event.preventDefault()
    if (!currentUser.data) return
    if (password.length < 12) { setFeedback('Use pelo menos 12 caracteres na senha offline.'); return }
    if (password !== confirmation) { setFeedback('As senhas offline não coincidem.'); return }
    setBusy(true); setFeedback(null)
    try { await vault.enable(currentUser.data, password); setFeedback('Acesso offline ativado neste dispositivo.'); setPassword(''); setConfirmation('') }
    catch (error) { setFeedback(error instanceof Error ? error.message : 'Não foi possível criar a cópia offline.') }
    finally { setBusy(false) }
  }

  async function refresh() {
    if (!currentUser.data || password.length === 0) { setFeedback('Digite a senha offline para atualizar a cópia.'); return }
    setBusy(true); setFeedback(null)
    try { await vault.refresh(currentUser.data, password); setFeedback('Dados offline atualizados.'); setPassword('') }
    catch (error) { setFeedback(error instanceof Error ? error.message : 'Não foi possível atualizar os dados offline.') }
    finally { setBusy(false) }
  }

  const hasVault = vault.state !== 'ABSENT' && vault.state !== 'LOADING'
  return (
    <section className="card settings-section" aria-label="Aplicativo e acesso offline">
      <h2 className="panel-title">Aplicativo e acesso offline</h2>
      <dl className="offline-status-list">
        <div><dt>Instalação</dt><dd>{pwa.installState === 'installed' ? 'Instalado' : pwa.installState === 'accepted' ? 'Solicitação aceita; aguardando confirmação' : pwa.installState === 'available' ? 'Disponível' : 'Use o menu do navegador quando compatível'}</dd></div>
        <div><dt>Service Worker</dt><dd>{pwa.registrationError ? 'Falha no registro' : pwa.serviceWorkerReady ? 'Ativo' : 'Aguardando'}</dd></div>
        <div><dt>Cópia offline</dt><dd>{vault.state === 'ABSENT' ? 'Desativada' : vault.state === 'LOCKED' ? 'Bloqueada' : vault.state.startsWith('UNLOCKED') ? 'Desbloqueada' : vault.state === 'CORRUPTED' ? 'Indisponível' : 'Verificando'}</dd></div>
        {vault.updatedAt && <div><dt>Última atualização</dt><dd>{new Date(vault.updatedAt).toLocaleString('pt-BR')}</dd></div>}
        {vault.size !== null && <div><dt>Tamanho criptografado</dt><dd>{Math.ceil(vault.size / 1024)} KB</dd></div>}
        {/* Zero would be a lie while the copy is locked: the queue lives inside
            the ciphertext, so "none" and "unreadable" are different answers. */}
        <div><dt>Alterações offline pendentes</dt><dd>{vault.destructiveRisk === 'UNKNOWN_LOCKED' ? 'Desconhecido (cópia bloqueada)' : vault.destructiveRisk === 'UNKNOWN_CORRUPTED' ? 'Desconhecido (cópia ilegível)' : vault.counts.total}</dd></div>
        <div><dt>Última sincronização</dt><dd>{vault.lastSyncAt ? new Date(vault.lastSyncAt).toLocaleString('pt-BR') : 'Ainda não sincronizado'}</dd></div>
      </dl>
      {/* Never claim "tudo sincronizado" while a conflict or a failure is
          waiting: that is exactly when the user most needs to look. */}
      {vault.counts.total > 0 && (
        <p className="settings-note">
          {vault.counts.conflicts + vault.counts.permanent > 0
            ? 'Há alterações offline que precisam da sua decisão.'
            : 'Há alterações offline aguardando envio.'}{' '}
          <Link to="/offline-sync">Abrir central de sincronização</Link>
        </p>
      )}
      {pwa.installState === 'available' && <button type="button" className="btn btn-primary" onClick={() => void pwa.install()}>Instalar Finora</button>}
      {pwa.installState === 'unsupported' && <p className="settings-note">No iPhone/iPad, use Compartilhar → Adicionar à Tela de Início. Alguns navegadores não oferecem instalação.</p>}
      <p className="settings-note">O acesso offline é opcional. Dados financeiros selecionados ficam criptografados neste navegador, podem ficar desatualizados e podem ser removidos pelo navegador, sistema operacional ou por você. Transações comuns, orçamentos, metas, itens da lista de desejos, opções de compra e observações de preço podem ser registrados offline e enviados depois; extratos, cartões, faturas, recorrentes e aportes continuam exigindo conexão.</p>
      <p className="settings-note">A sincronização acontece <strong>somente com o aplicativo aberto, com conexão e com a cópia offline desbloqueada</strong>. O Finora não sincroniza em segundo plano.</p>
      <p className="settings-note"><strong>A senha offline é separada da senha da conta e não pode ser recuperada pelo Finora.</strong> Se perdê-la, exclua e recrie a cópia local. Sair da conta também exclui a cópia.</p>
      {!hasVault ? (
        <form onSubmit={(event) => void enable(event)} className="offline-settings-form">
          <label htmlFor="enable-offline-password">Senha offline (mínimo de 12 caracteres)</label>
          <input id="enable-offline-password" className="input" type={showPassword ? 'text' : 'password'} autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} />
          <label htmlFor="confirm-offline-password">Confirmar senha offline</label>
          <input id="confirm-offline-password" className="input" type={showPassword ? 'text' : 'password'} autoComplete="new-password" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} />
          <button type="button" className="btn btn-secondary" aria-pressed={showPassword} onClick={() => setShowPassword((value) => !value)}>{showPassword ? 'Ocultar senhas' : 'Mostrar senhas'}</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>Ativar acesso offline neste dispositivo</button>
        </form>
      ) : (
        <div className="offline-settings-form">
          <label htmlFor="refresh-offline-password">Senha offline para atualizar</label>
          <input id="refresh-offline-password" className="input" type={showPassword ? 'text' : 'password'} autoComplete="off" value={password} onChange={(event) => setPassword(event.target.value)} />
          <button type="button" className="btn btn-secondary" onClick={() => void refresh()} disabled={busy}>Atualizar dados offline</button>
          <label className="offline-autosync">
            <input
              type="checkbox"
              checked={vault.autoSync}
              onChange={(event) => void vault.setAutoSync(event.target.checked)}
              disabled={vault.state !== 'UNLOCKED_ONLINE' && vault.state !== 'UNLOCKED_OFFLINE'}
            />
            Sincronizar automaticamente ao reconectar
          </label>
          <Link to="/offline-sync" className="btn btn-secondary">Abrir central de sincronização</Link>
          {/* Allowed offline: locking discards a key held in memory and touches
              nothing on the server. Left to the blanket rule this button was
              disabled with a tooltip saying the action needs a connection —
              which is false, and contradicted by the shell offering the very
              same action a few pixels away. */}
          <button data-offline-allowed="true" type="button" className="btn btn-secondary" onClick={vault.lock}>Bloquear dados offline</button>
          {/* Same guard as signing out, for the same reason: this button deletes
              the same record, and a locked copy is no more inspectable here. */}
          <button
            type="button"
            className="btn btn-danger"
            disabled={settling || removing}
            aria-describedby={settling ? 'disable-offline-settling' : undefined}
            onClick={() => { setRemovalFailed(null); setConfirmingRemoval(true) }}
          >
            Desativar e excluir cópia local
          </button>
          {settling && (
            <span id="disable-offline-settling" role="status" className="settings-note">
              Verificando a cópia offline deste dispositivo…
            </span>
          )}
        </div>
      )}
      <VaultDeletionDialog
        open={confirmingRemoval}
        risk={vault.destructiveRisk}
        counts={vault.counts}
        intent="DISABLE_OFFLINE"
        busy={removing}
        failure={removalFailed}
        onCancel={() => { setConfirmingRemoval(false); setRemovalFailed(null) }}
        onReview={() => { setConfirmingRemoval(false); navigate('/offline-sync') }}
        onConfirm={() => void removeVault()}
      />
      {feedback && <p role="status" className="settings-feedback">{feedback}</p>}
    </section>
  )
}
