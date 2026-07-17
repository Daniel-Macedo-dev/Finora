-- Assisted legacy-credit conversion: an auditable record that turns one
-- pre-card-era CREDIT transaction into a real credit-card purchase without
-- ever counting both as active expenses.
--
-- Accounting model (see docs/legacy-credit-conversion.md):
--   * While a conversion is ACTIVE, the generated card installments are the
--     expense source and the original transaction is retained as an audit
--     record, financially inactive (transactions.financially_active = FALSE).
--   * Reversing a conversion cancels the generated purchase and restores the
--     original transaction as the historical expense source — exactly once.
--   * Paying the generated invoices remains cash movement, never an expense.
--
-- Migration safety for existing rows: every current transaction keeps
-- financially_active = TRUE — nothing is ever marked converted by migration.

-- ── Financial deactivation flag ─────────────────────────────────────────────

ALTER TABLE transactions ADD COLUMN financially_active BOOLEAN NOT NULL DEFAULT TRUE;

-- Only legacy CREDIT rows can ever be deactivated (by an active conversion).
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_inactive_is_legacy
    CHECK (financially_active OR legacy_credit);

-- Conversion inventory scans: the user's legacy-credit rows by date.
CREATE INDEX ix_transactions_user_legacy_credit
    ON transactions (user_id, occurred_on) WHERE legacy_credit;

-- ── Generated-purchase origin link ──────────────────────────────────────────

-- Display-level origin on the purchase itself, following the wishlist_item_id
-- and commitment_id precedents; the conversion row is the authoritative link.
ALTER TABLE credit_card_purchases ADD COLUMN legacy_transaction_id BIGINT;

ALTER TABLE credit_card_purchases ADD CONSTRAINT fk_credit_card_purchases_legacy_tx_owner
    FOREIGN KEY (legacy_transaction_id, user_id) REFERENCES transactions (id, user_id);

-- At most one ACTIVE generated purchase per converted source, no matter how
-- many times a conversion is retried. A reversed conversion's CANCELLED
-- purchase stays in history, so the index is partial on the active status.
CREATE UNIQUE INDEX uq_credit_card_purchases_legacy_tx
    ON credit_card_purchases (legacy_transaction_id)
    WHERE legacy_transaction_id IS NOT NULL AND status = 'ACTIVE';

-- ── Conversion ledger ────────────────────────────────────────────────────────

CREATE TABLE legacy_credit_conversions (
    id                        BIGSERIAL   PRIMARY KEY,
    user_id                   BIGINT      NOT NULL REFERENCES users (id),
    source_transaction_id     BIGINT      NOT NULL,
    card_purchase_id          BIGINT      NOT NULL,
    card_id                   BIGINT      NOT NULL,
    -- Snapshot of the source's date at conversion time: the audit trail stays
    -- readable even if the source row is later edited.
    original_transaction_date DATE        NOT NULL,
    -- The date the user confirmed for the real card purchase; drives the
    -- deterministic invoice-cycle allocation.
    effective_purchase_date   DATE        NOT NULL,
    installment_count         INTEGER     NOT NULL,
    -- First invoice reference month (always day 1), explicitly confirmed by
    -- the user and validated against the deterministic cycle calculation.
    first_invoice_month       DATE        NOT NULL,
    status                    VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    converted_at              TIMESTAMPTZ NOT NULL,
    reversed_at               TIMESTAMPTZ,
    reversal_reason           VARCHAR(300),
    version                   BIGINT      NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_legacy_conversions_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_legacy_conversions_status CHECK (status IN ('ACTIVE', 'REVERSED')),
    -- A conversion is REVERSED exactly when its reversal timestamp exists.
    CONSTRAINT ck_legacy_conversions_reversal CHECK (
        (status = 'REVERSED') = (reversed_at IS NOT NULL)),
    CONSTRAINT ck_legacy_conversions_installments CHECK (
        installment_count BETWEEN 1 AND 120),
    CONSTRAINT ck_legacy_conversions_first_invoice_month CHECK (
        EXTRACT(DAY FROM first_invoice_month) = 1),
    -- Composite ownership keys: even if a service check is bypassed, the
    -- database rejects any cross-owner linkage.
    CONSTRAINT fk_legacy_conversions_source_owner
        FOREIGN KEY (source_transaction_id, user_id) REFERENCES transactions (id, user_id),
    CONSTRAINT fk_legacy_conversions_purchase_owner
        FOREIGN KEY (card_purchase_id, user_id) REFERENCES credit_card_purchases (id, user_id),
    CONSTRAINT fk_legacy_conversions_card_owner
        FOREIGN KEY (card_id, user_id) REFERENCES credit_cards (id, user_id)
);

-- One ACTIVE conversion per source transaction — the database backstop that
-- collapses concurrent conversion attempts into a single result.
CREATE UNIQUE INDEX uq_legacy_conversions_active_source
    ON legacy_credit_conversions (source_transaction_id) WHERE status = 'ACTIVE';

-- Each generated purchase belongs to exactly one conversion, ever.
CREATE UNIQUE INDEX uq_legacy_conversions_purchase
    ON legacy_credit_conversions (card_purchase_id);

-- Inventory and per-source history lookups.
CREATE INDEX ix_legacy_conversions_user_status
    ON legacy_credit_conversions (user_id, status);
CREATE INDEX ix_legacy_conversions_source
    ON legacy_credit_conversions (source_transaction_id);
