package io.github.thphuc06.jars.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class DataStackTest {

    private static DataStack newStack(App newApp) {
        StackProps props =
                StackProps.builder().env(TestConfig.CONFIG.awsEnvironment()).build();
        FoundationStack foundation = new FoundationStack(newApp, "JarsFoundation", props, TestConfig.CONFIG);
        NetworkStack network = new NetworkStack(newApp, "JarsNetwork", props, TestConfig.CONFIG);
        return new DataStack(newApp, "JarsData", props, TestConfig.CONFIG, network, foundation.alarms());
    }

    private static Template template() {
        return Template.fromStack(newStack(new App()));
    }

    @Test
    void cluster_isAuroraPostgres17_scalingToZero_withACapAndA5MinutePause() {
        Template template = template();

        template.hasResourceProperties(
                "AWS::RDS::DBCluster",
                Map.of(
                        "Engine", "aurora-postgresql",
                        "EngineVersion", "17.9",
                        "DBClusterIdentifier", "jars-test-db",
                        "DatabaseName", "jars",
                        "ServerlessV2ScalingConfiguration",
                                Map.of("MinCapacity", 0, "MaxCapacity", 2, "SecondsUntilAutoPause", 300)));
        template.hasResourceProperties(
                "AWS::RDS::DBInstance", Map.of("DBInstanceClass", "db.serverless", "PubliclyAccessible", false));
    }

    @Test
    void data_isEncrypted_protectedFromDeletion_andLeavesASnapshot() {
        template()
                .hasResource(
                        "AWS::RDS::DBCluster",
                        Map.of(
                                "DeletionPolicy", "Snapshot",
                                "UpdateReplacePolicy", "Snapshot",
                                "Properties",
                                        Map.of(
                                                "StorageEncrypted", true,
                                                "DeletionProtection", true,
                                                "EnableIAMDatabaseAuthentication", true,
                                                "EnableHttpEndpoint", true, // RDS Data API
                                                "BackupRetentionPeriod", 1)));
    }

    @Test
    void cluster_livesInTheIsolatedDataSubnets_behindTheDbSecurityGroup() {
        Template template = template();

        String subnetGroup = template.findResources("AWS::RDS::DBSubnetGroup").toString();
        assertThat(subnetGroup).contains("dataSubnet1").contains("dataSubnet2").doesNotContain("publicSubnet");
        String cluster = template.findResources("AWS::RDS::DBCluster").toString();
        assertThat(cluster).contains("DbSecurityGroup");
    }

    @Test
    void password_isGeneratedInSecretsManager_neverInTheTemplate() {
        Template template = template();

        template.hasResourceProperties(
                "AWS::SecretsManager::Secret",
                Map.of(
                        "Name",
                        "jars-test-db-credentials",
                        "GenerateSecretString",
                        Match.objectLike(Map.of("SecretStringTemplate", "{\"username\":\"jars_admin\"}"))));
        // the cluster gets the password by a dynamic reference resolved by CloudFormation at deploy time
        assertThat(template.findResources("AWS::RDS::DBCluster").toString()).contains("resolve:secretsmanager:");
    }

    @Test
    void neverPausesAlarm_firesAfterSixHoursAboveZero_andNotifiesTheAlarmTopic() {
        template()
                .hasResourceProperties(
                        "AWS::CloudWatch::Alarm",
                        Map.of(
                                "AlarmName", "jars-test-db-never-pauses",
                                "Namespace", "AWS/RDS",
                                "MetricName", "ServerlessDatabaseCapacity",
                                "Statistic", "Minimum",
                                "Period", 3600,
                                "EvaluationPeriods", 6,
                                "Threshold", 0,
                                "ComparisonOperator", "GreaterThanThreshold",
                                "TreatMissingData", "notBreaching",
                                "AlarmActions",
                                        List.of(Match.objectLike(Map.of("Fn::ImportValue", Match.anyValue())))));
    }

    @Test
    void cdkNag_passesWithOnlyTheDocumentedAcknowledgements() {
        App nagged = new App();
        newStack(nagged);
        Validations.of(nagged).addPlugins(new AwsSolutionsChecks(nagged));

        assertThatCode(nagged::synth).doesNotThrowAnyException();
    }
}
