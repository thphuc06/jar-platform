package io.github.thphuc06.jars.imports.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One uploaded statement file and what became of it.
 *
 * <pre>
 * AWAITING_UPLOAD --(file arrives: S3 event or local upload)--> PROCESSING --> COMPLETED
 *                                                                          \-> FAILED --(same file again)--> PROCESSING
 * </pre>
 *
 * A FAILED import can run again: Spring Batch restarts it from its last committed chunk, and the rows already in the
 * ledger are not posted twice (idempotency key per row).
 */
@Entity
@Table(name = "statement_imports")
public class StatementImport {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "object_key", nullable = false, updatable = false)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ImportStatus status;

    @Column(name = "file_sha256")
    private String fileSha256;

    @Column(name = "total_rows")
    private Integer totalRows;

    @Column(name = "posted_rows")
    private Integer postedRows;

    @Column(name = "skipped_rows")
    private Integer skippedRows;

    @Column(name = "failure_reason")
    private String failureReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected StatementImport() {}

    public StatementImport(UUID id, UUID userId, String fileName, Instant now) {
        this.id = id;
        this.userId = userId;
        this.fileName = fileName;
        this.objectKey = objectKeyOf(userId, id);
        this.status = ImportStatus.AWAITING_UPLOAD;
        this.createdAt = now;
    }

    /** Where the file lives: the upload prefix of the bucket (expires after 7 days), one folder per user. */
    public static String objectKeyOf(UUID userId, UUID importId) {
        return "uploads/" + userId + "/" + importId + ".csv";
    }

    /** False when the import already finished; a FAILED import may start again (restart). */
    public boolean canStart() {
        return status != ImportStatus.COMPLETED;
    }

    public void start(Instant now) {
        status = ImportStatus.PROCESSING;
        startedAt = now;
        failureReason = null;
    }

    public void fileRead(String sha256, int rows) {
        fileSha256 = sha256;
        totalRows = rows;
    }

    public void complete(int posted, int skipped, Instant now) {
        status = ImportStatus.COMPLETED;
        postedRows = posted;
        skippedRows = skipped;
        completedAt = now;
    }

    public void fail(String reason, int posted, int skipped, Instant now) {
        status = ImportStatus.FAILED;
        failureReason = reason;
        postedRows = posted;
        skippedRows = skipped;
        completedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public ImportStatus getStatus() {
        return status;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public Integer getTotalRows() {
        return totalRows;
    }

    public Integer getPostedRows() {
        return postedRows;
    }

    public Integer getSkippedRows() {
        return skippedRows;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
