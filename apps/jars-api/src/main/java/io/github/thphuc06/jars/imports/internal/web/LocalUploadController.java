package io.github.thphuc06.jars.imports.internal.web;

import io.github.thphuc06.jars.imports.internal.batch.ImportLauncher;
import io.github.thphuc06.jars.imports.internal.domain.StatementImport;
import io.github.thphuc06.jars.imports.internal.service.ImportService;
import io.github.thphuc06.jars.imports.internal.service.ImportService.ImportView;
import io.github.thphuc06.jars.imports.internal.storage.LocalStatementStore;
import io.github.thphuc06.jars.shared.UserId;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development and tests only (exists with the local store): receives the file that would go to S3, and starts the
 * import right away, which on AWS the S3 -> EventBridge -> SQS path does. The import runs in this request, so the
 * response already holds the result; clients still poll, as they must on AWS. Not part of the published API contract.
 */
@Hidden
@RestController
@ConditionalOnProperty(name = "jars.imports.storage", havingValue = "local", matchIfMissing = true)
class LocalUploadController {

    private final ImportService imports;
    private final LocalStatementStore store;
    private final ImportLauncher launcher;

    LocalUploadController(ImportService imports, LocalStatementStore store, ImportLauncher launcher) {
        this.imports = imports;
        this.store = store;
        this.launcher = launcher;
    }

    @PutMapping(
            value = "/api/v1/imports/{importId}/file",
            consumes = {"text/csv", "text/plain", "application/octet-stream"})
    ImportView upload(UserId userId, @PathVariable UUID importId, @RequestBody byte[] content) {
        StatementImport statementImport = imports.acceptingUpload(userId, importId);
        store.write(statementImport.getObjectKey(), content);
        launcher.run(importId);
        return imports.get(userId, importId);
    }
}
