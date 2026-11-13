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
 * JarsMessaging: the jobs queue and the month-close schedule (docs/02-architecture/aws-infrastructure.md
 * section 7, flows.md F6).
 *
 * <p>{@code jars-jobs} with its DLQ and the {@code month-close} schedule; {@code jars-imports} with its DLQ and the
 * {@code statement-uploaded} rule (Phase 2). The SNS/SQS FIFO chain belongs to a later phase.
 */
public final class MessagingStack extends Stack {

    public static final String JOBS_QUEUE_NAME = "jars-jobs";
    public static final String IMPORTS_QUEUE_NAME = "jars-imports";

    private final Queue importsQueue;
    private final Queue importsDeadLetterQueue;
    private final Queue jobsQueue;
    private final Queue jobsDeadLetterQueue;
    private final Queue schedulerDeadLetterQueue;

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
        // messages that EventBridge Scheduler could not deliver to jars-jobs at all
        this.schedulerDeadLetterQueue = Queue.Builder.create(this, "SchedulerDeadLetterQueue")
                .queueName(JOBS_QUEUE_NAME + "-scheduler-dlq")
                .retentionPeriod(Duration.days(14))
                .encryption(QueueEncryption.SQS_MANAGED)
                .enforceSsl(true)
                .build();

        addMonthCloseSchedule(cfg);

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
                "EventBridge Scheduler could not deliver a schedule to jars-jobs.");
        addDlqAlarm("ImportsDlqAlarm", cfg.name("imports-dlq-not-empty"), importsDeadLetterQueue, alarmTopic,
                "An upload event gave up after 3 receives on jars-imports: the import never ran. See the runbook.");
        Validations.of(importsDeadLetterQueue)
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions-SQS3")
                        .reason("This queue is the dead-letter queue of jars-imports; the alarm on its depth is the"
                                + " safety net.")
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
                        .reason("This queue is the dead-letter queue of the month-close schedule (a Scheduler"
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
        // the scheduler may only send to these two queues, nothing else (security.md section 3)
        Role schedulerRole = Role.Builder.create(this, "SchedulerRole")
                .roleName(cfg.name("scheduler"))
                .description("EventBridge Scheduler: send the month-close message to jars-jobs")
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

    public Queue schedulerDeadLetterQueue() {
        return schedulerDeadLetterQueue;
    }
}
