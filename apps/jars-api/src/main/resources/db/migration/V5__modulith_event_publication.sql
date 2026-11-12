-- V5 (WP4): the event publication registry of Spring Modulith, i.e. the transactional outbox for in-process events.
--
-- An event published inside a business transaction is written here in that same transaction, one row per listener
-- (@ApplicationModuleListener). After commit the listener runs; when it finishes the row is deleted
-- (spring.modulith.events.completion-mode=delete). A row that is still here is an event whose listener did not
-- complete, so nothing is lost when a listener fails or the process dies mid-way.
-- Columns follow spring-modulith-events 2.1 (schema-postgresql.sql of the JDBC module); Hibernate validates them.

CREATE TABLE event_publication (
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INTEGER,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);

-- only used when completion-mode=archive, but the JPA module maps both tables and Hibernate validates both
CREATE TABLE event_publication_archive (
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INTEGER,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_archive_by_completion_date_idx ON event_publication_archive (completion_date);
CREATE INDEX event_publication_archive_serialized_event_hash_idx
    ON event_publication_archive USING hash (serialized_event);
