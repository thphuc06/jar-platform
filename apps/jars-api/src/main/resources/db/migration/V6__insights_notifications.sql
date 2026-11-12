-- V6 (WP4): insights (anomaly detection + read model for the dashboard), notifications, and the inbox table that
-- makes event consumers idempotent. No foreign keys from insights/notification tables to ledger tables: modules own
-- their tables and refer to each other by id only (modules.md), which also keeps the insights split-out possible.

-- Inbox: a consumer inserts (consumer, message_id) in the same transaction as its work. Inserting a row that already
-- exists means the event was delivered before, so the work is skipped (at-least-once delivery, idempotent consumer).
CREATE TABLE processed_messages (
    consumer     TEXT        NOT NULL,
    message_id   TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, message_id)
);

-- One row per jar: the running baseline and the CUSUM accumulators of its spending.
-- mean/m2 are Welford's running mean and sum of squared deviations (variance = m2 / (count - 1)).
CREATE TABLE anomaly_detector_state (
    jar_id        UUID             PRIMARY KEY,
    user_id       UUID             NOT NULL,
    sample_count  INTEGER          NOT NULL DEFAULT 0,
    mean          DOUBLE PRECISION NOT NULL DEFAULT 0,
    m2            DOUBLE PRECISION NOT NULL DEFAULT 0,
    cusum_pos     DOUBLE PRECISION NOT NULL DEFAULT 0,
    cusum_neg     DOUBLE PRECISION NOT NULL DEFAULT 0,
    recent_amounts JSONB           NOT NULL DEFAULT '[]'::jsonb, -- the last amounts, window for the MAD test
    version       BIGINT           NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ      NOT NULL
);

CREATE TABLE anomalies (
    id             UUID             PRIMARY KEY,
    user_id        UUID             NOT NULL,
    jar_id         UUID             NOT NULL,
    transaction_id UUID             NOT NULL,
    detector       TEXT             NOT NULL,
    direction      TEXT             NOT NULL,
    severity       TEXT             NOT NULL,
    score          DOUBLE PRECISION NOT NULL,
    threshold      DOUBLE PRECISION NOT NULL,
    amount         NUMERIC(19, 2)   NOT NULL,
    status         TEXT             NOT NULL DEFAULT 'OPEN',
    detected_at    TIMESTAMPTZ      NOT NULL,
    feedback_at    TIMESTAMPTZ,
    CONSTRAINT ck_anomalies_detector CHECK (detector IN ('CUSUM', 'MAD')),
    CONSTRAINT ck_anomalies_direction CHECK (direction IN ('UP', 'DOWN')),
    CONSTRAINT ck_anomalies_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_anomalies_status CHECK (status IN ('OPEN', 'CONFIRMED', 'EXPECTED', 'FALSE_POSITIVE'))
);

CREATE INDEX idx_anomalies_user_status_detected ON anomalies (user_id, status, detected_at DESC, id DESC);

-- Read model for the dashboard (CQRS-lite), updated from ledger events. month = first day of the month in Vietnam time.
CREATE TABLE monthly_jar_spend (
    user_id   UUID           NOT NULL,
    jar_id    UUID           NOT NULL,
    month     DATE           NOT NULL,
    spent     NUMERIC(19, 2) NOT NULL DEFAULT 0,
    txn_count INTEGER        NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, jar_id, month)
);

-- category_id of an expense without a category is stored as the all-zero UUID so it can be part of the key
CREATE TABLE monthly_category_spend (
    user_id     UUID           NOT NULL,
    category_id UUID           NOT NULL,
    month       DATE           NOT NULL,
    spent       NUMERIC(19, 2) NOT NULL DEFAULT 0,
    txn_count   INTEGER        NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, category_id, month)
);

CREATE TABLE monthly_cashflow (
    user_id UUID           NOT NULL,
    month   DATE           NOT NULL,
    income  NUMERIC(19, 2) NOT NULL DEFAULT 0,
    expense NUMERIC(19, 2) NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, month)
);

CREATE TABLE notifications (
    id         UUID        PRIMARY KEY, -- UUIDv7, so "newer than id X" is simply id > X (used to replay missed SSE events)
    user_id    UUID        NOT NULL,
    type       TEXT        NOT NULL,
    severity   TEXT        NOT NULL,
    title      TEXT        NOT NULL,
    body       TEXT        NOT NULL,
    ref_type   TEXT,
    ref_id     UUID,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_notifications_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH'))
);

CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC, id DESC);
CREATE INDEX idx_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;
