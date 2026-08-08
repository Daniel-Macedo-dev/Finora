-- Statement-import currency metadata.
--
-- The statement-import domain predates Multi-currency Core. A batch knew its
-- destination account but not the denomination of the file it came from, and
-- the OFX parser discarded CURDEF entirely. Since raw uploaded bytes are never
-- persisted, that parse result cannot be recovered later by re-reading the
-- file: it has to become durable batch state or it is gone.
--
-- This migration adds that state and nothing else. No amount is converted and
-- no exchange rate exists anywhere in Finora -- an import is always read in the
-- destination account's currency, and these columns only record how confidently
-- Finora knows that reading matches the source.

-- ---------------------------------------------------------------------------
-- Currency source and file-declared currency.
--
-- currency_source mirrors the application enum StatementCurrencySource:
--
--   ACCOUNT          the account is the contract (CSV: no currency column
--                    exists in the Finora CSV contract, so picking the account
--                    IS the declaration)
--   FILE             OFX whose CURDEF was parsed and matched the account
--   ACCOUNT_ASSUMED  OFX that carried no CURDEF; the account currency is an
--                    assumption the user must acknowledge
--   LEGACY_UNKNOWN   OFX imported before this migration, when the parser did
--                    not record whether CURDEF was present
--
-- declared_currency is only meaningful for FILE. For every other source the
-- file's declaration is either absent (ACCOUNT_ASSUMED), not part of the
-- contract (ACCOUNT) or unknowable after the fact (LEGACY_UNKNOWN), and a
-- value there would be a fabricated claim about the source document.
-- ---------------------------------------------------------------------------

ALTER TABLE statement_import_batches
    ADD COLUMN currency_source   VARCHAR(20),
    ADD COLUMN declared_currency VARCHAR(3);

-- Deterministic backfill. CSV always inherited the account currency, which is
-- exactly what ACCOUNT means, so those rows can be classified with certainty.
UPDATE statement_import_batches
   SET currency_source = 'ACCOUNT'
 WHERE format = 'CSV';

-- Existing OFX batches deliberately do NOT become ACCOUNT_ASSUMED. That would
-- assert the uploaded file omitted CURDEF, which nobody knows: the old parser
-- never looked. LEGACY_UNKNOWN records the absence of evidence instead of
-- inventing evidence of absence.
UPDATE statement_import_batches
   SET currency_source = 'LEGACY_UNKNOWN'
 WHERE format = 'OFX';

-- No DEFAULT is introduced: after the backfill the application must classify
-- every new batch explicitly, exactly as V15 did for the currency columns.
ALTER TABLE statement_import_batches
    ALTER COLUMN currency_source SET NOT NULL;

ALTER TABLE statement_import_batches
    ADD CONSTRAINT ck_import_batches_currency_source CHECK (currency_source IN
        ('ACCOUNT', 'FILE', 'ACCOUNT_ASSUMED', 'LEGACY_UNKNOWN'));

-- Only the closed application catalogue may ever be stored, matching the
-- CHECK constraints V15 put on every monetary root.
ALTER TABLE statement_import_batches
    ADD CONSTRAINT ck_import_batches_declared_currency CHECK (
        declared_currency IS NULL
        OR declared_currency IN ('BRL','USD','EUR','GBP','CAD','AUD','CHF','JPY'));

-- The pairing is an invariant, not a convention: a FILE source without the
-- code it supposedly read is meaningless, and a declared code under any other
-- source is a claim the source cannot support.
ALTER TABLE statement_import_batches
    ADD CONSTRAINT ck_import_batches_declared_currency_source CHECK (
        (currency_source = 'FILE' AND declared_currency IS NOT NULL)
        OR (currency_source <> 'FILE' AND declared_currency IS NULL));

-- ---------------------------------------------------------------------------
-- Narrowly scoped repair of import-generated transaction denomination.
--
-- StatementMaterializationService set the destination account on every
-- generated transaction but never set the currency, so the Transaction entity
-- default (BRL) survived. Within a database that carries V15's composite
-- foreign key fk_transactions_account_currency the result is a rejected insert
-- rather than a mislabelled row, so on a healthy lineage this statement matches
-- nothing and is a no-op.
--
-- It runs anyway, because "nothing to repair" must be a verified outcome rather
-- than an assumption -- a restored dump or a lineage where the constraint was
-- absent could carry such a row, and an import transaction is denominated in
-- its destination account's currency by definition.
--
-- This is metadata correction, not conversion: only the currency label moves,
-- and only to the account that is already the source of truth for it. Amount,
-- date, type, category, account, description, notes and the statement-import
-- identity are all left untouched, and no transaction outside statement import
-- is considered.
-- ---------------------------------------------------------------------------

UPDATE transactions t
   SET currency = a.currency
  FROM accounts a
 WHERE t.account_id = a.id
   AND t.user_id = a.user_id
   AND t.statement_import_item_id IS NOT NULL
   AND t.currency <> a.currency;
