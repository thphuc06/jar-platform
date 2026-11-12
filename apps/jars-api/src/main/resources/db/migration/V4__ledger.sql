-- V4 (WP3): ledger module (docs/04-adr/0014-money-and-ledger-model.md).
--
-- jars.balance is a cached SUM(jar_entries.amount); jars.version is the optimistic lock that keeps the cache
-- correct under concurrent writes. jar_entries is append-only: nothing in the application updates or deletes it.
-- jar_entries.created_at is the occurrence time of the transaction that produced the entry, so daily balances can be
-- drawn from it; balance_after is the running balance in posting order.

CREATE TABLE jars (
    id          UUID          PRIMARY KEY,
    user_id     UUID          NOT NULL REFERENCES users (id),
    code        TEXT          NOT NULL,
    name        TEXT          NOT NULL,
    target_pct  NUMERIC(5, 2) NOT NULL DEFAULT 0,
    balance     NUMERIC(19, 2) NOT NULL DEFAULT 0,
    currency    VARCHAR(3)    NOT NULL DEFAULT 'VND',
    version     BIGINT        NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_jars_code CHECK (code IN ('NEC', 'FFA', 'LTSS', 'EDU', 'PLAY', 'GIVE', 'CUSTOM')),
    CONSTRAINT ck_jars_target_pct CHECK (target_pct BETWEEN 0 AND 100)
);

-- each user has at most one default jar per code; any number of custom jars
CREATE UNIQUE INDEX uk_jars_user_code ON jars (user_id, code) WHERE code <> 'CUSTOM';
CREATE INDEX idx_jars_user ON jars (user_id);

CREATE TABLE categories (
    id         UUID        PRIMARY KEY,
    user_id    UUID        REFERENCES users (id), -- NULL = system category visible to everyone
    parent_id  UUID        REFERENCES categories (id),
    name       TEXT        NOT NULL,
    kind       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_categories_kind CHECK (kind IN ('EXPENSE', 'INCOME'))
);

CREATE INDEX idx_categories_user ON categories (user_id);

CREATE TABLE transactions (
    id           UUID           PRIMARY KEY,
    user_id      UUID           NOT NULL REFERENCES users (id),
    type         TEXT           NOT NULL,
    amount       NUMERIC(19, 2) NOT NULL,
    currency     VARCHAR(3)     NOT NULL DEFAULT 'VND',
    jar_id       UUID           REFERENCES jars (id), -- source jar; NULL for income that is not assigned to a jar
    to_jar_id    UUID           REFERENCES jars (id), -- destination, only for JAR_TRANSFER
    category_id  UUID           REFERENCES categories (id),
    counterparty TEXT,
    note         TEXT,
    occurred_at  TIMESTAMPTZ    NOT NULL,
    source       TEXT           NOT NULL DEFAULT 'MANUAL',
    created_at   TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_transactions_type CHECK (type IN ('INCOME', 'EXPENSE', 'JAR_TRANSFER')),
    CONSTRAINT ck_transactions_amount CHECK (amount > 0),
    CONSTRAINT ck_transactions_source CHECK (source IN ('MANUAL', 'IMPORT')),
    CONSTRAINT ck_transactions_expense_jar CHECK (type <> 'EXPENSE' OR jar_id IS NOT NULL),
    CONSTRAINT ck_transactions_transfer CHECK (
        (type = 'JAR_TRANSFER' AND jar_id IS NOT NULL AND to_jar_id IS NOT NULL AND jar_id <> to_jar_id)
        OR (type <> 'JAR_TRANSFER' AND to_jar_id IS NULL))
);

-- Money Inbox: keyset pagination on (occurred_at DESC, id DESC)
CREATE INDEX idx_transactions_user_occurred ON transactions (user_id, occurred_at DESC, id DESC);
-- spending of one jar within a period
CREATE INDEX idx_transactions_user_jar_occurred ON transactions (user_id, jar_id, occurred_at);

CREATE TABLE jar_entries (
    id             UUID           PRIMARY KEY,
    jar_id         UUID           NOT NULL REFERENCES jars (id),
    transaction_id UUID           REFERENCES transactions (id),
    entry_type     TEXT           NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL, -- signed: + into the jar, - out of it
    balance_after  NUMERIC(19, 2) NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_jar_entries_type CHECK (entry_type IN ('ALLOCATE', 'SPEND', 'TRANSFER_IN', 'TRANSFER_OUT', 'ADJUST')),
    CONSTRAINT ck_jar_entries_amount CHECK (amount <> 0)
);

-- reconciliation of a jar balance, and daily balance charts
CREATE INDEX idx_jar_entries_jar_created ON jar_entries (jar_id, created_at);
CREATE INDEX idx_jar_entries_transaction ON jar_entries (transaction_id);

-- System categories (fixed ids so they are stable across environments)
INSERT INTO categories (id, user_id, parent_id, name, kind) VALUES
    ('018f0000-0000-7000-8000-000000000001', NULL, NULL, 'Ăn uống', 'EXPENSE'),
    ('018f0000-0000-7000-8000-000000000002', NULL, NULL, 'Siêu thị & đi chợ', 'EXPENSE'),
    ('018f0000-0000-7000-8000-000000000003', NULL, NULL, 'Di chuyển', 'EXPENSE'),
    ('018f0000-0000-7000-8000-000000000004', NULL, NULL, 'Nhà cửa & hóa đơn', 'EXPENSE'),
    ('018f0000-0000-7000-8000-000000000005', NULL, '018f0000-0000-7000-8000-000000000004', 'Tiền thuê nhà', 'EXPENSE'),
    ('018f0000-0000-7000-8000-000000000006', NULL, NULL, 'Sức khỏe & thể thao', 'EXPENSE'),
    ('018f0000-0000-7000-8000-000000000007', NULL, NULL, 'Giáo dục', 'EXPENSE'),
    ('018f0000-0000-7000-8000-000000000008', NULL, NULL, 'Giải trí', 'EXPENSE'),
    ('018f0000-0000-7000-8000-000000000009', NULL, NULL, 'Mua sắm', 'EXPENSE'),
    ('018f0000-0000-7000-8000-00000000000a', NULL, NULL, 'Du lịch', 'EXPENSE'),
    ('018f0000-0000-7000-8000-00000000000b', NULL, NULL, 'Con cái & thú cưng', 'EXPENSE'),
    ('018f0000-0000-7000-8000-00000000000c', NULL, NULL, 'Chăm sóc cá nhân', 'EXPENSE'),
    ('018f0000-0000-7000-8000-00000000000d', NULL, NULL, 'Quà tặng & từ thiện', 'EXPENSE'),
    ('018f0000-0000-7000-8000-00000000000e', NULL, NULL, 'Khác', 'EXPENSE'),
    ('018f0000-0000-7000-8000-000000000101', NULL, NULL, 'Lương', 'INCOME'),
    ('018f0000-0000-7000-8000-000000000102', NULL, NULL, 'Làm thêm', 'INCOME'),
    ('018f0000-0000-7000-8000-000000000103', NULL, NULL, 'Thưởng', 'INCOME'),
    ('018f0000-0000-7000-8000-000000000104', NULL, NULL, 'Lãi tiết kiệm', 'INCOME'),
    ('018f0000-0000-7000-8000-000000000105', NULL, NULL, 'Khác', 'INCOME');
