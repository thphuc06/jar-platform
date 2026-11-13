-- V13 (advisor products): two facts about the user that the advisor needs besides the risk profile (ADR-0027).
--
-- age_band is a band, never a birth date; income_situation says how regular the income is. Both are what the user chose
-- in onboarding, not part of the risk score, and both can be changed later. They are NULL for every user who registered
-- before this migration: "not answered yet" is a state the advisor and the profile screen handle, so existing users
-- and their questionnaire answers are left as they are.

ALTER TABLE users
    ADD COLUMN age_band         TEXT,
    ADD COLUMN income_situation TEXT,
    ADD CONSTRAINT ck_users_age_band CHECK (
        age_band IS NULL OR age_band IN ('UNDER_25', 'AGE_25_34', 'AGE_35_44', 'AGE_45_54', 'AGE_55_PLUS')),
    ADD CONSTRAINT ck_users_income_situation CHECK (
        income_situation IS NULL OR income_situation IN ('REGULAR_SALARY', 'IRREGULAR_INCOME', 'NO_INCOME_YET'));
