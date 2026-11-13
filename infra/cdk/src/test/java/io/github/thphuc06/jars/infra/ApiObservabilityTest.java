package io.github.thphuc06.jars.infra;

import static io.github.thphuc06.jars.infra.T.like;
import static io.github.thphuc06.jars.infra.T.properties;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

/** The ADOT collector sidecar, its permissions, the dashboard and the request alarms of JarsApp. */
class ApiObservabilityTest {

    private final Template template = InfraFixture.aurora().app();

    @Test
    void collectorIsAPinnedNonEssentialSidecarAndTheApiWaitsForItToStart() {
        Map<String, Object> collector = container("otel-collector");
        assertThat(collector)
                .containsEntry("Image", "public.ecr.aws/aws-observability/aws-otel-collector:v0.51.0")
                .containsEntry("Essential", false)
                .containsEntry("Memory", 128)
                .containsEntry("ReadonlyRootFilesystem", true);
        assertThat(container("api").get("DependsOn"))
                .isEqualTo(List.of(Map.of("ContainerName", "otel-collector", "Condition", "START")));
    }

    @Test
    void bothContainersFitInTheTaskMemory() {
        int api = (Integer) container("api").get("Memory");
        int collector = (Integer) container("otel-collector").get("Memory");
        assertThat(api + collector).isLessThanOrEqualTo(1024);
    }

    @Test
    void collectorSendsTracesToXRayAndOnlyBusinessMetricsToCloudWatch() {
        String config = collectorConfig();
        assertThat(config)
                .contains("exporters: [awsxray]")
                .contains("processors: [filter/business, batch/metrics]")
                .contains("not IsMatch(name, \"^jars\\\\.\")")
                .contains("namespace: Jars/Api")
                // ADOT's built-in ECS configuration exports every metric: it must not be the one in use
                .doesNotContain("ecs-default-config")
                .doesNotContain("statsd");
    }

    @Test
    void collectorConfigIsValidYamlWithTheTwoPipelines() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> yaml = new com.fasterxml.jackson.databind.ObjectMapper(
                        new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                .readValue(ApiObservability.collectorConfig("ap-southeast-1", "/jars/dev/api/metrics"), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> pipelines =
                (Map<String, Object>) ((Map<String, Object>) yaml.get("service")).get("pipelines");
        assertThat(pipelines).containsOnlyKeys("traces", "metrics");
    }

    @Test
    void telemetryPermissionsAreAPolicyOfTheirOwnWithEmfScopedToOneLogGroup() {
        template.hasResourceProperties(
                "AWS::IAM::Policy",
                like(
                        "PolicyDocument",
                        like(
                                "Statement",
                                Match.arrayWith(List.of(
                                        like("Sid", "XRayTraces", "Resource", "*"),
                                        like(
                                                "Sid", "EmfMetrics",
                                                "Resource",
                                                        Map.of(
                                                                "Fn::GetAtt",
                                                                List.of(
                                                                        Match.stringLikeRegexp(
                                                                                "ApiMetricsLogGroup.*"),
                                                                        "Arn")))))),
                        "Roles", List.of(Map.of("Ref", Match.stringLikeRegexp("ApiTaskRole.*")))));
        template.hasResourceProperties(
                "AWS::Logs::LogGroup", like("LogGroupName", "/jars/dev/api/metrics", "RetentionInDays", 14));
    }

    @Test
    void alarmsWatchThe5xxRateAndTheP95LatencyOfTheAlb() {
        template.hasResourceProperties(
                "AWS::CloudWatch::Alarm",
                like(
                        "AlarmName", "jars-dev-api-p95-latency",
                        "MetricName", "TargetResponseTime",
                        "ExtendedStatistic", "p95",
                        "Threshold", 1,
                        "EvaluationPeriods", 3,
                        "TreatMissingData", "notBreaching"));
        template.hasResourceProperties(
                "AWS::CloudWatch::Alarm",
                like(
                        "AlarmName", "jars-dev-api-5xx-rate",
                        "Threshold", 5,
                        "EvaluationPeriods", 2,
                        "Metrics",
                                Match.arrayWith(List.of(
                                        like("Expression", "IF(requests >= 20, 100 * errors / requests, 0)")))));
    }

    @Test
    void ledgerReconciliationAlarmWatchesTheLastRunsMismatchGaugeInTheJarsApiNamespace() {
        template.hasResourceProperties(
                "AWS::CloudWatch::Alarm",
                like(
                        "AlarmName", "jars-dev-ledger-reconciliation-mismatches",
                        "Namespace", "Jars/Api",
                        "MetricName", "jars.ledger.reconciliation.mismatches",
                        "Statistic", "Maximum",
                        "Period", 300,
                        "ComparisonOperator", "GreaterThanThreshold",
                        "Threshold", 0,
                        "EvaluationPeriods", 1,
                        "DatapointsToAlarm", 1,
                        // the task is replaced or destroyed between runs: no data must not keep the alarm in ALARM
                        "TreatMissingData", "notBreaching"));
        Map<String, Object> alarm = properties(template, "AWS::CloudWatch::Alarm").stream()
                .filter(a -> "jars-dev-ledger-reconciliation-mismatches".equals(a.get("AlarmName")))
                .findFirst()
                .orElseThrow();
        // EMF publishes the untagged gauge without dimensions, and the alarm must name exactly the same set
        assertThat(alarm).doesNotContainKey("Dimensions");
        // routed to the existing alarm topic of JarsFoundation
        assertThat(alarm.get("AlarmActions").toString()).contains("JarsFoundation:ExportsOutputRef");
    }

    @Test
    void ledgerReconciliationMetricPassesTheCollectorsJarsFilterAndTheAlarmsMetricNameIsTheApplicationsOne() {
        String config = collectorConfig();
        // the collector drops every metric whose name does not match ^jars.
        assertThat(config).contains("not IsMatch(name, \"^jars\\\\.\")");
        assertThat(ApiObservability.RECONCILIATION_MISMATCHES_METRIC).matches("^jars\\..*");
        assertThat(ApiObservability.RECONCILIATION_MISMATCHES_METRIC).isEqualTo("jars.ledger.reconciliation.mismatches");
    }

    @Test
    void oneDashboardNamedAfterTheEnvironment() {
        template.resourceCountIs("AWS::CloudWatch::Dashboard", 1);
        template.hasResourceProperties("AWS::CloudWatch::Dashboard", like("DashboardName", "jars-dev"));
    }

    private String collectorConfig() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> environment =
                (List<Map<String, Object>>) container("otel-collector").get("Environment");
        Object value = environment.stream()
                .filter(variable -> "AOT_CONFIG_CONTENT".equals(variable.get("Name")))
                .findFirst()
                .orElseThrow()
                .get("Value");
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> container(String name) {
        List<Map<String, Object>> containers = (List<Map<String, Object>>)
                properties(template, "AWS::ECS::TaskDefinition").get(0).get("ContainerDefinitions");
        return containers.stream()
                .filter(c -> name.equals(c.get("Name")))
                .findFirst()
                .orElseThrow();
    }
}
