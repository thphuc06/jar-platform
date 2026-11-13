package io.github.thphuc06.jars.imports.internal.batch;

import io.github.thphuc06.jars.imports.StatementImportCompleted;
import io.github.thphuc06.jars.imports.internal.domain.ImportStatus;
import io.github.thphuc06.jars.imports.internal.domain.StatementImport;
import io.github.thphuc06.jars.imports.internal.parsing.InvalidStatementException;
import io.github.thphuc06.jars.imports.internal.persistence.StatementImportRepository;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * After the job: writes the outcome to the import row and announces {@link StatementImportCompleted}, in one
 * transaction (the event goes through the Modulith outbox, so the notification is not lost if the process stops).
 */
@Component
class ImportFinisher implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ImportFinisher.class);

    private final StatementImportRepository imports;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    ImportFinisher(
            StatementImportRepository imports, ApplicationEventPublisher events, TransactionTemplate tx, Clock clock) {
        this.imports = imports;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public void afterJob(JobExecution job) {
        UUID importId = UUID.fromString(job.getJobParameters().getString(ImportJobConfig.IMPORT_ID));
        StepExecution step = job.getStepExecutions().stream().findFirst().orElse(null);
        int posted = step == null ? 0 : Math.toIntExact(step.getWriteCount());
        int skipped = step == null
                ? 0
                : Math.toIntExact(step.getReadSkipCount() + step.getProcessSkipCount() + step.getWriteSkipCount());
        ExecutionContext context = step == null ? new ExecutionContext() : step.getExecutionContext();
        boolean succeeded = job.getStatus() == BatchStatus.COMPLETED;
        String reason = succeeded ? null : reasonOf(job);
        if (!succeeded) {
            log.warn("Statement import {} failed: {}", importId, job.getAllFailureExceptions());
        }

        tx.executeWithoutResult(status -> {
            StatementImport statementImport = imports.findById(importId).orElseThrow();
            if (context.containsKey(StatementReader.SHA256)) {
                statementImport.fileRead(
                        context.getString(StatementReader.SHA256), context.getInt(StatementReader.ROWS, 0));
            }
            Instant now = clock.instant();
            if (succeeded) {
                statementImport.complete(posted, skipped, now);
            } else {
                statementImport.fail(reason, posted, skipped, now);
            }
            events.publishEvent(new StatementImportCompleted(
                    Uuids.v7(),
                    importId,
                    new UserId(statementImport.getUserId()),
                    statementImport.getFileName(),
                    statementImport.getStatus() == ImportStatus.COMPLETED,
                    posted,
                    skipped,
                    reason,
                    now));
        });
    }

    /** A message the user can act on when the file itself was the problem, a generic one otherwise. */
    static String reasonOf(JobExecution job) {
        for (Throwable failure : job.getAllFailureExceptions()) {
            for (Throwable t = failure; t != null; t = t.getCause()) {
                if (t instanceof InvalidStatementException) {
                    return t.getMessage();
                }
            }
        }
        return "Nhập sao kê bị lỗi hệ thống. Tải lại đúng tệp này sẽ tiếp tục từ chỗ đã dừng.";
    }
}
