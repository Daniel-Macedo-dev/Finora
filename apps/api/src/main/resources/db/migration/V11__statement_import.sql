-- Statement import: auditable bank-statement (CSV/OFX) import into existing
-- checking/savings accounts.
--
-- Model (see docs/statement-import.md):
--   * A batch records one upload: destination account, sanitized filename,
--     format, file hash and parser/fingerprint versions. The raw uploaded
--     bytes are NEVER persisted here — CSV files awaiting column mapping live
--     in bounded temporary storage referenced by temp_file_token and are
--     discarded after the authoritative parse.
--   * An item is one normalized statement row: a preview only, until the user
--     confirms it. One included item materializes into at most one ordinary
--     transaction — enforced by a partial unique index on
--     transactions.statement_import_item_id, the database backstop that
--     collapses concurrent confirmations into a single transaction.
--   * Category mapping rules are deterministic, owner-scoped text matchers —
--     no regex, no statistics.
--
-- Migration safety for existing rows: no existing transaction is marked as
-- imported; the new tables start empty.

-- ── Import batches ───────────────────────────────────────────────────────────

CREATE TABLE statement_import_batches (
    id                  BIGSERIAL     PRIMARY KEY,
    user_id             BIGINT        NOT NULL REFERENCES users (id),
    account_id          BIGINT        NOT NULL,
    original_filename   VARCHAR(255)  NOT NULL,
    format              VARCHAR(10)   NOT NULL,
    file_sha256         VARCHAR(64)   NOT NULL,
    file_size_bytes     BIGINT        NOT NULL,
    parser_version      INTEGER       NOT NULL,
    fingerprint_version INTEGER       NOT NULL,
    -- CSV column/locale mapping chosen by the user, serialized as bounded
    -- JSON. Configuration only — never statement content.
    csv_mapping         VARCHAR(2000),
    -- Random name of the bounded temporary file kept only while a CSV batch
    -- waits for column mapping; cleared when the authoritative parse discards
    -- the raw bytes.
    temp_file_token     VARCHAR(64),
    status              VARCHAR(20)   NOT NULL,
    total_rows          INTEGER       NOT NULL DEFAULT 0,
    confirmed_at        TIMESTAMPTZ,
    undone_at           TIMESTAMPTZ,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_import_batches_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_import_batches_format CHECK (format IN ('CSV', 'OFX')),
    CONSTRAINT ck_import_batches_status CHECK (status IN
        ('NEEDS_MAPPING', 'PREVIEW_READY', 'COMPLETED', 'PARTIALLY_COMPLETED', 'UNDONE')),
    -- Only CSV uploads can wait for column mapping.
    CONSTRAINT ck_import_batches_mapping_is_csv CHECK (
        status <> 'NEEDS_MAPPING' OR format = 'CSV'),
    CONSTRAINT ck_import_batches_total_rows CHECK (total_rows >= 0),
    -- Cross-owner destination accounts are rejected by the database itself.
    CONSTRAINT fk_import_batches_account_owner
        FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id)
);

-- Import history (newest first) and per-account listings.
CREATE INDEX ix_import_batches_user_created
    ON statement_import_batches (user_id, created_at DESC);
CREATE INDEX ix_import_batches_user_account
    ON statement_import_batches (user_id, account_id);
-- Exact file reupload detection.
CREATE INDEX ix_import_batches_user_file_hash
    ON statement_import_batches (user_id, file_sha256);

-- ── Import items ─────────────────────────────────────────────────────────────

CREATE TABLE statement_import_items (
    id                     BIGSERIAL     PRIMARY KEY,
    user_id                BIGINT        NOT NULL REFERENCES users (id),
    batch_id               BIGINT        NOT NULL,
    -- Denormalized copy of the batch destination (kept in sync while the
    -- batch is editable, frozen once imported) so the strong-identity and
    -- fingerprint backstops below can be plain indexes.
    account_id             BIGINT        NOT NULL,
    -- 1-based CSV row number or OFX STMTTRN sequence.
    source_index           INTEGER       NOT NULL,
    -- OFX FITID or explicitly mapped CSV external-id column.
    external_id            VARCHAR(255),
    -- OFX TRNTYPE (or 'CSV' for CSV rows); preview information only.
    source_type            VARCHAR(40),
    -- Effective (user-editable) normalized values used at materialization.
    posted_date            DATE,
    amount                 NUMERIC(14,2),
    type                   VARCHAR(10),
    description            VARCHAR(200),
    -- Immutable parsed originals, preserved for audit when the user edits.
    original_date          DATE,
    original_amount        NUMERIC(14,2),
    original_type          VARCHAR(10),
    original_description   VARCHAR(500),
    -- Canonical matching representation of the description (case/accent/
    -- whitespace-folded); input to the content fingerprint and rule engine.
    normalized_description VARCHAR(200),
    memo                   VARCHAR(500),
    -- Versioned SHA-256 content fingerprint (owner, account, date, type,
    -- amount, normalized description).
    fingerprint            VARCHAR(64),
    suggested_category_id  BIGINT,
    -- Rule that produced the suggestion. Informational snapshot — no FK, so
    -- deleting a rule never blocks on (or cascades into) the import ledger.
    matched_rule_id        BIGINT,
    selected_category_id   BIGINT,
    included               BOOLEAN       NOT NULL DEFAULT TRUE,
    duplicate_status       VARCHAR(30)   NOT NULL DEFAULT 'UNIQUE',
    -- Explicit user decision to import a possible duplicate anyway.
    duplicate_override     BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Existing transaction a possible duplicate matched. Informational — no
    -- FK, so deleting that transaction later never breaks the ledger.
    matched_transaction_id BIGINT,
    status                 VARCHAR(20)   NOT NULL,
    validation_code        VARCHAR(60),
    validation_message     VARCHAR(300),
    -- Structured outcome of the latest confirmation attempt.
    result_code            VARCHAR(60),
    result_message         VARCHAR(300),
    imported_at            TIMESTAMPTZ,
    undone_at              TIMESTAMPTZ,
    version                BIGINT        NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_import_items_id_user UNIQUE (id, user_id),
    CONSTRAINT uq_import_items_batch_row UNIQUE (batch_id, source_index),
    CONSTRAINT ck_import_items_status CHECK (status IN
        ('READY', 'INVALID', 'IMPORTED', 'FAILED', 'SKIPPED', 'UNDONE')),
    CONSTRAINT ck_import_items_duplicate_status CHECK (duplicate_status IN
        ('UNIQUE', 'EXACT_DUPLICATE', 'POSSIBLE_DUPLICATE', 'DUPLICATE_WITHIN_FILE')),
    CONSTRAINT ck_import_items_type CHECK (type IS NULL OR type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT ck_import_items_original_type CHECK (
        original_type IS NULL OR original_type IN ('INCOME', 'EXPENSE')),
    -- Finora amounts are always positive; direction lives in the type.
    CONSTRAINT ck_import_items_amount_positive CHECK (amount IS NULL OR amount > 0),
    CONSTRAINT ck_import_items_original_amount_positive CHECK (
        original_amount IS NULL OR original_amount > 0),
    -- An IMPORTED item carries its import timestamp; an UNDONE one its undo.
    CONSTRAINT ck_import_items_imported_at CHECK (
        status NOT IN ('IMPORTED', 'UNDONE') OR imported_at IS NOT NULL),
    CONSTRAINT ck_import_items_undone_at CHECK (
        (status = 'UNDONE') = (undone_at IS NOT NULL)),
    CONSTRAINT fk_import_items_batch_owner
        FOREIGN KEY (batch_id, user_id) REFERENCES statement_import_batches (id, user_id),
    CONSTRAINT fk_import_items_account_owner
        FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id),
    CONSTRAINT fk_import_items_suggested_category_owner
        FOREIGN KEY (suggested_category_id, user_id) REFERENCES categories (id, user_id),
    CONSTRAINT fk_import_items_selected_category_owner
        FOREIGN KEY (selected_category_id, user_id) REFERENCES categories (id, user_id)
);

-- Strong source identity: the same external id (OFX FITID / mapped CSV id)
-- can be IMPORTED at most once per owner and destination account, no matter
-- how many uploads, retries or concurrent confirmations race for it.
CREATE UNIQUE INDEX uq_import_items_external_imported
    ON statement_import_items (user_id, account_id, external_id)
    WHERE external_id IS NOT NULL AND status = 'IMPORTED';

-- Content-fingerprint lookups against previously imported rows.
CREATE INDEX ix_import_items_fingerprint_imported
    ON statement_import_items (user_id, account_id, fingerprint)
    WHERE fingerprint IS NOT NULL AND status = 'IMPORTED';

-- Batch detail and confirmation scans.
CREATE INDEX ix_import_items_batch_status
    ON statement_import_items (batch_id, status);

-- ── Generated-transaction link ───────────────────────────────────────────────

-- Source pointer on the transaction, following the wishlist_item_id and
-- commitment_id precedents: set exactly at materialization, never editable.
ALTER TABLE transactions ADD COLUMN statement_import_item_id BIGINT;

ALTER TABLE transactions ADD CONSTRAINT fk_transactions_import_item_owner
    FOREIGN KEY (statement_import_item_id, user_id)
    REFERENCES statement_import_items (id, user_id);

-- One live transaction per import item, ever — the idempotency/concurrency
-- backstop of the whole confirmation flow.
CREATE UNIQUE INDEX uq_transactions_import_item
    ON transactions (statement_import_item_id)
    WHERE statement_import_item_id IS NOT NULL;

-- ── Deterministic category-mapping rules ─────────────────────────────────────

CREATE TABLE category_mapping_rules (
    id               BIGSERIAL    PRIMARY KEY,
    user_id          BIGINT       NOT NULL REFERENCES users (id),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    transaction_type VARCHAR(10)  NOT NULL,
    -- Optional account scope: an account-specific rule beats a global one.
    account_id       BIGINT,
    match_field      VARCHAR(20)  NOT NULL DEFAULT 'DESCRIPTION',
    operation        VARCHAR(20)  NOT NULL,
    -- Pattern stored in the same canonical normalization applied to
    -- statement descriptions (lowercase, accent- and whitespace-folded).
    pattern          VARCHAR(200) NOT NULL,
    category_id      BIGINT       NOT NULL,
    priority         INTEGER      NOT NULL DEFAULT 0,
    match_count      BIGINT       NOT NULL DEFAULT 0,
    last_used_at     TIMESTAMPTZ,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_category_rules_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_category_rules_type CHECK (transaction_type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT ck_category_rules_field CHECK (match_field IN ('DESCRIPTION', 'MEMO')),
    -- Deterministic, ReDoS-free operations only — no user regex.
    CONSTRAINT ck_category_rules_operation CHECK (
        operation IN ('EXACT', 'STARTS_WITH', 'CONTAINS')),
    CONSTRAINT ck_category_rules_pattern_not_blank CHECK (length(trim(pattern)) > 0),
    CONSTRAINT fk_category_rules_account_owner
        FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id),
    CONSTRAINT fk_category_rules_category_owner
        FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id)
);

-- No two rules with the same identity for the same owner (0 = global scope).
CREATE UNIQUE INDEX uq_category_rules_identity
    ON category_mapping_rules
       (user_id, transaction_type, COALESCE(account_id, 0), match_field, operation, pattern);

-- Rule-engine scans load one owner's active rules.
CREATE INDEX ix_category_rules_user_active
    ON category_mapping_rules (user_id, active);
