import type { OfflineQuery } from './vaultCrypto'

/**
 * What an amount without a currency meant before multi-currency existed.
 *
 * Every value the ledger held was BRL by construction, and V15 labelled the
 * server side exactly that way without changing a single number. A cached copy
 * taken before that migration is the same data, so it is labelled the same way.
 *
 * This is deliberately a constant and not the user's current base currency. A
 * copy prepared while the base was BRL does not become dollars because the base
 * changed afterwards — reinterpreting it would restate money the user already
 * saw.
 */
const LEGACY_CURRENCY = 'BRL'

/**
 * Derived responses whose pre-multi-currency shape cannot be repaired.
 *
 * A cached dashboard from before this stage holds scalar totals that were
 * produced by adding whatever was there; a cached budget summary holds a
 * consumed figure with no notion of completeness. There is no way to turn
 * either into the new shape without inventing the parts that were never
 * recorded, so they are dropped and refetched the next time the copy is
 * prepared online. Losing a cached view costs a refresh; fabricating one costs
 * the user a decision made on a wrong number.
 */
const UNSAFE_DERIVED_ROOTS = new Set(['dashboard', 'budgets', 'forecast', 'insights'])

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** Adds `currency` only where it is genuinely absent; never overwrites. */
function withCurrency(value: unknown): unknown {
  if (!isObject(value)) return value
  if (typeof value.currency === 'string') return value
  return { ...value, currency: LEGACY_CURRENCY }
}

/** Same, but only for rows that actually carry an amount. */
function withCurrencyWhenAmountExists(value: unknown): unknown {
  if (!isObject(value)) return value
  if (value.amount === null || value.amount === undefined) return value
  return withCurrency(value)
}

/**
 * Applies a row mapper to a list, or to the `content` of a paged response.
 *
 * The cached shapes are either a bare array or a Spring page; anything else is
 * returned untouched rather than guessed at.
 */
function mapRows(data: unknown, map: (row: unknown) => unknown): unknown {
  if (Array.isArray(data)) return data.map(map)
  if (isObject(data) && Array.isArray(data.content)) {
    return { ...data, content: data.content.map(map) }
  }
  return data
}

/**
 * Upgrades one cached query from the pre-multi-currency shape.
 *
 * Migration is per known key, never a recursive walk that stamps `currency` on
 * anything with an `amount`. A blind walk would label derived and nested
 * objects it does not understand, and would silently keep working — wrongly —
 * as new shapes appear.
 *
 * @returns the upgraded query, or null when the cached response must be dropped
 */
export function migrateQueryToV3(query: OfflineQuery): OfflineQuery | null {
  const root = query.queryKey[0]
  if (typeof root !== 'string') return null
  if (UNSAFE_DERIVED_ROOTS.has(root)) return null

  const upgraded = (data: unknown): OfflineQuery => ({ ...query, data })

  switch (root) {
    case 'settings':
      return isObject(query.data) && typeof query.data.baseCurrency !== 'string'
        ? upgraded({ ...query.data, baseCurrency: LEGACY_CURRENCY })
        : query

    case 'accounts':
    case 'transactions':
    case 'credit-cards':
    case 'goals':
      return upgraded(mapRows(query.data, withCurrency))

    case 'commitments':
      // Either the list, or the upcoming window ({ items, totals }). The
      // totals object is a server-computed CurrencyTotals in the new shape and
      // cannot be reconstructed here, so an old upcoming window is dropped.
      if (Array.isArray(query.data)) return upgraded(query.data.map(withCurrency))
      if (isObject(query.data) && Array.isArray(query.data.items)) return null
      return query

    case 'wishlist':
      // The list, one item's detail, or that item's first history page. A
      // detail carries options and snapshots, which inherit the item's
      // currency — the same BRL by construction, derived from the item context
      // rather than stamped independently.
      if (Array.isArray(query.data)) return upgraded(query.data.map(withCurrency))
      if (isObject(query.data) && query.queryKey[2] === 'price-history') {
        return upgraded(mapRows(query.data, withCurrency))
      }
      if (isObject(query.data)) {
        const item = withCurrency(query.data) as Record<string, unknown>
        const currency = (item.currency as string) ?? LEGACY_CURRENCY
        const inherit = (row: unknown) =>
          isObject(row) && typeof row.currency !== 'string' ? { ...row, currency } : row
        return upgraded({
          ...item,
          ...(Array.isArray(item.options) ? { options: item.options.map(inherit) } : {}),
          ...(Array.isArray(item.priceHistory)
            ? { priceHistory: item.priceHistory.map(inherit) }
            : {}),
        })
      }
      return query

    case 'notifications':
      return upgraded(mapRows(query.data, withCurrencyWhenAmountExists))

    // Categories and notification preferences hold no money at all.
    default:
      return query
  }
}

/** Upgrades a whole cached dataset, dropping what cannot be repaired. */
export function migrateQueriesToV3(queries: OfflineQuery[]): OfflineQuery[] {
  const migrated: OfflineQuery[] = []
  for (const query of queries) {
    const result = migrateQueryToV3(query)
    if (result) migrated.push(result)
  }
  return migrated
}

/**
 * The currency a legacy queued mutation must be read as.
 *
 * Exported so projection and conflict display agree on it without any of them
 * writing it into the queued payload: the canonical request has to stay
 * byte-identical, because the server already hashed it into a receipt.
 */
export const LEGACY_MUTATION_CURRENCY = LEGACY_CURRENCY
