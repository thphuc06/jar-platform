-- V5 (P1.6): Idempotency-Key for the POSTs that move money (docs/03-api/api-contract.md §4).
-- The row is inserted at the START of the business transaction and completed at its end, so the key and the money
-- movement commit (or roll back) together. A concurrent request with the same key blocks on the primary key until
-- the first transaction ends, then replays its stored result.

CREATE TABLE idempotency_keys (
    user_id         UUID        NOT NULL CONSTRAINT fk_idempotency_keys_user REFERENCES users (id),
    -- scoped per user: two users may pick the same key, and nobody can probe the keys of somebody else
    idempotency_key UUID        NOT NULL,
    -- SHA-256 of the operation + normalized request: the same key with a different request is an error
    request_hash    TEXT        NOT NULL,
    -- result of the operation as JSON; NULL only inside the still-running transaction that reserved the key
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (user_id, idempotency_key)
);

-- for the clean-up job that deletes expired keys
CREATE INDEX ix_idempotency_keys_expires ON idempotency_keys (expires_at);
