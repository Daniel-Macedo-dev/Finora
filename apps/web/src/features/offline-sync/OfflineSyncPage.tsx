import { useState } from 'react'
import { RefreshCw } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import ConfirmDialog from '../../components/ConfirmDialog'
import { EmptyState } from '../../components/states'
import { useVault } from '../../offline/VaultProvider'
import { useConnection } from '../../offline/connection'
import type { OutboxEntry } from '../../offline/outbox/types'
import SyncEntryRow from './SyncEntryRow'
import './offline-sync.css'

function timestamp(value: string): string {
  return new Date(value).toLocaleString('pt-BR')
}

export default function OfflineSyncPage() {
  const vault = useVault()
  const connection = useConnection()
  const [expanded, setExpanded] = useState<string | null>(null)
  const [confirming, setConfirming] = useState<OutboxEntry | null>(null)
  const [applying, setApplying] = useState<OutboxEntry | null>(null)
  const [feedback, setFeedback] = useState<string | null>(null)
  const [password, setPassword] = useState('')

  const online = connection.state === 'ONLINE'
  const unlocked = vault.state === 'UNLOCKED_ONLINE' || vault.state === 'UNLOCKED_OFFLINE'
  const canSync = online && vault.state === 'UNLOCKED_ONLINE' && !vault.replaying

  async function syncNow() {
    setFeedback(null)
    const outcome = await vault.replay()
    if (!outcome) {
      setFeedback('Outra aba já está sincronizando. Aguarde alguns instantes.')
      return
    }
    if (outcome.transportError) {
      setFeedback(`Não foi possível concluir agora: ${outcome.transportError}`)
      return
    }
    setFeedback(
      `${outcome.applied} operação(ões) sincronizada(s), ${outcome.conflicts} conflito(s), `
      + `${outcome.rejected} recusada(s).`,
    )
  }

  if (!unlocked) {
    return (
      <>
        <PageHeader
          title="Sincronização offline"
          description="Alterações feitas sem conexão e o estado de envio de cada uma."
        />
        <EmptyState
          title={
            vault.state === 'CORRUPTED' ? 'Cópia offline ilegível' : 'Cópia offline bloqueada'
          }
          description={
            vault.state === 'CORRUPTED'
              ? 'A cópia deste dispositivo não pôde ser lida com a senha informada. Tente de '
                + 'novo: o Finora não consegue saber se ela guarda alterações ainda não enviadas '
                + 'enquanto ela não abrir.'
              : 'Quaisquer alterações pendentes continuam guardadas e criptografadas neste '
                + 'dispositivo. Desbloqueie a cópia offline para vê-las e sincronizá-las.'
          }
        />
        {/* The unlock screen only appears when the browser is offline, so
            without this there is no way back into the queue once the
            connection returns — the one moment it can actually be sent.
            CORRUPTED is offered the same form: by far its most common cause is
            a mistyped password, and refusing to let the user try again would
            leave deleting the copy as the only way forward. */}
        {(vault.state === 'LOCKED' || vault.state === 'CORRUPTED') && (
          <form
            className="card sync-unlock"
            onSubmit={(event) => {
              event.preventDefault()
              void vault.unlock(password, online).catch(() => setPassword(''))
            }}
          >
            <label htmlFor="sync-unlock-password">Senha offline</label>
            <input
              id="sync-unlock-password"
              className="input"
              type="password"
              autoComplete="off"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              aria-describedby={vault.error ? 'sync-unlock-error' : undefined}
            />
            {vault.error && (
              <p id="sync-unlock-error" role="alert" className="field-error">
                {vault.error}
              </p>
            )}
            <button
              type="submit"
              className="btn btn-primary"
              disabled={password.length === 0}
            >
              Desbloquear cópia offline
            </button>
          </form>
        )}
      </>
    )
  }

  return (
    <>
      <PageHeader
        title="Sincronização offline"
        description="Alterações feitas sem conexão e o estado de envio de cada uma."
        actions={
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => void syncNow()}
            disabled={!canSync || vault.counts.total === 0}
          >
            <RefreshCw size={16} aria-hidden="true" />
            Sincronizar agora
          </button>
        }
      />

      <section className="card sync-summary" aria-label="Resumo da sincronização">
        <dl className="sync-summary-list">
          <div>
            <dt>Pendentes</dt>
            <dd>{vault.counts.pending}</dd>
          </div>
          <div>
            <dt>Aguardando outro item</dt>
            <dd>{vault.counts.blocked}</dd>
          </div>
          <div>
            <dt>Conflitos</dt>
            <dd>{vault.counts.conflicts}</dd>
          </div>
          <div>
            <dt>Falhas</dt>
            <dd>{vault.counts.permanent + vault.counts.retryable}</dd>
          </div>
          <div>
            <dt>Conexão</dt>
            <dd>{online ? 'Online' : 'Offline'}</dd>
          </div>
          <div>
            <dt>Cópia offline</dt>
            <dd>{vault.state === 'UNLOCKED_ONLINE' ? 'Desbloqueada' : 'Desbloqueada (offline)'}</dd>
          </div>
          <div>
            <dt>Última sincronização</dt>
            <dd>{vault.lastSyncAt ? timestamp(vault.lastSyncAt) : 'Ainda não sincronizado'}</dd>
          </div>
        </dl>
        {/* Restrained live region: one message per completed run, never a
            countdown tick, so a screen reader is not interrupted repeatedly. */}
        <p role="status" className="sync-feedback">
          {vault.replaying ? 'Sincronizando alterações pendentes…' : feedback}
        </p>
        {!online && (
          <p className="sync-note">
            A sincronização acontece apenas com o aplicativo aberto, com conexão e com a cópia
            offline desbloqueada. Não há sincronização em segundo plano.
          </p>
        )}
      </section>

      {vault.entries.length === 0 ? (
        <EmptyState
          title="Nenhuma alteração pendente"
          description="Tudo o que você registrou offline já foi enviado ao servidor."
        />
      ) : (
        <ul className="sync-list">
          {vault.entries.map((entry) => (
            <SyncEntryRow
              key={entry.clientMutationId}
              entry={entry}
              expanded={expanded === entry.clientMutationId}
              onToggle={() =>
                setExpanded((current) =>
                  current === entry.clientMutationId ? null : entry.clientMutationId,
                )
              }
              onConfirmDiscard={setConfirming}
              onConfirmApplyLocal={setApplying}
            />
          ))}
        </ul>
      )}

      <ConfirmDialog
        open={confirming !== null}
        title="Descartar alteração offline"
        message={
          `Descartar "${confirming?.label ?? ''}"? Esta alteração ainda não chegou ao servidor `
          + 'e será perdida definitivamente.'
        }
        confirmLabel="Descartar definitivamente"
        danger
        onConfirm={() => {
          if (confirming) void vault.discard(confirming.clientMutationId)
          setConfirming(null)
        }}
        onCancel={() => setConfirming(null)}
      />

      <ConfirmDialog
        open={applying !== null}
        title="Aplicar sua alteração"
        message={
          'Sua alteração vai substituir o que está salvo no servidor. '
          + 'Confirme apenas se quiser sobrescrever o valor mostrado na coluna do servidor.'
        }
        confirmLabel="Aplicar minha alteração"
        danger
        onConfirm={() => {
          if (applying) void vault.resolve(applying.clientMutationId, 'APPLY_LOCAL')
          setApplying(null)
          setExpanded(null)
        }}
        onCancel={() => setApplying(null)}
      />
    </>
  )
}
