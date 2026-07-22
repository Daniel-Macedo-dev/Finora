-- Manual, owner-scoped historical price observations for wishlist items.
-- Existing reference prices and purchase options intentionally do not create
-- observations: history begins only with an explicit user action.

CREATE TABLE wishlist_price_snapshots (
    id                   BIGSERIAL      PRIMARY KEY,
    user_id              BIGINT         NOT NULL REFERENCES users (id),
    wishlist_item_id     BIGINT         NOT NULL,
    purchase_option_id   BIGINT         REFERENCES purchase_options (id) ON DELETE SET NULL,
    series_key            VARCHAR(220)   NOT NULL,
    client_request_id     UUID           NOT NULL,
    merchant              VARCHAR(150)   NOT NULL,
    merchant_normalized   VARCHAR(150)   NOT NULL,
    payment_kind          VARCHAR(20)    NOT NULL,
    base_price            NUMERIC(14,2)  NOT NULL,
    shipping              NUMERIC(14,2)  NOT NULL DEFAULT 0,
    fees                  NUMERIC(14,2)  NOT NULL DEFAULT 0,
    nominal_cost          NUMERIC(14,2)  NOT NULL,
    installment_count     INTEGER,
    installment_amount    NUMERIC(14,2),
    observed_on           DATE           NOT NULL,
    offer_url              VARCHAR(2000),
    notes                  VARCHAR(2000),
    version                BIGINT         NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uq_price_snapshots_user_request UNIQUE (user_id, client_request_id),
    CONSTRAINT fk_price_snapshots_item_owner
        FOREIGN KEY (wishlist_item_id, user_id)
        REFERENCES wishlist_items (id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_price_snapshots_kind
        CHECK (payment_kind IN ('CASH', 'INSTALLMENT')),
    CONSTRAINT ck_price_snapshots_merchant
        CHECK (length(trim(merchant)) BETWEEN 1 AND 150),
    CONSTRAINT ck_price_snapshots_merchant_normalized
        CHECK (length(trim(merchant_normalized)) BETWEEN 1 AND 150),
    CONSTRAINT ck_price_snapshots_series_key
        CHECK (length(trim(series_key)) BETWEEN 1 AND 220),
    CONSTRAINT ck_price_snapshots_base_price CHECK (base_price > 0),
    CONSTRAINT ck_price_snapshots_shipping CHECK (shipping >= 0),
    CONSTRAINT ck_price_snapshots_fees CHECK (fees >= 0),
    CONSTRAINT ck_price_snapshots_nominal_cost CHECK (
        nominal_cost > 0 AND nominal_cost = base_price + shipping + fees),
    CONSTRAINT ck_price_snapshots_payment_shape CHECK (
        (payment_kind = 'CASH'
            AND installment_count IS NULL
            AND installment_amount IS NULL)
        OR
        (payment_kind = 'INSTALLMENT'
            AND installment_count BETWEEN 1 AND 120
            AND installment_amount > 0)),
    CONSTRAINT ck_price_snapshots_offer_url CHECK (
        offer_url IS NULL
        OR (length(offer_url) <= 2000
            AND offer_url !~ '[[:cntrl:]]'
            AND offer_url ~* '^https?://[^[:space:]]+$')),
    CONSTRAINT ck_price_snapshots_notes CHECK (
        notes IS NULL OR length(notes) <= 2000)
);

-- Stable raw-history pagination and item-wide summaries.
CREATE INDEX ix_price_snapshots_item_history
    ON wishlist_price_snapshots (user_id, wishlist_item_id, observed_on DESC, id DESC);

-- Latest/previous observations within a trusted server-generated series.
CREATE INDEX ix_price_snapshots_series_history
    ON wishlist_price_snapshots
        (user_id, wishlist_item_id, series_key, observed_on DESC, id DESC);

-- Case-insensitive exact merchant filtering without scanning another owner.
CREATE INDEX ix_price_snapshots_merchant
    ON wishlist_price_snapshots (user_id, wishlist_item_id, merchant_normalized);

