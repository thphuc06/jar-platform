-- V7 (P1.8): risk questionnaire answers and their score (business-rules BR-4), owned by the identity module.
-- Append-only history: every submission is a new row, the newest one is the current profile.
-- Typed columns instead of one JSONB blob: the database validates every answer and they stay queryable.
-- questionnaire_version records which questions and which scoring produced the row.

CREATE TABLE risk_assessments (
    id                    UUID        PRIMARY KEY,
    user_id               UUID        NOT NULL CONSTRAINT fk_risk_assessments_user REFERENCES users (id),
    questionnaire_version INT         NOT NULL,
    risk_tolerance        TEXT        NOT NULL,
    horizon               TEXT        NOT NULL,
    liquidity             TEXT        NOT NULL,
    experience            TEXT        NOT NULL,
    main_goal             TEXT        NOT NULL,
    score                 INT         NOT NULL,
    risk_level            TEXT        NOT NULL,
    -- true when the Q2/Q3 cap lowered the level the score alone would give
    capped                BOOLEAN     NOT NULL,
    assessed_at           TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_risk_assessments_tolerance CHECK (risk_tolerance IN ('CONSERVATIVE', 'MODERATE', 'AGGRESSIVE')),
    CONSTRAINT ck_risk_assessments_horizon
        CHECK (horizon IN ('UNDER_2_YEARS', 'YEARS_2_TO_5', 'YEARS_5_TO_10', 'OVER_10_YEARS')),
    CONSTRAINT ck_risk_assessments_liquidity CHECK (liquidity IN ('IMMEDIATE', 'WITHIN_A_MONTH', 'FLEXIBLE')),
    CONSTRAINT ck_risk_assessments_experience CHECK (experience IN ('BEGINNER', 'EXPERIENCED', 'VERY_EXPERIENCED')),
    CONSTRAINT ck_risk_assessments_main_goal
        CHECK (main_goal IN ('EMERGENCY_FUND', 'LONG_TERM_GROWTH', 'SPECIFIC_GOAL', 'PASSIVE_INCOME')),
    CONSTRAINT ck_risk_assessments_score CHECK (score BETWEEN 0 AND 13),
    CONSTRAINT ck_risk_assessments_level CHECK (risk_level IN ('CONSERVATIVE', 'BALANCED', 'GROWTH'))
);

-- "current profile" = newest row of the user
CREATE INDEX ix_risk_assessments_user_assessed ON risk_assessments (user_id, assessed_at DESC, id DESC);
