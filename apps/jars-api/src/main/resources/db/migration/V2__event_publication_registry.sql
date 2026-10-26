-- V2 (P1.4): Spring Modulith event publication registry = the transactional OUTBOX.
-- Copied from spring-modulith-events-jdbc 2.1.2, schemas/v2/schema-postgresql.sql (the default structure since
-- Modulith 2.0). When upgrading Spring Modulith, diff that file against this table first.
--
-- How it works: publishing an event inside a transaction inserts one row per interested listener here, in the SAME
-- transaction as the business data. After commit each listener runs; on success its row is deleted
-- (spring.modulith.events.completion-mode=delete), on failure the row stays (status FAILED) and can be resubmitted.

CREATE TABLE event_publication (
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

-- the registry looks a publication up by its serialized event when marking it completed
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
