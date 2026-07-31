import type { OutboxEntry, ResourceMapping } from '../../offline/outbox/types'
import { localId, projectList, type Projected } from '../../offline/outbox/projection'
import type {
  PurchaseOption,
  PurchaseOptionRequest,
  WishlistItemDetail,
  WishlistItemRequest,
} from './types'

/**
 * Reading an offline-created wishlist item back out of the queue.
 *
 * An item created without a connection has no server id, so its detail page
 * cannot be fetched — but it still has to be openable, because that page is
 * where purchase options and price observations are added, and those are
 * exactly the operations that are supposed to be able to name a parent that
 * only exists locally. The list gives such an item a negative id; this module
 * turns that id back into the queued entry it stands for.
 */

/** The queued CREATE a negative list id refers to, if it is still queued. */
export function findLocalItemEntry(
  entries: readonly OutboxEntry[],
  itemId: number,
): OutboxEntry | null {
  if (itemId >= 0) return null
  return (
    entries.find(
      (entry) =>
        entry.resourceType === 'WISHLIST_ITEM'
        && entry.operation === 'CREATE'
        && localId(entry.clientResourceId) === itemId,
    ) ?? null
  )
}

/**
 * The server id a negative local id turned into, once replay assigned one.
 *
 * Without this, someone sitting on a locally created item's page when it
 * synchronizes would be left addressing an id that stopped meaning anything.
 */
export function mappedServerId(
  mappings: readonly ResourceMapping[],
  localItemId: number,
): number | null {
  if (localItemId >= 0) return null
  const mapping = mappings.find(
    (candidate) =>
      candidate.resourceType === 'WISHLIST_ITEM'
      && localId(candidate.clientResourceId) === localItemId,
  )
  return mapping?.serverId ?? null
}

function optionFrom(base: PurchaseOption | null, entry: OutboxEntry): PurchaseOption | null {
  if (entry.operation === 'DELETE') return base
  const payload = entry.payload as Partial<PurchaseOptionRequest>
  const basePrice = Number(payload.basePrice ?? base?.basePrice ?? 0)
  const shipping = Number(payload.shipping ?? base?.shipping ?? 0)
  const fees = Number(payload.fees ?? base?.fees ?? 0)
  return {
    id: base?.id ?? localId(entry.clientResourceId),
    merchant: String(payload.merchant ?? base?.merchant ?? entry.label),
    kind: payload.kind ?? base?.kind ?? 'CASH',
    basePrice,
    shipping,
    fees,
    // The sum of three numbers the user just typed — not a server calculation
    // being reproduced, which is why it is safe to show before synchronizing.
    nominalCost: basePrice + shipping + fees,
    installmentCount: payload.installmentCount ?? base?.installmentCount ?? null,
    installmentAmount: payload.installmentAmount ?? base?.installmentAmount ?? null,
    creditCardId: payload.creditCardId ?? base?.creditCardId ?? null,
    creditCardName: base?.creditCardName ?? null,
    notes: payload.notes ?? base?.notes ?? null,
    version: base?.version ?? 0,
  }
}

/**
 * Builds the detail view of an item that exists only in the queue.
 *
 * Returns null once the creation has been applied and archived — by then the
 * real server id is what the caller should be using.
 */
export function localItemDetail(
  entries: readonly OutboxEntry[],
  entry: OutboxEntry,
): WishlistItemDetail {
  const payload = entry.payload as Partial<WishlistItemRequest>
  const options = entries
    .filter(
      (candidate) =>
        candidate.resourceType === 'PURCHASE_OPTION'
        && candidate.operation === 'CREATE'
        && candidate.dependencies.includes(entry.clientResourceId),
    )
    .flatMap((candidate) => {
      const option = optionFrom(null, candidate)
      return option ? [option] : []
    })

  return {
    id: localId(entry.clientResourceId),
    name: String(payload.name ?? entry.label),
    notes: payload.notes ?? null,
    category: null,
    referencePrice: payload.referencePrice ?? null,
    targetPrice: payload.targetPrice ?? null,
    priority: payload.priority ?? 'MEDIUM',
    desiredDate: payload.desiredDate ?? null,
    status: payload.status ?? 'PLANNING',
    options,
    version: 0,
  }
}

/**
 * The options of a server-backed item with the queue laid over them.
 *
 * Only creations that name *this* item are folded in, so a pending option for a
 * different item never leaks onto the wrong page.
 */
export function projectOptions(
  serverOptions: readonly PurchaseOption[],
  entries: readonly OutboxEntry[],
  itemId: number,
): Projected<PurchaseOption>[] {
  const serverIds = new Set(serverOptions.map((option) => option.id))
  const relevant = entries.filter((entry) => {
    if (entry.resourceType !== 'PURCHASE_OPTION') return false
    if (entry.operation === 'CREATE') {
      return (entry.payload as { item?: { serverId?: number } }).item?.serverId === itemId
    }
    return entry.target.serverId != null && serverIds.has(entry.target.serverId)
  })
  return projectList(serverOptions, relevant, 'PURCHASE_OPTION', optionFrom)
}
