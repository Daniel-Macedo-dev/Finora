import { useState } from 'react'
import { CalendarClock, History, Pause, Pencil, Play, Plus, RefreshCw, Trash2 } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import Money from '../../components/Money'
import Dialog from '../../components/Dialog'
import ConfirmDialog from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, LoadingCards, errorMessage } from '../../components/states'
import { formatBRL, formatDate } from '../../lib/format'
import CommitmentForm from './CommitmentForm'
import OccurrencesDialog from './OccurrencesDialog'
import LegacyCardMappingDialog from './LegacyCardMappingDialog'
import {
  useCommitments,
  useCreateCommitment,
  useDeleteCommitment,
  useProcessDue,
  useSetCommitmentActive,
  useUpcomingCommitments,
  useUpdateCommitment,
} from './api'
import {
  CADENCE_LABELS,
  TARGET_LABELS,
  type Commitment,
  type CommitmentRequest,
} from './types'
import './commitments.css'

export default function CommitmentsPage() {
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Commitment | null>(null)
  const [deleting, setDeleting] = useState<Commitment | null>(null)
  const [inspecting, setInspecting] = useState<Commitment | null>(null)
  const [mapping, setMapping] = useState<Commitment | null>(null)

  const commitments = useCommitments()
  const upcoming = useUpcomingCommitments(2)
  const createMutation = useCreateCommitment()
  const updateMutation = useUpdateCommitment()
  const deleteMutation = useDeleteCommitment()
  const activeMutation = useSetCommitmentActive()
  const processDue = useProcessDue()

  function openCreate() {
    setEditing(null)
    createMutation.reset()
    updateMutation.reset()
    setFormOpen(true)
  }

  function openEdit(commitment: Commitment) {
    setEditing(commitment)
    createMutation.reset()
    updateMutation.reset()
    setFormOpen(true)
  }

  function handleSubmit(request: CommitmentRequest) {
    const onSuccess = () => setFormOpen(false)
    if (editing) {
      updateMutation.mutate({ id: editing.id, request }, { onSuccess })
    } else {
      createMutation.mutate(request, { onSuccess })
    }
  }

  function statusOf(commitment: Commitment): { label: string; className: string } | null {
    const today = new Date().toISOString().slice(0, 10)
    if (commitment.endDate && commitment.endDate < today) {
      return { label: 'Encerrado', className: 'badge-neutral' }
    }
    if (!commitment.active) {
      return { label: 'Pausado', className: 'badge-warning' }
    }
    return null
  }

  const busy = createMutation.isPending || updateMutation.isPending
  const submitError = editing ? updateMutation.error : createMutation.error

  return (
    <>
      <PageHeader
        title="Recorrentes"
        description="Receitas e despesas que se repetem — projete, execute e acompanhe cada ocorrência."
        actions={
          <div className="cc-detail-actions">
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => processDue.mutate()}
              disabled={processDue.isPending}
            >
              <RefreshCw size={16} aria-hidden="true" />
              Processar vencidos
            </button>
            <button type="button" className="btn btn-primary" onClick={openCreate}>
              <Plus size={16} aria-hidden="true" />
              Novo recorrente
            </button>
          </div>
        }
      />

      {processDue.isSuccess && (
        <p className="commitment-form-note" role="status">
          {processDue.data.materialized > 0
            ? `${processDue.data.materialized} ocorrência(s) executada(s).`
            : 'Nenhuma ocorrência automática pendente.'}
          {processDue.data.failed > 0 && ` ${processDue.data.failed} falharam — veja o histórico.`}
        </p>
      )}
      {processDue.isError && (
        <div role="alert" className="field-error">
          {errorMessage(processDue.error)}
        </div>
      )}
      {deleteMutation.isError && (
        <div role="alert" className="field-error">
          {errorMessage(deleteMutation.error)}
        </div>
      )}

      {commitments.isPending ? (
        <LoadingCards count={3} height={72} />
      ) : commitments.isError ? (
        <ErrorState error={commitments.error} onRetry={() => commitments.refetch()} />
      ) : commitments.data && commitments.data.length === 0 ? (
        <EmptyState
          title="Nenhum recorrente cadastrado"
          description="Cadastre salários, assinaturas e contas fixas para projetar o caixa e executar lançamentos automaticamente."
          action={
            <button type="button" className="btn btn-primary" onClick={openCreate}>
              <Plus size={16} aria-hidden="true" />
              Novo recorrente
            </button>
          }
        />
      ) : commitments.data ? (
        <div className="commitments-layout">
          <section aria-label="Recorrentes cadastrados" className="commitments-main">
            <ul className="commitment-list">
              {commitments.data.map((commitment) => {
                const status = statusOf(commitment)
                return (
                  <li
                    key={commitment.id}
                    className={`card commitment-row ${commitment.active ? '' : 'commitment-inactive'}`}
                  >
                    <div className="commitment-info">
                      <span className="commitment-description">{commitment.description}</span>
                      <span className="commitment-meta">
                        <span className="badge badge-neutral">{commitment.category.name}</span>
                        <span>
                          {CADENCE_LABELS[commitment.cadence]}
                          {commitment.cadence === 'MONTHLY' && ` · dia ${commitment.dueDay}`}
                        </span>
                        <span className="badge badge-info">
                          {TARGET_LABELS[commitment.targetKind]}
                          {commitment.targetKind === 'ACCOUNT_TRANSACTION' &&
                            commitment.accountName &&
                            ` · ${commitment.accountName}`}
                          {commitment.targetKind === 'CREDIT_CARD_PURCHASE' &&
                            commitment.creditCardName &&
                            ` · ${commitment.creditCardName}${
                              commitment.installmentCount > 1
                                ? ` · ${commitment.installmentCount}×`
                                : ''
                            }`}
                        </span>
                        {commitment.executionMode === 'AUTOMATIC' && (
                          <span className="badge badge-positive">Automático</span>
                        )}
                        {status && (
                          <span className={`badge ${status.className}`}>{status.label}</span>
                        )}
                        {commitment.legacyProjectionOnly && (
                          <button
                            type="button"
                            className="badge badge-warning commitment-legacy-action"
                            title="Criado antes da automação com pagamento no crédito: continua apenas como planejamento até você escolher um cartão de destino."
                            onClick={() => setMapping(commitment)}
                          >
                            Crédito legado — migrar para cartão
                          </button>
                        )}
                        {commitment.failedOccurrences > 0 && (
                          <span className="badge badge-negative">
                            {commitment.failedOccurrences} falha(s)
                          </span>
                        )}
                      </span>
                    </div>
                    <div className="commitment-side">
                      <Money
                        value={
                          commitment.category.type === 'INCOME'
                            ? commitment.amount
                            : -commitment.amount
                        }
                        signed
                      />
                      <span className="commitment-next">
                        {commitment.nextDueDate
                          ? `Próximo: ${formatDate(commitment.nextDueDate)}`
                          : 'Sem próxima ocorrência'}
                      </span>
                    </div>
                    <div className="commitment-actions">
                      <button
                        type="button"
                        className="btn btn-ghost btn-icon"
                        onClick={() => setInspecting(commitment)}
                        aria-label={`Ocorrências de ${commitment.description}`}
                      >
                        <History size={16} aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="btn btn-ghost btn-icon"
                        onClick={() =>
                          activeMutation.mutate({ id: commitment.id, active: !commitment.active })
                        }
                        aria-label={
                          commitment.active
                            ? `Pausar ${commitment.description}`
                            : `Retomar ${commitment.description}`
                        }
                      >
                        {commitment.active ? (
                          <Pause size={16} aria-hidden="true" />
                        ) : (
                          <Play size={16} aria-hidden="true" />
                        )}
                      </button>
                      <button
                        type="button"
                        className="btn btn-ghost btn-icon"
                        onClick={() => openEdit(commitment)}
                        aria-label={`Editar ${commitment.description}`}
                      >
                        <Pencil size={16} aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="btn btn-ghost btn-icon"
                        onClick={() => setDeleting(commitment)}
                        aria-label={`Excluir ${commitment.description}`}
                      >
                        <Trash2 size={16} aria-hidden="true" />
                      </button>
                    </div>
                  </li>
                )
              })}
            </ul>
          </section>

          <aside className="card commitments-upcoming" aria-label="Próximos vencimentos">
            <h2 className="panel-title">
              <CalendarClock size={16} aria-hidden="true" /> Próximos 2 meses
            </h2>
            {upcoming.isPending ? (
              <p className="panel-empty">Calculando…</p>
            ) : upcoming.isError ? (
              <p className="panel-empty">Não foi possível projetar os vencimentos.</p>
            ) : upcoming.data.items.length === 0 ? (
              <p className="panel-empty">Nenhum vencimento na janela projetada.</p>
            ) : (
              <>
                <ul className="mini-list">
                  {upcoming.data.items.map((item) => (
                    <li key={`${item.commitmentId}-${item.dueDate}`}>
                      <span className="mini-list-main">{item.description}</span>
                      <span className="mini-list-meta">{formatDate(item.dueDate)}</span>
                      <Money value={item.amount} />
                    </li>
                  ))}
                </ul>
                <p className="stat-footnote">
                  Total projetado: {formatBRL(upcoming.data.totalAmount)}
                </p>
              </>
            )}
          </aside>
        </div>
      ) : null}

      <Dialog
        open={formOpen}
        title={editing ? 'Editar recorrente' : 'Novo recorrente'}
        onClose={() => setFormOpen(false)}
        wide
      >
        {formOpen && (
          <CommitmentForm
            key={editing?.id ?? 'new'}
            editing={editing}
            busy={busy}
            submitError={submitError}
            onSubmit={handleSubmit}
            onCancel={() => setFormOpen(false)}
          />
        )}
      </Dialog>

      {inspecting && (
        <OccurrencesDialog commitment={inspecting} onClose={() => setInspecting(null)} />
      )}

      {mapping && (
        <LegacyCardMappingDialog commitment={mapping} onClose={() => setMapping(null)} />
      )}

      <ConfirmDialog
        open={deleting !== null}
        title="Excluir recorrente"
        message={`Excluir "${deleting?.description}"? Recorrentes com histórico executado devem ser pausados ou encerrados.`}
        confirmLabel="Excluir"
        danger
        busy={deleteMutation.isPending}
        onConfirm={() =>
          deleting &&
          deleteMutation.mutate(deleting.id, { onSettled: () => setDeleting(null) })
        }
        onCancel={() => setDeleting(null)}
      />
    </>
  )
}
