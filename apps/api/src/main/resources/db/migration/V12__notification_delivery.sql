-- Persistent, owner-scoped notification delivery derived from the existing
-- due-event feed. Notifications are presentation artifacts only: no financial
-- table references them and lifecycle actions cannot mutate source records.

CREATE TABLE notification_preferences (
    id                         BIGSERIAL    PRIMARY KEY,
    user_id                    BIGINT       NOT NULL REFERENCES users (id),
    enabled                    BOOLEAN      NOT NULL DEFAULT TRUE,
    upcoming_lead_days         INTEGER      NOT NULL DEFAULT 7,
    recurring_due_enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    invoice_due_enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    execution_failure_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    cash_risk_enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    browser_enabled            BOOLEAN      NOT NULL DEFAULT FALSE,
    browser_minimum_severity   VARCHAR(10)  NOT NULL DEFAULT 'WARNING',
    browser_show_amounts       BOOLEAN      NOT NULL DEFAULT FALSE,
    browser_enabled_at         TIMESTAMPTZ,
    version                    BIGINT       NOT NULL DEFAULT 0,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_notification_preferences_user UNIQUE (user_id),
    CONSTRAINT ck_notification_preferences_lead_days
        CHECK (upcoming_lead_days BETWEEN 1 AND 14),
    CONSTRAINT ck_notification_preferences_severity
        CHECK (browser_minimum_severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_notification_preferences_browser_baseline CHECK (
        (browser_enabled AND browser_enabled_at IS NOT NULL)
        OR (NOT browser_enabled))
);

-- Existing owners receive the same safe defaults as future registrations.
INSERT INTO notification_preferences (user_id)
SELECT id FROM users;

CREATE TABLE notifications (
    id                          BIGSERIAL     PRIMARY KEY,
    user_id                     BIGINT        NOT NULL REFERENCES users (id),
    source_key                  VARCHAR(255)  NOT NULL,
    source_event_id             VARCHAR(255)  NOT NULL,
    type                        VARCHAR(40)   NOT NULL,
    severity                    VARCHAR(10)   NOT NULL,
    event_date                  DATE          NOT NULL,
    title                       VARCHAR(300)  NOT NULL,
    amount                      NUMERIC(14,2),
    resource_type               VARCHAR(40)   NOT NULL,
    resource_id                 BIGINT,
    route                       VARCHAR(500)  NOT NULL,
    revision                    INTEGER       NOT NULL DEFAULT 1,
    read_revision               INTEGER,
    dismissed_revision          INTEGER,
    browser_delivered_revision  INTEGER,
    snoozed_revision            INTEGER,
    snoozed_until               TIMESTAMPTZ,
    first_seen_at               TIMESTAMPTZ   NOT NULL,
    last_seen_at                TIMESTAMPTZ   NOT NULL,
    revision_changed_at         TIMESTAMPTZ   NOT NULL,
    resolved_at                 TIMESTAMPTZ,
    version                     BIGINT        NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_notifications_user_source UNIQUE (user_id, source_key),
    CONSTRAINT uq_notifications_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_notifications_type CHECK (type IN (
        'RECURRING_DUE_SOON', 'RECURRING_DUE_TODAY', 'RECURRING_OVERDUE',
        'RECURRING_FAILED', 'INVOICE_DUE_SOON', 'INVOICE_DUE_TODAY',
        'INVOICE_OVERDUE', 'INSUFFICIENT_CASH_PROJECTED')),
    CONSTRAINT ck_notifications_severity
        CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_notifications_revision CHECK (revision > 0),
    CONSTRAINT ck_notifications_revision_states CHECK (
        (read_revision IS NULL OR read_revision BETWEEN 1 AND revision)
        AND (dismissed_revision IS NULL OR dismissed_revision BETWEEN 1 AND revision)
        AND (browser_delivered_revision IS NULL
             OR browser_delivered_revision BETWEEN 1 AND revision)
        AND (snoozed_revision IS NULL OR snoozed_revision BETWEEN 1 AND revision)),
    CONSTRAINT ck_notifications_snooze_pair CHECK (
        (snoozed_revision IS NULL) = (snoozed_until IS NULL)),
    CONSTRAINT ck_notifications_source_key CHECK (length(trim(source_key)) > 0),
    CONSTRAINT ck_notifications_source_event_id CHECK (length(trim(source_event_id)) > 0),
    CONSTRAINT ck_notifications_title CHECK (length(trim(title)) > 0),
    CONSTRAINT ck_notifications_route CHECK (route LIKE '/%' AND route NOT LIKE '//%'),
    CONSTRAINT ck_notifications_resource_type CHECK (
        resource_type IN ('COMMITMENT', 'CARD_INVOICE', 'FORECAST'))
);

-- Default active inbox: one owner, unresolved, urgent/event ordering.
CREATE INDEX ix_notifications_active_inbox
    ON notifications (user_id, severity DESC, event_date, id)
    WHERE resolved_at IS NULL;

-- Count query for current-revision unread active items.
CREATE INDEX ix_notifications_unread
    ON notifications (user_id, revision, read_revision)
    WHERE resolved_at IS NULL;

-- Bounded foreground-browser claim selection.
CREATE INDEX ix_notifications_browser_claim
    ON notifications (user_id, severity, revision_changed_at, id)
    WHERE resolved_at IS NULL;

-- Stable resolved-history pagination.
CREATE INDEX ix_notifications_resolved_history
    ON notifications (user_id, resolved_at DESC, id DESC)
    WHERE resolved_at IS NOT NULL;

-- Explicit source lookup and user-scoped synchronization scans are backed by
-- the unique (user_id, source_key) index; no duplicate index is necessary.
