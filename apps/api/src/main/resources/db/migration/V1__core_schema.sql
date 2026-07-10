-- Core financial schema: accounts, categories and transactions.

CREATE TABLE accounts (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100)  NOT NULL,
    type            VARCHAR(20)   NOT NULL,
    opening_balance NUMERIC(14,2) NOT NULL DEFAULT 0,
    archived        BOOLEAN       NOT NULL DEFAULT FALSE,
    display_order   INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_accounts_name UNIQUE (name),
    CONSTRAINT ck_accounts_type CHECK (type IN ('CHECKING', 'SAVINGS', 'CASH', 'OTHER'))
);

CREATE TABLE categories (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(60) NOT NULL,
    type       VARCHAR(10) NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    is_default BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_categories_name_type UNIQUE (name, type),
    CONSTRAINT ck_categories_type CHECK (type IN ('INCOME', 'EXPENSE'))
);

CREATE TABLE transactions (
    id             BIGSERIAL PRIMARY KEY,
    type           VARCHAR(10)   NOT NULL,
    amount         NUMERIC(14,2) NOT NULL,
    description    VARCHAR(200)  NOT NULL,
    occurred_on    DATE          NOT NULL,
    category_id    BIGINT        NOT NULL REFERENCES categories (id),
    account_id     BIGINT        REFERENCES accounts (id),
    payment_method VARCHAR(20),
    notes          TEXT,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_transactions_type CHECK (type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT ck_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_transactions_payment_method CHECK (
        payment_method IS NULL
        OR payment_method IN ('PIX', 'DEBIT', 'CREDIT', 'CASH', 'BANK_TRANSFER', 'OTHER')
    )
);

CREATE INDEX ix_transactions_occurred_on ON transactions (occurred_on);
CREATE INDEX ix_transactions_category_id ON transactions (category_id);
CREATE INDEX ix_transactions_account_id ON transactions (account_id);

-- Default categories shipped with a fresh installation (user-facing names in pt-BR).
INSERT INTO categories (name, type, is_default) VALUES
    ('Moradia',     'EXPENSE', TRUE),
    ('Alimentação', 'EXPENSE', TRUE),
    ('Transporte',  'EXPENSE', TRUE),
    ('Saúde',       'EXPENSE', TRUE),
    ('Educação',    'EXPENSE', TRUE),
    ('Lazer',       'EXPENSE', TRUE),
    ('Compras',     'EXPENSE', TRUE),
    ('Assinaturas', 'EXPENSE', TRUE),
    ('Outros',      'EXPENSE', TRUE),
    ('Salário',     'INCOME',  TRUE),
    ('Freelance',   'INCOME',  TRUE),
    ('Outros',      'INCOME',  TRUE);
