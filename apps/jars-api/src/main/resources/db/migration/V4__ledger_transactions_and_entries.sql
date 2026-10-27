-- V4 (P1.5): money movements. "transactions" is what the user did (always a positive amount, direction given by
-- type); "jar_entries" is the append-only ledger of each jar (signed amounts). ADR-0014, business-rules BR-2.

-- Lets child tables reference (jar id, owner) as a pair, so the DATABASE refuses a row that combines one user's
-- transaction with another user's jar. Defence in depth behind the findByIdAndUserId checks in the code (IDOR).
ALTER TABLE jars ADD CONSTRAINT uk_jars_id_user UNIQUE (id, user_id);

CREATE TABLE transactions (
    id           UUID           PRIMARY KEY,
    user_id      UUID           NOT NULL CONSTRAINT fk_transactions_user REFERENCES users (id),
    type         TEXT           NOT NULL,
    amount       NUMERIC(19, 2) NOT NULL,
    currency     CHAR(3)        NOT NULL DEFAULT 'VND',
    -- INCOME: receiving jar (POOL unless the user picked one); EXPENSE: paying jar; JAR_TRANSFER: source jar
    jar_id       UUID           NOT NULL,
    to_jar_id    UUID,
    category_id  UUID           CONSTRAINT fk_transactions_category REFERENCES categories (id),
    counterparty TEXT,
    note         TEXT,
    occurred_at  TIMESTAMPTZ    NOT NULL,
    source       TEXT           NOT NULL DEFAULT 'MANUAL',
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_transactions_type CHECK (type IN ('INCOME', 'EXPENSE', 'JAR_TRANSFER')),
    CONSTRAINT ck_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_transactions_source CHECK (source IN ('MANUAL', 'IMPORT')),
    -- only a transfer has a destination, and it differs from the source
    CONSTRAINT ck_transactions_transfer_target CHECK ((type = 'JAR_TRANSFER') = (to_jar_id IS NOT NULL)),
    CONSTRAINT ck_transactions_transfer_distinct_jars CHECK (to_jar_id IS NULL OR to_jar_id <> jar_id),
    -- composite: the jar must belong to the same user as the transaction (NULL to_jar_id is not checked)
    CONSTRAINT fk_transactions_jar FOREIGN KEY (jar_id, user_id) REFERENCES jars (id, user_id),
    CONSTRAINT fk_transactions_to_jar FOREIGN KEY (to_jar_id, user_id) REFERENCES jars (id, user_id)
);

-- Money Inbox (newest first, keyset pagination in P1.7) and spending per jar
CREATE INDEX ix_transactions_user_occurred ON transactions (user_id, occurred_at DESC, id DESC);
CREATE INDEX ix_transactions_user_jar_occurred ON transactions (user_id, jar_id, occurred_at);

CREATE TABLE jar_entries (
    id             UUID           PRIMARY KEY,
    jar_id         UUID           NOT NULL CONSTRAINT fk_jar_entries_jar REFERENCES jars (id),
    transaction_id UUID           CONSTRAINT fk_jar_entries_transaction REFERENCES transactions (id),
    entry_type     TEXT           NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL,
    balance_after  NUMERIC(19, 2) NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_jar_entries_type
        CHECK (entry_type IN ('INCOME', 'SPEND', 'TRANSFER_IN', 'TRANSFER_OUT', 'ADJUST')),
    -- the sign follows the type: money in is positive, money out negative; ADJUST either way, never zero
    CONSTRAINT ck_jar_entries_sign CHECK (
        (entry_type IN ('INCOME', 'TRANSFER_IN') AND amount > 0)
        OR (entry_type IN ('SPEND', 'TRANSFER_OUT') AND amount < 0)
        OR (entry_type = 'ADJUST' AND amount <> 0)),
    CONSTRAINT ck_jar_entries_balance_after_not_negative CHECK (balance_after >= 0)
);

-- reconciliation (jars.balance = SUM(amount)) and "entries of a transaction"
CREATE INDEX ix_jar_entries_jar_created ON jar_entries (jar_id, created_at);
CREATE INDEX ix_jar_entries_transaction ON jar_entries (transaction_id);

-- Append-only, enforced by the database: an entry is never changed or removed, a correction is a new ADJUST entry.
-- SQLSTATE 23000 (integrity_constraint_violation) so Spring reports it as a DataIntegrityViolationException.
CREATE FUNCTION forbid_jar_entry_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'jar_entries is append-only: % is not allowed', TG_OP USING ERRCODE = '23000';
END;
$$;

CREATE TRIGGER trg_jar_entries_append_only
    BEFORE UPDATE OR DELETE ON jar_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_jar_entry_change();
