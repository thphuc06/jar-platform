package io.github.thphuc06.jars.imports.internal.batch;

import io.github.thphuc06.jars.shared.UserId;
import java.util.UUID;

/**
 * What the reader, processor, writer and skip listener of one step execution share. Step scoped: a new one per
 * execution, so two imports running at the same time never see each other's file.
 */
class ImportContext {

    private final UUID importId;
    private final UserId userId;
    private String sha256;

    ImportContext(UUID importId, UserId userId) {
        this.importId = importId;
        this.userId = userId;
    }

    void fileRead(String sha256) {
        this.sha256 = sha256;
    }

    UUID importId() {
        return importId;
    }

    UserId userId() {
        return userId;
    }

    /**
     * Idempotency key of one row: the file's hash and the row's line. The same file uploaded twice, or one import
     * restarted after a crash, produces the same keys, so the ledger records each row once.
     */
    String keyOf(int line) {
        return "import_" + sha256.substring(0, 32) + "_" + line;
    }
}
