import { useState } from 'react'
import { CheckCircle2, Undo2 } from 'lucide-react'
import ConfirmDialog from '../../components/ConfirmDialog'
import Money from '../../components/Money'
import { errorMessage } from '../../components/states'
import { formatBRL } from '../../lib/format'
import { useConfirmImport, useUndoBatch } from './api'
import {
  ITEM_RESULT_LABELS,
  type BatchDetail,
  type ConfirmResponse,
  type ItemResultCode,
} from './types'

interface ConfirmImportSectionProps {
  batch: BatchDetail
}

const RESULT_BADGES: Record<ItemResultCode, string> = {
  SUCCESS: 'badge-positive',
  FAILED: 'badge-negative',
  SKIPPED: 'badge-neutral',
  EXACT_DUPLICATE: 'badge-negative',
  ALREADY_IMPORTED: 'badge-info',
  UNDONE: 'badge-positive',
  ALREADY_UNDONE: 'badge-info',
  BLOCKED: 'badge-warning',
}

/**
 * The moment real transactions are created (or removed): pre-confirmation
 * summary with explicit blockers, the confirmation itself, per-item
 * results with visible partial failure, idempotent retry and audited undo.
 */
export default function ConfirmImportSection({ batch }: ConfirmImportSectionProps) {
  const confirm = useConfirmImport()
  const undoBatch = useUndoBatch()
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [undoOpen, setUndoOpen] = useState(false)
  const [lastRun, setLastRun] = useState<ConfirmResponse | null>(null)

  const totals = batch.totals
  const pending = totals.includedPendingCount
  const unmapped = totals.unmappedCategoryCount
  const confirmable =
    (batch.status === 'PREVIEW_READY' || batch.status === 'PARTIALLY_COMPLETED') &&
    pending > 0 &&
    unmapped === 0
  const hasImported = totals.importedCount > 0

  function runConfirm() {
    setConfirmOpen(false)
    confirm.mutate(
      { batchId: batch.id },
      { onSuccess: (response) => setLastRun(response) },
    )
  }

  function runUndoBatch() {
    setUndoOpen(false)
    undoBatch.mutate(
      { batchId: batch.id },
      { onSuccess: (response) => setLastRun(response) },
    )
  }

  function describeItem(itemId: number): string {
    const item = batch.items.find((candidate) => candidate.id === itemId)
    return item?.description ?? `Linha ${item?.sourceIndex ?? itemId}`
  }

  const failedInRun = lastRun?.results.filter((result) => result.result === 'FAILED').length ?? 0

  return (
    <section className="card si-confirm" aria-label="Confirmação da importação">
      <h2 className="si-subtitle">Confirmar importação</h2>
      <dl className="si-confirm-summary">
        <div>
          <dt>Conta de destino</dt>
          <dd>{batch.accountName}</dd>
        </div>
        <div>
          <dt>Serão importados</dt>
          <dd>{pending}</dd>
        </div>
        <div>
          <dt>Excluídos por você</dt>
          <dd>{totals.excludedCount}</dd>
        </div>
        <div>
          <dt>Duplicatas exatas bloqueadas</dt>
          <dd>{totals.exactDuplicateCount}</dd>
        </div>
        <div>
          <dt>Entradas</dt>
          <dd>{formatBRL(totals.pendingIncomeTotal)}</dd>
        </div>
        <div>
          <dt>Saídas</dt>
          <dd>{formatBRL(totals.pendingExpenseTotal)}</dd>
        </div>
        <div>
          <dt>Efeito na conta</dt>
          <dd>
            <Money value={totals.pendingNetEffect} signed />
          </dd>
        </div>
      </dl>

      {unmapped > 0 && (
        <p className="si-warning" role="status">
          {unmapped === 1
            ? '1 lançamento incluído ainda está sem categoria.'
            : `${unmapped} lançamentos incluídos ainda estão sem categoria.`}{' '}
          Escolha as categorias (ou exclua as linhas) para liberar a confirmação.
        </p>
      )}
      {totals.invalidCount > 0 && (
        <p className="si-warning" role="status">
          {totals.invalidCount === 1
            ? '1 linha inválida não será importada.'
            : `${totals.invalidCount} linhas inválidas não serão importadas.`}
        </p>
      )}

      <div className="si-confirm-actions">
        {(batch.status === 'PREVIEW_READY' || batch.status === 'PARTIALLY_COMPLETED') && (
          <button
            type="button"
            className="btn btn-primary"
            disabled={!confirmable || confirm.isPending}
            onClick={() => setConfirmOpen(true)}
          >
            <CheckCircle2 size={16} aria-hidden="true" />
            {confirm.isPending
              ? 'Importando…'
              : pending === 1
                ? 'Importar 1 lançamento (cria transação real)'
                : `Importar ${pending} lançamentos (cria transações reais)`}
          </button>
        )}
        {hasImported && batch.status !== 'UNDONE' && (
          <button
            type="button"
            className="btn btn-danger"
            disabled={undoBatch.isPending}
            onClick={() => setUndoOpen(true)}
          >
            <Undo2 size={16} aria-hidden="true" />
            {undoBatch.isPending ? 'Desfazendo…' : 'Desfazer importação'}
          </button>
        )}
      </div>

      {(confirm.isError || undoBatch.isError) && (
        <p className="field-error" role="alert">
          {confirm.isError ? errorMessage(confirm.error) : errorMessage(undoBatch.error)}
        </p>
      )}

      {lastRun && (
        <section className="si-results" aria-label="Resultado por lançamento">
          <h3 className="si-subtitle">
            Resultado da operação
            {failedInRun > 0 && (
              <span className="si-failed-note">
              {' '}
                · {failedInRun === 1 ? '1 lançamento falhou' : `${failedInRun} lançamentos falharam`}
              </span>
            )}
          </h3>
          <ul className="si-result-list">
            {lastRun.results.map((result) => (
              <li key={result.itemId}>
                <span className={`badge ${RESULT_BADGES[result.result]}`}>
                  {ITEM_RESULT_LABELS[result.result]}
                </span>
                <span className="si-result-description">{describeItem(result.itemId)}</span>
                <span className="si-result-message">{result.message}</span>
              </li>
            ))}
            {lastRun.results.length === 0 && (
              <li className="si-result-empty">Nenhum lançamento estava pendente.</li>
            )}
          </ul>
          {failedInRun > 0 && (
            <button
              type="button"
              className="btn btn-secondary"
              disabled={confirm.isPending}
              onClick={() => runConfirm()}
            >
              Tentar novamente os lançamentos com falha
            </button>
          )}
        </section>
      )}

      <ConfirmDialog
        open={confirmOpen}
        title="Importar lançamentos"
        message={`Serão criadas ${pending === 1 ? '1 transação real' : `${pending} transações reais`} na conta ${batch.accountName}. Duplicatas exatas continuam bloqueadas e cada lançamento retorna um resultado individual.`}
        confirmLabel="Criar transações"
        busy={confirm.isPending}
        onConfirm={runConfirm}
        onCancel={() => setConfirmOpen(false)}
      />
      <ConfirmDialog
        open={undoOpen}
        title="Desfazer importação"
        message={`As transações criadas por esta importação serão removidas da conta ${batch.accountName} e o efeito financeiro desaparece. O histórico da importação permanece como registro de auditoria.`}
        confirmLabel="Desfazer importação"
        danger
        busy={undoBatch.isPending}
        onConfirm={runUndoBatch}
        onCancel={() => setUndoOpen(false)}
      />
    </section>
  )
}
