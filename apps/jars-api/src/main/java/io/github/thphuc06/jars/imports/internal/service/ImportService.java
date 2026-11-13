package io.github.thphuc06.jars.imports.internal.service;

import io.github.thphuc06.jars.imports.internal.domain.ImportProblems;
import io.github.thphuc06.jars.imports.internal.domain.ImportStatus;
import io.github.thphuc06.jars.imports.internal.domain.StatementImport;
import io.github.thphuc06.jars.imports.internal.persistence.ImportErrorStore;
import io.github.thphuc06.jars.imports.internal.persistence.ImportErrorStore.RowError;
import io.github.thphuc06.jars.imports.internal.persistence.StatementImportRepository;
import io.github.thphuc06.jars.imports.internal.storage.StatementStore;
import io.github.thphuc06.jars.imports.internal.storage.StatementStore.UploadTarget;
import io.github.thphuc06.jars.platform.audit.Audit;
import io.github.thphuc06.jars.platform.audit.AuditAction;
import io.github.thphuc06.jars.platform.audit.Audited;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creating an import (and its upload URL), and reading imports back. Running them is {@code ImportLauncher}'s job. */
@Service
public class ImportService {

    static final int HISTORY_SIZE = 20;
    static final int ERRORS_SHOWN = 100;
    private static final List<String> EXTENSIONS = List.of(".csv", ".tsv", ".txt");

    public record ImportView(
            UUID id,
            String fileName,
            String status,
            Integer totalRows,
            Integer postedRows,
            Integer skippedRows,
            String failureReason,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            List<RowError> errors) {}

    public record CreatedImport(ImportView statementImport, UploadTarget upload) {}

    private final StatementImportRepository imports;
    private final ImportErrorStore errors;
    private final StatementStore store;
    private final Clock clock;

    ImportService(StatementImportRepository imports, ImportErrorStore errors, StatementStore store, Clock clock) {
        this.imports = imports;
        this.errors = errors;
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    @Audited(AuditAction.IMPORT_CREATED)
    public CreatedImport create(UserId userId, String fileName) {
        String name = fileName.strip();
        if (EXTENSIONS.stream().noneMatch(ext -> name.toLowerCase(Locale.ROOT).endsWith(ext))) {
            throw new DomainException(
                    ImportProblems.INVALID_IMPORT, "Chỉ nhận sao kê dạng CSV (.csv, .tsv hoặc .txt).");
        }
        Instant now = clock.instant();
        StatementImport statementImport = imports.save(new StatementImport(Uuids.v7(), userId.value(), name, now));
        // one row for the whole statement, never one per CSV line; the file name is user text, not recorded
        Audit.entity(statementImport.getId());
        return new CreatedImport(view(statementImport, List.of()), store.uploadTarget(statementImport, now));
    }

    @Transactional(readOnly = true)
    public ImportView get(UserId userId, UUID importId) {
        StatementImport statementImport = find(userId, importId);
        return view(statementImport, errors.firstErrors(importId, ERRORS_SHOWN));
    }

    @Transactional(readOnly = true)
    public List<ImportView> history(UserId userId) {
        return imports.findByUserIdOrderByCreatedAtDesc(userId.value(), Limit.of(HISTORY_SIZE)).stream()
                .map(i -> view(i, List.of()))
                .toList();
    }

    /** For the local upload endpoint: the import of this user that may still receive its file. */
    @Transactional(readOnly = true)
    public StatementImport acceptingUpload(UserId userId, UUID importId) {
        StatementImport statementImport = find(userId, importId);
        if (statementImport.getStatus() == ImportStatus.PROCESSING
                || statementImport.getStatus() == ImportStatus.COMPLETED) {
            throw new DomainException(
                    ImportProblems.IMPORT_ALREADY_STARTED, "Phiên nhập này đã nhận tệp. Hãy tạo phiên nhập mới.");
        }
        return statementImport;
    }

    private StatementImport find(UserId userId, UUID importId) {
        return imports.findByIdAndUserId(importId, userId.value())
                .orElseThrow(() -> DomainException.notFound("phiên nhập sao kê"));
    }

    private static ImportView view(StatementImport i, List<RowError> rowErrors) {
        return new ImportView(
                i.getId(),
                i.getFileName(),
                i.getStatus().name(),
                i.getTotalRows(),
                i.getPostedRows(),
                i.getSkippedRows(),
                i.getFailureReason(),
                i.getCreatedAt(),
                i.getStartedAt(),
                i.getCompletedAt(),
                rowErrors);
    }
}
