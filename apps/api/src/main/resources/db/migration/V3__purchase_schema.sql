-- Purchase planning schema: financial settings, wishlist items and purchase options.

-- Single-row table with the calculation assumptions used by budgets and
-- the purchase analysis engine. Kept in the database so preferences survive
-- restarts without introducing per-user identity in this release.
CREATE TABLE app_settings (
    id                               BIGINT        PRIMARY KEY,
    minimum_cash_buffer              NUMERIC(14,2) NOT NULL DEFAULT 0,
    max_installment_commitment_ratio NUMERIC(5,4)  NOT NULL DEFAULT 0.3000,
    monthly_opportunity_rate         NUMERIC(7,6)  NOT NULL DEFAULT 0.000000,
    budget_warning_threshold         NUMERIC(5,4)  NOT NULL DEFAULT 0.8000,
    created_at                       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_settings_singleton CHECK (id = 1),
    CONSTRAINT ck_settings_buffer_non_negative CHECK (minimum_cash_buffer >= 0),
    CONSTRAINT ck_settings_ratio_range CHECK (max_installment_commitment_ratio BETWEEN 0 AND 1),
    CONSTRAINT ck_settings_rate_range CHECK (monthly_opportunity_rate BETWEEN 0 AND 0.2),
    CONSTRAINT ck_settings_threshold_range CHECK (budget_warning_threshold BETWEEN 0 AND 1)
);

INSERT INTO app_settings (id) VALUES (1);

CREATE TABLE wishlist_items (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(150)  NOT NULL,
    notes           TEXT,
    category_id     BIGINT        REFERENCES categories (id),
    reference_price NUMERIC(14,2),
    target_price    NUMERIC(14,2),
    priority        VARCHAR(20)   NOT NULL DEFAULT 'MEDIUM',
    desired_date    DATE,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PLANNING',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_wishlist_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'ESSENTIAL')),
    CONSTRAINT ck_wishlist_status CHECK (
        status IN ('PLANNING', 'MONITORING', 'READY_TO_BUY', 'PURCHASED', 'ARCHIVED')),
    CONSTRAINT ck_wishlist_reference_price CHECK (reference_price IS NULL OR reference_price >= 0),
    CONSTRAINT ck_wishlist_target_price CHECK (target_price IS NULL OR target_price >= 0)
);

CREATE TABLE purchase_options (
    id                 BIGSERIAL PRIMARY KEY,
    wishlist_item_id   BIGINT        NOT NULL REFERENCES wishlist_items (id) ON DELETE CASCADE,
    merchant           VARCHAR(150)  NOT NULL,
    payment_kind       VARCHAR(20)   NOT NULL,
    base_price         NUMERIC(14,2) NOT NULL,
    shipping           NUMERIC(14,2) NOT NULL DEFAULT 0,
    fees               NUMERIC(14,2) NOT NULL DEFAULT 0,
    installment_count  INTEGER,
    installment_amount NUMERIC(14,2),
    notes              TEXT,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_options_kind CHECK (payment_kind IN ('CASH', 'INSTALLMENT')),
    CONSTRAINT ck_options_base_price CHECK (base_price > 0),
    CONSTRAINT ck_options_shipping CHECK (shipping >= 0),
    CONSTRAINT ck_options_fees CHECK (fees >= 0),
    CONSTRAINT ck_options_installment_count CHECK (installment_count IS NULL OR installment_count >= 1),
    CONSTRAINT ck_options_installment_amount CHECK (installment_amount IS NULL OR installment_amount > 0),
    -- installment options carry installment data; cash options must not
    CONSTRAINT ck_options_kind_consistency CHECK (
        (payment_kind = 'CASH' AND installment_count IS NULL AND installment_amount IS NULL)
        OR
        (payment_kind = 'INSTALLMENT' AND installment_count IS NOT NULL AND installment_amount IS NOT NULL))
);

CREATE INDEX ix_purchase_options_item ON purchase_options (wishlist_item_id);
