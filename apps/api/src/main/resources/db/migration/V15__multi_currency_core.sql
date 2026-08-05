-- Native currency for every monetary root.
--
-- Finora stored amounts without a currency because every value was BRL by
-- construction. Multi-currency makes that assumption explicit: each monetary
-- root now names the currency its amounts are denominated in, and children
-- inherit from their authoritative parent instead of carrying a duplicate
-- column that could drift.
--
-- Backfill contract: every existing row becomes BRL and no numeric value
-- changes. The ledger is reinterpreted by nobody -- it is simply labelled with
-- the currency it always had.
--
-- Column type is CHAR-like VARCHAR(3) holding an ISO 4217 alphabetic code
-- constrained to the application's closed catalogue (CurrencyCode). Storage
-- scale of the amounts stays NUMERIC(14,2) for every currency; JPY's
-- zero-decimal rule is an application invariant, not a column type.

-- ---------------------------------------------------------------------------
-- Base currency: one per user, the denomination of budgets and the settings
-- that are base-denominated rather than tied to a single account.
-- ---------------------------------------------------------------------------

-- The DEFAULT is kept here on purpose: a newly registered user's base currency
-- really is BRL until they change it, so an insert that omits it is correct.
ALTER TABLE app_settings
    ADD COLUMN base_currency VARCHAR(3) NOT NULL DEFAULT 'BRL';

ALTER TABLE app_settings
    ADD CONSTRAINT ck_app_settings_base_currency
    CHECK (base_currency IN ('BRL','USD','EUR','GBP','CAD','AUD','CHF','JPY'));

-- ---------------------------------------------------------------------------
-- Monetary roots.
--
-- Each column is added with a temporary DEFAULT so the backfill labels every
-- existing row as BRL, then the DEFAULT is dropped. Without dropping it a
-- future insert that forgets the currency would silently become BRL, which is
-- exactly the ambiguity this migration exists to remove: after V15 the
-- application must resolve the currency explicitly.
-- ---------------------------------------------------------------------------

ALTER TABLE accounts        ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'BRL';
ALTER TABLE transactions    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'BRL';
ALTER TABLE credit_cards    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'BRL';
ALTER TABLE commitments     ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'BRL';
ALTER TABLE goals           ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'BRL';
ALTER TABLE wishlist_items  ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'BRL';
ALTER TABLE notifications   ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'BRL';

ALTER TABLE accounts        ALTER COLUMN currency DROP DEFAULT;
ALTER TABLE transactions    ALTER COLUMN currency DROP DEFAULT;
ALTER TABLE credit_cards    ALTER COLUMN currency DROP DEFAULT;
ALTER TABLE commitments     ALTER COLUMN currency DROP DEFAULT;
ALTER TABLE goals           ALTER COLUMN currency DROP DEFAULT;
ALTER TABLE wishlist_items  ALTER COLUMN currency DROP DEFAULT;
ALTER TABLE notifications   ALTER COLUMN currency DROP DEFAULT;

ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_currency
    CHECK (currency IN ('BRL','USD','EUR','GBP','CAD','AUD','CHF','JPY'));
ALTER TABLE transactions
    ADD CONSTRAINT ck_transactions_currency
    CHECK (currency IN ('BRL','USD','EUR','GBP','CAD','AUD','CHF','JPY'));
ALTER TABLE credit_cards
    ADD CONSTRAINT ck_credit_cards_currency
    CHECK (currency IN ('BRL','USD','EUR','GBP','CAD','AUD','CHF','JPY'));
ALTER TABLE commitments
    ADD CONSTRAINT ck_commitments_currency
    CHECK (currency IN ('BRL','USD','EUR','GBP','CAD','AUD','CHF','JPY'));
ALTER TABLE goals
    ADD CONSTRAINT ck_goals_currency
    CHECK (currency IN ('BRL','USD','EUR','GBP','CAD','AUD','CHF','JPY'));
ALTER TABLE wishlist_items
    ADD CONSTRAINT ck_wishlist_items_currency
    CHECK (currency IN ('BRL','USD','EUR','GBP','CAD','AUD','CHF','JPY'));
ALTER TABLE notifications
    ADD CONSTRAINT ck_notifications_currency
    CHECK (currency IN ('BRL','USD','EUR','GBP','CAD','AUD','CHF','JPY'));

-- ---------------------------------------------------------------------------
-- Same-currency integrity at the database layer.
--
-- Children inherit currency from their authoritative parent (card purchases,
-- installments, invoices and adjustments from the card; contributions from the
-- goal; occurrences from the commitment; options and price snapshots from the
-- wishlist item; import items from the account). They deliberately get no
-- currency column: a duplicate would be a second source of truth that could
-- drift from the parent.
--
-- Where a same-currency rule crosses two roots, a composite unique key gives
-- the child FK something to reference so the pairing cannot be violated even
-- if a service forgets to check. Postgres needs a UNIQUE target for a
-- composite FK, so the (id, currency) keys below exist purely to be
-- referenced -- id alone is already the primary key.
-- ---------------------------------------------------------------------------

ALTER TABLE accounts
    ADD CONSTRAINT uq_accounts_id_currency UNIQUE (id, currency);
ALTER TABLE credit_cards
    ADD CONSTRAINT uq_credit_cards_id_currency UNIQUE (id, currency);

-- A transaction settles in the currency of the account it moves money in.
-- Accountless transactions (account_id IS NULL) are unconstrained here: they
-- carry their own currency and have no account to agree with. Postgres skips
-- FK enforcement when any referencing column is NULL under MATCH SIMPLE, which
-- gives exactly that behaviour.
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_account_currency
    FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency);

-- A card's default payment account must settle in the card's currency, so an
-- invoice can never be paid from an account of a different denomination.
ALTER TABLE credit_cards
    ADD CONSTRAINT fk_credit_cards_payment_account_currency
    FOREIGN KEY (default_payment_account_id, currency) REFERENCES accounts (id, currency);
