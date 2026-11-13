package io.github.thphuc06.jars.infra;

import io.github.thphuc06.jars.infra.config.EnvConfig;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Acknowledgment;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.MetricOptions;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
import software.amazon.awscdk.services.events.EventPattern;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.targets.SqsQueue;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.ServicePrincipalOpts;
import software.amazon.awscdk.services.scheduler.CfnSchedule;
import software.amazon.awscdk.services.sns.ITopic;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.sqs.QueueEncryption;
import software.constructs.Construct;

/**
 * JarsMessaging: the jobs queues and the month-close and ledger-reconciliation schedules
 * (docs/02-architecture/aws-infrastructure.md section 7, flows.md F6).
 *
 * <p>{@code jars-jobs} with its DLQ and the {@code month-close} schedule; {@code jars-imports} with its DLQ and the
 * {@code statement-uploaded} rule (Phase 2); {@code jars-reconciliation} with its DLQ and the nightly
 * {@code ledger-reconciliation} schedule. The SNS/SQS FIFO chain belongs to a later phase.
 */
public final class MessagingStack extends Stack {

    public static final String JOBS_QUEUE_NAME = "jars-jobs";
    public static final String IMPORTS_QUEUE_NAME = "jars-imports";
    /** Nightly ledger reconciliation (balance == SUM(entries)); its own queue, see addReconciliationSchedule. */
    public static final String RECONCILIATION_QUEUE_NAME = "jars-reconciliation";
    /**
     * How long an unconsumed reconciliation message lives. The check is idempotent and the next night brings a fresh
     * one, so an old message is worthless: with JarsApp destroyed (the normal state between sessions) the nightly
     * messages expire here instead of piling up, and a deploy never finds a week of stale runs. A failed run is
     * delivered again within about 15 minutes (3 receives, 5-minute visibility), well inside this hour.
     */
    static final Duration RECONCILIATION_RETENTION = Duration.hours(1);

    private final Queue importsQueue;
    private final Queue importsDeadLetterQueue;
    private final Queue jobsQueue;
    private final Queue jobsDeadLetterQueue;
    private final Queue schedulerDeadLetterQueue;
    private final Queue reconciliationQueue;
    private final Queue reconciliationDeadLetterQueue;
    private Role schedulerRole;

    public MessagingStack(Construct scope, String id, StackProps props, EnvConfig cfg, FoundationStack foundation) {
        super(scope, id, props);

        this.jobsDeadLetterQueue = Queue.Builder.create(this, "JobsDeadLetterQueue")
                .queueName(JOBS_QUEUE_NAME + "-dlq")
                .retentionPeriod(Duration.days(14))
                .encryption(QueueEncryption.SQS_MANAGED)
                .enforceSsl(true)
                .build();
        this.jobsQueue = Queue.Builder.create(this, "JobsQueue")
                .queueName(JOBS_QUEUE_NAME)
                // longer than the slowest job so that a running job is not delivered a second time
                .visibilityTimeout(Duration.minutes(5))
                .deadLetterQueue(DeadLetterQueue.builder()
                        .queue(jobsDeadLetterQueue)
                        .maxReceiveCount(5)
                        .build())
                .encryption(QueueEncryption.SQS_MANAGED)
                .enforceSsl(true)
                .build();
        // messages that EventBridge Scheduler could not deliver to jars-jobs or jars-reconciliation at all
        this.schedulerDeadLetterQueue = Queue.Builder.create(this, "SchedulerDeadLetterQueue")
                .queueName(JOBS_QUEUE_NAME + "-scheduler-dlq")
                .retentionPeriod(Duration.days(14))
                .encryption(QueueEncryption.SQS_MANAGED)
                .enforceSsl(true)
                .build();

        addMonthCloseSchedule(cfg);

        // nightly ledger reconciliation: schedule -> jars-reconciliation -> the API's listener
        this.reconciliationDeadLetterQueue = Queue.Builder.create(this, "ReconciliationDeadLetterQueue")
                .queueName(RECONCILIATION_QUEUE_NAME + "-dlq")
                // counted from the first enqueue, so it must outlive the source queue by a wide margin
                .retentionPeriod(Duration.days(14))
                .encryption(QueueEncryption.SQS_MANAGED)
                .enforceSsl(true)
                .build();
        this.reconciliationQueue = Queue.Builder.create(this, "ReconciliationQueue")
                .queueName(RECONCILIATION_QUEUE_NAME)
                .retentionPeriod(RECONCILIATION_RETENTION)
                // the check is one read-only statement, seconds long; longer than the slowest imaginable run
                .visibilityTimeout(Duration.minutes(5))
                .deadLetterQueue(DeadLetterQueue.builder()
                        .queue(reconciliationDeadLetterQueue)
                        .maxReceiveCount(3)
                        .build())
                .encryption(QueueEncryption.SQS_MANAGED)
                .enforceSsl(true)
                .build();
        addReconciliationSchedule();

        // statement uploads: S3 (EventBridge notifications on) -> rule -> jars-imports -> the API's listener
        this.importsDeadLetterQueue = Queue.Builder.create(this, "ImportsDeadLetterQueue")
                .queueName(IMPORTS_QUEUE_NAME + "-dlq")
                .retentionPeriod(Duration.days(14))
                .encryption(QueueEncryption.SQS_MANAGED)
                .enforceSsl(true)
                .build();
        this.importsQueue = Queue.Builder.create(this, "ImportsQueue")
                .queueName(IMPORTS_QUEUE_NAME)
                // an import of a 5 MB statement takes well under this; a delivery while it runs would only be ignored
                .visibilityTimeout(Duration.minutes(15))
                .deadLetterQueue(DeadLetterQueue.builder()
                        .queue(importsDeadLetterQueue)
                        .maxReceiveCount(3)
                        .build())
                .encryption(QueueEncryption.SQS_MANAGED)
                .enforceSsl(true)
                .build();
        Rule.Builder.create(this, "StatementUploaded")
                .ruleName("statement-uploaded")
                .description("A statement was uploaded to the statements bucket: start its import")
                .eventPattern(EventPattern.builder()
                        .source(List.of("aws.s3"))
                        .detailType(List.of("Object Created"))
                        .detail(Map.of(
                                "bucket", Map.of("name", List.of(DataStack.statementsBucketName(cfg, getAccount()))),
                                "object", Map.of("key", List.of(Map.of("prefix", "uploads/")))))
                        .build())
                // the target adds the queue policy that lets this rule (and only it) send to the queue
                .targets(List.of(SqsQueue.Builder.create(importsQueue).build()))
                .build();

        // the alarm topic belongs to JarsFoundation; its policy there already lets CloudWatch publish
        ITopic alarmTopic = Topic.fromTopicArn(this, "AlarmTopic", foundation.alarmTopic().getTopicArn());
        addDlqAlarm("JobsDlqAlarm", cfg.name("jobs-dlq-not-empty"), jobsDeadLetterQueue, alarmTopic,
                "A message gave up after 5 receives on jars-jobs. See the runbook section on DLQ messages.");
        addDlqAlarm("SchedulerDlqAlarm", cfg.name("scheduler-dlq-not-empty"), schedulerDeadLetterQueue, alarmTopic,
                "EventBridge Scheduler could not deliver a schedule to jars-jobs or jars-reconciliation.");
        addDlqAlarm("ImportsDlqAlarm", cfg.name("imports-dlq-not-empty"), importsDeadLetterQueue, alarmTopic,
                "An upload event gave up after 3 receives on jars-imports: the import never ran. See the runbook.");
        addDlqAlarm("ReconciliationDlqAlarm", cfg.name("reconciliation-dlq-not-empty"), reconciliationDeadLetterQueue,
                alarmTopic,
                "The nightly ledger reconciliation failed 3 times in a row (the database could not be read), so the"
                        + " balances were not verified tonight. See the runbook section on DLQ messages.");
        Validations.of(importsDeadLetterQueue)
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions-SQS3")
                        .reason("This queue is the dead-letter queue of jars-imports; the alarm on its depth is the"
                                + " safety net.")
                        .build());

        Validations.of(reconciliationDeadLetterQueue)
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions-SQS3")
                        .reason("This queue is the dead-letter queue of jars-reconciliation; the alarm on its depth is"
                                + " the safety net.")
                        .build());

        Validations.of(jobsDeadLetterQueue)
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions-SQS3")
                        .reason("This queue is the dead-letter queue of jars-jobs; a DLQ for the DLQ would only move"
                                + " the question one level down. The alarm on its depth is the safety net.")
                        .build());
        Validations.of(schedulerDeadLetterQueue)
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions-SQS3")
                        .reason("This queue is the dead-letter queue of the month-close and ledger-reconciliation"
                                + " schedules (a Scheduler"
                                + " DeadLetterConfig, which cdk-nag does not recognise as a redrive policy)."
                                + " The alarm on its depth is the safety net.")
                        .build());

        CfnOutput.Builder.create(this, "JobsQueueUrl")
                .description("SQS queue that receives the scheduled jobs")
                .value(jobsQueue.getQueueUrl())
                .build();
        CfnOutput.Builder.create(this, "JobsDeadLetterQueueUrl")
                .description("DLQ of jars-jobs")
                .value(jobsDeadLetterQueue.getQueueUrl())
                .build();
    }

    private void addMonthCloseSchedule(EnvConfig cfg) {
        // the scheduler may only send to the queues of its schedules (and its own DLQ), nothing else (security.md section 3)
        this.schedulerRole = Role.Builder.create(this, "SchedulerRole")
                .roleName(cfg.name("scheduler"))
                .description("EventBridge Scheduler: send the scheduled job messages")
                .assumedBy(new ServicePrincipal(
                        "scheduler.amazonaws.com",
                        ServicePrincipalOpts.builder()
                                .conditions(Map.of("StringEquals", Map.of("aws:SourceAccount", getAccount())))
                                .build()))
                .build();
        schedulerRole.addToPolicy(PolicyStatement.Builder.create()
                .sid("SendToJobsQueue")
                .effect(Effect.ALLOW)
                .actions(List.of("sqs:SendMessage"))
                .resources(List.of(jobsQueue.getQueueArn()))
                .build());
        // Scheduler writes undeliverable events to the DLQ with the same execution role
        schedulerRole.addToPolicy(PolicyStatement.Builder.create()
                .sid("SendToSchedulerDeadLetterQueue")
                .effect(Effect.ALLOW)
                .actions(List.of("sqs:SendMessage"))
                .resources(List.of(schedulerDeadLetterQueue.getQueueArn()))
                .build());

        CfnSchedule.Builder.create(this, "MonthClose")
                .name("month-close")
                .description("Closes the month for every user: 00:05 on the 1st, Vietnam time (flows.md F6)")
                .state("ENABLED")
                .scheduleExpression("cron(5 0 1 * ? *)")
                .scheduleExpressionTimezone("Asia/Ho_Chi_Minh")
                .flexibleTimeWindow(
                        CfnSchedule.FlexibleTimeWindowProperty.builder().mode("OFF").build())
                .target(CfnSchedule.TargetProperty.builder()
                        .arn(jobsQueue.getQueueArn())
                        .roleArn(schedulerRole.getRoleArn())
                        .input("{\"job\":\"MONTH_CLOSE\"}")
                        .retryPolicy(CfnSchedule.RetryPolicyProperty.builder()
                                .maximumRetryAttempts(5)
                                .maximumEventAgeInSeconds(3600)
                                .build())
                        .deadLetterConfig(CfnSchedule.DeadLetterConfigProperty.builder()
                                .arn(schedulerDeadLetterQueue.getQueueArn())
                                .build())
                        .build())
                .build();
    }

    /**
     * A second schedule with its own queue rather than a second schedule on jars-jobs: the retention of jars-jobs
     * (4 days, right for a monthly job) would let a week of destroyed JarsApp deliver several stale nightly runs on
     * the next deploy, and a poison or failing reconciliation message would share the DLQ, the redrive count and the
     * alarm of month close. The price is one queue, one DLQ and one alarm; the scheduler role and the scheduler DLQ
     * are reused.
     */
    private void addReconciliationSchedule() {
        schedulerRole.addToPolicy(PolicyStatement.Builder.create()
                .sid("SendToReconciliationQueue")
                .effect(Effect.ALLOW)
                .actions(List.of("sqs:SendMessage"))
                .resources(List.of(reconciliationQueue.getQueueArn()))
                .build());

        CfnSchedule.Builder.create(this, "LedgerReconciliation")
                .name("ledger-reconciliation")
                .description("Checks balance == SUM(entries) for every jar: 02:30 every night, Vietnam time."
                        + " One wake-up of Aurora per day (detect and report only).")
                .state("ENABLED")
                .scheduleExpression("cron(30 2 * * ? *)")
                .scheduleExpressionTimezone("Asia/Ho_Chi_Minh")
                .flexibleTimeWindow(
                        CfnSchedule.FlexibleTimeWindowProperty.builder().mode("OFF").build())
                .target(CfnSchedule.TargetProperty.builder()
                        .arn(reconciliationQueue.getQueueArn())
                        .roleArn(schedulerRole.getRoleArn())
                        .input("{\"job\":\"LEDGER_RECONCILIATION\"}")
                        .retryPolicy(CfnSchedule.RetryPolicyProperty.builder()
                                // the message is worthless after its retention: do not retry past it
                                .maximumRetryAttempts(2)
                                .maximumEventAgeInSeconds(3600)
                                .build())
                        .deadLetterConfig(CfnSchedule.DeadLetterConfigProperty.builder()
                                .arn(schedulerDeadLetterQueue.getQueueArn())
                                .build())
                        .build())
                .build();
    }

    private void addDlqAlarm(String id, String alarmName, Queue dlq, ITopic topic, String description) {
        Alarm alarm = Alarm.Builder.create(this, id)
                .alarmName(alarmName)
                .alarmDescription(description)
                .metric(dlq.metricApproximateNumberOfMessagesVisible(MetricOptions.builder()
                        .period(Duration.minutes(5))
                        .statistic("Maximum")
                        .build()))
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .threshold(0)
                .evaluationPeriods(1)
                // SQS stops reporting for a queue that has been empty for a long time: that is "no messages"
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();
        alarm.addAlarmAction(new SnsAction(topic));
    }

    public Queue importsQueue() {
        return importsQueue;
    }

    public Queue importsDeadLetterQueue() {
        return importsDeadLetterQueue;
    }

    public Queue jobsQueue() {
        return jobsQueue;
    }

    public Queue jobsDeadLetterQueue() {
        return jobsDeadLetterQueue;
    }

    public Queue reconciliationQueue() {
        return reconciliationQueue;
    }

    public Queue reconciliationDeadLetterQueue() {
        return reconciliationDeadLetterQueue;
    }

    public Queue schedulerDeadLetterQueue() {
        return schedulerDeadLetterQueue;
    }
}
