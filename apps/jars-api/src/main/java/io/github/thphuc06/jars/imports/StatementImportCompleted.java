package io.github.thphuc06.jars.imports;

import io.github.thphuc06.jars.shared.UserId;
import java.time.Instant;
import java.util.UUID;

/**
 * A statement import finished, successfully or not. The postings themselves were announced one by one with
 * {@code TransactionPosted}; this event is for the user (notification) and for anything that wants the summary.
 *
 * @param succeeded false when the whole file was rejected (no usable header, already imported, too large) or the job
 *     failed; {@code failureReason} then says why, in Vietnamese
 * @param postedRows rows recorded in the ledger
 * @param skippedRows rows that could not be imported (their reasons are listed by {@code GET /imports/{id}})
 */
public record StatementImportCompleted(
        UUID eventId,
        UUID importId,
        UserId userId,
        String fileName,
        boolean succeeded,
        int postedRows,
        int skippedRows,
        String failureReason,
        Instant completedAt) {}
