-- Installment purchase options may reference one of the user's credit cards,
-- letting the purchase analysis consider real limit and invoice pressure and
-- letting the wishlist execution create an actual card purchase.
--
-- purchase_options carry no user_id column: ownership is enforced through the
-- parent wishlist item (every access path is owner-scoped), matching the V4
-- decision. The service layer additionally verifies that the referenced card
-- belongs to the same owner as the item.

ALTER TABLE purchase_options ADD COLUMN credit_card_id BIGINT REFERENCES credit_cards (id);

CREATE INDEX ix_purchase_options_card ON purchase_options (credit_card_id);
