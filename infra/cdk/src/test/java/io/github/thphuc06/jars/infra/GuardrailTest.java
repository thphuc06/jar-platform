package io.github.thphuc06.jars.infra;

import static io.github.thphuc06.jars.infra.T.map;
import static io.github.thphuc06.jars.infra.T.properties;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thphuc06.jars.infra.InfraFixture.Synthesized;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.assertions.Template;

/**
 * The guardrails that protect the budget and the security posture (docs/05-operations/cost-and-budget.md,
 * docs/02-architecture/security.md). Every test runs over all stacks of the app, for the default Aurora
 * configuration and for the RDS fallback, so that a new stack cannot slip past them.
 */
class GuardrailTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Synthesized variant(String name) {
        return "rds".equals(name) ? InfraFixture.rds() : InfraFixture.aurora();
    }

    private static List<Template> templates(String variant) {
        Synthesized synthesized = variant(variant);
        List<Template> all = new ArrayList<>();
        for (Stack stack : List.of(
                synthesized.stacks().foundation(),
                synthesized.stacks().network(),
                synthesized.stacks().auth(),
                synthesized.stacks().data(),
                synthesized.stacks().messaging(),
                synthesized.stacks().app())) {
            all.add(Template.fromStack(stack));
        }
        return all;
    }

    private static List<Map<String, Object>> allProperties(String variant, String type) {
        return templates(variant).stream()
                .flatMap(template -> properties(template, type).stream())
                .collect(Collectors.toList());
    }

    // ---- cost ----

    @ParameterizedTest
    @ValueSource(strings = {"aurora", "rds"})
    void noStackCreatesANatGateway(String variant) {
        for (Template template : templates(variant)) {
            template.resourceCountIs("AWS::EC2::NatGateway", 0);
            // an Elastic IP without NAT would only bill for an idle IPv4 address
            template.resourceCountIs("AWS::EC2::EIP", 0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aurora", "rds"})
    void everyTaskDefinitionRunsOnArm64(String variant) {
        List<Map<String, Object>> taskDefinitions = allProperties(variant, "AWS::ECS::TaskDefinition");
        assertThat(taskDefinitions).isNotEmpty();
        assertThat(taskDefinitions).allSatisfy(taskDefinition -> assertThat(taskDefinition)
                .extractingByKey("RuntimePlatform")
                .isEqualTo(map("CpuArchitecture", "ARM64", "OperatingSystemFamily", "LINUX")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aurora", "rds"})
    void everyLogGroupKeepsLogsForAtMostFourteenDays(String variant) {
        List<Map<String, Object>> logGroups = allProperties(variant, "AWS::Logs::LogGroup");
        assertThat(logGroups).isNotEmpty();
        assertThat(logGroups).allSatisfy(logGroup -> {
            // a log group without RetentionInDays keeps everything forever
            assertThat(logGroup).containsKey("RetentionInDays");
            assertThat(((Number) logGroup.get("RetentionInDays")).intValue()).isLessThanOrEqualTo(14);
        });
    }

    @Test
    void auroraScalesToZeroAndPausesAfterFiveMinutes() {
        List<Map<String, Object>> clusters = allProperties("aurora", "AWS::RDS::DBCluster");
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).get("ServerlessV2ScalingConfiguration"))
                .isEqualTo(map("MinCapacity", 0, "MaxCapacity", 2, "SecondsUntilAutoPause", 300));
        // the fallback has no cluster at all
        assertThat(allProperties("rds", "AWS::RDS::DBCluster")).isEmpty();
    }

    // ---- security ----

    @ParameterizedTest
    @ValueSource(strings = {"aurora", "rds"})
    void noSecurityGroupAcceptsTrafficFromTheWholeInternet(String variant) {
        List<Map<String, Object>> groups = allProperties(variant, "AWS::EC2::SecurityGroup");
        assertThat(groups).isNotEmpty();
        List<Map<String, Object>> rules = new ArrayList<>(allProperties(variant, "AWS::EC2::SecurityGroupIngress"));
        for (Map<String, Object> group : groups) {
            Object inline = group.get("SecurityGroupIngress");
            if (inline != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> inlineRules = (List<Map<String, Object>>) inline;
                rules.addAll(inlineRules);
            }
        }
        assertThat(rules).isNotEmpty();
        assertThat(rules).allSatisfy(rule -> {
            assertThat(rule.get("CidrIp")).isNotIn("0.0.0.0/0");
            assertThat(rule.get("CidrIpv6")).isNotIn("::/0");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"aurora", "rds"})
    void ecrRepositoriesHaveImmutableTagsAndScanOnPush(String variant) {
        List<Map<String, Object>> repositories = allProperties(variant, "AWS::ECR::Repository");
        assertThat(repositories).isNotEmpty();
        assertThat(repositories).allSatisfy(repository -> {
            assertThat(repository).containsEntry("ImageTagMutability", "IMMUTABLE");
            assertThat(repository).containsEntry("ImageScanningConfiguration", map("ScanOnPush", true));
        });
    }

    @Test
    void githubDeployRoleTrustHasNoWildcardSubject() throws Exception {
        Template foundation = InfraFixture.aurora().foundation();
        int checkedStatements = 0;
        for (Map<String, Object> role : properties(foundation, "AWS::IAM::Role")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> statements = (List<Map<String, Object>>)
                    ((Map<String, Object>) role.get("AssumeRolePolicyDocument")).get("Statement");
            for (Map<String, Object> statement : statements) {
                if (!"sts:AssumeRoleWithWebIdentity".equals(statement.get("Action"))) {
                    continue;
                }
                checkedStatements++;
                @SuppressWarnings("unchecked")
                Map<String, Object> condition = (Map<String, Object>) statement.get("Condition");
                // exact match only: StringLike would allow wildcards
                assertThat(condition).containsOnlyKeys("StringEquals");
                String conditions = JSON.writeValueAsString(condition);
                assertThat(conditions).doesNotContain("*").doesNotContain("?");
                @SuppressWarnings("unchecked")
                Map<String, Object> equals = (Map<String, Object>) condition.get("StringEquals");
                assertThat(equals)
                        .containsEntry(
                                "token.actions.githubusercontent.com:sub", "repo:thphuc06/jar-platform:ref:refs/heads/main")
                        .containsEntry("token.actions.githubusercontent.com:aud", "sts.amazonaws.com");
            }
        }
        assertThat(checkedStatements).as("web identity trust statements").isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aurora", "rds"})
    void everyS3BucketBlocksPublicAccess(String variant) {
        List<Map<String, Object>> buckets = allProperties(variant, "AWS::S3::Bucket");
        assertThat(buckets).isNotEmpty();
        assertThat(buckets).allSatisfy(bucket -> assertThat(bucket)
                .containsEntry(
                        "PublicAccessBlockConfiguration",
                        map(
                                "BlockPublicAcls", true,
                                "BlockPublicPolicy", true,
                                "IgnorePublicAcls", true,
                                "RestrictPublicBuckets", true)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aurora", "rds"})
    void everyLoadBalancerIsInternal(String variant) {
        List<Map<String, Object>> loadBalancers = allProperties(variant, "AWS::ElasticLoadBalancingV2::LoadBalancer");
        assertThat(loadBalancers).isNotEmpty();
        assertThat(loadBalancers).allSatisfy(loadBalancer -> assertThat(loadBalancer).containsEntry("Scheme", "internal"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aurora", "rds"})
    void theDatabaseIsNeverPubliclyAccessibleAndAlwaysEncrypted(String variant) {
        List<Map<String, Object>> instances = allProperties(variant, "AWS::RDS::DBInstance");
        assertThat(instances).isNotEmpty();
        assertThat(instances).allSatisfy(instance -> assertThat(instance).containsEntry("PubliclyAccessible", false));
        List<Map<String, Object>> storage = new ArrayList<>(allProperties(variant, "AWS::RDS::DBCluster"));
        storage.addAll(allProperties(variant, "AWS::RDS::DBInstance").stream()
                .filter(instance -> instance.containsKey("StorageEncrypted"))
                .collect(Collectors.toList()));
        assertThat(storage).isNotEmpty();
        assertThat(storage).allSatisfy(db -> assertThat(db).containsEntry("StorageEncrypted", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aurora", "rds"})
    void noCognitoAppClientHasASecretBecauseBothAreUsedFromPublicClients(String variant) {
        List<Map<String, Object>> clients = allProperties(variant, "AWS::Cognito::UserPoolClient");
        assertThat(clients).hasSize(2);
        assertThat(clients).allSatisfy(client -> assertThat(client).containsEntry("GenerateSecret", false));
    }

    @Test
    void allStacksCarryTheProjectTagsOnTheirTaggableResources() {
        // the budget filters on Project=jars: an untagged resource would be invisible to it
        for (Template template : templates("aurora")) {
            for (String type : List.of(
                    "AWS::ECR::Repository",
                    "AWS::SNS::Topic",
                    "AWS::EC2::VPC",
                    "AWS::Cognito::UserPool",
                    "AWS::RDS::DBCluster",
                    "AWS::SQS::Queue",
                    "AWS::ECS::Service",
                    "AWS::ElasticLoadBalancingV2::LoadBalancer",
                    "AWS::CloudFront::Distribution",
                    "AWS::S3::Bucket")) {
                for (Map<String, Object> resource : properties(template, type)) {
                    assertThat(resource.get("Tags") != null || resource.get("UserPoolTags") != null)
                            .as("%s has tags", type)
                            .isTrue();
                    assertThat(String.valueOf(resource.getOrDefault("Tags", resource.get("UserPoolTags"))))
                            .contains("Project")
                            .contains("jars");
                }
            }
        }
    }
}
