-- Offline mutation synchronization: optimistic resource versions, stable
-- client-created resource identities and durable owner-scoped idempotency
-- receipts.
--
-- Nothing here fabricates data. Every existing row keeps its values, starts at
-- version 0 and carries a NULL client_resource_id (it was created online, so it
-- never had a client-side identity). wishlist_price_snapshots already owns both
-- an optimistic version (V13) and an owner-scoped client identity
-- (client_request_id): both are reused as-is and deliberately left untouched.

-- ---------------------------------------------------------------------------
-- 1. Optimistic versions for the resources supported by offline mutations.
--    updated_at is an audit timestamp, never a conflict token: concurrent
--    writes inside the same clock tick would compare equal.
-- ---------------------------------------------------------------------------
ALTER TABLE transactions     ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE budgets          ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE goals            ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE wishlist_items   ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE purchase_options ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE transactions
    ADD CONSTRAINT ck_transactions_version CHECK (version >= 0);
ALTER TABLE budgets
    ADD CONSTRAINT ck_budgets_version CHECK (version >= 0);
ALTER TABLE goals
    ADD CONSTRAINT ck_goals_version CHECK (version >= 0);
ALTER TABLE wishlist_items
    ADD CONSTRAINT ck_wishlist_items_version CHECK (version >= 0);
ALTER TABLE purchase_options
    ADD CONSTRAINT ck_purchase_options_version CHECK (version >= 0);

-- ---------------------------------------------------------------------------
-- 2. Denormalized ownership on purchase_options.
--
--    V8 documented that options carry no user_id because every access path is
--    owner-scoped through the parent item. Offline synchronization adds a new
--    access path — resolving an option by the client-generated UUID it was
--    created with — and that lookup must be unique *per owner*, which a
--    per-item column cannot express. The column is backfilled from the parent,
--    kept NOT NULL and bound to the parent by a composite foreign key, so it
--    can never drift from the item's owner.
-- ---------------------------------------------------------------------------
ALTER TABLE purchase_options ADD COLUMN user_id BIGINT;

UPDATE purchase_options o
SET user_id = i.user_id
FROM wishlist_items i
WHERE i.id = o.wishlist_item_id;

ALTER TABLE purchase_options ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE purchase_options
    ADD CONSTRAINT fk_purchase_options_user
    FOREIGN KEY (user_id) REFERENCES users (id);

ALTER TABLE purchase_options
    ADD CONSTRAINT fk_purchase_options_item_owner
    FOREIGN KEY (wishlist_item_id, user_id)
    REFERENCES wishlist_items (id, user_id) ON DELETE CASCADE;

-- ---------------------------------------------------------------------------
-- 3. Stable client-created resource identities.
--
--    NULL for everything that already exists and for everything created online:
--    only a resource born inside an offline outbox needs a client identity, and
--    the service layer requires one there. Uniqueness is partial and scoped to
--    the owner, so two different users may independently generate the same UUID
--    without colliding — and a foreign UUID resolves to nothing.
-- ---------------------------------------------------------------------------
ALTER TABLE transactions     ADD COLUMN client_resource_id UUID;
ALTER TABLE budgets          ADD COLUMN client_resource_id UUID;
ALTER TABLE goals            ADD COLUMN client_resource_id UUID;
ALTER TABLE wishlist_items   ADD COLUMN client_resource_id UUID;
ALTER TABLE purchase_options ADD COLUMN client_resource_id UUID;

CREATE UNIQUE INDEX uq_transactions_user_client_resource
    ON transactions (user_id, client_resource_id)
    WHERE client_resource_id IS NOT NULL;

CREATE UNIQUE INDEX uq_budgets_user_client_resource
    ON budgets (user_id, client_resource_id)
    WHERE client_resource_id IS NOT NULL;

CREATE UNIQUE INDEX uq_goals_user_client_resource
    ON goals (user_id, client_resource_id)
    WHERE client_resource_id IS NOT NULL;

CREATE UNIQUE INDEX uq_wishlist_items_user_client_resource
    ON wishlist_items (user_id, client_resource_id)
    WHERE client_resource_id IS NOT NULL;

CREATE UNIQUE INDEX uq_purchase_options_user_client_resource
    ON purchase_options (user_id, client_resource_id)
    WHERE client_resource_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 4. Durable idempotency receipts.
--
--    A receipt is written in the same database transaction as the domain
--    mutation it describes: either both exist or neither does. Replaying a
--    mutation whose HTTP response was lost therefore returns the stored result
--    instead of repeating the financial side effect.
--
--    request_hash is a SHA-256 over the canonical envelope (resource type,
--    operation, target, base version, normalized payload). The same mutation id
--    arriving with a different hash is an idempotency-key reuse conflict — the
--    stored receipt is never overwritten and the new payload is never applied.
-- ---------------------------------------------------------------------------
CREATE TABLE offline_mutation_receipts (
    id                  BIGSERIAL     PRIMARY KEY,
    user_id             BIGINT        NOT NULL REFERENCES users (id),
    client_mutation_id  UUID          NOT NULL,
    resource_type       VARCHAR(30)   NOT NULL,
    operation           VARCHAR(10)   NOT NULL,
    request_hash        VARCHAR(64)   NOT NULL,
    client_resource_id  UUID,
    resource_id         BIGINT,
    resource_version    BIGINT,
    result_code         VARCHAR(40)   NOT NULL,
    response_payload    JSONB         NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- The idempotency key is unique per owner, never globally: two users may
    -- generate the same UUID and neither can observe the other's receipt.
    CONSTRAINT uq_offline_receipts_user_mutation
        UNIQUE (user_id, client_mutation_id),

    -- The allowlist lives in application code; the database refuses anything
    -- outside it as a second, non-bypassable boundary.
    CONSTRAINT ck_offline_receipts_resource_type CHECK (
        resource_type IN ('TRANSACTION', 'BUDGET', 'GOAL',
                          'WISHLIST_ITEM', 'PURCHASE_OPTION', 'PRICE_SNAPSHOT')),
    CONSTRAINT ck_offline_receipts_operation CHECK (
        operation IN ('CREATE', 'UPDATE', 'DELETE')),
    CONSTRAINT ck_offline_receipts_request_hash CHECK (
        request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_offline_receipts_result_code CHECK (
        length(trim(result_code)) BETWEEN 1 AND 40),
    CONSTRAINT ck_offline_receipts_response_payload CHECK (
        jsonb_typeof(response_payload) = 'object'),
    CONSTRAINT ck_offline_receipts_resource_version CHECK (
        resource_version IS NULL OR resource_version >= 0),
    CONSTRAINT ck_offline_receipts_resource_id CHECK (
        resource_id IS NULL OR resource_id > 0)
);

-- The owner/idempotency unique constraint is the hot lookup (replay checks it
-- for every mutation). This second index only serves the bounded, owner-scoped
-- receipt history listing; no other receipt column is indexed on speculation.
CREATE INDEX ix_offline_receipts_user_created
    ON offline_mutation_receipts (user_id, created_at DESC, id DESC);
