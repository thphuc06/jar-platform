package io.github.thphuc06.jars.budgeting.internal.jobs;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.github.thphuc06.jars.budgeting.internal.service.MonthCloseJob;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Receives the scheduled jobs from the SQS queue named by {@code jars.jobs.queue-name} ({@code JARS_JOBS_QUEUE}). It
 * exists only in the {@code aws} profile: locally and in tests nothing connects to AWS, and the logic it triggers
 * ({@link MonthCloseJob}) is called directly.
 *
 * <p>A message is deleted from the queue only when {@code onMessage} returns normally, so a failure delivers it again
 * (after the visibility timeout) and, after {@code maxReceiveCount} attempts, moves it to the DLQ, which has an alarm.
 */
@Component
@Profile("aws")
class MonthCloseQueueListener {

    private final MonthCloseJob job;

    MonthCloseQueueListener(MonthCloseJob job) {
        this.job = job;
    }

    @SqsListener("${jars.jobs.queue-name}")
    void onMessage(String payload) {
        job.handle(payload);
    }
}
