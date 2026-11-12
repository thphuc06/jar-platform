package io.github.thphuc06.jars.infra;

import io.github.thphuc06.jars.infra.config.EnvConfig;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Acknowledgment;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.Dashboard;
import software.amazon.awscdk.services.cloudwatch.GraphWidget;
import software.amazon.awscdk.services.cloudwatch.IMetric;
import software.amazon.awscdk.services.cloudwatch.MathExpression;
import software.amazon.awscdk.services.cloudwatch.Metric;
import software.amazon.awscdk.services.cloudwatch.MetricOptions;
import software.amazon.awscdk.services.cloudwatch.TextWidget;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.ContainerDefinition;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerDependency;
import software.amazon.awscdk.services.ecs.ContainerDependencyCondition;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.LogDrivers;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.HttpCodeTarget;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.Policy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.logs.ILogGroup;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.sns.ITopic;

/**
 * Observability of jars-api (aws-infrastructure.md section 11): an ADOT collector sidecar that forwards the OTLP traces
 * and business metrics of the application to X-Ray and CloudWatch, a dashboard and the alarms on the ALB.
 *
 * <p>Everything here lives and dies with JarsApp: the alarms watch the ALB and the service of this stack, so a separate
 * stack would need exports from JarsApp and one more deploy and destroy step for the same lifecycle.
 */
final class ApiObservability {

    static final String COLLECTOR_CONTAINER = "otel-collector";
    /** Pinned like every image: an unpinned collector would change behaviour between two deploys of the same commit. */
    static final String COLLECTOR_IMAGE = "public.ecr.aws/aws-observability/aws-otel-collector:v0.51.0";
    static final String METRICS_NAMESPACE = "Jars/Api";
    /** Container memory of the two containers; together they stay inside the 1 GB of the task. */
    static final int API_MEMORY_MIB = 896;
    static final int COLLECTOR_MEMORY_MIB = 128;

    private ApiObservability() {}

    /**
     * The collector configuration (ADOT reads it from AOT_CONFIG_CONTENT). Unlike ADOT's built-in ECS configuration it
     * keeps only metrics named {@code jars.*}: every metric and dimension set that reaches EMF is a paid custom metric,
     * and the JVM and HTTP metrics of Micrometer would be a few hundred of them. Request rate, latency and errors come
     * from the ALB metrics, which are free.
     */
    static String collectorConfig(String region, String metricsLogGroup) {
        return """
                extensions:
                  health_check:
                receivers:
                  otlp:
                    protocols:
                      http:
                        endpoint: 0.0.0.0:4318
                processors:
                  batch/traces:
                    timeout: 1s
                    send_batch_size: 50
                  batch/metrics:
                    timeout: 60s
                  filter/business:
                    error_mode: ignore
                    metrics:
                      metric:
                        - 'not IsMatch(name, "^jars\\\\.")'
                exporters:
                  awsxray:
                    region: %1$s
                  awsemf:
                    region: %1$s
                    namespace: %2$s
                    log_group_name: '%3$s'
                    log_stream_name: emf
                    dimension_rollup_option: NoDimensionRollup
                service:
                  extensions: [health_check]
                  pipelines:
                    traces:
                      receivers: [otlp]
                      processors: [batch/traces]
                      exporters: [awsxray]
                    metrics:
                      receivers: [otlp]
                      processors: [filter/business, batch/metrics]
                      exporters: [awsemf]
                """
                .formatted(region, METRICS_NAMESPACE, metricsLogGroup);
    }

    /** Adds the collector to the task, with the permissions it needs on the task role and its EMF log group. */
    static void addCollector(
            Stack stack,
            EnvConfig cfg,
            FargateTaskDefinition taskDefinition,
            ContainerDefinition api,
            ILogGroup appLogs,
            IRole taskRole) {
        LogGroup metricsLogs = LogGroup.Builder.create(stack, "ApiMetricsLogGroup")
                .logGroupName("/jars/" + cfg.env() + "/api/metrics")
                .retention(RetentionDays.TWO_WEEKS)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        ContainerDefinition collector = taskDefinition.addContainer(
                COLLECTOR_CONTAINER,
                ContainerDefinitionOptions.builder()
                        .containerName(COLLECTOR_CONTAINER)
                        .image(ContainerImage.fromRegistry(COLLECTOR_IMAGE))
                        // losing telemetry must never stop the API
                        .essential(false)
                        .memoryLimitMiB(COLLECTOR_MEMORY_MIB)
                        .readonlyRootFilesystem(true)
                        .environment(Map.of(
                                "AOT_CONFIG_CONTENT",
                                collectorConfig(stack.getRegion(), metricsLogs.getLogGroupName())))
                        .logging(LogDrivers.awsLogs(AwsLogDriverProps.builder()
                                .logGroup(appLogs)
                                .streamPrefix("otel")
                                .build()))
                        .build());
        // the first spans of the startup should find a listening collector
        api.addContainerDependencies(ContainerDependency.builder()
                .container(collector)
                .condition(ContainerDependencyCondition.START)
                .build());

        // kept apart from the task role's default policy, which is the application's own (SQS) permissions
        Policy telemetry = Policy.Builder.create(stack, "ApiTelemetryPolicy")
                .statements(List.of(
                        PolicyStatement.Builder.create()
                                .sid("XRayTraces")
                                .effect(Effect.ALLOW)
                                .actions(List.of(
                                        "xray:PutTraceSegments",
                                        "xray:PutTelemetryRecords",
                                        "xray:GetSamplingRules",
                                        "xray:GetSamplingTargets"))
                                .resources(List.of("*"))
                                .build(),
                        PolicyStatement.Builder.create()
                                .sid("EmfMetrics")
                                .effect(Effect.ALLOW)
                                .actions(List.of(
                                        "logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"))
                                .resources(List.of(metricsLogs.getLogGroupArn()))
                                .build()))
                .roles(List.of(taskRole))
                .build();
        Validations.of(telemetry)
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions-IAM5[Resource::*]")
                        .reason("X-Ray PutTraceSegments, PutTelemetryRecords and the sampling calls have no"
                                + " resource-level permissions in IAM. The EMF statement is scoped to one log group.")
                        .build());
    }

    /** The dashboard and the request alarms. Both use ALB, ECS, RDS and SQS metrics, which cost nothing. */
    static void addDashboardAndAlarms(
            Stack stack,
            EnvConfig cfg,
            ApplicationLoadBalancer alb,
            FargateService service,
            String dbClusterIdentifier,
            ITopic alarmTopic) {
        Duration fiveMinutes = Duration.minutes(5);
        Metric requests = alb.getMetrics()
                .requestCount(MetricOptions.builder().period(fiveMinutes).build());
        Metric errors = alb.getMetrics()
                .httpCodeTarget(
                        HttpCodeTarget.TARGET_5XX_COUNT,
                        MetricOptions.builder().period(fiveMinutes).build());
        Metric p95 = alb.getMetrics()
                .targetResponseTime(MetricOptions.builder()
                        .statistic("p95")
                        .period(fiveMinutes)
                        .build());

        // a percentage only means something with some traffic: below 20 requests in 5 minutes it counts as 0
        MathExpression errorRate = MathExpression.Builder.create()
                .expression("IF(requests >= 20, 100 * errors / requests, 0)")
                .usingMetrics(Map.of("requests", requests, "errors", errors))
                .label("5xx rate (%)")
                .period(fiveMinutes)
                .build();

        Alarm errorAlarm = Alarm.Builder.create(stack, "Api5xxRateAlarm")
                .alarmName(cfg.name("api-5xx-rate"))
                .alarmDescription("More than 5% of API requests answered 5xx in two 5-minute periods in a row"
                        + " (at least 20 requests per period). Logs: /jars/" + cfg.env() + "/api; traces: X-Ray.")
                .metric(errorRate)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .threshold(5)
                .evaluationPeriods(2)
                .datapointsToAlarm(2)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();
        errorAlarm.addAlarmAction(new SnsAction(alarmTopic));

        Alarm latencyAlarm = Alarm.Builder.create(stack, "ApiP95LatencyAlarm")
                .alarmName(cfg.name("api-p95-latency"))
                .alarmDescription("p95 of the API response time (ALB TargetResponseTime) above 1 s for 15 minutes."
                        + " The first request after Aurora resumed can take ~15 s, so one slow period is ignored.")
                .metric(p95)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .threshold(1)
                .evaluationPeriods(3)
                .datapointsToAlarm(3)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();
        latencyAlarm.addAlarmAction(new SnsAction(alarmTopic));

        Map<String, String> serviceDimensions = Map.of(
                "ClusterName", service.getCluster().getClusterName(),
                "ServiceName", service.getServiceName());
        Dashboard.Builder.create(stack, "Dashboard")
                .dashboardName(cfg.prefix())
                .defaultInterval(Duration.hours(3))
                .widgets(List.of(
                        List.of(TextWidget.Builder.create()
                                .markdown("# Jars " + cfg.env() + "\nRequests and latency from the ALB; business"
                                        + " metrics from the app (" + METRICS_NAMESPACE + ", sent through the ADOT"
                                        + " collector); traces in X-Ray. Runbook: docs/05-operations/runbook.md")
                                .width(24)
                                .height(2)
                                .build()),
                        List.of(
                                graph("Requests and 5xx (5 min)", List.of(requests, errors), List.of(errorRate)),
                                graph(
                                        "Response time (s)",
                                        List.of(
                                                withStatistic(alb, "p50"),
                                                p95,
                                                withStatistic(alb, "p99")),
                                        List.of())),
                        List.of(
                                graph(
                                        "ECS CPU and memory (%)",
                                        List.of(
                                                ecs("CPUUtilization", serviceDimensions),
                                                ecs("MemoryUtilization", serviceDimensions)),
                                        List.of()),
                                graph(
                                        "Aurora capacity (ACU, 0 = paused)",
                                        List.of(Metric.Builder.create()
                                                .namespace("AWS/RDS")
                                                .metricName("ServerlessDatabaseCapacity")
                                                .dimensionsMap(Map.of("DBClusterIdentifier", dbClusterIdentifier))
                                                .statistic("Maximum")
                                                .period(fiveMinutes)
                                                .build()),
                                        List.of())),
                        List.of(
                                graph(
                                        "Business events (per 5 min)",
                                        List.of(
                                                search("jars.transactions.posted", "Sum"),
                                                search("jars.anomalies.detected", "Sum")),
                                        List.of()),
                                graph(
                                        "Jobs queue (messages waiting)",
                                        List.of(
                                                queueDepth(MessagingStack.JOBS_QUEUE_NAME),
                                                queueDepth(MessagingStack.JOBS_QUEUE_NAME + "-dlq")),
                                        List.of()))))
                .build();
    }

    /**
     * By the fixed queue name rather than through the Queue construct: that would be one more cross-stack export of
     * JarsMessaging for a dashboard line (see StableExportsTest).
     */
    private static Metric queueDepth(String queueName) {
        return Metric.Builder.create()
                .namespace("AWS/SQS")
                .metricName("ApproximateNumberOfMessagesVisible")
                .dimensionsMap(Map.of("QueueName", queueName))
                .statistic("Maximum")
                .period(Duration.minutes(5))
                .build();
    }

    private static Metric withStatistic(ApplicationLoadBalancer alb, String statistic) {
        return alb.getMetrics()
                .targetResponseTime(MetricOptions.builder()
                        .statistic(statistic)
                        .period(Duration.minutes(5))
                        .build());
    }

    private static Metric ecs(String name, Map<String, String> dimensions) {
        return Metric.Builder.create()
                .namespace("AWS/ECS")
                .metricName(name)
                .dimensionsMap(dimensions)
                .statistic("Average")
                .period(Duration.minutes(5))
                .build();
    }

    /** All series of one business metric, whatever its tags became as dimensions. */
    private static MathExpression search(String metricName, String statistic) {
        return MathExpression.Builder.create()
                .expression("SEARCH('{" + METRICS_NAMESPACE + "} MetricName=\"" + metricName + "\"', '" + statistic
                        + "', 300)")
                .label(metricName)
                .period(Duration.minutes(5))
                .build();
    }

    private static GraphWidget graph(String title, List<IMetric> left, List<IMetric> right) {
        GraphWidget.Builder builder = GraphWidget.Builder.create().title(title).width(12).height(6);
        if (!left.isEmpty()) {
            builder.left(left);
        }
        if (!right.isEmpty()) {
            builder.right(right);
        }
        return builder.build();
    }
}
