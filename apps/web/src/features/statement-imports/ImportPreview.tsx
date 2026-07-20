import { useMemo, useState } from 'react'
import { Pencil, Settings2, Undo2 } from 'lucide-react'
import ConfirmDialog from '../../components/ConfirmDialog'
import Money from '../../components/Money'
import { errorMessage } from '../../components/states'
import { formatBRL, formatDate } from '../../lib/format'
import { useCategories } from '../shared/api'
import { usePatchItem, useUndoItem } from './api'
import CategoryRuleManager from './CategoryRuleManager'
import ConfirmImportSection from './ConfirmImportSection'
import DuplicateReview from './DuplicateReview'
import ImportItemEditor from './ImportItemEditor'
import {
  DUPLICATE_STATUS_LABELS,
  ITEM_STATUS_LABELS,
  RULE_CONFIDENCE_LABELS,
  type BatchDetail,
  type StatementItem,
} from './types'

interface ImportPreviewProps {
  batch: BatchDetail
}

type ItemFilter =
  | 'ALL'
  | 'PENDING'
  | 'INVALID'
  | 'DUPLICATE'
  | 'EXCLUDED'
  | 'IMPORTED'
  | 'FAILED'
  | 'UNDONE'

const FILTER_LABELS: Record<ItemFilter, string> = {
  ALL: 'Todos',
  PENDING: 'Pendentes',
  INVALID: 'Inválidos',
  DUPLICATE: 'Duplicatas',
  EXCLUDED: 'Excluídos',
  IMPORTED: 'Importados',
  FAILED: 'Com falha',
  UNDONE: 'Desfeitos',
}

function matchesFilter(item: StatementItem, filter: ItemFilter): boolean {
  switch (filter) {
    case 'ALL':
      return true
    case 'PENDING':
      return (
        item.included &&
        (item.status === 'READY' || item.status === 'FAILED' || item.status === 'SKIPPED')
      )
    case 'INVALID':
      return item.status === 'INVALID'
    case 'DUPLICATE':
      return item.duplicateStatus !== 'UNIQUE'
    case 'EXCLUDED':
      return !item.included
    case 'IMPORTED':
      return item.status === 'IMPORTED'
    case 'FAILED':
      return item.status === 'FAILED'
    case 'UNDONE':
      return item.status === 'UNDONE'
  }
}

/** Whether the row still accepts pre-confirmation edits. */
function editable(batch: BatchDetail, item: StatementItem): boolean {
  const batchEditable =
    batch.status === 'PREVIEW_READY' || batch.status === 'PARTIALLY_COMPLETED'
  return batchEditable && item.status !== 'IMPORTED' && item.status !== 'UNDONE'
}

function signedAmount(item: StatementItem): number | null {
  if (item.amount === null) {
    return null
  }
  return item.type === 'EXPENSE' ? -item.amount : item.amount
}

/**
 * Review workbench of a parsed batch: totals, filters, inclusion control,
 * per-item category assignment, duplicate review and item editing. The
 * confirmation section at the bottom creates the real transactions.
 */
export default function ImportPreview({ batch }: ImportPreviewProps) {
  const [filter, setFilter] = useState<ItemFilter>('ALL')
  const [search, setSearch] = useState('')
  const [editingItem, setEditingItem] = useState<StatementItem | null>(null)
  const [reviewingItem, setReviewingItem] = useState<StatementItem | null>(null)
  const [rulesOpen, setRulesOpen] = useState(false)
  const [undoingItem, setUndoingItem] = useState<StatementItem | null>(null)
  const patchItem = usePatchItem()
  const undoItem = useUndoItem()
  const incomeCategories = useCategories('INCOME')
  const expenseCategories = useCategories('EXPENSE')

  const totals = batch.totals
  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    return batch.items.filter(
      (item) =>
        matchesFilter(item, filter) &&
        (term === '' || (item.description ?? '').toLowerCase().includes(term)),
    )
  }, [batch.items, filter, search])

  const bulkTargets = filtered.filter((item) => editable(batch, item))

  async function setIncludedForFiltered(included: boolean) {
    // Sequential on purpose: bounded row count and honest error surfacing.
    for (const item of bulkTargets) {
      if (item.included !== included) {
        await patchItem.mutateAsync({ batchId: batch.id, itemId: item.id, patch: { included } })
      }
    }
  }

  function categoriesFor(item: StatementItem) {
    return (item.type === 'INCOME' ? incomeCategories.data : expenseCategories.data) ?? []
  }

  function renderCategoryCell(item: StatementItem) {
    if (!editable(batch, item)) {
      return item.selectedCategoryName ?? '—'
    }
    return (
      <div className="si-category-cell">
        <select
          className="select si-category-select"
          aria-label={`Categoria de ${item.description ?? `linha ${item.sourceIndex}`}`}
          value={item.selectedCategoryId !== null ? String(item.selectedCategoryId) : ''}
          onChange={(event) =>
            patchItem.mutate({
              batchId: batch.id,
              itemId: item.id,
              patch: { selectedCategoryId: Number(event.target.value) },
            })
          }
        >
          <option value="" disabled>
            Escolha…
          </option>
          {categoriesFor(item)
            .filter((category) => category.active)
            .map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
        </select>
        {item.suggestedCategoryName && item.matchedRulePattern && (
          <span className="si-rule-hint">
            Sugerida pela regra “{item.matchedRulePattern}”
            {item.ruleConfidence &&
              ` (confiança ${RULE_CONFIDENCE_LABELS[item.ruleConfidence].toLowerCase()})`}
          </span>
        )}
      </div>
    )
  }

  function renderDuplicateCell(item: StatementItem) {
    if (item.duplicateStatus === 'UNIQUE') {
      return <span className="badge badge-neutral">Único</span>
    }
    const badgeClass =
      item.duplicateStatus === 'POSSIBLE_DUPLICATE' ? 'badge-warning' : 'badge-negative'
    return (
      <button
        type="button"
        className={`badge ${badgeClass} si-badge-button`}
        onClick={() => setReviewingItem(item)}
      >
        {DUPLICATE_STATUS_LABELS[item.duplicateStatus]}
        {item.duplicateStatus === 'POSSIBLE_DUPLICATE' && !item.duplicateOverride && (
          <span className="visually-hidden">. Requer decisão: revisar</span>
        )}
        {item.duplicateOverride && <span> · importar mesmo assim</span>}
      </button>
    )
  }

  function renderStatusCell(item: StatementItem) {
    const badge =
      item.status === 'IMPORTED'
        ? 'badge-positive'
        : item.status === 'INVALID' || item.status === 'FAILED'
          ? 'badge-negative'
          : item.status === 'READY'
            ? 'badge-info'
            : 'badge-neutral'
    const message = item.validationMessage ?? item.resultMessage
    return (
      <div className="si-status-cell">
        <span className={`badge ${badge}`}>{ITEM_STATUS_LABELS[item.status]}</span>
        {message && item.status !== 'IMPORTED' && (
          <span className="si-status-message">{message}</span>
        )}
      </div>
    )
  }

  return (
    <>
      <h2 className="visually-hidden">Pré-visualização e revisão dos lançamentos</h2>
      <div className="si-stats">
        <div className="card stat-card">
          <span className="stat-label">Lançamentos no arquivo</span>
          <span className="stat-value">{totals.totalRows}</span>
          <span className="stat-footnote">
            {totals.invalidCount > 0
              ? `${totals.invalidCount} inválido(s)`
              : 'Todos lidos com sucesso'}
          </span>
        </div>
        <div className="card stat-card">
          <span className="stat-label">Prontos para importar</span>
          <span className="stat-value">{totals.includedPendingCount}</span>
          <span className="stat-footnote">
            {totals.unmappedCategoryCount > 0
              ? `${totals.unmappedCategoryCount} sem categoria`
              : 'Categorias completas'}
          </span>
        </div>
        <div className="card stat-card">
          <span className="stat-label">Duplicatas</span>
          <span className="stat-value">
            {totals.exactDuplicateCount +
              totals.possibleDuplicateCount +
              totals.withinFileDuplicateCount}
          </span>
          <span className="stat-footnote">
            {totals.possibleDuplicateCount > 0
              ? `${totals.possibleDuplicateCount} exigem decisão`
              : 'Nenhuma decisão pendente'}
          </span>
        </div>
        <div className="card stat-card">
          <span className="stat-label">Efeito pendente na conta</span>
          <span className="stat-value">
            <Money value={totals.pendingNetEffect} signed />
          </span>
          <span className="stat-footnote">
            +{formatBRL(totals.pendingIncomeTotal)} · −{formatBRL(totals.pendingExpenseTotal)}
          </span>
        </div>
      </div>

      <div className="si-toolbar">
        <select
          className="select si-filter"
          aria-label="Filtrar lançamentos por situação"
          value={filter}
          onChange={(event) => setFilter(event.target.value as ItemFilter)}
        >
          {Object.entries(FILTER_LABELS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        <input
          className="input si-filter"
          type="search"
          placeholder="Buscar descrição…"
          aria-label="Buscar por descrição"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <div className="si-toolbar-actions">
          <button
            type="button"
            className="btn btn-secondary"
            disabled={patchItem.isPending || bulkTargets.length === 0}
            onClick={() => void setIncludedForFiltered(true)}
          >
            Incluir visíveis
          </button>
          <button
            type="button"
            className="btn btn-secondary"
            disabled={patchItem.isPending || bulkTargets.length === 0}
            onClick={() => void setIncludedForFiltered(false)}
          >
            Excluir visíveis
          </button>
          <button type="button" className="btn btn-secondary" onClick={() => setRulesOpen(true)}>
            <Settings2 size={16} aria-hidden="true" />
            Regras de categoria
          </button>
        </div>
      </div>

      {patchItem.isError && (
        <p className="field-error" role="alert">
          {errorMessage(patchItem.error)}
        </p>
      )}

      <div className="card table-wrap si-items" style={{ padding: 0 }}>
        <table className="data">
          <caption className="visually-hidden">Lançamentos do extrato</caption>
          <thead>
            <tr>
              <th scope="col">Incluir</th>
              <th scope="col">Data</th>
              <th scope="col">Descrição</th>
              <th scope="col" style={{ textAlign: 'right' }}>
                Valor
              </th>
              <th scope="col">Categoria</th>
              <th scope="col">Duplicidade</th>
              <th scope="col">Situação</th>
              <th scope="col">
                <span className="visually-hidden">Ações</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((item) => (
              <tr key={item.id} className={item.included ? undefined : 'si-row-excluded'}>
                <td>
                  <input
                    type="checkbox"
                    aria-label={`Incluir ${item.description ?? `linha ${item.sourceIndex}`}`}
                    checked={item.included}
                    disabled={!editable(batch, item) || patchItem.isPending}
                    onChange={(event) =>
                      patchItem.mutate({
                        batchId: batch.id,
                        itemId: item.id,
                        patch: { included: event.target.checked },
                      })
                    }
                  />
                </td>
                <td>{formatDate(item.postedDate)}</td>
                <td className="si-description">
                  {item.description ?? '—'}
                  {item.memo && <span className="si-memo">{item.memo}</span>}
                </td>
                <td style={{ textAlign: 'right' }}>
                  <Money value={signedAmount(item)} signed />
                </td>
                <td>{renderCategoryCell(item)}</td>
                <td>{renderDuplicateCell(item)}</td>
                <td>{renderStatusCell(item)}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                  {editable(batch, item) && (
                    <button
                      type="button"
                      className="btn btn-ghost btn-icon"
                      onClick={() => setEditingItem(item)}
                      aria-label={`Editar ${item.description ?? `linha ${item.sourceIndex}`}`}
                    >
                      <Pencil size={16} aria-hidden="true" />
                    </button>
                  )}
                  {item.status === 'IMPORTED' && (
                    <button
                      type="button"
                      className="btn btn-ghost btn-icon"
                      onClick={() => setUndoingItem(item)}
                      disabled={undoItem.isPending}
                      aria-label={`Desfazer importação de ${item.description ?? `linha ${item.sourceIndex}`}`}
                    >
                      <Undo2 size={16} aria-hidden="true" />
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <tr>
                <td colSpan={8} className="si-empty-row">
                  Nenhum lançamento corresponde ao filtro.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <ConfirmImportSection batch={batch} />

      {undoItem.isError && (
        <p className="field-error" role="alert">
          {errorMessage(undoItem.error)}
        </p>
      )}

      {editingItem && (
        <ImportItemEditor
          batchId={batch.id}
          item={editingItem}
          onClose={() => setEditingItem(null)}
        />
      )}
      {undoingItem && (
        <ConfirmDialog
          open
          title="Desfazer este lançamento"
          message={`A transação criada para “${undoingItem.description ?? `linha ${undoingItem.sourceIndex}`}” será removida da conta e o efeito financeiro desaparece. O registro da importação permanece para auditoria.`}
          confirmLabel="Desfazer lançamento"
          danger
          busy={undoItem.isPending}
          onConfirm={() =>
            undoItem.mutate(
              { batchId: batch.id, itemId: undoingItem.id },
              { onSettled: () => setUndoingItem(null) },
            )
          }
          onCancel={() => setUndoingItem(null)}
        />
      )}
      {reviewingItem && (
        <DuplicateReview
          batchId={batch.id}
          item={reviewingItem}
          accountName={batch.accountName}
          onClose={() => setReviewingItem(null)}
        />
      )}
      {rulesOpen && <CategoryRuleManager onClose={() => setRulesOpen(false)} />}
    </>
  )
}
