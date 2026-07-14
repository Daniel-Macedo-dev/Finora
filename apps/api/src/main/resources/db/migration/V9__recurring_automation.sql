-- Recurring automation: commitments become full recurring definitions with an
-- execution target (projection-only, account transaction or credit-card
-- purchase) and an auditable occurrence ledger.
--
-- Migration safety for existing rows: every current commitment keeps its
-- behavior — MANUAL execution, PROJECTION_ONLY target, one installment. A
-- legacy commitment with payment_method = 'CREDIT' therefore never becomes a
-- generic credit transaction: it stays projection-only until the user assigns
-- a real credit card.

-- ── Recurring definition evolution ──────────────────────────────────────────

ALTER TABLE commitments DROP CONSTRAINT ck_commitments_cadence;
ALTER TABLE commitments ADD CONSTRAINT ck_commitments_cadence
    CHECK (cadence IN ('WEEKLY', 'MONTHLY', 'YEARLY'));

ALTER TABLE commitments ADD COLUMN execution_mode VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE commitments ADD COLUMN target_kind VARCHAR(30) NOT NULL DEFAULT 'PROJECTION_ONLY';
ALTER TABLE commitments ADD COLUMN account_id BIGINT;
ALTER TABLE commitments ADD COLUMN credit_card_id BIGINT;
ALTER TABLE commitments ADD COLUMN installment_count INT NOT NULL DEFAULT 1;

ALTER TABLE commitments ADD CONSTRAINT ck_commitments_execution_mode
    CHECK (execution_mode IN ('MANUAL', 'AUTOMATIC'));
ALTER TABLE commitments ADD CONSTRAINT ck_commitments_target_kind
    CHECK (target_kind IN ('PROJECTION_ONLY', 'ACCOUNT_TRANSACTION', 'CREDIT_CARD_PURCHASE'));
ALTER TABLE commitments ADD CONSTRAINT ck_commitments_installment_count
    CHECK (installment_count BETWEEN 1 AND 120);
-- Target coherence lives in the database too: each target kind carries exactly
-- the reference it needs, and automation requires a concrete target.
ALTER TABLE commitments ADD CONSTRAINT ck_commitments_target_refs CHECK (
    (target_kind = 'PROJECTION_ONLY'        AND account_id IS NULL AND credit_card_id IS NULL)
 OR (target_kind = 'ACCOUNT_TRANSACTION'    AND account_id IS NOT NULL AND credit_card_id IS NULL)
 OR (target_kind = 'CREDIT_CARD_PURCHASE'   AND credit_card_id IS NOT NULL AND account_id IS NULL)
);
ALTER TABLE commitments ADD CONSTRAINT ck_commitments_automatic_has_target
    CHECK (execution_mode <> 'AUTOMATIC' OR target_kind <> 'PROJECTION_ONLY');

-- Composite ownership keys: commitments and transactions did not yet expose
-- UNIQUE (id, user_id) — required for cross-owner-safe composite foreign keys.
ALTER TABLE commitments  ADD CONSTRAINT uq_commitments_id_user  UNIQUE (id, user_id);
ALTER TABLE transactions ADD CONSTRAINT uq_transactions_id_user UNIQUE (id, user_id);

ALTER TABLE commitments ADD CONSTRAINT fk_commitments_account_owner
    FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id);
ALTER TABLE commitments ADD CONSTRAINT fk_commitments_card_owner
    FOREIGN KEY (credit_card_id, user_id) REFERENCES credit_cards (id, user_id);

-- ── Occurrence ledger ────────────────────────────────────────────────────────

CREATE TABLE commitment_occurrences (
    id                BIGSERIAL     PRIMARY KEY,
    user_id           BIGINT        NOT NULL REFERENCES users (id),
    commitment_id     BIGINT        NOT NULL,
    -- Stable identity: the date the recurrence originally scheduled.
    scheduled_date    DATE          NOT NULL,
    -- Where the occurrence currently lands (reschedule moves only this).
    effective_date    DATE          NOT NULL,
    status            VARCHAR(20)   NOT NULL DEFAULT 'SCHEDULED',
    auto_generated    BOOLEAN       NOT NULL DEFAULT FALSE,
    transaction_id    BIGINT,
    card_purchase_id  BIGINT,
    failure_code      VARCHAR(60),
    failure_message   VARCHAR(300),
    materialized_at   TIMESTAMPTZ,
    reversed_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_commitment_occurrences_id_user UNIQUE (id, user_id),
    -- One identity per definition and original scheduled date: the durable
    -- idempotency anchor for generation, materialization and catch-up.
    CONSTRAINT uq_commitment_occurrences_identity UNIQUE (commitment_id, scheduled_date),
    CONSTRAINT ck_commitment_occurrences_status
        CHECK (status IN ('SCHEDULED', 'MATERIALIZED', 'SKIPPED', 'FAILED', 'REVERSED')),
    -- An occurrence produces at most one artifact, never both kinds.
    CONSTRAINT ck_commitment_occurrences_single_artifact
        CHECK (transaction_id IS NULL OR card_purchase_id IS NULL),
    CONSTRAINT fk_commitment_occurrences_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_commitment_occurrences_commitment_owner
        FOREIGN KEY (commitment_id, user_id) REFERENCES commitments (id, user_id),
    CONSTRAINT fk_commitment_occurrences_transaction_owner
        FOREIGN KEY (transaction_id, user_id) REFERENCES transactions (id, user_id),
    CONSTRAINT fk_commitment_occurrences_purchase_owner
        FOREIGN KEY (card_purchase_id, user_id) REFERENCES credit_card_purchases (id, user_id)
);

-- Each generated artifact belongs to exactly one occurrence, regardless of
-- application-level races.
CREATE UNIQUE INDEX uq_commitment_occurrences_transaction
    ON commitment_occurrences (transaction_id) WHERE transaction_id IS NOT NULL;
CREATE UNIQUE INDEX uq_commitment_occurrences_purchase
    ON commitment_occurrences (card_purchase_id) WHERE card_purchase_id IS NOT NULL;

-- Due processing scans and per-user timelines.
CREATE INDEX ix_commitment_occurrences_due
    ON commitment_occurrences (status, effective_date);
CREATE INDEX ix_commitment_occurrences_user_date
    ON commitment_occurrences (user_id, effective_date);
CREATE INDEX ix_commitment_occurrences_commitment
    ON commitment_occurrences (commitment_id, scheduled_date);

-- ── Generated-artifact traceability ─────────────────────────────────────────

-- Display-level origin on the artifacts themselves (the occurrence row is the
-- authoritative link; these columns follow the wishlist_item_id precedent).
ALTER TABLE transactions ADD COLUMN commitment_id BIGINT;
ALTER TABLE transactions ADD CONSTRAINT fk_transactions_commitment_owner
    FOREIGN KEY (commitment_id, user_id) REFERENCES commitments (id, user_id);

ALTER TABLE credit_card_purchases ADD COLUMN commitment_id BIGINT;
ALTER TABLE credit_card_purchases ADD CONSTRAINT fk_credit_card_purchases_commitment_owner
    FOREIGN KEY (commitment_id, user_id) REFERENCES commitments (id, user_id);

CREATE INDEX ix_transactions_commitment ON transactions (commitment_id)
    WHERE commitment_id IS NOT NULL;
CREATE INDEX ix_credit_card_purchases_commitment ON credit_card_purchases (commitment_id)
    WHERE commitment_id IS NOT NULL;
