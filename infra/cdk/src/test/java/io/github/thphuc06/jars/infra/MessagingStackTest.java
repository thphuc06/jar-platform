package io.github.thphuc06.jars.infra;

import static io.github.thphuc06.jars.infra.T.like;
import static io.github.thphuc06.jars.infra.T.map;
import static io.github.thphuc06.jars.infra.T.properties;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class MessagingStackTest {

    private final Template template = InfraFixture.aurora().messaging();

    @Test
    void jobsQueueHasFiveMinuteVisibilityAndAFiveReceiveRedrivePolicy() {
        template.hasResourceProperties(
                "AWS::SQS::Queue",
                like(
                        "QueueName", "jars-jobs",
                        "VisibilityTimeout", 300,
                        "RedrivePolicy", like("maxReceiveCount", 5)));
    }

    @Test
    void uploadsOfTheStatementsBucketReachTheImportsQueue() {
        template.hasResourceProperties(
                "AWS::SQS::Queue",
                like(
                        "QueueName", "jars-imports",
                        "VisibilityTimeout", 900,
                        "RedrivePolicy", like("maxReceiveCount", 3)));
        template.hasResourceProperties(
                "AWS::Events::Rule",
                like(
                        "Name", "statement-uploaded",
                        "EventPattern",
                                map(
                                        "source", List.of("aws.s3"),
                                        "detail-type", List.of("Object Created"),
                                        "detail",
                                                map(
                                                        "bucket",
                                                                map("name", List.of("jars-dev-statements-626212071763")),
                                                        "object",
                                                                map("key", List.of(map("prefix", "uploads/"))))),
                        "Targets", List.of(like("Arn", map("Fn::GetAtt", List.of(Match.stringLikeRegexp("ImportsQueue.*"), "Arn"))))));
        // only this rule may send to the queue
        template.hasResourceProperties(
                "AWS::SQS::QueuePolicy",
                like(
                        "PolicyDocument",
                        like(
                                "Statement",
                                Match.arrayWith(List.of(like(
                                        "Action", List.of("sqs:GetQueueAttributes", "sqs:GetQueueUrl", "sqs:SendMessage"),
                                        "Principal", map("Service", "events.amazonaws.com"),
                                        "Condition",
                                                map(
                                                        "ArnEquals",
                                                        map(
                                                                "aws:SourceArn",
                                                                map(
                                                                        "Fn::GetAtt",
                                                                        List.of(
                                                                                Match.stringLikeRegexp(
                                                                                        "StatementUploaded.*"),
                                                                                "Arn"))))))))));
        template.hasResourceProperties(
                "AWS::CloudWatch::Alarm", like("AlarmName", "jars-dev-imports-dlq-not-empty"));
    }

    @Test
    void queuesAreEncryptedAndRefuseNonTlsRequests() {
        for (Map<String, Object> queue : properties(template, "AWS::SQS::Queue")) {
            assertThat(queue).containsEntry("SqsManagedSseEnabled", true);
        }
        // jars-jobs, its DLQ, the scheduler DLQ, jars-imports (TLS + the EventBridge rule) and its DLQ
        template.resourceCountIs("AWS::SQS::QueuePolicy", 5);
        template.hasResourceProperties(
                "AWS::SQS::QueuePolicy",
                like(
                        "PolicyDocument",
                        like(
                                "Statement",
                                Match.arrayWith(List.of(like(
                                        "Effect", "Deny",
                                        "Condition", map("Bool", map("aws:SecureTransport", "false"))))))));
    }

    @Test
    void monthCloseScheduleRunsAt0005OnTheFirstInVietnamTimeWithTheDocumentedPayload() {
        template.hasResourceProperties(
                "AWS::Scheduler::Schedule",
                like(
                        "Name", "month-close",
                        "ScheduleExpression", "cron(5 0 1 * ? *)",
                        "ScheduleExpressionTimezone", "Asia/Ho_Chi_Minh",
                        "FlexibleTimeWindow", map("Mode", "OFF"),
                        "State", "ENABLED",
                        "Target", like("Input", "{\"job\":\"MONTH_CLOSE\"}", "DeadLetterConfig", like("Arn", Match.anyValue()))));
    }

    @Test
    void scheduleTargetsTheJobsQueueAndNotTheDlq() {
        Map<String, Object> schedule = properties(template, "AWS::Scheduler::Schedule").get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) schedule.get("Target");
        assertThat(target.get("Arn").toString()).contains("JobsQueue").doesNotContain("DeadLetter");
        assertThat(((Map<?, ?>) target.get("DeadLetterConfig")).get("Arn").toString())
                .contains("SchedulerDeadLetterQueue");
    }

    @Test
    void schedulerRoleCanOnlySendMessagesToTheJobsQueueAndItsOwnDlq() {
        template.hasResourceProperties(
                "AWS::IAM::Role",
                like(
                        "RoleName", "jars-dev-scheduler",
                        "AssumeRolePolicyDocument",
                                like(
                                        "Statement",
                                        List.of(like(
                                                "Principal", map("Service", "scheduler.amazonaws.com"),
                                                "Condition", map("StringEquals", map("aws:SourceAccount", "626212071763")))))));
        List<Map<String, Object>> policies = properties(template, "AWS::IAM::Policy");
        assertThat(policies).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> statements =
                (List<Map<String, Object>>) ((Map<String, Object>) policies.get(0).get("PolicyDocument")).get("Statement");
        assertThat(statements).hasSize(2);
        assertThat(statements).allSatisfy(statement -> {
            assertThat(statement).containsEntry("Action", "sqs:SendMessage").containsEntry("Effect", "Allow");
            assertThat(statement.get("Resource").toString()).doesNotContain("*");
        });
    }

    @Test
    void alarmsFireWhenADeadLetterQueueHoldsAMessage() {
        template.hasResourceProperties(
                "AWS::CloudWatch::Alarm",
                like(
                        "AlarmName", "jars-dev-jobs-dlq-not-empty",
                        "Namespace", "AWS/SQS",
                        "MetricName", "ApproximateNumberOfMessagesVisible",
                        "ComparisonOperator", "GreaterThanThreshold",
                        "Threshold", 0,
                        "EvaluationPeriods", 1,
                        "TreatMissingData", "notBreaching"));
        template.resourceCountIs("AWS::CloudWatch::Alarm", 3);
        for (Map<String, Object> alarm : properties(template, "AWS::CloudWatch::Alarm")) {
            // the SNS topic comes from JarsFoundation
            assertThat(alarm.get("AlarmActions").toString()).contains("Fn::ImportValue");
        }
    }
}
