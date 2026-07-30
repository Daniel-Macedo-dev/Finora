package com.finora.api.offlinesync;

/**
 * The closed set of resources a queued offline mutation may target.
 *
 * <p>This allowlist lives in application code — the client never names a route,
 * a class, a repository or a table. Anything outside this enum fails request
 * deserialization before a handler is ever selected, and the database repeats
 * the same list as a check constraint on the receipt table.
 *
 * <p>Deliberately absent: accounts, categories, credit cards, card purchases,
 * invoices, statement imports, recurring commitments, notifications, settings
 * and goal contributions. Those workflows are audit-heavy or depend on current
 * server state, so they stay online-only.
 */
public enum SyncResourceType {

    /** Ordinary user-entered transactions only — never generated records. */
    TRANSACTION,

    BUDGET,

    GOAL,

    WISHLIST_ITEM,

    PURCHASE_OPTION,

    /** History-only manual price observations; capture stays online-only. */
    PRICE_SNAPSHOT
}
