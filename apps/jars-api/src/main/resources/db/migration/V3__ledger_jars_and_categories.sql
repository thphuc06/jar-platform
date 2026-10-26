-- V3 (P1.4): tables owned by the ledger module (docs/02-architecture/modules.md).
-- jar_entries and transactions follow in P1.5, together with the code that writes them.

-- One row per jar. balance is a cache of SUM(jar_entries.amount) guarded by optimistic locking (ADR-0014).
CREATE TABLE jars (
    id          UUID          PRIMARY KEY,
    user_id     UUID          NOT NULL CONSTRAINT fk_jars_user REFERENCES users (id),
    code        TEXT          NOT NULL,
    name        TEXT          NOT NULL,
    target_pct  NUMERIC(5, 2),
    balance     NUMERIC(19, 2) NOT NULL DEFAULT 0,
    currency    CHAR(3)       NOT NULL DEFAULT 'VND',
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    archived_at TIMESTAMPTZ,
    CONSTRAINT ck_jars_code CHECK (code IN ('NEC', 'FFA', 'LTSS', 'EDU', 'PLAY', 'GIVE', 'POOL', 'CUSTOM')),
    CONSTRAINT ck_jars_target_pct_range CHECK (target_pct BETWEEN 0 AND 100),
    -- BR-1: the hidden POOL jar has no target percentage, every other jar has one
    CONSTRAINT ck_jars_target_pct_only_without_pool CHECK ((code = 'POOL') = (target_pct IS NULL)),
    -- BR-1: POOL can never be archived
    CONSTRAINT ck_jars_pool_never_archived CHECK (code <> 'POOL' OR archived_at IS NULL),
    -- BR-2: no overspending. Last line of defence behind the service check; drop it if overdraft is ever allowed
    CONSTRAINT ck_jars_balance_not_negative CHECK (balance >= 0)
);

-- at most one jar per standard code per user (CUSTOM jars may repeat). Also makes default-jar creation idempotent.
CREATE UNIQUE INDEX uk_jars_user_code ON jars (user_id, code) WHERE code <> 'CUSTOM';
CREATE INDEX ix_jars_user ON jars (user_id);

-- Categories classify transactions and suggest a jar (BR-3). user_id NULL = system default shared by everybody.
CREATE TABLE categories (
    id               UUID        PRIMARY KEY,
    user_id          UUID        CONSTRAINT fk_categories_user REFERENCES users (id),
    parent_id        UUID        CONSTRAINT fk_categories_parent REFERENCES categories (id),
    name             TEXT        NOT NULL,
    kind             TEXT        NOT NULL,
    default_jar_code TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_categories_kind CHECK (kind IN ('EXPENSE', 'INCOME')),
    -- a code, not a jar id: CUSTOM cannot name a specific jar, so it is not allowed here
    CONSTRAINT ck_categories_default_jar_code
        CHECK (default_jar_code IN ('NEC', 'FFA', 'LTSS', 'EDU', 'PLAY', 'GIVE', 'POOL')),
    -- BR-2: an expense is never paid from POOL
    CONSTRAINT ck_categories_expense_not_from_pool CHECK (kind <> 'EXPENSE' OR default_jar_code <> 'POOL')
);

-- names are unique per owner and kind, case-insensitive. NULLS NOT DISTINCT (PostgreSQL 15+) makes two system
-- rows (user_id NULL) collide too; with the default NULLS DISTINCT they never would.
CREATE UNIQUE INDEX uk_categories_owner_kind_name ON categories (user_id, kind, lower(name)) NULLS NOT DISTINCT;

-- BR-3 system categories. Fixed ids so every environment (local, CI, AWS) has the same rows; the clients may
-- refer to them by id.
INSERT INTO categories (id, user_id, name, kind, default_jar_code) VALUES
    ('01900000-0000-7000-8000-000000000001', NULL, 'Ăn uống',                                'EXPENSE', 'NEC'),
    ('01900000-0000-7000-8000-000000000002', NULL, 'Đi chợ / siêu thị',                      'EXPENSE', 'NEC'),
    ('01900000-0000-7000-8000-000000000003', NULL, 'Nhà ở & hóa đơn (điện, nước, internet)', 'EXPENSE', 'NEC'),
    ('01900000-0000-7000-8000-000000000004', NULL, 'Di chuyển',                              'EXPENSE', 'NEC'),
    ('01900000-0000-7000-8000-000000000005', NULL, 'Sức khỏe',                               'EXPENSE', 'NEC'),
    ('01900000-0000-7000-8000-000000000006', NULL, 'Học tập',                                'EXPENSE', 'EDU'),
    ('01900000-0000-7000-8000-000000000007', NULL, 'Mua sắm',                                'EXPENSE', 'PLAY'),
    ('01900000-0000-7000-8000-000000000008', NULL, 'Giải trí',                               'EXPENSE', 'PLAY'),
    ('01900000-0000-7000-8000-000000000009', NULL, 'Du lịch',                                'EXPENSE', 'PLAY'),
    ('01900000-0000-7000-8000-00000000000a', NULL, 'Đầu tư',                                 'EXPENSE', 'FFA'),
    ('01900000-0000-7000-8000-00000000000b', NULL, 'Từ thiện & quà tặng',                    'EXPENSE', 'GIVE'),
    ('01900000-0000-7000-8000-00000000000c', NULL, 'Khác (chi)',                             'EXPENSE', 'NEC'),
    ('01900000-0000-7000-8000-000000000101', NULL, 'Lương',                                  'INCOME',  'POOL'),
    ('01900000-0000-7000-8000-000000000102', NULL, 'Thu nhập tự do',                         'INCOME',  'POOL'),
    ('01900000-0000-7000-8000-000000000103', NULL, 'Thu nhập khác',                          'INCOME',  'POOL');
