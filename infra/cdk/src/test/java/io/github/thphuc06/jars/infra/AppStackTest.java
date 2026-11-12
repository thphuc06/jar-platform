package io.github.thphuc06.jars.infra;

import static io.github.thphuc06.jars.infra.T.like;
import static io.github.thphuc06.jars.infra.T.map;
import static io.github.thphuc06.jars.infra.T.properties;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class AppStackTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Template template = InfraFixture.aurora().app();

    // ---- compute: task definition ----

    @Test
    void taskDefinitionIsArm64LinuxWithHalfAVcpuAndOneGb() {
        template.hasResourceProperties(
                "AWS::ECS::TaskDefinition",
                like(
                        "Family", "jars-dev-api",
                        "Cpu", "512",
                        "Memory", "1024",
                        "NetworkMode", "awsvpc",
                        "RequiresCompatibilities", List.of("FARGATE"),
                        "RuntimePlatform", map("CpuArchitecture", "ARM64", "OperatingSystemFamily", "LINUX")));
    }

    @Test
    void containerFollowsTheLifecycleSettingsOfTheDesign() {
        template.hasResourceProperties(
                "AWS::ECS::TaskDefinition",
                like(
                        "ContainerDefinitions",
                        List.of(like(
                                "Name", "api",
                                "Essential", true,
                                "PortMappings", List.of(like("ContainerPort", 8080, "Protocol", "tcp")),
                                "ReadonlyRootFilesystem", true,
                                "StopTimeout", 30,
                                "LinuxParameters", like("InitProcessEnabled", true),
                                "MountPoints",
                                        List.of(like("ContainerPath", "/tmp", "SourceVolume", "tmp", "ReadOnly", false)))),
                        "Volumes", List.of(map("Name", "tmp"))));
    }

    @Test
    void containerImageComesFromTheFoundationRepositoryWithTheContextTag() throws Exception {
        String image = JSON.writeValueAsString(container().get("Image"));
        assertThat(image).contains("JarsFoundation:ExportsOutput").contains("\":test-tag\"");
    }

    @Test
    void containerLogsToTheJarsLogGroupThroughAwslogs() {
        template.hasResourceProperties(
                "AWS::ECS::TaskDefinition",
                like(
                        "ContainerDefinitions",
                        List.of(like(
                                "LogConfiguration",
                                like(
                                        "LogDriver", "awslogs",
                                        "Options", like("awslogs-stream-prefix", "api", "awslogs-region", "ap-southeast-1"))))));
        template.hasResourceProperties(
                "AWS::Logs::LogGroup", like("LogGroupName", "/jars/dev/api", "RetentionInDays", 14));
    }

    @Test
    void containerEnvironmentIsExactlyTheContractWithTheApplication() throws Exception {
        Map<String, String> environment = environment();
        assertThat(environment.keySet())
                .containsExactlyInAnyOrder(
                        "SPRING_PROFILES_ACTIVE",
                        "SPRING_DATASOURCE_URL",
                        "JARS_ISSUER_URI",
                        "JARS_ALLOWED_CLIENT_IDS",
                        "JARS_JOBS_QUEUE",
                        "JAVA_TOOL_OPTIONS");
        assertThat(environment).containsEntry("SPRING_PROFILES_ACTIVE", "aws");
        assertThat(environment).containsEntry("JARS_JOBS_QUEUE", "jars-jobs");
        assertThat(environment).containsEntry("JAVA_TOOL_OPTIONS", "-XX:MaxRAMPercentage=75");
        // token-bearing values are serialized with their Fn::Join parts, enough to check the shape
        assertThat(environment.get("SPRING_DATASOURCE_URL"))
                .contains("jdbc:postgresql://")
                .contains("JarsData:ExportsOutputFnGetAttCluster")
                .contains(":5432/jars?sslmode=require");
        assertThat(environment.get("JARS_ISSUER_URI"))
                .contains("https://cognito-idp.ap-southeast-1.amazonaws.com/")
                .contains("JarsAuth:ExportsOutputRefUserPool");
        // both clients are allowed to call the API: the SPA client of this stack and the smoke client
        assertThat(environment.get("JARS_ALLOWED_CLIENT_IDS"))
                .contains("SpaClient")
                .contains(",")
                .contains("JarsAuth:ExportsOutputRefUserPoolSmokeClient");
    }

    @Test
    void databaseCredentialsComeFromSecretsManagerAndNeverFromTheEnvironment() throws Exception {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secrets = (List<Map<String, Object>>) container().get("Secrets");
        assertThat(secrets.stream().map(s -> s.get("Name")).collect(Collectors.toList()))
                .containsExactlyInAnyOrder("SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD");
        for (Map<String, Object> secret : secrets) {
            String valueFrom = JSON.writeValueAsString(secret.get("ValueFrom"));
            assertThat(valueFrom).contains("JarsData:ExportsOutputRefClusterSecretAttachment");
            assertThat(valueFrom).endsWith(secret.get("Name").toString().endsWith("USERNAME") ? ":username::\"]]}" : ":password::\"]]}");
        }
        assertThat(environment().keySet()).noneMatch(name -> name.contains("PASSWORD") || name.contains("USERNAME"));
    }

    // ---- compute: service ----

    @Test
    void serviceUsesTheLifecycleSettingsOfAwsInfrastructureSection4() {
        template.hasResourceProperties(
                "AWS::ECS::Service",
                like(
                        "ServiceName", "jars-api",
                        "LaunchType", "FARGATE",
                        "DesiredCount", 1,
                        "HealthCheckGracePeriodSeconds", 90,
                        "DeploymentConfiguration",
                                like(
                                        "MinimumHealthyPercent", 100,
                                        "MaximumPercent", 200,
                                        "DeploymentCircuitBreaker", map("Enable", true, "Rollback", true)),
                        "LoadBalancers", List.of(like("ContainerName", "api", "ContainerPort", 8080))));
    }

    @Test
    void serviceRunsInThePublicSubnetsWithAPublicIpBehindSgApp() throws Exception {
        template.hasResourceProperties(
                "AWS::ECS::Service",
                like("NetworkConfiguration", like("AwsvpcConfiguration", like("AssignPublicIp", "ENABLED"))));
        String networkConfiguration = JSON.writeValueAsString(
                properties(template, "AWS::ECS::Service").get(0).get("NetworkConfiguration"));
        assertThat(networkConfiguration)
                .contains("VpcpublicSubnet1")
                .contains("VpcpublicSubnet2")
                .contains("AppSecurityGroup")
                .doesNotContain("lbSubnet")
                .doesNotContain("dataSubnet");
    }

    @Test
    void serviceWaitsForTheExecutionRolePolicy() {
        Map<String, Map<String, Object>> services = template.findResources("AWS::ECS::Service");
        assertThat(services).hasSize(1);
        Object dependsOn = services.values().iterator().next().get("DependsOn");
        assertThat(dependsOn.toString()).contains("ApiExecutionRoleDefaultPolicy");
    }

    @Test
    void tasksInheritTheProjectTagsSoTheBudgetSeesTheirCost() {
        template.hasResourceProperties(
                "AWS::ECS::Service", like("PropagateTags", "SERVICE", "EnableECSManagedTags", true));
    }

    @Test
    void desiredCountComesFromTheContextAndAcceptsTheCliStringForm() {
        // -c apiDesiredCount=0 arrives as the string "0"
        InfraFixture.Synthesized sleeping = InfraFixture.synth(Map.of("apiDesiredCount", "0"));
        sleeping.app().hasResourceProperties("AWS::ECS::Service", like("DesiredCount", 0));
    }

    // ---- load balancer ----

    @Test
    void albIsInternalAndLivesInTheLbSubnetsBehindSgAlb() throws Exception {
        template.hasResourceProperties(
                "AWS::ElasticLoadBalancingV2::LoadBalancer",
                like("Scheme", "internal", "Type", "application", "Name", "jars-dev-api-alb"));
        String alb = JSON.writeValueAsString(
                properties(template, "AWS::ElasticLoadBalancingV2::LoadBalancer").get(0));
        assertThat(alb)
                .contains("VpclbSubnet1")
                .contains("VpclbSubnet2")
                .contains("AlbSecurityGroup")
                .doesNotContain("publicSubnet");
    }

    @Test
    void listenerAcceptsHttpOnPort80AndForwardsToThePort8080TargetGroup() {
        template.hasResourceProperties(
                "AWS::ElasticLoadBalancingV2::Listener",
                like("Port", 80, "Protocol", "HTTP", "DefaultActions", List.of(like("Type", "forward"))));
        template.resourceCountIs("AWS::ElasticLoadBalancingV2::Listener", 1);
    }

    @Test
    void targetGroupChecksReadinessEvery15SecondsAndDrainsIn30() {
        template.hasResourceProperties(
                "AWS::ElasticLoadBalancingV2::TargetGroup",
                like(
                        "Port", 8080,
                        "Protocol", "HTTP",
                        "TargetType", "ip",
                        "HealthCheckPath", "/actuator/health/readiness",
                        "HealthCheckIntervalSeconds", 15,
                        "HealthyThresholdCount", 2,
                        "TargetGroupAttributes",
                                Match.arrayWith(List.of(map("Key", "deregistration_delay.timeout_seconds", "Value", "30")))));
    }

    @Test
    void thisStackAddsNoSecurityGroupRules() {
        // all rules belong to JarsNetwork, the groups are imported as immutable references
        template.resourceCountIs("AWS::EC2::SecurityGroup", 0);
        template.resourceCountIs("AWS::EC2::SecurityGroupIngress", 0);
        template.resourceCountIs("AWS::EC2::SecurityGroupEgress", 0);
    }

    // ---- IAM ----

    @Test
    void taskRoleMayOnlyConsumeTheJobsQueueAndNothingElse() {
        template.hasResourceProperties("AWS::IAM::Role", like("RoleName", "jars-dev-api-task"));
        List<Map<String, Object>> statements = statementsOfPolicyFor("ApiTaskRoleDefaultPolicy");
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0))
                .containsEntry("Effect", "Allow")
                .containsEntry(
                        "Action",
                        List.of(
                                "sqs:ChangeMessageVisibility",
                                "sqs:DeleteMessage",
                                "sqs:GetQueueAttributes",
                                "sqs:GetQueueUrl",
                                "sqs:ReceiveMessage"));
        assertThat(statements.get(0).get("Resource").toString())
                .contains("JarsMessaging:ExportsOutputFnGetAttJobsQueue")
                .doesNotContain("*");
    }

    @Test
    void executionRoleOnlyHasTheWildcardThatEcrAuthorizationTokenRequires() {
        template.hasResourceProperties("AWS::IAM::Role", like("RoleName", "jars-dev-api-exec"));
        List<Map<String, Object>> wildcard = statementsOfPolicyFor("ApiExecutionRoleDefaultPolicy").stream()
                .filter(statement -> "*".equals(statement.get("Resource")))
                .collect(Collectors.toList());
        assertThat(wildcard).hasSize(1);
        assertThat(wildcard.get(0)).containsEntry("Action", "ecr:GetAuthorizationToken");
    }

    // ---- edge ----

    @Test
    @SuppressWarnings("unchecked")
    void oneDistributionRoutesApiToTheVpcOriginAndEverythingElseToS3() throws Exception {
        template.resourceCountIs("AWS::CloudFront::Distribution", 1);
        template.resourceCountIs("AWS::CloudFront::VpcOrigin", 1);
        template.hasResourceProperties(
                "AWS::CloudFront::VpcOrigin",
                like(
                        "VpcOriginEndpointConfig",
                        like("HTTPPort", 80, "OriginProtocolPolicy", "http-only", "Name", "jars-dev-api-alb")));
        Map<String, Object> config = distributionConfig();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apiBehaviors = (List<Map<String, Object>>) config.get("CacheBehaviors");
        assertThat(apiBehaviors).hasSize(1);
        Map<String, Object> api = apiBehaviors.get(0);
        assertThat(api)
                .containsEntry("PathPattern", "/api/*")
                .containsEntry("ViewerProtocolPolicy", "https-only")
                // CachingDisabled and AllViewerExceptHostHeader managed policies
                .containsEntry("CachePolicyId", "4135ea2d-6df8-44a3-9df3-4b5a84be39ad")
                .containsEntry("OriginRequestPolicyId", "b689b0a8-53d0-40ab-baf2-68738e2966ac");
        assertThat((List<Object>) api.get("AllowedMethods"))
                .containsExactlyInAnyOrder("GET", "HEAD", "OPTIONS", "PUT", "PATCH", "POST", "DELETE");
        // default behavior: S3 through origin access control, cached
        @SuppressWarnings("unchecked")
        Map<String, Object> defaults = (Map<String, Object>) config.get("DefaultCacheBehavior");
        assertThat(defaults).containsEntry("CachePolicyId", "658327ea-f89d-4fab-a63d-7e88639e58f6");
        assertThat(JSON.writeValueAsString(config.get("Origins")))
                .contains("OriginAccessControlId")
                .contains("VpcOriginConfig");
        template.resourceCountIs("AWS::CloudFront::OriginAccessControl", 1);
    }

    @Test
    void spaFallbackDoesNotRewriteApiErrorsIntoTheSpaPage() {
        // custom error responses are distribution-wide: a 404 from the API would become index.html with 200
        assertThat(distributionConfig()).doesNotContainKey("CustomErrorResponses");
        // the fallback is a viewer-request function on the default (S3) behavior only
        template.hasResourceProperties(
                "AWS::CloudFront::Function",
                like(
                        "Name", "jars-dev-spa-rewrite",
                        "FunctionConfig", like("Runtime", "cloudfront-js-2.0"),
                        "FunctionCode", Match.stringLikeRegexp(".*request\\.uri = '/index\\.html'.*")));
        @SuppressWarnings("unchecked")
        Map<String, Object> defaults = (Map<String, Object>) distributionConfig().get("DefaultCacheBehavior");
        assertThat(defaults.get("FunctionAssociations").toString()).contains("viewer-request");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apiBehaviors = (List<Map<String, Object>>) distributionConfig().get("CacheBehaviors");
        assertThat(apiBehaviors.get(0)).doesNotContainKey("FunctionAssociations");
    }

    @Test
    void responseHeadersPolicyAddsHstsNosniffAndNoFraming() {
        template.hasResourceProperties(
                "AWS::CloudFront::ResponseHeadersPolicy",
                like(
                        "ResponseHeadersPolicyConfig",
                        like(
                                "SecurityHeadersConfig",
                                like(
                                        "StrictTransportSecurity",
                                        like("AccessControlMaxAgeSec", 31536000, "IncludeSubdomains", true, "Override", true),
                                        "ContentTypeOptions", map("Override", true),
                                        "FrameOptions", map("FrameOption", "DENY", "Override", true),
                                        "ContentSecurityPolicy",
                                                map("ContentSecurityPolicy", "frame-ancestors 'none'", "Override", true)))));
    }

    @Test
    void webBucketIsPrivateEncryptedAndTlsOnly() {
        template.hasResourceProperties(
                "AWS::S3::Bucket",
                like(
                        "PublicAccessBlockConfiguration",
                        map(
                                "BlockPublicAcls", true,
                                "BlockPublicPolicy", true,
                                "IgnorePublicAcls", true,
                                "RestrictPublicBuckets", true),
                        "BucketEncryption",
                        like(
                                "ServerSideEncryptionConfiguration",
                                List.of(like("ServerSideEncryptionByDefault", map("SSEAlgorithm", "AES256"))))));
        template.hasResourceProperties(
                "AWS::S3::BucketPolicy",
                like(
                        "PolicyDocument",
                        like(
                                "Statement",
                                Match.arrayWith(List.of(
                                        like(
                                                "Effect", "Deny",
                                                "Action", "s3:*",
                                                "Condition", map("Bool", map("aws:SecureTransport", "false"))))))));
        // read access for CloudFront only, through the distribution ARN (origin access control)
        template.hasResourceProperties(
                "AWS::S3::BucketPolicy",
                like(
                        "PolicyDocument",
                        like(
                                "Statement",
                                Match.arrayWith(List.of(like(
                                        "Action", "s3:GetObject",
                                        "Principal", map("Service", "cloudfront.amazonaws.com")))))));
    }

    @Test
    void webDeploymentIsOnlyCreatedWhenRequested() throws Exception {
        template.resourceCountIs("Custom::CDKBucketDeployment", 0);

        java.nio.file.Path dist = java.nio.file.Files.createTempDirectory("jars-web-dist");
        java.nio.file.Files.writeString(dist.resolve("index.html"), "<!doctype html>");
        Template withWeb = InfraFixture.withWeb(dist).app();
        withWeb.resourceCountIs("Custom::CDKBucketDeployment", 1);
        withWeb.hasResourceProperties(
                "Custom::CDKBucketDeployment",
                like(
                        "DistributionPaths", List.of("/*"),
                        "Prune", true));
    }

    // ---- Cognito ----

    @Test
    void spaClientIsPublicAuthorizationCodeWithTheDocumentedScopesAndUrls() throws Exception {
        template.hasResourceProperties(
                "AWS::Cognito::UserPoolClient",
                like(
                        "ClientName", "jars-dev-web",
                        "GenerateSecret", false,
                        "AllowedOAuthFlows", List.of("code"),
                        "AllowedOAuthFlowsUserPoolClient", true,
                        "AllowedOAuthScopes", List.of("openid", "email", "profile"),
                        "SupportedIdentityProviders", List.of("COGNITO"),
                        "AccessTokenValidity", 60,
                        "RefreshTokenValidity", 43200));
        Map<String, Object> client = properties(template, "AWS::Cognito::UserPoolClient").get(0);
        String callbacks = JSON.writeValueAsString(client.get("CallbackURLs"));
        assertThat(callbacks)
                .contains("/auth/callback")
                .contains("http://localhost:5173/auth/callback")
                .contains("Distribution");
        String logouts = JSON.writeValueAsString(client.get("LogoutURLs"));
        assertThat(logouts).contains("http://localhost:5173/").contains("Distribution");
        // implicit grant must never be switched on for a public client
        assertThat(client.get("AllowedOAuthFlows").toString()).doesNotContain("implicit");
    }

    @Test
    void spaClientHasManagedLoginBrandingSoTheLoginPageRenders() {
        template.hasResourceProperties("AWS::Cognito::ManagedLoginBranding", like("UseCognitoProvidedValues", true));
    }

    // ---- alarms and outputs ----

    @Test
    void auroraAlarmFiresWhenCapacityStaysAboveZeroForSixConsecutiveHours() {
        template.hasResourceProperties(
                "AWS::CloudWatch::Alarm",
                like(
                        "AlarmName", "jars-dev-aurora-not-paused",
                        "Namespace", "AWS/RDS",
                        "MetricName", "ServerlessDatabaseCapacity",
                        "Dimensions", List.of(map("Name", "DBClusterIdentifier", "Value", "jars-dev-db")),
                        "ComparisonOperator", "GreaterThanThreshold",
                        "Threshold", 0,
                        "Period", 3600,
                        "EvaluationPeriods", 6,
                        "DatapointsToAlarm", 6,
                        "Statistic", "Minimum",
                        "TreatMissingData", "notBreaching"));
        assertThat(properties(template, "AWS::CloudWatch::Alarm").get(0).get("AlarmActions").toString())
                .contains("JarsFoundation:ExportsOutputRef");
    }

    @Test
    void thereIsNoAuroraAlarmInRdsMode() {
        InfraFixture.rds().app().resourceCountIs("AWS::CloudWatch::Alarm", 0);
    }

    @Test
    void outputsGiveTheSmokeTestTheCloudFrontDomainAndTheSpaClient() {
        template.hasOutput("CloudFrontDomain", map());
        template.hasOutput("SpaClientId", map());
        template.hasOutput("DistributionId", map());
        template.hasOutput("EcsClusterName", map());
        template.hasOutput("EcsServiceName", map());
    }

    // ---- without an image tag ----

    @Test
    void withoutAnImageTagJarsAppIsLeftOutAndTheOtherStacksStillSynthesize() {
        InfraFixture.Synthesized withoutTag = InfraFixture.synth(Map.of("imageTag", ""));
        assertThat(withoutTag.stacks().app()).isNull();
        assertThat(withoutTag.stacks().auth()).isNotNull();
        assertThat(InfraFixture.validationReportRaw(withoutTag.cdkApp())).contains("jars:no-image-tag");
    }

    // ---- helpers ----

    private Map<String, Object> distributionConfig() {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) properties(template, "AWS::CloudFront::Distribution")
                .get(0)
                .get("DistributionConfig");
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> container() {
        List<Map<String, Object>> containers = (List<Map<String, Object>>)
                properties(template, "AWS::ECS::TaskDefinition").get(0).get("ContainerDefinitions");
        assertThat(containers).hasSize(1);
        return containers.get(0);
    }

    /** Environment as name to JSON text of the value, so that Fn::Join values can be matched by content. */
    @SuppressWarnings("unchecked")
    private Map<String, String> environment() throws Exception {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (Map<String, Object> variable : (List<Map<String, Object>>) container().get("Environment")) {
            Object value = variable.get("Value");
            result.put(
                    (String) variable.get("Name"),
                    value instanceof String text ? text : JSON.writeValueAsString(value));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> statementsOfPolicyFor(String logicalIdPrefix) {
        return template.findResources("AWS::IAM::Policy").entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(logicalIdPrefix))
                .flatMap(entry -> ((List<Map<String, Object>>) ((Map<String, Object>)
                                        ((Map<String, Object>) entry.getValue().get("Properties")).get("PolicyDocument"))
                                .get("Statement"))
                        .stream())
                .collect(Collectors.toList());
    }
}
