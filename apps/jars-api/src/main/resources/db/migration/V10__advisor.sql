-- V10 (Phase 2 WP4): AI advisor. Chat sessions and their messages, and the per-user usage counters of the rate limit
-- (ADR-0023). No foreign keys to users, like the other modules.

CREATE TABLE chat_sessions (
    id         UUID        PRIMARY KEY, -- UUIDv7
    user_id    UUID        NOT NULL,
    title      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- the session list of one user, most recently used first
CREATE INDEX idx_chat_sessions_user ON chat_sessions (user_id, updated_at DESC);

CREATE TABLE chat_messages (
    id              UUID        PRIMARY KEY, -- UUIDv7, so id order is time order within a session
    session_id      UUID        NOT NULL REFERENCES chat_sessions (id) ON DELETE CASCADE,
    role            TEXT        NOT NULL,
    content         TEXT        NOT NULL,
    input_tokens    INTEGER,
    output_tokens   INTEGER,
    guardrail       TEXT,                    -- INPUT or OUTPUT when the guardrail intervened
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_chat_messages_role CHECK (role IN ('USER', 'ASSISTANT'))
);

CREATE INDEX idx_chat_messages_session ON chat_messages (session_id, id);

-- Fixed-window counters: one row per user, window kind and window start. The rate limiter increments a counter with a
-- single INSERT ... ON CONFLICT DO UPDATE ... WHERE count < limit, so two concurrent messages cannot both take the last
-- slot. A user's expired windows are deleted when that user sends the next message.
CREATE TABLE advisor_usage (
    user_id      UUID        NOT NULL,
    window_kind  TEXT        NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    count        INTEGER     NOT NULL,
    PRIMARY KEY (user_id, window_kind, window_start),
    CONSTRAINT ck_advisor_usage_kind CHECK (window_kind IN ('HOUR', 'DAY'))
);
