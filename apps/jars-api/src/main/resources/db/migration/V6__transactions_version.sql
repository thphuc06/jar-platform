-- V6 (P1.7): transactions become editable (PATCH: category, note, jar), so they get optimistic locking too.
-- Without it two concurrent edits of the same transaction could overwrite each other: Hibernate writes every column,
-- so an edit of the category could put back the OLD jar_id after another request had moved the money to a new jar.
ALTER TABLE transactions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
