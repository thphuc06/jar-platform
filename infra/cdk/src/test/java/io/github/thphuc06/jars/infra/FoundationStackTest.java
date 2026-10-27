package io.github.thphuc06.jars.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class FoundationStackTest {

    static final EnvConfig CONFIG = TestConfig.CONFIG;

    private static FoundationStack newStack(App app, EnvConfig config) {
        return new FoundationStack(
                app,
                "JarsFoundation",
                StackProps.builder().env(config.awsEnvironment()).build(),
                config);
    }

    private static Template template() {
        return Template.fromStack(newStack(new App(), CONFIG));
    }

    @Test
    void ecr_hasImmutableTagsScanOnPushAndKeepsTenImages() {
        Template template = template();

        template.hasResource(
                "AWS::ECR::Repository",
                Map.of(
                        "DeletionPolicy",
                        "Retain",
                        "Properties",
                        Map.of(
                                "RepositoryName",
                                "jars/jars-api",
                                "ImageTagMutability",
                                "IMMUTABLE",
                                "ImageScanningConfiguration",
                                Map.of("ScanOnPush", true),
                                "LifecyclePolicy",
                                Map.of("LifecyclePolicyText", Match.stringLikeRegexp(".*\"countNumber\":10.*")))));
    }

    @Test
    void githubRole_trustsOnlyThisRepositorysDevEnvironment_exactly() {
        template()
                .hasResourceProperties(
                        "AWS::IAM::Role",
                        Map.of(
                                "RoleName",
                                "jars-test-github-deploy",
                                "MaxSessionDuration",
                                3600,
                                "AssumeRolePolicyDocument",
                                Map.of(
                                        "Statement",
                                        List.of(Map.of(
                                                "Action",
                                                "sts:AssumeRoleWithWebIdentity",
                                                "Effect",
                                                "Allow",
                                                "Principal",
                                                Map.of("Federated", Match.anyValue()),
                                                // exact: no extra StringLike (e.g. "repo:*") may widen it
                                                "Condition",
                                                Match.exact(Map.of(
                                                        "StringEquals",
                                                        Map.of(
                                                                "token.actions.githubusercontent.com:aud",
                                                                "sts.amazonaws.com",
                                                                "token.actions.githubusercontent.com:sub",
                                                                "repo:thphuc06/jar-platform:environment:test"))))))));
    }

    @Test
    void githubRole_canOnlyAssumeCdkRolesAndPushTheApiImage() {
        Map<String, Map<String, Object>> policies = template().findResources("AWS::IAM::Policy");
        assertThat(policies).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> statements = (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>)
                                policies.values().iterator().next().get("Properties"))
                        .get("PolicyDocument"))
                .get("Statement");
        Set<String> sids = new TreeSet<>();
        statements.forEach(s -> sids.add((String) s.get("Sid")));

        assertThat(sids).containsExactly("AssumeCdkBootstrapRoles", "EcrLogin", "PushJarsApiImage");
        assertThat(template().findResources("AWS::IAM::ManagedPolicy")).isEmpty();
        assertThat(statements.toString()).doesNotContain("\"*:*\"", "iam:*", "AdministratorAccess");
    }

    @Test
    void githubOidcProvider_isCreated_unlessTheAccountAlreadyHasOne() {
        Template created = template();
        created.resourceCountIs("AWS::IAM::OIDCProvider", 1);
        created.hasResourceProperties(
                "AWS::IAM::OIDCProvider",
                Map.of("Url", FoundationStack.GITHUB_OIDC_URL, "ClientIdList", List.of("sts.amazonaws.com")));

        String existing = "arn:aws:iam::111111111111:oidc-provider/token.actions.githubusercontent.com";
        EnvConfig withExisting = new EnvConfig(
                "test",
                "111111111111",
                "ap-southeast-1",
                "jars-test-prefix",
                "http://localhost:5173",
                "thphuc06/jar-platform",
                Optional.of(existing),
                30);
        Template imported = Template.fromStack(newStack(new App(), withExisting));
        imported.resourceCountIs("AWS::IAM::OIDCProvider", 0); // one provider per URL per account
        assertThat(imported.findResources("AWS::IAM::Role").toString()).contains(existing);
    }

    @Test
    void budget_isMonthlyForTheProjectTag_andAlertsBeforeAndAtTheLimit() {
        Template template = template();

        template.hasResourceProperties(
                "AWS::Budgets::Budget",
                Map.of(
                        "Budget",
                        Map.of(
                                "BudgetName", "jars-test-monthly",
                                "BudgetType", "COST",
                                "TimeUnit", "MONTHLY",
                                "BudgetLimit", Map.of("Amount", 30, "Unit", "USD"),
                                "CostFilters", Map.of("TagKeyValue", List.of("user:Project$jars"))),
                        "NotificationsWithSubscribers",
                        Match.arrayWith(List.of(
                                Match.objectLike(Map.of(
                                        "Notification",
                                        Map.of(
                                                "NotificationType", "ACTUAL",
                                                "Threshold", 80,
                                                "ComparisonOperator", "GREATER_THAN",
                                                "ThresholdType", "PERCENTAGE"))),
                                Match.objectLike(Map.of(
                                        "Notification",
                                        Match.objectLike(
                                                Map.of("NotificationType", "FORECASTED", "Threshold", 100))))))));
    }

    @Test
    void alarmTopic_acceptsBudgetsOnlyFromThisAccount_andRequiresTls() {
        String policy = template().findResources("AWS::SNS::TopicPolicy").toString();

        assertThat(policy)
                .contains("budgets.amazonaws.com")
                .contains("aws:SourceAccount")
                .contains("aws:SecureTransport"); // enforceSsl: deny publishing over plain HTTP
    }

    @Test
    void cdkNag_passesWithOnlyTheDocumentedAcknowledgements() {
        App app = new App();
        newStack(app, CONFIG);
        Validations.of(app).addPlugins(new AwsSolutionsChecks(app));

        assertThatCode(app::synth).doesNotThrowAnyException();
    }
}
