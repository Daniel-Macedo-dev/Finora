import { useMemo, useState } from 'react'
import { ArrowRightLeft, CheckCircle2, CircleDollarSign, Layers, Undo2 } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import Money from '../../components/Money'
import { EmptyState, ErrorState, LoadingCards } from '../../components/states'
import { formatBRL, formatDate } from '../../lib/format'
import { useCategories } from '../shared/api'
import { useConversionInventory } from './api'
import LegacyConversionWizard from './LegacyConversionWizard'
import LegacyConversionDetail from './LegacyConversionDetail'
import LegacyBatchConversion from './LegacyBatchConversion'
import {
  INVENTORY_STATE_LABELS,
  MAX_BATCH_SIZE,
  type ConversionInventoryItem,
  type ConversionInventoryState,
} from './types'
import './legacy-conversions.css'

const STATE_BADGES: Record<ConversionInventoryState, string> = {
  ELIGIBLE: 'badge-info',
  CONVERTED: 'badge-positive',
  REVERSED: 'badge-warning',
  BLOCKED: 'badge-neutral',
}

/** Sources the user may convert now: eligible or reversed-and-restorable. */
function isConvertible(item: ConversionInventoryItem): boolean {
  return item.state === 'ELIGIBLE' || item.state === 'REVERSED'
}

export default function LegacyConversionsPage() {
  const [month, setMonth] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [minAmount, setMinAmount] = useState('')
  const [maxAmount, setMaxAmount] = useState('')
  const [state, setState] = useState<ConversionInventoryState | ''>('')
  const [page, setPage] = useState(0)
  const [converting, setConverting] = useState<ConversionInventoryItem | null>(null)
  const [inspectingId, setInspectingId] = useState<number | null>(null)
  const [selected, setSelected] = useState<Map<number, ConversionInventoryItem>>(new Map())
  const [batchOpen, setBatchOpen] = useState(false)

  const filters = useMemo(
    () => ({
      month: month || undefined,
      from: from || undefined,
      to: to || undefined,
      categoryId: categoryId ? Number(categoryId) : undefined,
      minAmount: minAmount ? Number(minAmount) : undefined,
      maxAmount: maxAmount ? Number(maxAmount) : undefined,
      state: state || undefined,
      page,
    }),
    [month, from, to, categoryId, minAmount, maxAmount, state, page],
  )
  const inventory = useConversionInventory(filters)
  const categories = useCategories('EXPENSE')

  function resetToFirstPage() {
    setPage(0)
  }

  const data = inventory.data
  const pageItems = data?.page.content ?? []
  const convertibleOnPage = pageItems.filter(isConvertible)
  const allOnPageSelected =
    convertibleOnPage.length > 0 &&
    convertibleOnPage.every((item) => selected.has(item.transactionId))

  function toggleSelected(item: ConversionInventoryItem) {
    setSelected((current) => {
      const next = new Map(current)
      if (next.has(item.transactionId)) {
        next.delete(item.transactionId)
      } else if (next.size < MAX_BATCH_SIZE) {
        next.set(item.transactionId, item)
      }
      return next
    })
  }

  function toggleAllOnPage() {
    setSelected((current) => {
      const next = new Map(current)
      if (allOnPageSelected) {
        for (const item of convertibleOnPage) {
          next.delete(item.transactionId)
        }
      } else {
        for (const item of convertibleOnPage) {
          if (next.size >= MAX_BATCH_SIZE) {
            break
          }
          next.set(item.transactionId, item)
        }
      }
      return next
    })
  }

  const hasFilters = Boolean(month || from || to || categoryId || minAmount || maxAmount || state)

  return (
    <>
      <PageHeader
        title="Crédito legado"
        description="Converta compras antigas no crédito em compras de cartão reais, com faturas e parcelas exatas."
      />

      {inventory.isPending ? (
        <LoadingCards count={4} height={90} />
      ) : inventory.isError ? (
        <ErrorState error={inventory.error} onRetry={() => inventory.refetch()} />
      ) : data ? (
        <>
          <div className="lc-stats">
            <div className="card stat-card">
              <span className="stat-label">
                <ArrowRightLeft size={16} aria-hidden="true" />
                Elegíveis para conversão
              </span>
              <span className="stat-value">{data.summary.eligibleCount}</span>
            </div>
            <div className="card stat-card">
              <span className="stat-label">
                <CheckCircle2 size={16} aria-hidden="true" />
                Convertidas
              </span>
              <span className="stat-value">{data.summary.convertedCount}</span>
            </div>
            <div className="card stat-card">
              <span className="stat-label">
                <Undo2 size={16} aria-hidden="true" />
                Estornadas
              </span>
              <span className="stat-value">{data.summary.reversedCount}</span>
            </div>
            <div className="card stat-card">
              <span className="stat-label">
                <CircleDollarSign size={16} aria-hidden="true" />
                Valor histórico pendente
              </span>
              <span className="stat-value">{formatBRL(data.summary.pendingAmount)}</span>
            </div>
          </div>

          <div className="lc-filters">
            <input
              className="input lc-filter"
              type="month"
              aria-label="Filtrar por mês"
              value={month}
              onChange={(event) => {
                setMonth(event.target.value)
                resetToFirstPage()
              }}
            />
            <input
              className="input lc-filter"
              type="date"
              aria-label="Filtrar a partir da data"
              value={from}
              onChange={(event) => {
                setFrom(event.target.value)
                resetToFirstPage()
              }}
            />
            <input
              className="input lc-filter"
              type="date"
              aria-label="Filtrar até a data"
              value={to}
              onChange={(event) => {
                setTo(event.target.value)
                resetToFirstPage()
              }}
            />
            <select
              className="select lc-filter"
              aria-label="Filtrar por categoria"
              value={categoryId}
              onChange={(event) => {
                setCategoryId(event.target.value)
                resetToFirstPage()
              }}
            >
              <option value="">Todas as categorias</option>
              {(categories.data ?? []).map((category) => (
                <option key={category.id} value={category.id}>
                  {category.name}
                </option>
              ))}
            </select>
            <input
              className="input lc-filter-amount"
              type="number"
              min="0"
              step="0.01"
              placeholder="Valor mín."
              aria-label="Valor mínimo"
              value={minAmount}
              onChange={(event) => {
                setMinAmount(event.target.value)
                resetToFirstPage()
              }}
            />
            <input
              className="input lc-filter-amount"
              type="number"
              min="0"
              step="0.01"
              placeholder="Valor máx."
              aria-label="Valor máximo"
              value={maxAmount}
              onChange={(event) => {
                setMaxAmount(event.target.value)
                resetToFirstPage()
              }}
            />
            <select
              className="select lc-filter"
              aria-label="Filtrar por estado da conversão"
              value={state}
              onChange={(event) => {
                setState(event.target.value as ConversionInventoryState | '')
                resetToFirstPage()
              }}
            >
              <option value="">Todos os estados</option>
              {Object.entries(INVENTORY_STATE_LABELS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>

          {selected.size > 0 && (
            <div className="lc-selection-bar" role="status">
              <span>
                {selected.size === 1
                  ? '1 transação selecionada'
                  : `${selected.size} transações selecionadas`}
                {selected.size >= MAX_BATCH_SIZE && ` (máximo de ${MAX_BATCH_SIZE} por lote)`}
              </span>
              <span style={{ display: 'inline-flex', gap: 'var(--space-2)' }}>
                <button
                  type="button"
                  className="btn btn-ghost"
                  onClick={() => setSelected(new Map())}
                >
                  Limpar seleção
                </button>
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={() => setBatchOpen(true)}
                >
                  <Layers size={16} aria-hidden="true" />
                  Converter selecionadas
                </button>
              </span>
            </div>
          )}

          {pageItems.length === 0 ? (
            <EmptyState
              title="Nenhum crédito legado encontrado"
              description={
                hasFilters
                  ? 'Nenhuma transação de crédito legado corresponde aos filtros selecionados.'
                  : 'Você não tem compras antigas no crédito aguardando conversão. Registros novos no cartão já nascem como compras reais.'
              }
            />
          ) : (
            <>
              <div className="card table-wrap" style={{ padding: 0 }}>
                <table className="data">
                  <thead>
                    <tr>
                      <th scope="col">
                        <input
                          type="checkbox"
                          aria-label="Selecionar todas as elegíveis da página"
                          checked={allOnPageSelected}
                          disabled={convertibleOnPage.length === 0}
                          onChange={toggleAllOnPage}
                        />
                      </th>
                      <th scope="col">Data</th>
                      <th scope="col">Descrição</th>
                      <th scope="col" className="lc-col-optional">
                        Categoria
                      </th>
                      <th scope="col" style={{ textAlign: 'right' }}>
                        Valor
                      </th>
                      <th scope="col">Estado</th>
                      <th scope="col">
                        <span className="visually-hidden">Ações</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {pageItems.map((item) => (
                      <tr key={item.transactionId}>
                        <td>
                          <input
                            type="checkbox"
                            aria-label={`Selecionar ${item.description}`}
                            checked={selected.has(item.transactionId)}
                            disabled={
                              !isConvertible(item) ||
                              (!selected.has(item.transactionId) &&
                                selected.size >= MAX_BATCH_SIZE)
                            }
                            onChange={() => toggleSelected(item)}
                          />
                        </td>
                        <td>{formatDate(item.date)}</td>
                        <td className="lc-description" title={item.description}>
                          {item.description}
                        </td>
                        <td className="lc-col-optional">
                          <span className="badge badge-neutral">{item.category.name}</span>
                        </td>
                        <td style={{ textAlign: 'right' }}>
                          <Money value={-item.amount} signed />
                        </td>
                        <td>
                          <span
                            className={`badge ${STATE_BADGES[item.state]}`}
                            title={item.stateMessage ?? undefined}
                          >
                            {INVENTORY_STATE_LABELS[item.state]}
                            {item.stateMessage && (
                              <span className="visually-hidden">. {item.stateMessage}</span>
                            )}
                          </span>
                        </td>
                        <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                          {isConvertible(item) && (
                            <button
                              type="button"
                              className="btn btn-secondary"
                              onClick={() => setConverting(item)}
                            >
                              Converter
                            </button>
                          )}
                          {item.conversionId !== null && (
                            <button
                              type="button"
                              className="btn btn-ghost"
                              onClick={() => setInspectingId(item.conversionId)}
                            >
                              Ver conversão
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {data.page.totalPages > 1 && (
                <nav className="lc-pagination" aria-label="Paginação">
                  <button
                    type="button"
                    className="btn btn-secondary"
                    disabled={page === 0}
                    onClick={() => setPage((value) => value - 1)}
                  >
                    Anterior
                  </button>
                  <span style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)' }}>
                    Página {data.page.page + 1} de {data.page.totalPages}
                  </span>
                  <button
                    type="button"
                    className="btn btn-secondary"
                    disabled={data.page.page + 1 >= data.page.totalPages}
                    onClick={() => setPage((value) => value + 1)}
                  >
                    Próxima
                  </button>
                </nav>
              )}
            </>
          )}
        </>
      ) : null}

      {converting && (
        <LegacyConversionWizard
          source={converting}
          onClose={() => setConverting(null)}
          onConverted={(conversionId) => {
            setConverting(null)
            setSelected(new Map())
            setInspectingId(conversionId)
          }}
        />
      )}

      {inspectingId !== null && (
        <LegacyConversionDetail
          conversionId={inspectingId}
          onClose={() => setInspectingId(null)}
        />
      )}

      {batchOpen && (
        <LegacyBatchConversion
          sources={[...selected.values()]}
          onClose={() => setBatchOpen(false)}
          onFinished={() => setSelected(new Map())}
        />
      )}
    </>
  )
}
