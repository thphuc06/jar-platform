package io.github.thphuc06.jars.infra;

import io.github.thphuc06.jars.infra.config.EnvConfig;
import io.github.thphuc06.jars.infra.config.EnvConfig.DbMode;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Acknowledgment;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.rds.AuroraPostgresClusterEngineProps;
import software.amazon.awscdk.services.rds.AuroraPostgresEngineFeatures;
import software.amazon.awscdk.services.rds.AuroraPostgresEngineVersion;
import software.amazon.awscdk.services.rds.BackupProps;
import software.amazon.awscdk.services.rds.ClusterInstance;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseCluster;
import software.amazon.awscdk.services.rds.DatabaseClusterEngine;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine;
import software.amazon.awscdk.services.rds.IClusterEngine;
import software.amazon.awscdk.services.rds.IInstanceEngine;
import software.amazon.awscdk.services.rds.ParameterGroup;
import software.amazon.awscdk.services.rds.PostgresEngineVersion;
import software.amazon.awscdk.services.rds.PostgresInstanceEngineProps;
import software.amazon.awscdk.services.rds.ServerlessV2ClusterInstanceProps;
import software.amazon.awscdk.services.rds.StorageType;
import software.amazon.awscdk.services.secretsmanager.ISecret;
import software.constructs.Construct;

/**
 * JarsData: the database (docs/02-architecture/aws-infrastructure.md section 5, ADR-0008).
 *
 * <p>Default is Aurora PostgreSQL Serverless v2 that scales to zero ACU. {@code -c dbMode=rds}
 * builds the fixed-price RDS PostgreSQL fallback instead; both expose the same things to JarsApp
 * (generated secret, writer endpoint, identifier) so the app stack does not care which one runs.
 */
public final class DataStack extends Stack {

    /**
     * Newest 17.x offered in ap-southeast-1 on 2026-11-12 (aws rds describe-db-engine-versions). Scale to
     * zero needs 16.3 or newer.
     */
    static final String ENGINE_VERSION = "17.11";

    static final String ENGINE_MAJOR_VERSION = "17";

    /**
     * The CloudFormation Validate plugin of the CDK (cfn-lint) ships a snapshot of the engine versions
     * and does not list the newest minor yet. The version itself was read from the live API.
     */
    private static final Acknowledgment ENGINE_VERSION_NEWER_THAN_LINT_SNAPSHOT = Acknowledgment.builder()
            .id("CloudFormation-Validate::E9006")
            .reason("Version " + ENGINE_VERSION + " is the newest 17.x that `aws rds describe-db-engine-versions` lists in"
                    + " ap-southeast-1 (checked 2026-11-12). The cfn-lint snapshot bundled with the CDK is older"
                    + " and stops at 17.10.")
            .build();

    /** Findings acknowledged for the generated secret (a child of the cluster or instance construct). */
    private static final Acknowledgment NO_SECRET_ROTATION = Acknowledgment.builder()
            .id("AwsSolutions-SMG4")
            .reason("Automatic rotation needs a Lambda inside the VPC with a route to Secrets Manager (NAT or an"
                    + " interface endpoint), which ADR-0013 deliberately does not pay for."
                    + " The password is rotated by hand, see the runbook section on the DB secret.")
            .build();

    static final String DATABASE_NAME = "jars";

    /** Master user. The app uses it too until the per-service users of Phase 5 exist. */
    static final String DB_USERNAME = "jarsadmin";

    private final DbMode mode;
    private final ISecret secret;
    private final String endpointHostname;
    private final String identifier;

    public DataStack(Construct scope, String id, StackProps props, EnvConfig cfg, NetworkStack network) {
        super(scope, id, props);
        this.mode = cfg.dbMode();
        Vpc vpc = network.vpc();
        // immutable reference: the rules of sg-db are owned by JarsNetwork
        ISecurityGroup dbSecurityGroup = NetworkStack.immutable(this, "DbSecurityGroupRef", network.dbSecurityGroup());

        String identifier = cfg.name("db");
        SubnetSelection dataSubnets =
                SubnetSelection.builder().subnetGroupName(NetworkStack.SUBNET_GROUP_DATA).build();
        // no fixed secret name: a deleted secret blocks its name for the recovery window, which would
        // make delete-and-recreate of this stack fail
        Credentials credentials = Credentials.fromGeneratedSecret(DB_USERNAME);

        if (mode == DbMode.RDS) {
            DatabaseInstance instance = rdsInstance(cfg, identifier, vpc, dataSubnets, dbSecurityGroup, credentials);
            this.secret = instance.getSecret();
            this.endpointHostname = instance.getDbInstanceEndpointAddress();
        } else {
            DatabaseCluster cluster = auroraCluster(cfg, identifier, vpc, dataSubnets, dbSecurityGroup, credentials);
            this.secret = cluster.getSecret();
            this.endpointHostname = cluster.getClusterEndpoint().getHostname();
        }
        this.identifier = identifier;

        CfnOutput.Builder.create(this, "DbClusterIdentifier")
                .description((mode == DbMode.RDS ? "RDS instance" : "Aurora cluster") + " identifier"
                        + " (CloudWatch dimension DBClusterIdentifier for Aurora, DBInstanceIdentifier for RDS)")
                .value(identifier)
                .build();
        CfnOutput.Builder.create(this, "DbEndpoint")
                .description("Writer endpoint host name")
                .value(endpointHostname)
                .build();
        CfnOutput.Builder.create(this, "DbSecretArn")
                .description("Secrets Manager secret with the JSON keys username and password")
                .value(secret.getSecretArn())
                .build();
    }

    private DatabaseCluster auroraCluster(
            EnvConfig cfg,
            String identifier,
            Vpc vpc,
            SubnetSelection dataSubnets,
            ISecurityGroup dbSecurityGroup,
            Credentials credentials) {
        IClusterEngine engine = DatabaseClusterEngine.auroraPostgres(AuroraPostgresClusterEngineProps.builder()
                .version(AuroraPostgresEngineVersion.of(
                        ENGINE_VERSION,
                        ENGINE_MAJOR_VERSION,
                        AuroraPostgresEngineFeatures.builder()
                                .serverlessV2AutoPauseSupported(true)
                                .build()))
                .build());

        ParameterGroup clusterParameters = ParameterGroup.Builder.create(this, "ClusterParameters")
                .engine(engine)
                .description("Jars Aurora PostgreSQL: TLS is mandatory")
                .parameters(Map.of("rds.force_ssl", "1"))
                .build();

        DatabaseCluster cluster = DatabaseCluster.Builder.create(this, "Cluster")
                .clusterIdentifier(identifier)
                .engine(engine)
                .credentials(credentials)
                .defaultDatabaseName(DATABASE_NAME)
                .vpc(vpc)
                .vpcSubnets(dataSubnets)
                .securityGroups(List.of(dbSecurityGroup))
                .parameterGroup(clusterParameters)
                // one writer, no reader (cost); 0 to 2 ACU, paused after 5 idle minutes
                .writer(ClusterInstance.serverlessV2(
                        "Writer",
                        ServerlessV2ClusterInstanceProps.builder()
                                .instanceIdentifier(identifier + "-writer")
                                .publiclyAccessible(false)
                                .build()))
                .serverlessV2MinCapacity(0)
                .serverlessV2MaxCapacity(2)
                .serverlessV2AutoPauseDuration(Duration.seconds(300))
                .storageEncrypted(true)
                .backup(BackupProps.builder().retention(Duration.days(1)).build())
                .copyTagsToSnapshot(true)
                .deletionProtection(false)
                .removalPolicy(RemovalPolicy.SNAPSHOT)
                .build();

        Validations.of(cluster)
                .acknowledge(
                        ENGINE_VERSION_NEWER_THAN_LINT_SNAPSHOT,
                        NO_SECRET_ROTATION,
                        Acknowledgment.builder()
                                .id("AwsSolutions-RDS10")
                                .reason("Deletion protection is off on purpose: the stack is allowed to be destroyed and"
                                        + " RemovalPolicy.SNAPSHOT takes a final snapshot (ADR-0008, runbook)."
                                        + " The data is a demo data set.")
                                .build(),
                        Acknowledgment.builder()
                                .id("AwsSolutions-RDS11")
                                .reason("The default port 5432 is documented in the security groups and in the JDBC URL;"
                                        + " access is already limited to sg-app in subnets without any route to the"
                                        + " internet, so a non-default port adds no real protection.")
                                .build(),
                        Acknowledgment.builder()
                                .id("AwsSolutions-RDS6")
                                .reason("The app connects with the generated Secrets Manager password. IAM database"
                                        + " authentication needs token handling in the app and is the prod path"
                                        + " (well-architected-review.md section 9).")
                                .build());
        return cluster;
    }

    private DatabaseInstance rdsInstance(
            EnvConfig cfg,
            String identifier,
            Vpc vpc,
            SubnetSelection dataSubnets,
            ISecurityGroup dbSecurityGroup,
            Credentials credentials) {
        IInstanceEngine engine = DatabaseInstanceEngine.postgres(PostgresInstanceEngineProps.builder()
                .version(PostgresEngineVersion.of(ENGINE_VERSION, ENGINE_MAJOR_VERSION))
                .build());

        ParameterGroup parameters = ParameterGroup.Builder.create(this, "InstanceParameters")
                .engine(engine)
                .description("Jars RDS PostgreSQL: TLS is mandatory")
                .parameters(Map.of("rds.force_ssl", "1"))
                .build();

        DatabaseInstance instance = DatabaseInstance.Builder.create(this, "Instance")
                .instanceIdentifier(identifier)
                .engine(engine)
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE4_GRAVITON, InstanceSize.MICRO))
                .credentials(credentials)
                .databaseName(DATABASE_NAME)
                .vpc(vpc)
                .vpcSubnets(dataSubnets)
                .securityGroups(List.of(dbSecurityGroup))
                .parameterGroup(parameters)
                .allocatedStorage(20)
                .storageType(StorageType.GP3)
                .storageEncrypted(true)
                .multiAz(false)
                .publiclyAccessible(false)
                .backupRetention(Duration.days(1))
                .copyTagsToSnapshot(true)
                .deletionProtection(false)
                .removalPolicy(RemovalPolicy.SNAPSHOT)
                .build();

        Validations.of(instance)
                .acknowledge(
                        ENGINE_VERSION_NEWER_THAN_LINT_SNAPSHOT,
                        NO_SECRET_ROTATION,
                        Acknowledgment.builder()
                                .id("AwsSolutions-RDS3")
                                .reason("Single-AZ is the documented dev choice (well-architected-review.md section 9)."
                                        + " Multi-AZ doubles the fixed price of the fallback instance.")
                                .build(),
                        Acknowledgment.builder()
                                .id("AwsSolutions-RDS10")
                                .reason("Deletion protection is off on purpose: the stack is allowed to be destroyed and"
                                        + " RemovalPolicy.SNAPSHOT takes a final snapshot (ADR-0008, runbook).")
                                .build(),
                        Acknowledgment.builder()
                                .id("AwsSolutions-RDS11")
                                .reason("The default port 5432 is documented in the security groups and in the JDBC URL;"
                                        + " access is already limited to sg-app in subnets without any route to the"
                                        + " internet.")
                                .build(),
                        Acknowledgment.builder()
                                .id("AwsSolutions-RDS6")
                                .reason("The app connects with the generated Secrets Manager password; IAM database"
                                        + " authentication is the prod path (well-architected-review.md section 9).")
                                .build());
        return instance;
    }

    public DbMode mode() {
        return mode;
    }

    /** Generated secret with the JSON keys {@code username} and {@code password}. */
    public ISecret secret() {
        return secret;
    }

    /** Host name of the writer endpoint, as a CloudFormation token. */
    public String endpointHostname() {
        return endpointHostname;
    }

    /** Cluster identifier (Aurora) or instance identifier (RDS). */
    public String identifier() {
        return identifier;
    }
}
