import { useState } from 'react'
import { Plus } from 'lucide-react'
import { EmptyState } from '../../../components/states'
import { formatBRL, formatDate } from '../../../lib/format'
import PendingBadge from '../../offline-sync/PendingBadge'
import type { OutboxEntry } from '../../../offline/outbox/types'
import type { PurchaseOption } from '../types'
import { SnapshotDialog, type OfflineParents } from './PriceHistorySection'
import './priceHistory.css'

interface Props {
  itemId: number
  options: PurchaseOption[]
  offline: OfflineParents
  entries: readonly OutboxEntry[]
  itemClientResourceId: string
}

interface QueuedObservation {
  key: string
  merchant: string
  observedOn: string
  nominalCost: number
  paymentKind: string
}

/**
 * The price history of an item that exists only in the queue.
 *
 * There is no server series to chart, summarise or filter — the server has
 * never seen this item — so the section shows exactly what is true: the
 * observations waiting to be sent. Showing the ordinary history UI with empty
 * KPIs would read as "no prices recorded" rather than "not synchronized yet".
 */
export default function LocalPriceHistory({
  itemId,
  options,
  offline,
  entries,
  itemClientResourceId,
}: Props) {
  const [formOpen, setFormOpen] = useState(false)

  const observations: QueuedObservation[] = entries
    .filter(
      (entry) =>
        entry.resourceType === 'PRICE_SNAPSHOT'
        && entry.operation === 'CREATE'
        && entry.dependencies.includes(itemClientResourceId),
    )
    .map((entry) => {
      const payload = entry.payload as {
        merchant?: string
        observedOn?: string
        basePrice?: number
        shipping?: number
        fees?: number
        paymentKind?: string
      }
      return {
        key: entry.clientMutationId,
        merchant: String(payload.merchant ?? entry.label),
        observedOn: String(payload.observedOn ?? entry.createdAt.slice(0, 10)),
        nominalCost:
          Number(payload.basePrice ?? 0) + Number(payload.shipping ?? 0) + Number(payload.fees ?? 0),
        paymentKind: payload.paymentKind === 'INSTALLMENT' ? 'Parcelado' : 'À vista',
      }
    })

  return (
    <section className="wishlist-detail-section price-history" aria-labelledby="price-history-title">
      <div className="wishlist-detail-section-header">
        <div>
          <h2 id="price-history-title">Histórico de preços</h2>
          <p className="stat-footnote">
            Este item ainda não foi enviado ao servidor, então só aparecem aqui as observações
            registradas offline.
          </p>
        </div>
        <button className="btn btn-primary" type="button" onClick={() => setFormOpen(true)}>
          <Plus size={16} aria-hidden="true" /> Registrar preço
        </button>
      </div>

      {observations.length === 0 ? (
        <EmptyState
          title="Nenhum preço observado"
          description="Registre um preço para acompanhá-lo assim que o item for sincronizado."
        />
      ) : (
        <div className="price-table-wrap">
          <table className="price-table">
            <thead>
              <tr>
                <th scope="col">Data</th>
                <th scope="col">Loja</th>
                <th scope="col">Pagamento</th>
                <th scope="col">Total</th>
                <th scope="col">Situação</th>
              </tr>
            </thead>
            <tbody>
              {observations.map((observation) => (
                <tr key={observation.key}>
                  <td>{formatDate(observation.observedOn)}</td>
                  <td>{observation.merchant}</td>
                  <td>{observation.paymentKind}</td>
                  <td>{formatBRL(observation.nominalCost)}</td>
                  <td>
                    <PendingBadge state="CREATED" />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <SnapshotDialog
        key={String(formOpen)}
        itemId={itemId}
        options={options}
        offline={offline}
        open={formOpen}
        initial={null}
        onClose={() => setFormOpen(false)}
      />
    </section>
  )
}
