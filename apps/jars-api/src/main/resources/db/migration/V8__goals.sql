-- V8 (WP5): goals module. Like the other modules' tables there are no foreign keys to users or to the ledger: a goal
-- refers to its owner and to the jar that funds it by id only (modules.md). A funding jar that was archived later simply
-- stops counting as saved money; the goal itself stays.

CREATE TABLE goals (
    id             UUID           PRIMARY KEY,
    user_id        UUID           NOT NULL,
    name           TEXT           NOT NULL,
    target_amount  NUMERIC(19, 2) NOT NULL,
    currency       VARCHAR(3)     NOT NULL DEFAULT 'VND',
    target_date    DATE           NOT NULL,
    funding_jar_id UUID,
    status         TEXT           NOT NULL DEFAULT 'ACTIVE',
    version        BIGINT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ    NOT NULL,
    updated_at     TIMESTAMPTZ    NOT NULL,
    archived_at    TIMESTAMPTZ,
    CONSTRAINT ck_goals_status CHECK (status IN ('ACTIVE', 'ACHIEVED', 'ARCHIVED')),
    CONSTRAINT ck_goals_target_amount CHECK (target_amount > 0),
    CONSTRAINT ck_goals_archived CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL))
);

-- the goal list of one user, oldest first, without the archived ones
CREATE INDEX idx_goals_user_status ON goals (user_id, status, created_at, id);
