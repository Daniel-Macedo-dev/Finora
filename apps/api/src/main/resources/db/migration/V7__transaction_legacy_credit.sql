-- Legacy CREDIT compatibility and wishlist execution linkage.
--
-- Ordinary transactions with payment_method = 'CREDIT' predate the credit-card
-- domain: their card, closing day, due day and installment structure are
-- unknown, so they are preserved untouched and flagged as legacy. New generic
-- CREDIT transactions are rejected by the application; the check constraint
-- below enforces the same rule in the database (only rows explicitly flagged
-- as legacy may carry the CREDIT payment method).

ALTER TABLE transactions ADD COLUMN legacy_credit BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE transactions SET legacy_credit = TRUE WHERE payment_method = 'CREDIT';

ALTER TABLE transactions ADD CONSTRAINT ck_transactions_credit_is_legacy
    CHECK (payment_method <> 'CREDIT' OR legacy_credit);

-- Cash execution of a wishlist option creates a regular expense transaction
-- linked to its wishlist item. The partial unique index gives the execution
-- durable idempotency: retries can never produce a second linked transaction.
ALTER TABLE transactions ADD COLUMN wishlist_item_id BIGINT;

ALTER TABLE transactions ADD CONSTRAINT fk_transactions_wishlist_owner
    FOREIGN KEY (wishlist_item_id, user_id) REFERENCES wishlist_items (id, user_id);

CREATE UNIQUE INDEX uq_transactions_wishlist_item
    ON transactions (wishlist_item_id) WHERE wishlist_item_id IS NOT NULL;
