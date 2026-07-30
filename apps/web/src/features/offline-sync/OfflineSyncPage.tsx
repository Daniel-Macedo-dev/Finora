import { useState } from 'react'
import { RefreshCw, Trash2, RotateCcw } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import ConfirmDialog from '../../components/ConfirmDialog'
import { EmptyState } from '../../components/states'
import { useVault } from '../../offline/VaultProvider'
import { useConnection } from '../../offline/connection'

import type { OutboxEntry, OutboxStatus, ResolutionOption } from '../../offline/outbox/types'
import ConflictComparison from './ConflictComparison'
import './offline-sync.css'

const RESOURCE_LABELS: Record<OutboxEntry['resourceType'], string> = {
  TRANSACTION: 'Transação',
  BUDGET: 'Orçamento',
  GOAL: 'Meta',
  WISHLIST_ITEM: 'Item da lista de desejos',
  PURCHASE_OPTION: 'Opção de compra',
  PRICE_SNAPSHOT: 'Observação de preço',
}

const OPERATION_LABELS: Record<OutboxEntry['operation'], string> = {
  CREATE: 'Criação',
  UPDATE: 'Edição',
  DELETE: 'Exclusão',
}

const STATUS_LABELS: Record<OutboxStatus, string> = {
  PENDING: 'Pendente',
  BLOCKED: 'Aguardando outro item',
  SYNCING: 'Sincronizando',
  CONFLICT: 'Conflito',
  FAILED_RETRYABLE: 'Falha temporária',
  FAILED_PERMANENT: 'Falha',
  APPLIED: 'Sincronizado',
  DISCARDED: 'Descartado',
}

const RESOLUTION_LABELS: Record<ResolutionOption, string> = {
  KEEP_SERVER: 'Manter o do servidor',
  APPLY_LOCAL: 'Aplicar minha alteração',
  EDIT_AND_RETRY: 'Editar e tentar de novo',
  DISCARD_LOCAL: 'Descartar minha alteração',
}

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
          title="Cópia offline bloqueada"
          description={
            'As alterações pendentes continuam guardadas e criptografadas neste dispositivo. '
            + 'Desbloqueie a cópia offline para vê-las e sincronizá-las.'
          }
        />
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
          {vault.entries.map((entry) => {
            const open = expanded === entry.clientMutationId
            return (
              <li key={entry.clientMutationId} className="card sync-entry">
                <div className="sync-entry-head">
                  <div>
                    <p className="sync-entry-title">{entry.label}</p>
                    <p className="sync-entry-meta">
                      {RESOURCE_LABELS[entry.resourceType]} ·{' '}
                      {OPERATION_LABELS[entry.operation]} · {timestamp(entry.createdAt)}
                    </p>
                  </div>
                  <span className={`badge sync-status sync-status-${entry.status.toLowerCase()}`}>
                    {STATUS_LABELS[entry.status]}
                  </span>
                </div>

                <dl className="sync-entry-facts">
                  <div>
                    <dt>Tentativas</dt>
                    <dd>{entry.attemptCount}</dd>
                  </div>
                  {entry.nextAttemptAt && (
                    <div>
                      <dt>Próxima tentativa</dt>
                      <dd>{timestamp(entry.nextAttemptAt)}</dd>
                    </div>
                  )}
                  {entry.dependencies.length > 0 && (
                    <div>
                      <dt>Depende de</dt>
                      <dd>{entry.dependencies.length} alteração(ões) anterior(es)</dd>
                    </div>
                  )}
                </dl>

                {entry.lastError && (
                  <p className="sync-error">{entry.lastError.detail}</p>
                )}

                <div className="sync-entry-actions">
                  {entry.status === 'CONFLICT' && (
                    <button
                      type="button"
                      className="btn btn-secondary"
                      aria-expanded={open}
                      onClick={() => setExpanded(open ? null : entry.clientMutationId)}
                    >
                      {open ? 'Ocultar comparação' : 'Resolver conflito'}
                    </button>
                  )}
                  {(entry.status === 'FAILED_RETRYABLE' || entry.status === 'BLOCKED') && (
                    <button
                      type="button"
                      className="btn btn-secondary"
                      onClick={() => void vault.retry(entry.clientMutationId)}
                    >
                      <RotateCcw size={16} aria-hidden="true" />
                      Tentar novamente: {entry.label}
                    </button>
                  )}
                  <button
                    type="button"
                    className="btn btn-danger"
                    onClick={() => setConfirming(entry)}
                  >
                    <Trash2 size={16} aria-hidden="true" />
                    Descartar: {entry.label}
                  </button>
                </div>

                {open && entry.status === 'CONFLICT' && (
                  <div className="sync-conflict">
                    {/* Lazily rendered: hundreds of expanded comparisons would
                        be a lot of DOM nobody is looking at. */}
                    <ConflictComparison entry={entry} />
                    <div className="sync-entry-actions">
                      {(entry.conflict?.resolutionOptions ?? []).map((option) => (
                        <button
                          key={option}
                          type="button"
                          className={option === 'DISCARD_LOCAL' ? 'btn btn-danger' : 'btn btn-secondary'}
                          onClick={() => {
                            if (option === 'APPLY_LOCAL') {
                              setApplying(entry)
                              return
                            }
                            void vault.resolve(entry.clientMutationId, option)
                            setExpanded(null)
                          }}
                        >
                          {RESOLUTION_LABELS[option]}
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </li>
            )
          })}
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
