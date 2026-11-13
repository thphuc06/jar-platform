-- V11 (F14): audit log, "who did what, when" (ADR-0025).
-- A row is inserted by the audit aspect inside the same transaction as the business change it describes, so a rolled
-- back change leaves no row and a committed one leaves exactly one. The table is append-only: the application has no
-- UPDATE or DELETE for it and the trigger below refuses both, so a bug (or a careless psql session) cannot rewrite
-- history. No foreign keys, like the other modules; the table belongs to the platform module.

CREATE TABLE audit_log (
    id          UUID        PRIMARY KEY,                -- UUIDv7: time-ordered, the tie-breaker of the keyset cursor
    occurred_at TIMESTAMPTZ NOT NULL,                   -- from the application Clock
    actor       TEXT        NOT NULL,                   -- the user id (UUID text) or 'system' for jobs without a caller
    action      TEXT        NOT NULL,                   -- stable name, e.g. TRANSACTION_POSTED
    entity_type TEXT        NOT NULL,                   -- e.g. TRANSACTION, JAR, GOAL
    entity_id   UUID        NOT NULL,
    trace_id    TEXT,                                   -- same value as the traceId of the logs and of error bodies
    payload     JSONB       NOT NULL DEFAULT '{}'::jsonb, -- a few non-sensitive facts (ids, amounts), never free text
    CONSTRAINT ck_audit_log_payload_object CHECK (jsonb_typeof(payload) = 'object')
);

-- GET /api/v1/audit-log: the caller's own rows, newest first, keyset-paged on (occurred_at, id). Optional action and
-- entity type filters are applied inside the actor's own rows, which is always a small range of this index.
CREATE INDEX idx_audit_log_actor ON audit_log (actor, occurred_at DESC, id DESC);

CREATE FUNCTION audit_log_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

-- row level: UPDATE and DELETE of any row
CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_reject_change();

-- statement level: TRUNCATE does not fire row triggers
CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_reject_change();
