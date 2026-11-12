-- V3 (WP2): identity module.
--
-- A user is identified only by the Cognito subject. Cognito access tokens carry no e-mail claim, so e-mail is
-- optional for now, and the case-insensitive unique index from V1 is dropped: a person who deletes their Cognito
-- account and registers again gets a new sub with the same e-mail, which must not make provisioning fail.

DROP INDEX uk_users_email_lower;
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

-- one row per questionnaire submission; the latest row is the user's current profile
CREATE TABLE risk_assessments (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id),
    answers     JSONB       NOT NULL,
    score       INTEGER     NOT NULL,
    risk_level  TEXT        NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_risk_assessments_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT ck_risk_assessments_level CHECK (risk_level IN ('CONSERVATIVE', 'BALANCED', 'GROWTH'))
);

CREATE INDEX idx_risk_assessments_user_assessed ON risk_assessments (user_id, assessed_at DESC);
