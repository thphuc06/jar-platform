package io.github.thphuc06.jars.ledger.internal.jobs;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.github.thphuc06.jars.ledger.internal.service.ReconciliationJob;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Receives the nightly reconciliation message from the SQS queue named by {@code jars.jobs.reconciliation-queue-name}
 * ({@code JARS_RECONCILIATION_QUEUE}). Like the month-close listener it exists only in the {@code aws} profile: locally
 * and in tests nothing connects to AWS, and the logic it triggers ({@link ReconciliationJob}) is called directly.
 *
 * <p>A message is deleted only when {@code onMessage} returns normally. A failed run (the database could not be read)
 * is delivered again after the visibility timeout and, after {@code maxReceiveCount} receives, moved to the DLQ, which
 * has an alarm. Found mismatches are not failures.
 */
@Component
@Profile("aws")
class ReconciliationQueueListener {

    private final ReconciliationJob job;

    ReconciliationQueueListener(ReconciliationJob job) {
        this.job = job;
    }

    @SqsListener("${jars.jobs.reconciliation-queue-name}")
    void onMessage(String payload) {
        job.handle(payload);
    }
}
