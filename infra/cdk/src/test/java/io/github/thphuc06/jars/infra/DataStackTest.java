package io.github.thphuc06.jars.infra;

import static io.github.thphuc06.jars.infra.T.like;
import static io.github.thphuc06.jars.infra.T.map;
import static io.github.thphuc06.jars.infra.T.resources;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class DataStackTest {

    private final Template aurora = InfraFixture.aurora().data();

    @Test
    void clusterIsAuroraPostgres17ThatScalesToZeroAndPausesAfterFiveMinutes() {
        aurora.hasResourceProperties(
                "AWS::RDS::DBCluster",
                like(
                        "Engine", "aurora-postgresql",
                        "EngineVersion", "17.11",
                        "DBClusterIdentifier", "jars-dev-db",
                        "DatabaseName", "jars",
                        "ServerlessV2ScalingConfiguration",
                                map("MinCapacity", 0, "MaxCapacity", 2, "SecondsUntilAutoPause", 300)));
    }

    @Test
    void clusterHasExactlyOneServerlessWriterAndNoReader() {
        aurora.resourceCountIs("AWS::RDS::DBInstance", 1);
        aurora.hasResourceProperties(
                "AWS::RDS::DBInstance",
                like(
                        "DBInstanceClass", "db.serverless",
                        "Engine", "aurora-postgresql",
                        "PubliclyAccessible", false));
    }

    @Test
    void clusterIsEncryptedBackedUpForOneDayAndSnapshottedOnDelete() {
        aurora.hasResourceProperties(
                "AWS::RDS::DBCluster",
                like("StorageEncrypted", true, "BackupRetentionPeriod", 1, "DeletionProtection", false));
        aurora.hasResource(
                "AWS::RDS::DBCluster", like("DeletionPolicy", "Snapshot", "UpdateReplacePolicy", "Snapshot"));
    }

    @Test
    void clusterParameterGroupForcesTls() {
        aurora.hasResourceProperties(
                "AWS::RDS::DBClusterParameterGroup",
                like("Family", "aurora-postgresql17", "Parameters", map("rds.force_ssl", "1")));
    }

    @Test
    void clusterLivesInTheDataSubnetsBehindSgDb() {
        aurora.hasResourceProperties(
                "AWS::RDS::DBSubnetGroup",
                like("SubnetIds", Match.anyValue()));
        List<Object> subnetIds = subnetIdsOfTheDbSubnetGroup();
        // two subnets (one per AZ), both imported from the network stack
        assertThat(subnetIds).hasSize(2);
        aurora.hasResourceProperties("AWS::RDS::DBCluster", like("VpcSecurityGroupIds", Match.anyValue()));
    }

    @SuppressWarnings("unchecked")
    private List<Object> subnetIdsOfTheDbSubnetGroup() {
        return (List<Object>) ((java.util.Map<String, Object>)
                        resources(aurora, "AWS::RDS::DBSubnetGroup").values().iterator().next().get("Properties"))
                .get("SubnetIds");
    }

    @Test
    void credentialsAreGeneratedInSecretsManagerWithoutRotation() {
        aurora.hasResourceProperties(
                "AWS::SecretsManager::Secret",
                like(
                        "GenerateSecretString",
                        like("SecretStringTemplate", "{\"username\":\"jarsadmin\"}", "GenerateStringKey", "password")));
        aurora.resourceCountIs("AWS::SecretsManager::RotationSchedule", 0);
        aurora.resourceCountIs("AWS::SecretsManager::SecretTargetAttachment", 1);
    }

    @Test
    void outputsExposeTheIdentifierAndTheSecret() {
        aurora.hasOutput("DbClusterIdentifier", like("Value", "jars-dev-db"));
        aurora.hasOutput("DbEndpoint", map());
        aurora.hasOutput("DbSecretArn", map());
    }

    @Test
    void rdsModeBuildsAFixedPriceInstanceInsteadOfACluster() {
        Template rds = Template.fromStack(InfraFixture.rds().stacks().data());
        rds.resourceCountIs("AWS::RDS::DBCluster", 0);
        rds.hasResourceProperties(
                "AWS::RDS::DBInstance",
                like(
                        "Engine", "postgres",
                        "EngineVersion", "17.11",
                        "DBInstanceClass", "db.t4g.micro",
                        "DBInstanceIdentifier", "jars-dev-db",
                        "DBName", "jars",
                        "MultiAZ", false,
                        "StorageEncrypted", true,
                        "BackupRetentionPeriod", 1,
                        "DeletionProtection", false,
                        "PubliclyAccessible", false));
        rds.hasResourceProperties(
                "AWS::RDS::DBParameterGroup",
                like("Family", "postgres17", "Parameters", map("rds.force_ssl", "1")));
        rds.hasResource("AWS::RDS::DBInstance", like("DeletionPolicy", "Snapshot"));
        // same outputs as Aurora so that JarsApp and the smoke test need no branching
        rds.hasOutput("DbClusterIdentifier", like("Value", "jars-dev-db"));
    }
}
