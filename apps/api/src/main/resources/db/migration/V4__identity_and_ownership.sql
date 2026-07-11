-- Identity and per-user data ownership.
--
-- Existing v1 data (if any) is preserved: it is assigned to a special
-- PENDING_LEGACY_CLAIM user that cannot authenticate. Ownership is transferred
-- later through the environment-gated legacy claim flow. On a fresh database
-- the seeded global defaults are removed instead — every new user receives
-- their own default categories and settings at registration time.

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    display_name  VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status        VARCHAR(30)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED', 'PENDING_LEGACY_CLAIM'))
);

-- Email uniqueness is case-insensitive: the application always normalizes to
-- lower case, and the database enforces it independently.
CREATE UNIQUE INDEX uq_users_email ON users (lower(email));

-- 1. Add nullable ownership columns.
ALTER TABLE accounts       ADD COLUMN user_id BIGINT;
ALTER TABLE categories     ADD COLUMN user_id BIGINT;
ALTER TABLE transactions   ADD COLUMN user_id BIGINT;
ALTER TABLE budgets        ADD COLUMN user_id BIGINT;
ALTER TABLE commitments    ADD COLUMN user_id BIGINT;
ALTER TABLE goals          ADD COLUMN user_id BIGINT;
ALTER TABLE wishlist_items ADD COLUMN user_id BIGINT;
ALTER TABLE app_settings   ADD COLUMN user_id BIGINT;

-- 2. Preserve v1 data under a pending legacy owner, or clean seeded defaults
--    on a fresh installation.
DO $$
DECLARE
    has_personal_data BOOLEAN;
    legacy_id BIGINT;
BEGIN
    SELECT EXISTS (SELECT 1 FROM transactions)
        OR EXISTS (SELECT 1 FROM accounts)
        OR EXISTS (SELECT 1 FROM budgets)
        OR EXISTS (SELECT 1 FROM commitments)
        OR EXISTS (SELECT 1 FROM goals)
        OR EXISTS (SELECT 1 FROM wishlist_items)
    INTO has_personal_data;

    IF has_personal_data THEN
        -- The placeholder hash is not a valid bcrypt value, so no password can
        -- ever match it; the PENDING_LEGACY_CLAIM status additionally blocks
        -- authentication at the application level.
        INSERT INTO users (display_name, email, password_hash, status)
        VALUES ('Dados anteriores do Finora', 'legacy@finora.local', 'unclaimable', 'PENDING_LEGACY_CLAIM')
        RETURNING id INTO legacy_id;

        UPDATE accounts       SET user_id = legacy_id;
        UPDATE categories     SET user_id = legacy_id;
        UPDATE transactions   SET user_id = legacy_id;
        UPDATE budgets        SET user_id = legacy_id;
        UPDATE commitments    SET user_id = legacy_id;
        UPDATE goals          SET user_id = legacy_id;
        UPDATE wishlist_items SET user_id = legacy_id;
        UPDATE app_settings   SET user_id = legacy_id;
    ELSE
        -- Fresh install: global seed rows give way to per-user rows created
        -- during registration.
        DELETE FROM categories;
        DELETE FROM app_settings;
    END IF;
END $$;

-- 3. Ownership becomes mandatory.
ALTER TABLE accounts       ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE categories     ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE transactions   ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE budgets        ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE commitments    ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE goals          ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE wishlist_items ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE app_settings   ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE accounts       ADD CONSTRAINT fk_accounts_user       FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE categories     ADD CONSTRAINT fk_categories_user     FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE transactions   ADD CONSTRAINT fk_transactions_user   FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE budgets        ADD CONSTRAINT fk_budgets_user        FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE commitments    ADD CONSTRAINT fk_commitments_user    FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE goals          ADD CONSTRAINT fk_goals_user          FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE wishlist_items ADD CONSTRAINT fk_wishlist_items_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE app_settings   ADD CONSTRAINT fk_app_settings_user   FOREIGN KEY (user_id) REFERENCES users (id);

-- 4. Global uniqueness becomes per-user (case-insensitive where the
--    application compares case-insensitively).
ALTER TABLE accounts   DROP CONSTRAINT uq_accounts_name;
CREATE UNIQUE INDEX uq_accounts_user_name ON accounts (user_id, lower(name));

ALTER TABLE categories DROP CONSTRAINT uq_categories_name_type;
CREATE UNIQUE INDEX uq_categories_user_name_type ON categories (user_id, lower(name), type);

ALTER TABLE budgets    DROP CONSTRAINT uq_budgets_month_category;
ALTER TABLE budgets    ADD CONSTRAINT uq_budgets_user_month_category UNIQUE (user_id, month_ref, category_id);

-- 5. Settings: singleton row becomes one row per user.
ALTER TABLE app_settings DROP CONSTRAINT ck_settings_singleton;
ALTER TABLE app_settings ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
SELECT setval(pg_get_serial_sequence('app_settings', 'id'),
              COALESCE((SELECT MAX(id) FROM app_settings), 0) + 1, false);
ALTER TABLE app_settings ADD CONSTRAINT uq_app_settings_user UNIQUE (user_id);

-- 6. Cross-owner reference protection. The composite keys let the database
--    itself reject a foreign key that points at another user's row, even if
--    an application bug slipped past service-level checks. With PostgreSQL's
--    MATCH SIMPLE semantics a NULL account/category leaves the constraint
--    satisfied, preserving optional relationships.
ALTER TABLE categories     ADD CONSTRAINT uq_categories_id_user     UNIQUE (id, user_id);
ALTER TABLE accounts       ADD CONSTRAINT uq_accounts_id_user       UNIQUE (id, user_id);
ALTER TABLE wishlist_items ADD CONSTRAINT uq_wishlist_items_id_user UNIQUE (id, user_id);

ALTER TABLE transactions
    DROP CONSTRAINT transactions_category_id_fkey,
    ADD CONSTRAINT fk_transactions_category_owner
        FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id);
ALTER TABLE transactions
    DROP CONSTRAINT transactions_account_id_fkey,
    ADD CONSTRAINT fk_transactions_account_owner
        FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id);
ALTER TABLE budgets
    DROP CONSTRAINT budgets_category_id_fkey,
    ADD CONSTRAINT fk_budgets_category_owner
        FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id);
ALTER TABLE commitments
    DROP CONSTRAINT commitments_category_id_fkey,
    ADD CONSTRAINT fk_commitments_category_owner
        FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id);
ALTER TABLE wishlist_items
    DROP CONSTRAINT wishlist_items_category_id_fkey,
    ADD CONSTRAINT fk_wishlist_items_category_owner
        FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id);

-- purchase_options remain owned strictly through their parent wishlist item;
-- every access path resolves the parent with an owner-scoped query first.

-- 7. Indexes matching the new access patterns (every query now filters by user).
CREATE INDEX ix_accounts_user       ON accounts (user_id);
CREATE INDEX ix_categories_user     ON categories (user_id);
CREATE INDEX ix_transactions_user_date ON transactions (user_id, occurred_on);
CREATE INDEX ix_budgets_user_month  ON budgets (user_id, month_ref);
CREATE INDEX ix_commitments_user    ON commitments (user_id);
CREATE INDEX ix_goals_user          ON goals (user_id);
CREATE INDEX ix_wishlist_items_user ON wishlist_items (user_id);
