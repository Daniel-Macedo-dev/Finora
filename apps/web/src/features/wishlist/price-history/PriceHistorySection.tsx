import { useRef, useState } from 'react'
import { ExternalLink, Pencil, Plus, Trash2, TrendingDown, TrendingUp } from 'lucide-react'
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import ConfirmDialog from '../../../components/ConfirmDialog'
import Dialog from '../../../components/Dialog'
import FormActions from '../../../components/FormActions'
import { EmptyState, ErrorState, LoadingCards } from '../../../components/states'
import { formatBRL, formatDate } from '../../../lib/format'
import { useCreatePriceSnapshot, useDeletePriceSnapshot, usePriceHistory,
  usePriceHistoryChart, usePriceHistorySummary, useUpdatePriceSnapshot } from '../api'
import type { PriceSnapshot, PurchaseOption, PurchaseOptionKind } from '../types'
import { createRequestId } from './requestId'
import './priceHistory.css'

interface Props { itemId: number; options: PurchaseOption[] }

export default function PriceHistorySection({ itemId, options }: Props) {
  const [page, setPage] = useState(0)
  const [merchant, setMerchant] = useState('')
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<PriceSnapshot | null>(null)
  const [deleting, setDeleting] = useState<PriceSnapshot | null>(null)
  const history = usePriceHistory(itemId, page, merchant)
  const summary = usePriceHistorySummary(itemId)
  const chart = usePriceHistoryChart(itemId)
  const remove = useDeletePriceSnapshot(itemId)

  return <section className="wishlist-detail-section price-history" aria-labelledby="price-history-title">
    <div className="wishlist-detail-section-header">
      <div><h2 id="price-history-title">Histórico de preços</h2>
        <p className="stat-footnote">Observações manuais; opções atuais continuam sendo a fonte da análise.</p></div>
      <button className="btn btn-primary" type="button" onClick={() => setFormOpen(true)}>
        <Plus size={16} aria-hidden="true" /> Registrar preço
      </button>
    </div>

    {summary.isPending ? <LoadingCards count={3} height={90} /> : summary.isError ?
      <ErrorState error={summary.error} onRetry={() => summary.refetch()} /> : summary.data &&
      <PriceSummary data={summary.data} />}

    <div className="card price-chart-card">
      <h3>Evolução do menor preço observado por dia</h3>
      {chart.isPending ? <LoadingCards count={1} height={220} /> : chart.isError ?
        <ErrorState error={chart.error} onRetry={() => chart.refetch()} /> : chart.data?.points.length ? <>
          <p className="sr-only">Gráfico com {chart.data.points.length} dias, de {formatDate(chart.data.points[0].observedOn)} a {formatDate(chart.data.points.at(-1)!.observedOn)}.</p>
          <div className="price-chart" role="img" aria-label="Linha da evolução dos preços observados">
            <ResponsiveContainer width="100%" height="100%"><LineChart data={chart.data.points}>
              <CartesianGrid strokeDasharray="3 3" /><XAxis dataKey="observedOn" tickFormatter={formatDate} />
              <YAxis tickFormatter={(v) => formatBRL(Number(v))} width={92} />
              <Tooltip formatter={(v) => formatBRL(Number(v))} labelFormatter={(v) => formatDate(String(v))} />
              <Line type="monotone" dataKey="nominalCost" name="Menor preço" stroke="var(--brand)" strokeWidth={3} dot />
            </LineChart></ResponsiveContainer>
          </div></> : <p className="panel-empty">Registre preços para visualizar a evolução.</p>}
    </div>

    <div className="price-history-toolbar">
      <label>Filtrar por loja<input value={merchant} onChange={(e) => { setMerchant(e.target.value); setPage(0) }} /></label>
    </div>
    {history.isPending ? <LoadingCards count={2} height={90} /> : history.isError ?
      <ErrorState error={history.error} onRetry={() => history.refetch()} /> : !history.data?.content.length ?
      <EmptyState title="Nenhum preço observado" description="Registre manualmente um preço ou capture os valores de uma opção atual." /> : <>
      <div className="price-table-wrap"><table className="price-table"><thead><tr>
        <th>Data</th><th>Loja</th><th>Pagamento</th><th>Total</th><th>Oferta</th><th>Ações</th>
      </tr></thead><tbody>{history.data.content.map((row) => <tr key={row.id}>
        <td>{formatDate(row.observedOn)}</td><td>{row.merchant}{!row.linkedOptionAvailable && row.seriesKey.startsWith('OPTION:') && <small>Opção removida</small>}</td>
        <td>{row.paymentKind === 'CASH' ? 'À vista' : `${row.installmentCount}× ${formatBRL(row.installmentAmount ?? 0)}`}</td>
        <td>{formatBRL(row.nominalCost)}</td><td>{row.offerUrl ? <a href={row.offerUrl} target="_blank" rel="noopener noreferrer" aria-label={`Abrir oferta de ${row.merchant}`}>Ver oferta <ExternalLink size={13} aria-hidden="true" /></a> : '—'}</td>
        <td><div className="price-row-actions"><button className="btn btn-ghost btn-icon" aria-label={`Editar observação de ${row.merchant}`} onClick={() => setEditing(row)}><Pencil size={15} /></button>
          <button className="btn btn-ghost btn-icon" aria-label={`Excluir observação de ${row.merchant}`} onClick={() => setDeleting(row)}><Trash2 size={15} /></button></div></td>
      </tr>)}</tbody></table></div>
      <nav className="price-pagination" aria-label="Paginação do histórico"><button className="btn btn-secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Anterior</button>
        <span>Página {page + 1} de {history.data.totalPages}</span><button className="btn btn-secondary" disabled={page + 1 >= history.data.totalPages} onClick={() => setPage(page + 1)}>Próxima</button></nav>
    </>}

    <SnapshotDialog itemId={itemId} options={options} open={formOpen || editing !== null} initial={editing} onClose={() => { setFormOpen(false); setEditing(null) }} />
    <ConfirmDialog open={deleting !== null} title="Excluir observação" message={`Excluir a observação de ${deleting?.merchant ?? ''} em ${deleting ? formatDate(deleting.observedOn) : ''}? A opção atual não será alterada.`}
      confirmLabel="Excluir observação" danger busy={remove.isPending} onCancel={() => setDeleting(null)} onConfirm={() => deleting && remove.mutate(deleting.id, { onSuccess: () => setDeleting(null) })} />
  </section>
}

export function PriceSummary({ data }: { data: import('../types').PriceHistorySummary }) {
  const change = data.percentageChange
  return <><div className="price-kpis">
    <div className="card"><span>Último preço observado</span><strong>{data.latestObservedBestCost === null ? '—' : formatBRL(data.latestObservedBestCost)}</strong></div>
    <div className="card"><span>Menor preço registrado</span><strong>{data.historicalMinimum === null ? '—' : formatBRL(data.historicalMinimum)}</strong></div>
    <div className="card"><span>Média histórica</span><strong>{data.historicalAverage === null ? '—' : formatBRL(data.historicalAverage)}</strong></div>
    <div className="card"><span>Observações</span><strong>{data.observationCount}</strong></div>
  </div>{change !== null && <p className="price-trend">{change < 0 ? <TrendingDown aria-hidden="true" /> : <TrendingUp aria-hidden="true" />}
    {change < 0 ? 'Preço diminuiu' : change > 0 ? 'Preço aumentou' : 'Preço sem alteração'} {Math.abs(change).toLocaleString('pt-BR')}% em relação à observação comparável anterior.</p>}
    {data.targetReached !== null && <p className={`price-target ${data.targetReached ? 'reached' : ''}`}>{data.targetReached ? 'Preço alvo atingido' : `Preço alvo ainda não atingido${data.distanceToTarget !== null ? ` · faltam ${formatBRL(data.distanceToTarget)}` : ''}`}</p>}</>
}

function SnapshotDialog({ itemId, options, open, initial, onClose }: { itemId: number; options: PurchaseOption[]; open: boolean; initial: PriceSnapshot | null; onClose: () => void }) {
  const create = useCreatePriceSnapshot(itemId); const update = useUpdatePriceSnapshot(itemId)
  const requestId = useRef<string | null>(null)
  const linked = initial?.purchaseOptionId ?? null
  const [optionId, setOptionId] = useState<string>(linked?.toString() ?? '')
  const selected = options.find((o) => o.id === Number(optionId))
  const [merchant, setMerchant] = useState(initial?.merchant ?? selected?.merchant ?? '')
  const [kind, setKind] = useState<PurchaseOptionKind>(initial?.paymentKind ?? selected?.kind ?? 'CASH')
  const [basePrice, setBasePrice] = useState(String(initial?.basePrice ?? selected?.basePrice ?? ''))
  const [shipping, setShipping] = useState(String(initial?.shipping ?? selected?.shipping ?? 0))
  const [fees, setFees] = useState(String(initial?.fees ?? selected?.fees ?? 0))
  const [count, setCount] = useState(String(initial?.installmentCount ?? selected?.installmentCount ?? ''))
  const [amount, setAmount] = useState(String(initial?.installmentAmount ?? selected?.installmentAmount ?? ''))
  const [observedOn, setObservedOn] = useState(initial?.observedOn ?? new Date().toISOString().slice(0, 10))
  const [offerUrl, setOfferUrl] = useState(initial?.offerUrl ?? '')
  const [notes, setNotes] = useState(initial?.notes ?? '')
  const [updateOption, setUpdateOption] = useState(false)
  const busy = create.isPending || update.isPending
  function choose(value: string) { setOptionId(value); const option = options.find((o) => o.id === Number(value)); if (option) { setMerchant(option.merchant); setKind(option.kind); setBasePrice(String(option.basePrice)); setShipping(String(option.shipping)); setFees(String(option.fees)); setCount(String(option.installmentCount ?? '')); setAmount(String(option.installmentAmount ?? '')) } }
  return <Dialog open={open} title={initial ? 'Editar observação' : 'Registrar preço'} onClose={onClose} wide><form onSubmit={(e) => { e.preventDefault(); const common = { purchaseOptionId: optionId ? Number(optionId) : null, merchant, paymentKind: kind, basePrice: Number(basePrice), shipping: Number(shipping || 0), fees: Number(fees || 0), installmentCount: kind === 'INSTALLMENT' ? Number(count) : null, installmentAmount: kind === 'INSTALLMENT' ? Number(amount) : null, observedOn, offerUrl: offerUrl || null, notes: notes || null }; if (initial) update.mutate({ id: initial.id, request: common }, { onSuccess: onClose }); else { requestId.current ??= createRequestId(); create.mutate({ ...common, clientRequestId: requestId.current, updateLinkedOption: updateOption }, { onSuccess: onClose }) } }}>
    <label className="form-field">Associar a uma opção atual<select value={optionId} onChange={(e) => choose(e.target.value)}><option value="">Sem associação</option>{options.map((o) => <option key={o.id} value={o.id}>{o.merchant} · {formatBRL(o.nominalCost)}</option>)}</select></label>
    <div className="price-form-grid"><label className="form-field">Loja<input required maxLength={150} value={merchant} onChange={(e) => setMerchant(e.target.value)} /></label><label className="form-field">Pagamento<select value={kind} onChange={(e) => setKind(e.target.value as PurchaseOptionKind)}><option value="CASH">À vista</option><option value="INSTALLMENT">Parcelado</option></select></label>
      <label className="form-field">Preço<input required inputMode="decimal" type="number" min="0.01" step="0.01" value={basePrice} onChange={(e) => setBasePrice(e.target.value)} /></label><label className="form-field">Frete<input inputMode="decimal" type="number" min="0" step="0.01" value={shipping} onChange={(e) => setShipping(e.target.value)} /></label><label className="form-field">Taxas<input inputMode="decimal" type="number" min="0" step="0.01" value={fees} onChange={(e) => setFees(e.target.value)} /></label>
      {kind === 'INSTALLMENT' && <><label className="form-field">Parcelas<input required type="number" min="1" max="120" value={count} onChange={(e) => setCount(e.target.value)} /></label><label className="form-field">Valor da parcela<input required inputMode="decimal" type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} /></label></>}<label className="form-field">Data<input required type="date" value={observedOn} onChange={(e) => setObservedOn(e.target.value)} /></label></div>
    <label className="form-field">Link da oferta<input type="url" placeholder="https://" value={offerUrl} onChange={(e) => setOfferUrl(e.target.value)} /></label><label className="form-field">Observações<textarea maxLength={2000} value={notes} onChange={(e) => setNotes(e.target.value)} /></label>
    {!initial && optionId && <label className="price-update-option"><input type="checkbox" checked={updateOption} onChange={(e) => setUpdateOption(e.target.checked)} /> Salvar no histórico e atualizar a opção atual</label>}
    {(create.isError || update.isError) && <p className="form-error" role="alert">{(create.error ?? update.error)?.message}</p>}<FormActions busy={busy} submitLabel={updateOption ? 'Salvar e atualizar opção' : 'Salvar no histórico'} onCancel={onClose} />
  </form></Dialog>
}
