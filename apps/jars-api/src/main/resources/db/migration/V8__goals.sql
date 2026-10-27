-- V8 (P1.8): savings goals (business-rules BR-6), owned by the goals module.
--
-- funding_jar_id deliberately has NO foreign key to jars: jars belong to the ledger module, and a module never
-- couples its schema to the tables of another one (docs/02-architecture/modules.md, rule 4). Ownership is checked
-- through the public LedgerApi when the goal is written; a jar archived later is shown as such.

CREATE TABLE goals (
    id             UUID           PRIMARY KEY,
    user_id        UUID           NOT NULL CONSTRAINT fk_goals_user REFERENCES users (id),
    name           TEXT           NOT NULL,
    target_amount  NUMERIC(19, 2) NOT NULL,
    target_date    DATE           NOT NULL,
    funding_jar_id UUID,
    status         TEXT           NOT NULL DEFAULT 'ACTIVE',
    version        BIGINT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_goals_name_length CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT ck_goals_target_amount_positive CHECK (target_amount > 0),
    CONSTRAINT ck_goals_status CHECK (status IN ('ACTIVE', 'ACHIEVED', 'ARCHIVED'))
);

-- the list of a user (archived ones are filtered out by the query)
CREATE INDEX ix_goals_user_target_date ON goals (user_id, target_date);
