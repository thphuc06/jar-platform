package io.github.thphuc06.jars.infra;

import static io.github.thphuc06.jars.infra.T.like;
import static io.github.thphuc06.jars.infra.T.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class FoundationStackTest {

    private final Template template = InfraFixture.aurora().foundation();

    @Test
    void ecrRepositoryHasImmutableTagsScanOnPushAndKeepsTenImages() {
        template.hasResourceProperties(
                "AWS::ECR::Repository",
                like(
                        "RepositoryName", "jars/jars-api",
                        "ImageTagMutability", "IMMUTABLE",
                        "ImageScanningConfiguration", map("ScanOnPush", true),
                        "LifecyclePolicy",
                                like(
                                        "LifecyclePolicyText",
                                        Match.stringLikeRegexp(".*imageCountMoreThan.*\"countNumber\":10.*"))));
        // images survive a stack delete
        template.hasResource("AWS::ECR::Repository", like("DeletionPolicy", "Retain"));
    }

    @Test
    void alarmTopicIsNamedAndGetsAnEmailSubscription() {
        template.hasResourceProperties("AWS::SNS::Topic", like("TopicName", "jars-dev-alarms"));
        template.hasResourceProperties(
                "AWS::SNS::Subscription", like("Protocol", "email", "Endpoint", "alerts@example.test"));
    }

    @Test
    void budgetIs30UsdFilteredByProjectTagWithFourAlerts() {
        template.hasResourceProperties(
                "AWS::Budgets::Budget",
                like(
                        "Budget",
                        like(
                                "BudgetLimit", map("Amount", 30, "Unit", "USD"),
                                "TimeUnit", "MONTHLY",
                                "CostFilters", map("TagKeyValue", List.of("user:Project$jars"))),
                        "NotificationsWithSubscribers",
                        List.of(
                                notification("ACTUAL", 50),
                                notification("ACTUAL", 80),
                                notification("ACTUAL", 100),
                                notification("FORECASTED", 100))));
        assertThat(template.findResources("AWS::Budgets::Budget")).hasSize(1);
    }

    private static Object notification(String type, int threshold) {
        return like(
                "Notification",
                like(
                        "NotificationType", type,
                        "Threshold", threshold,
                        "ThresholdType", "PERCENTAGE",
                        "ComparisonOperator", "GREATER_THAN"));
    }

    @Test
    void githubDeployRoleTrustsOnlyTheMainBranchOfTheRepository() {
        Object condition = map(
                "StringEquals",
                map(
                        "token.actions.githubusercontent.com:aud", "sts.amazonaws.com",
                        "token.actions.githubusercontent.com:sub", "repo:thphuc06/jar-platform:ref:refs/heads/main"));
        template.hasResourceProperties(
                "AWS::IAM::Role",
                like(
                        "RoleName", "jars-dev-github-deploy",
                        "AssumeRolePolicyDocument",
                                like(
                                        "Statement",
                                        List.of(like(
                                                "Action", "sts:AssumeRoleWithWebIdentity", "Condition", condition)))));
    }

    @Test
    void githubDeployRoleMayOnlyAssumeTheCdkBootstrapRoles() {
        String suffix = "-626212071763-ap-southeast-1";
        List<String> bootstrapRoles = List.of(
                role("deploy-role", suffix),
                role("file-publishing-role", suffix),
                role("image-publishing-role", suffix),
                role("lookup-role", suffix));
        template.hasResourceProperties(
                "AWS::IAM::Policy",
                like(
                        "PolicyDocument",
                        like(
                                "Statement",
                                List.of(like("Action", "sts:AssumeRole", "Effect", "Allow", "Resource", bootstrapRoles)))));
    }

    @Test
    void githubDeployRoleMayPushOnlyTheApiImageAndReadOnlyJarsStacks() {
        template.hasResourceProperties(
                "AWS::IAM::Policy",
                like(
                        "PolicyDocument",
                        like(
                                "Statement",
                                Match.arrayWith(List.of(
                                        like(
                                                "Sid", "PushApiImage",
                                                "Resource", map("Fn::GetAtt", List.of(Match.anyValue(), "Arn"))),
                                        like(
                                                "Sid", "ReadJarsStackOutputs",
                                                "Action", "cloudformation:DescribeStacks",
                                                "Resource",
                                                        "arn:aws:cloudformation:ap-southeast-1:626212071763:stack/Jars*/*")))),
                        "Roles", List.of(map("Ref", Match.stringLikeRegexp("GitHubDeployRole.*")))));
        // no statement grants write access beyond the ECR repository
        assertThat(template.toJSON().toString())
                .doesNotContain("\"ecr:*\"", "\"cloudformation:*\"", "\"iam:*\"", "\"s3:*\"");
    }

    private static String role(String kind, String suffix) {
        return "arn:aws:iam::626212071763:role/cdk-hnb659fds-" + kind + suffix;
    }

    @Test
    void oidcProviderIsTheNativeResourceForGitHub() {
        template.hasResourceProperties(
                "AWS::IAM::OIDCProvider",
                like(
                        "Url", "https://token.actions.githubusercontent.com",
                        "ClientIdList", List.of("sts.amazonaws.com")));
    }

    @Test
    void resourcesCarryTheProjectTags() {
        template.hasResourceProperties(
                "AWS::ECR::Repository",
                like(
                        "Tags",
                        Match.arrayWith(List.of(
                                map("Key", "Env", "Value", "dev"),
                                map("Key", "ManagedBy", "Value", "cdk"),
                                map("Key", "Owner", "Value", "thphuc06"),
                                map("Key", "Project", "Value", "jars")))));
    }
}
