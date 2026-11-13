package io.github.thphuc06.jars.imports.internal.storage;

import io.github.thphuc06.jars.imports.internal.domain.StatementImport;
import io.github.thphuc06.jars.imports.internal.parsing.InvalidStatementException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development and tests: files in a local folder, uploaded through {@code PUT /api/v1/imports/{id}/file} (which only
 * exists with this store) instead of a presigned S3 URL. The upload endpoint starts the import itself, standing in for
 * the S3 -> EventBridge -> SQS path.
 */
@Component
@ConditionalOnProperty(name = "jars.imports.storage", havingValue = "local", matchIfMissing = true)
public class LocalStatementStore implements StatementStore {

    private final ImportProperties properties;

    LocalStatementStore(ImportProperties properties) {
        this.properties = properties;
    }

    @Override
    public UploadTarget uploadTarget(StatementImport statementImport, Instant now) {
        return new UploadTarget(
                "/api/v1/imports/" + statementImport.getId() + "/file",
                "PUT",
                Map.of("Content-Type", "text/csv"),
                now.plus(properties.uploadTtl()),
                true);
    }

    public void write(String objectKey, byte[] content) {
        if (content.length > properties.maxFileSize().toBytes()) {
            throw tooLarge();
        }
        try {
            Path file = file(objectKey);
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] read(String objectKey) {
        Path file = file(objectKey);
        try {
            if (!Files.exists(file)) {
                throw new InvalidStatementException("Không tìm thấy tệp đã tải lên.");
            }
            if (Files.size(file) > properties.maxFileSize().toBytes()) {
                throw tooLarge();
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private InvalidStatementException tooLarge() {
        return new InvalidStatementException(
                "Tệp lớn hơn " + properties.maxFileSize().toMegabytes() + " MB. Hãy tách sao kê theo tháng.");
    }

    private Path file(String objectKey) {
        // keys are built from two UUIDs (StatementImport.objectKeyOf), so they cannot climb out of the folder
        return properties.localDir().resolve(objectKey).normalize();
    }
}
