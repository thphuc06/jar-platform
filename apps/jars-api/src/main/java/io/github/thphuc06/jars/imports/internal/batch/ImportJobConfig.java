package io.github.thphuc06.jars.imports.internal.batch;

import io.github.thphuc06.jars.imports.internal.domain.StatementImport;
import io.github.thphuc06.jars.imports.internal.parsing.InvalidRowException;
import io.github.thphuc06.jars.imports.internal.persistence.ImportErrorStore;
import io.github.thphuc06.jars.imports.internal.persistence.StatementImportRepository;
import io.github.thphuc06.jars.imports.internal.storage.StatementStore;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.UserId;
import java.time.Clock;
import java.util.UUID;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The statement import as a Spring Batch job: one chunk-oriented step, {@value #CHUNK_SIZE} rows per chunk.
 *
 * <p><b>One transaction per chunk, the application's own.</b> The ledger postings of a chunk, their idempotency keys,
 * the skipped-row records and the reader position stored by Spring Batch commit together. That makes a restart exact:
 * it resumes after the last committed chunk, and a chunk that was rolled back left nothing behind. (A step with a
 * resourceless transaction manager, where each posting commits on its own, would also work thanks to the idempotency
 * keys, but the "after commit" callbacks of the postings, such as the outbox of {@code TransactionPosted}, would then
 * belong to the chunk and be lost when a later item rolls it back.)
 *
 * <p><b>Fault tolerance.</b> A row the parser or the ledger rejects ({@link InvalidRowException},
 * {@link DomainException}) is skipped and recorded with its reason; an optimistic-lock conflict with a posting the user
 * makes at the same time retries the chunk.
 */
@Configuration(proxyBeanMethods = false)
class ImportJobConfig {

    static final String JOB_NAME = "importStatement";
    static final String IMPORT_ID = "importId";
    static final int CHUNK_SIZE = 50;
    /** A statement where nearly every row is rejected is still finished, with its reasons listed. */
    static final long MAX_SKIPPED_ROWS = 100_000;

    @Bean
    Job importStatementJob(JobRepository jobRepository, Step importStatementStep, ImportFinisher finisher) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(importStatementStep)
                .listener(finisher)
                .build();
    }

    @Bean
    Step importStatementStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            StatementReader statementReader,
            RowProcessor rowProcessor,
            LedgerRowWriter ledgerRowWriter,
            ImportSkips importSkips) {
        return new StepBuilder(JOB_NAME + "Rows", jobRepository)
                .<ImportRow.Raw, ImportRow.Mapped>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(statementReader)
                .processor(rowProcessor)
                .writer(ledgerRowWriter)
                .faultTolerant()
                .retry(OptimisticLockingFailureException.class)
                .retryLimit(3)
                .skip(InvalidRowException.class, DomainException.class)
                .skipLimit(MAX_SKIPPED_ROWS)
                .skipListener(importSkips)
                .build();
    }

    // ------------------------------------------------------------------ step scoped: one set per step execution

    @Bean
    @StepScope
    ImportContext importContext(
            @Value("#{jobParameters['" + IMPORT_ID + "']}") String importId, StatementImportRepository imports) {
        StatementImport statementImport =
                imports.findById(UUID.fromString(importId)).orElseThrow();
        return new ImportContext(statementImport.getId(), new UserId(statementImport.getUserId()));
    }

    @Bean
    @StepScope
    StatementReader statementReader(
            @Value("#{jobParameters['" + IMPORT_ID + "']}") String importId,
            StatementStore store,
            StatementImportRepository imports,
            ImportContext importContext) {
        return new StatementReader(
                imports.findById(UUID.fromString(importId)).orElseThrow(), store, imports, importContext);
    }

    @Bean
    @StepScope
    RowProcessor rowProcessor(ImportContext importContext, LedgerApi ledger, Clock clock) {
        return new RowProcessor(ledger.jars(importContext.userId()), clock);
    }

    @Bean
    @StepScope
    LedgerRowWriter ledgerRowWriter(LedgerApi ledger, ImportContext importContext) {
        return new LedgerRowWriter(ledger, importContext);
    }

    @Bean
    @StepScope
    ImportSkips importSkips(ImportErrorStore errors, ImportContext importContext) {
        return new ImportSkips(errors, importContext);
    }
}
