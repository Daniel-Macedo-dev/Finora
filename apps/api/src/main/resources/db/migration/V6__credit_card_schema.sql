-- Credit-card domain: cards, invoices, purchases, installments, payments and
-- adjustments. Every table is owned by a user and carries the same composite
-- (id, user_id) cross-owner protection introduced in V4: even if a service
-- check is bypassed, the database rejects a reference to another user's row.
--
-- Accounting model (see docs/credit-cards.md):
--   * A card purchase creates N installments, each assigned to one invoice.
--     Installments are the expense — recognized in their invoice month.
--   * Paying an invoice settles cash from an account. It restores card limit
--     and reduces the invoice outstanding balance, but it is NOT an expense.
--   * Invoice closing/due dates are snapshots taken when the invoice is
--     created; changing the card's closing/due days never mutates them.

CREATE TABLE credit_cards (
    id                         BIGSERIAL PRIMARY KEY,
    user_id                    BIGINT        NOT NULL REFERENCES users (id),
    name                       VARCHAR(100)  NOT NULL,
    issuer                     VARCHAR(100),
    brand                      VARCHAR(20)   NOT NULL,
    last_four_digits           CHAR(4),
    credit_limit               NUMERIC(14,2) NOT NULL,
    closing_day                INTEGER       NOT NULL,
    due_day                    INTEGER       NOT NULL,
    default_payment_account_id BIGINT,
    archived                   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_credit_cards_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_credit_cards_brand CHECK (
        brand IN ('VISA', 'MASTERCARD', 'ELO', 'AMEX', 'HIPERCARD', 'OTHER')),
    CONSTRAINT ck_credit_cards_last_four CHECK (
        last_four_digits IS NULL OR last_four_digits ~ '^[0-9]{4}$'),
    CONSTRAINT ck_credit_cards_limit_positive CHECK (credit_limit > 0),
    CONSTRAINT ck_credit_cards_closing_day CHECK (closing_day BETWEEN 1 AND 31),
    CONSTRAINT ck_credit_cards_due_day CHECK (due_day BETWEEN 1 AND 31),
    CONSTRAINT fk_credit_cards_payment_account_owner
        FOREIGN KEY (default_payment_account_id, user_id) REFERENCES accounts (id, user_id)
);

CREATE UNIQUE INDEX uq_credit_cards_user_name ON credit_cards (user_id, lower(name));
CREATE INDEX ix_credit_cards_user ON credit_cards (user_id);

CREATE TABLE credit_card_invoices (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users (id),
    card_id         BIGINT      NOT NULL,
    reference_month DATE        NOT NULL, -- always the first day of the month
    closing_date    DATE        NOT NULL, -- snapshot: immutable once created
    due_date        DATE        NOT NULL, -- snapshot: immutable once created
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_credit_card_invoices_id_user UNIQUE (id, user_id),
    CONSTRAINT uq_credit_card_invoices_card_month UNIQUE (user_id, card_id, reference_month),
    CONSTRAINT ck_credit_card_invoices_month_first_day CHECK (EXTRACT(DAY FROM reference_month) = 1),
    CONSTRAINT ck_credit_card_invoices_dates CHECK (due_date >= closing_date),
    CONSTRAINT fk_credit_card_invoices_card_owner
        FOREIGN KEY (card_id, user_id) REFERENCES credit_cards (id, user_id)
);

CREATE INDEX ix_credit_card_invoices_user_month ON credit_card_invoices (user_id, reference_month);
CREATE INDEX ix_credit_card_invoices_card ON credit_card_invoices (card_id);

CREATE TABLE credit_card_purchases (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT        NOT NULL REFERENCES users (id),
    card_id           BIGINT        NOT NULL,
    category_id       BIGINT        NOT NULL,
    description       VARCHAR(200)  NOT NULL,
    merchant          VARCHAR(150),
    purchase_date     DATE          NOT NULL,
    total_amount      NUMERIC(14,2) NOT NULL,
    installment_count INTEGER       NOT NULL,
    status            VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    wishlist_item_id  BIGINT,
    notes             TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_credit_card_purchases_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_credit_card_purchases_amount_positive CHECK (total_amount > 0),
    CONSTRAINT ck_credit_card_purchases_installments CHECK (installment_count >= 1),
    CONSTRAINT ck_credit_card_purchases_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT fk_credit_card_purchases_card_owner
        FOREIGN KEY (card_id, user_id) REFERENCES credit_cards (id, user_id),
    CONSTRAINT fk_credit_card_purchases_category_owner
        FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id),
    CONSTRAINT fk_credit_card_purchases_wishlist_owner
        FOREIGN KEY (wishlist_item_id, user_id) REFERENCES wishlist_items (id, user_id)
);

CREATE INDEX ix_credit_card_purchases_user ON credit_card_purchases (user_id);
CREATE INDEX ix_credit_card_purchases_card_date ON credit_card_purchases (card_id, purchase_date);
-- Durable idempotency for wishlist execution: a wishlist item can be
-- converted into at most one card purchase, no matter how many times the
-- request is retried.
CREATE UNIQUE INDEX uq_credit_card_purchases_wishlist_item
    ON credit_card_purchases (wishlist_item_id) WHERE wishlist_item_id IS NOT NULL;

CREATE TABLE credit_card_installments (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT        NOT NULL REFERENCES users (id),
    purchase_id        BIGINT        NOT NULL,
    invoice_id         BIGINT        NOT NULL,
    sequence_number    INTEGER       NOT NULL,
    total_installments INTEGER       NOT NULL,
    amount             NUMERIC(14,2) NOT NULL,
    status             VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_credit_card_installments_id_user UNIQUE (id, user_id),
    CONSTRAINT uq_credit_card_installments_sequence UNIQUE (purchase_id, sequence_number),
    CONSTRAINT ck_credit_card_installments_sequence CHECK (
        sequence_number >= 1 AND sequence_number <= total_installments),
    CONSTRAINT ck_credit_card_installments_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_credit_card_installments_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT fk_credit_card_installments_purchase_owner
        FOREIGN KEY (purchase_id, user_id) REFERENCES credit_card_purchases (id, user_id),
    CONSTRAINT fk_credit_card_installments_invoice_owner
        FOREIGN KEY (invoice_id, user_id) REFERENCES credit_card_invoices (id, user_id)
);

CREATE INDEX ix_credit_card_installments_invoice ON credit_card_installments (invoice_id);
CREATE INDEX ix_credit_card_installments_purchase ON credit_card_installments (purchase_id);
CREATE INDEX ix_credit_card_installments_user ON credit_card_installments (user_id);

CREATE TABLE credit_card_payments (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT        NOT NULL REFERENCES users (id),
    invoice_id  BIGINT        NOT NULL,
    account_id  BIGINT        NOT NULL,
    amount      NUMERIC(14,2) NOT NULL,
    paid_on     DATE          NOT NULL,
    status      VARCHAR(20)   NOT NULL DEFAULT 'COMPLETED',
    notes       TEXT,
    reversed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_credit_card_payments_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_credit_card_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_credit_card_payments_status CHECK (status IN ('COMPLETED', 'REVERSED')),
    -- A payment is REVERSED exactly when its reversal timestamp exists.
    CONSTRAINT ck_credit_card_payments_reversal CHECK (
        (status = 'REVERSED') = (reversed_at IS NOT NULL)),
    CONSTRAINT fk_credit_card_payments_invoice_owner
        FOREIGN KEY (invoice_id, user_id) REFERENCES credit_card_invoices (id, user_id),
    CONSTRAINT fk_credit_card_payments_account_owner
        FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id)
);

CREATE INDEX ix_credit_card_payments_invoice ON credit_card_payments (invoice_id);
CREATE INDEX ix_credit_card_payments_account ON credit_card_payments (account_id);
CREATE INDEX ix_credit_card_payments_user ON credit_card_payments (user_id);

-- Auditable invoice adjustments: fees, interest and other debits increase the
-- invoice; credits and refunds reduce it. Debit kinds require an expense
-- category so they participate in budgets like any other expense.
CREATE TABLE credit_card_adjustments (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT        NOT NULL REFERENCES users (id),
    invoice_id  BIGINT        NOT NULL,
    category_id BIGINT,
    kind        VARCHAR(20)   NOT NULL,
    description VARCHAR(200)  NOT NULL,
    amount      NUMERIC(14,2) NOT NULL,
    status      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    reversed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_credit_card_adjustments_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_credit_card_adjustments_kind CHECK (
        kind IN ('FEE', 'INTEREST', 'CREDIT', 'REFUND', 'OTHER_DEBIT')),
    CONSTRAINT ck_credit_card_adjustments_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_credit_card_adjustments_status CHECK (status IN ('ACTIVE', 'REVERSED')),
    CONSTRAINT ck_credit_card_adjustments_reversal CHECK (
        (status = 'REVERSED') = (reversed_at IS NOT NULL)),
    -- Debit kinds are expenses and must carry an expense category.
    CONSTRAINT ck_credit_card_adjustments_debit_category CHECK (
        kind IN ('CREDIT', 'REFUND') OR category_id IS NOT NULL),
    CONSTRAINT fk_credit_card_adjustments_invoice_owner
        FOREIGN KEY (invoice_id, user_id) REFERENCES credit_card_invoices (id, user_id),
    CONSTRAINT fk_credit_card_adjustments_category_owner
        FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id)
);

CREATE INDEX ix_credit_card_adjustments_invoice ON credit_card_adjustments (invoice_id);
CREATE INDEX ix_credit_card_adjustments_user ON credit_card_adjustments (user_id);
