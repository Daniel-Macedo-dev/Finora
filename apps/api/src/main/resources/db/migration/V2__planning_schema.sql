-- Planning schema: monthly budgets, recurring commitments and savings goals.

CREATE TABLE budgets (
    id           BIGSERIAL PRIMARY KEY,
    month_ref    DATE          NOT NULL, -- always the first day of the month
    category_id  BIGINT        NOT NULL REFERENCES categories (id),
    limit_amount NUMERIC(14,2) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_budgets_month_category UNIQUE (month_ref, category_id),
    CONSTRAINT ck_budgets_limit_positive CHECK (limit_amount > 0),
    CONSTRAINT ck_budgets_month_first_day CHECK (EXTRACT(DAY FROM month_ref) = 1)
);

CREATE INDEX ix_budgets_month_ref ON budgets (month_ref);

CREATE TABLE commitments (
    id             BIGSERIAL PRIMARY KEY,
    description    VARCHAR(200)  NOT NULL,
    amount         NUMERIC(14,2) NOT NULL,
    category_id    BIGINT        NOT NULL REFERENCES categories (id),
    cadence        VARCHAR(20)   NOT NULL,
    due_day        INTEGER,
    start_date     DATE          NOT NULL,
    end_date       DATE,
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    payment_method VARCHAR(20),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_commitments_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_commitments_cadence CHECK (cadence IN ('MONTHLY', 'YEARLY')),
    CONSTRAINT ck_commitments_due_day CHECK (due_day IS NULL OR (due_day BETWEEN 1 AND 31)),
    CONSTRAINT ck_commitments_period CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX ix_commitments_active ON commitments (active);

CREATE TABLE goals (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(100)  NOT NULL,
    target_amount  NUMERIC(14,2) NOT NULL,
    current_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    target_date    DATE,
    archived       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_goals_target_positive CHECK (target_amount > 0),
    CONSTRAINT ck_goals_current_non_negative CHECK (current_amount >= 0)
);
