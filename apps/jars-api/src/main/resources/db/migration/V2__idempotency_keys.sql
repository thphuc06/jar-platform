-- V2 (WP1): Idempotency-Key support for POSTs that change money (api-contract.md §4).
-- The row is inserted inside the same transaction as the business change, so two concurrent requests with the
-- same key queue up on the primary key; the second one then sees the committed row and replays its response.

CREATE TABLE idempotency_keys (
    user_id       UUID        NOT NULL,
    key           TEXT        NOT NULL,
    request_hash  TEXT        NOT NULL,
    status_code   INTEGER,
    response_body JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (user_id, key)
);

-- used by the clean-up that deletes keys older than 24 hours
CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);
