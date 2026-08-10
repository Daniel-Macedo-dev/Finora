-- Honest denomination for notifications created between V15 and this release.
--
-- V15 added notifications.currency NOT NULL and backfilled every row that then
-- existed as BRL. That backfill was correct: Finora was single-currency by
-- construction before V15, so those rows are labelled with the currency they
-- always had.
--
-- What V15 could not fix is the application. The Notification entity kept a
-- BRL field initializer and the synchronizer never passed the source's
-- currency, so every notification created AFTER V15 was persisted as BRL no
-- matter what the money actually was. A dollar commitment falling due produced
-- a row that said reais. This migration repairs that window.
--
-- The repair touches exactly two things, and only where each is justified: the
-- currency label, where a live record can still prove what it should be; and
-- the amount, which is withdrawn -- never rewritten to a different figure --
-- on the forecast rows whose denomination cannot be proven at all (see the
-- third block). No date, revision, read/dismiss/snooze state, browser-delivery
-- mark, resolution or history is altered, and no row is created or deleted.
-- Notifications are derived artifacts: they are not financial authority, and
-- this migration never treats them as evidence about anything except
-- themselves.
--
-- Every statement is owner-scoped through user_id as well as the resource id.
-- The notifications table carries no FK to its resource, so joining on the id
-- alone would let one owner's commitment relabel another owner's notification.

-- ---------------------------------------------------------------------------
-- Deterministic provenance: the source still exists and still knows.
--
-- A COMMITMENT notification names its commitment in resource_id, and the
-- commitment is the authority on the currency it settles in. A CARD_INVOICE
-- notification names its invoice, which bills in its card's currency. Both are
-- facts read from the live record, not inferences.
-- ---------------------------------------------------------------------------

UPDATE notifications n
   SET currency = c.currency
  FROM commitments c
 WHERE n.resource_type = 'COMMITMENT'
   AND n.resource_id = c.id
   AND n.user_id = c.user_id
   AND n.currency <> c.currency;

UPDATE notifications n
   SET currency = cc.currency
  FROM credit_card_invoices i
  JOIN credit_cards cc
    ON cc.id = i.card_id
   AND cc.user_id = i.user_id
 WHERE n.resource_type = 'CARD_INVOICE'
   AND n.resource_id = i.id
   AND n.user_id = i.user_id
   AND n.currency <> cc.currency;

-- ---------------------------------------------------------------------------
-- Unreconstructible provenance: the forecast cash-risk rows.
--
-- A forecast notification has no resource_id and its old identity,
-- FORECAST:INSUFFICIENT_CASH, names no currency. It was derived from the
-- forecast's scalar first-negative date, which is populated only for a
-- homogeneous forecast — and a homogeneous forecast can be homogeneous in
-- dollars. The projection that produced the row no longer exists, so the
-- denomination is genuinely gone.
--
-- No currency is invented here and no forecast row is relabelled. What is
-- withdrawn is the CLAIM: the amount is set to NULL wherever the label cannot
-- be trusted, because a figure under a currency nobody can vouch for is a wrong
-- number, while no figure is only less information. The row keeps its title,
-- date, revisions, read and dismissal state and its place in history.
--
-- One class of forecast row is exempt, on evidence rather than optimism: a row
-- created before V15 ran. Finora had a single currency then, so BRL is a fact
-- for those rows, exactly as V15's own backfill states. When V15 ran is
-- recorded durably in this same database, by Flyway itself. installed_on is a
-- naive local timestamp and created_at is absolute, so the comparison converts
-- through the session timezone; the residual ambiguity is a few seconds around
-- the migration itself, and the condition is written so that anything not
-- strictly earlier — including a lineage with no successful V15 row at all — is
-- treated as unproven and withdrawn.
--
-- These rows are also all about to close: the cash-risk source key now names a
-- currency, so the old key stops being produced and the next synchronization
-- resolves each of them into history without deleting anything.
-- ---------------------------------------------------------------------------

UPDATE notifications n
   SET amount = NULL
 WHERE n.resource_type = 'FORECAST'
   AND n.amount IS NOT NULL
   AND NOT EXISTS (
           SELECT 1
             FROM flyway_schema_history h
            WHERE h.version = '15'
              AND h.success
              AND n.created_at
                  < h.installed_on AT TIME ZONE current_setting('TimeZone'));
