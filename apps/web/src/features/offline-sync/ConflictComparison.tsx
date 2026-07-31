import { formatBRL, formatDate } from '../../lib/format'
import { useAccounts, useCategories } from '../shared/api'
import { PAYMENT_METHOD_LABELS, type PaymentMethod } from '../shared/types'
import type { OutboxEntry, SyncResourceType } from '../../offline/outbox/types'

/**
 * Shows a conflict as two readable columns rather than raw data.
 *
 * A JSON diff would be technically complete and practically useless: the person
 * deciding needs to see "R$ 80,00" beside "R$ 25,00", not two objects. Each
 * resource declares which of its fields matter and how to render them, so the
 * comparison speaks the domain's language.
 */

type Formatter = (value: unknown) => string

const money: Formatter = (value) =>
  value == null || value === '' ? '—' : formatBRL(Number(value))
const date: Formatter = (value) => (typeof value === 'string' ? formatDate(value) : '—')
const text: Formatter = (value) =>
  value == null || value === '' ? '—' : String(value)
const integer: Formatter = (value) => (value == null ? '—' : String(value))
const bool: Formatter = (value) => (value ? 'Sim' : 'Não')

interface FieldSpec {
  label: string
  /** Where to read the value from the local payload. */
  local: string
  /** Where to read it from the server snapshot, when it differs. */
  server?: string
  format: Formatter
}

const PAYMENT: Formatter = (value) =>
  value ? (PAYMENT_METHOD_LABELS[value as PaymentMethod] ?? String(value)) : '—'

const TYPE_LABELS: Record<string, string> = { INCOME: 'Receita', EXPENSE: 'Despesa' }
const KIND_LABELS: Record<string, string> = { CASH: 'À vista', INSTALLMENT: 'Parcelado' }

const FIELDS: Record<SyncResourceType, FieldSpec[]> = {
  TRANSACTION: [
    { label: 'Tipo', local: 'type', format: (value) => TYPE_LABELS[String(value)] ?? text(value) },
    { label: 'Valor', local: 'amount', format: money },
    { label: 'Descrição', local: 'description', format: text },
    { label: 'Data', local: 'date', format: date },
    { label: 'Categoria', local: 'categoryId', server: 'category.name', format: text },
    { label: 'Conta', local: 'accountId', server: 'account.name', format: text },
    { label: 'Forma de pagamento', local: 'paymentMethod', format: PAYMENT },
    { label: 'Observações', local: 'notes', format: text },
  ],
  BUDGET: [
    { label: 'Mês', local: 'month', format: text },
    { label: 'Categoria', local: 'categoryId', server: 'category.name', format: text },
    { label: 'Limite', local: 'limitAmount', format: money },
  ],
  GOAL: [
    { label: 'Nome', local: 'name', format: text },
    { label: 'Valor alvo', local: 'targetAmount', format: money },
    { label: 'Valor atual', local: 'currentAmount', format: money },
    { label: 'Data alvo', local: 'targetDate', format: date },
    { label: 'Arquivada', local: 'archived', format: bool },
  ],
  WISHLIST_ITEM: [
    { label: 'Nome', local: 'name', format: text },
    { label: 'Observações', local: 'notes', format: text },
    { label: 'Categoria', local: 'categoryId', server: 'category.name', format: text },
    { label: 'Preço de referência', local: 'referencePrice', format: money },
    { label: 'Preço alvo', local: 'targetPrice', format: money },
    { label: 'Prioridade', local: 'priority', format: text },
    { label: 'Data desejada', local: 'desiredDate', format: date },
    { label: 'Situação', local: 'status', format: text },
  ],
  PURCHASE_OPTION: [
    { label: 'Loja', local: 'merchant', format: text },
    { label: 'Forma de pagamento', local: 'kind', format: (value) => KIND_LABELS[String(value)] ?? text(value) },
    { label: 'Preço', local: 'basePrice', format: money },
    { label: 'Frete', local: 'shipping', format: money },
    { label: 'Taxas', local: 'fees', format: money },
    { label: 'Parcelas', local: 'installmentCount', format: integer },
    { label: 'Valor da parcela', local: 'installmentAmount', format: money },
    { label: 'Cartão', local: 'creditCardId', server: 'creditCardName', format: text },
    { label: 'Observações', local: 'notes', format: text },
  ],
  PRICE_SNAPSHOT: [
    { label: 'Loja', local: 'merchant', format: text },
    { label: 'Forma de pagamento', local: 'paymentKind', format: (value) => KIND_LABELS[String(value)] ?? text(value) },
    { label: 'Preço', local: 'basePrice', format: money },
    { label: 'Frete', local: 'shipping', format: money },
    { label: 'Taxas', local: 'fees', format: money },
    { label: 'Parcelas', local: 'installmentCount', format: integer },
    { label: 'Valor da parcela', local: 'installmentAmount', format: money },
    { label: 'Data da observação', local: 'observedOn', format: date },
    { label: 'Endereço', local: 'offerUrl', format: text },
    { label: 'Observações', local: 'notes', format: text },
  ],
}

/** Payload keys holding a server id that has a human name elsewhere. */
const REFERENCE_FIELDS = new Set(['categoryId', 'accountId'])

function read(source: Record<string, unknown> | null, path: string): unknown {
  if (!source) return undefined
  return path.split('.').reduce<unknown>((value, key) => {
    if (value && typeof value === 'object') {
      return (value as Record<string, unknown>)[key]
    }
    return undefined
  }, source)
}

export default function ConflictComparison({ entry }: { entry: OutboxEntry }) {
  // The queued payload stores references as ids, because that is what the
  // server resolves. Showing "5" next to "Alimentação" would both read as
  // nonsense and mark every referenced field as changed, so the ids are
  // resolved back to names here, from the same lists the forms used.
  const categories = useCategories()
  const accounts = useAccounts()
  const names = new Map<string, string>()
  for (const category of categories.data ?? []) names.set(`categoryId:${category.id}`, category.name)
  for (const account of accounts.data ?? []) names.set(`accountId:${account.id}`, account.name)

  const conflict = entry.conflict
  if (!conflict) return null
  const snapshot = conflict.serverSnapshot
  const fields = FIELDS[entry.resourceType]

  const rows = fields.map((field) => {
    const raw = read(entry.payload, field.local)
    const resolved = REFERENCE_FIELDS.has(field.local) && raw != null
      ? names.get(`${field.local}:${String(raw)}`)
      : undefined
    const localValue = resolved ?? field.format(raw)
    const serverValue = snapshot
      ? field.format(read(snapshot, field.server ?? field.local))
      : '—'
    return { label: field.label, localValue, serverValue, changed: localValue !== serverValue }
  })

  return (
    <div className="sync-comparison">
      <p className="sync-conflict-detail">{conflict.detail}</p>
      {snapshot ? (
        // The scroll container is a wrapper, never the table itself: giving a
        // <table> `display: block` strips its row and column semantics in
        // several screen readers, which is precisely the information this
        // comparison exists to convey.
        <div
          className="sync-comparison-scroll"
          tabIndex={0}
          role="group"
          aria-label="Comparação de valores"
        >
        <table className="data sync-comparison-table">
          <caption className="visually-hidden">
            Comparação entre o valor salvo no servidor e a alteração feita offline
          </caption>
          <thead>
            <tr>
              <th scope="col">Campo</th>
              <th scope="col">Valor salvo no servidor</th>
              <th scope="col">Alteração feita offline</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.label} className={row.changed ? 'sync-row-changed' : undefined}>
                <th scope="row">{row.label}</th>
                <td>
                  {row.serverValue}
                  {/* Difference is announced in text, never by colour alone. */}
                  {row.changed && <span className="visually-hidden"> (diferente)</span>}
                </td>
                <td>
                  <strong>{row.localValue}</strong>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      ) : (
        <p className="sync-note">
          O servidor não enviou uma versão para comparar. Sua alteração offline está listada abaixo.
        </p>
      )}
      <details className="sync-technical">
        <summary>Detalhes técnicos</summary>
        <p>
          Versão local: {conflict.localBaseVersion ?? '—'} · Versão no servidor:{' '}
          {conflict.serverVersion ?? '—'}
        </p>
      </details>
    </div>
  )
}
