-- V7 (WP5): budgeting module. Like the insights tables there are no foreign keys into the ledger (or users): modules
-- own their tables and refer to each other by id only (modules.md).

-- One row per jar and calendar month (first day of the month in Vietnam time).
--   planned_amount: money put into the jar that month by an applied allocation (written by POST /budget/allocations)
--   spent_amount:   expenses of the jar that month, an additive read model fed by TransactionPosted and
--                   TransactionRecategorized (it can briefly go negative when a "moved to another jar" delta is
--                   applied before the expense it refers to)
--   closed_at:      set by the month-close job, once; never moved afterwards
CREATE TABLE jar_periods (
    id             UUID           PRIMARY KEY,
    jar_id         UUID           NOT NULL,
    user_id        UUID           NOT NULL,
    period_month   DATE           NOT NULL,
    planned_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    spent_amount   NUMERIC(19, 2) NOT NULL DEFAULT 0,
    closed_at      TIMESTAMPTZ,
    created_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uk_jar_periods_jar_month UNIQUE (jar_id, period_month),
    CONSTRAINT ck_jar_periods_first_of_month CHECK (period_month = date_trunc('month', period_month)::date),
    CONSTRAINT ck_jar_periods_planned CHECK (planned_amount >= 0)
);

CREATE INDEX idx_jar_periods_user_month ON jar_periods (user_id, period_month);

-- What the user did with a suggested split: ACCEPTED it as proposed, EDITED it before applying, or SKIPPED it. The
-- unique (user_id, idempotency_key) makes recording safe to repeat: replaying a request never adds a second row (and so
-- never counts the amount twice in jar_periods.planned_amount). suggested/applied are [{jarId, amount}] so a later
-- version of the suggestion can learn from the edits.
CREATE TABLE allocation_decisions (
    id              UUID           PRIMARY KEY,
    user_id         UUID           NOT NULL,
    idempotency_key TEXT           NOT NULL,
    request_hash    TEXT           NOT NULL,
    decision        TEXT           NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL,
    period_month    DATE           NOT NULL,
    transaction_id  UUID,
    suggested       JSONB,
    applied         JSONB,
    created_at      TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uk_allocation_decisions_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_allocation_decisions_decision CHECK (decision IN ('ACCEPTED', 'EDITED', 'SKIPPED')),
    CONSTRAINT ck_allocation_decisions_amount CHECK (amount > 0)
);

CREATE INDEX idx_allocation_decisions_user_created ON allocation_decisions (user_id, created_at DESC);
