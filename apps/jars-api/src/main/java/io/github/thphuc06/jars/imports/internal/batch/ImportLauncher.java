package io.github.thphuc06.jars.imports.internal.batch;

import io.github.thphuc06.jars.imports.internal.domain.StatementImport;
import io.github.thphuc06.jars.imports.internal.persistence.StatementImportRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts (or restarts) the import of one uploaded file. Called by whatever learns that the file arrived: the SQS
 * listener of the S3 event on AWS, the local upload endpoint in development. Safe to call twice for the same import
 * (S3 events are delivered at least once): a running import is left alone, a finished one is not run again, and a
 * failed one restarts after its last committed chunk (same job parameters = same job instance).
 *
 * <p>Runs the job in the calling thread: the SQS listener keeps the message invisible meanwhile, and acknowledges it
 * only when the import is finished.
 */
@Service
public class ImportLauncher {

    private static final Logger log = LoggerFactory.getLogger(ImportLauncher.class);

    private final JobOperator jobs;
    private final Job importStatementJob;
    private final StatementImportRepository imports;
    private final TransactionTemplate tx;
    private final Clock clock;

    ImportLauncher(
            JobOperator jobs,
            Job importStatementJob,
            StatementImportRepository imports,
            TransactionTemplate tx,
            Clock clock) {
        this.jobs = jobs;
        this.importStatementJob = importStatementJob;
        this.imports = imports;
        this.tx = tx;
        this.clock = clock;
    }

    /** @return false when there is no such import or it already finished */
    public boolean run(UUID importId) {
        Optional<StatementImport> started = tx.execute(status -> imports.findById(importId)
                .filter(StatementImport::canStart)
                .map(i -> {
                    i.start(clock.instant());
                    return i;
                }));
        if (started == null || started.isEmpty()) {
            log.info("Import {} is unknown or already completed: nothing to run", importId);
            return false;
        }
        try {
            jobs.start(
                    importStatementJob,
                    new JobParametersBuilder()
                            .addString(ImportJobConfig.IMPORT_ID, importId.toString())
                            .toJobParameters());
            return true;
        } catch (JobExecutionAlreadyRunningException | JobInstanceAlreadyCompleteException e) {
            // a duplicate S3 event while the first one is still running, or after it finished
            log.info("Import {} not started again: {}", importId, e.getMessage());
            return false;
        } catch (Exception e) {
            throw new IllegalStateException("Could not start the import " + importId, e);
        }
    }
}
