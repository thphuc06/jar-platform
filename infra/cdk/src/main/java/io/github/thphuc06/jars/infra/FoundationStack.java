package io.github.thphuc06.jars.infra;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Acknowledgment;
import software.amazon.awscdk.Aws;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.services.budgets.CfnBudget;
import software.amazon.awscdk.services.budgets.CfnBudgetProps;
import software.amazon.awscdk.services.ecr.LifecycleRule;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecr.TagMutability;
import software.amazon.awscdk.services.iam.IOidcProvider;
import software.amazon.awscdk.services.iam.OidcProviderNative;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.WebIdentityPrincipal;
import software.amazon.awscdk.services.sns.Topic;
import software.constructs.Construct;

/**
 * Long-lived account-level pieces of the project (aws-infrastructure.md §1): the image registry, the identity
 * GitHub Actions deploys with, where alarms go, and the cost budget. Nothing here costs money by the hour.
 */
public class FoundationStack extends Stack {

    static final String GITHUB_OIDC_URL = "https://token.actions.githubusercontent.com";

    /** The CDK bootstrap roles (default qualifier hnb659fds) of the deploying account and region. */
    static final String BOOTSTRAP_ROLES =
            "arn:aws:iam::" + Aws.ACCOUNT_ID + ":role/cdk-hnb659fds-*-" + Aws.ACCOUNT_ID + "-" + Aws.REGION;

    private final Repository apiRepository;
    private final Topic alarms;
    private final Role deployRole;

    public FoundationStack(Construct scope, String id, StackProps props, EnvConfig config) {
        super(scope, id, props);

        apiRepository = Repository.Builder.create(this, "JarsApiRepository")
                .repositoryName("jars/jars-api")
                // a tag is the git SHA and never moves: what was tested is what runs, and rollback is exact
                .imageTagMutability(TagMutability.IMMUTABLE)
                .imageScanOnPush(true)
                .lifecycleRules(List.of(LifecycleRule.builder()
                        .description("Keep the 10 most recent images (rollback window); storage is paid per GB")
                        .maxImageCount(10)
                        .build()))
                // images are rebuilt from git, but deleting the stack must not break a running service
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        alarms = Topic.Builder.create(this, "Alarms")
                .topicName(config.name("alarms"))
                .displayName("Jars alarms")
                .enforceSsl(true)
                .build();
        // AWS Budgets publishes to the topic, but only for this account's budgets (confused-deputy protection)
        alarms.addToResourcePolicy(PolicyStatement.Builder.create()
                .sid("AllowBudgetsFromThisAccount")
                .principals(List.of(new ServicePrincipal("budgets.amazonaws.com")))
                .actions(List.of("sns:Publish"))
                .resources(List.of(alarms.getTopicArn()))
                .conditions(Map.of("StringEquals", Map.of("aws:SourceAccount", getAccount())))
                .build());

        new CfnBudget(this, "MonthlyBudget", budget(config, alarms.getTopicArn()));

        deployRole = githubDeployRole(config);

        output("ApiRepositoryUri", apiRepository.getRepositoryUri(), "docker push target for jars-api images");
        output("AlarmTopicArn", alarms.getTopicArn(), "subscribe an e-mail once: aws sns subscribe ...");
        output(
                "GitHubDeployRoleArn",
                deployRole.getRoleArn(),
                "role-to-assume for aws-actions/configure-aws-credentials");
    }

    /**
     * Monthly cost budget for resources tagged Project=jars (the tag must be activated as a cost allocation tag,
     * otherwise the budget sees $0). Alerts at 50/80/100 % of actual spend and 100 % of the forecast.
     */
    private static CfnBudgetProps budget(EnvConfig config, String topicArn) {
        return CfnBudgetProps.builder()
                .budget(CfnBudget.BudgetDataProperty.builder()
                        .budgetName(config.name("monthly"))
                        .budgetType("COST")
                        .timeUnit("MONTHLY")
                        .budgetLimit(CfnBudget.SpendProperty.builder()
                                .amount(config.monthlyBudgetUsd())
                                .unit("USD")
                                .build())
                        // "user:" = a user-defined cost allocation tag; "$" separates key and value
                        .costFilters(Map.of("TagKeyValue", List.of("user:Project$jars")))
                        .build())
                .notificationsWithSubscribers(List.of(
                        alert("ACTUAL", 50, topicArn),
                        alert("ACTUAL", 80, topicArn),
                        alert("ACTUAL", 100, topicArn),
                        alert("FORECASTED", 100, topicArn)))
                .build();
    }

    private static CfnBudget.NotificationWithSubscribersProperty alert(String type, double percent, String topicArn) {
        return CfnBudget.NotificationWithSubscribersProperty.builder()
                .notification(CfnBudget.NotificationProperty.builder()
                        .notificationType(type)
                        .comparisonOperator("GREATER_THAN")
                        .threshold(percent)
                        .thresholdType("PERCENTAGE")
                        .build())
                .subscribers(List.of(CfnBudget.SubscriberProperty.builder()
                        .subscriptionType("SNS")
                        .address(topicArn)
                        .build()))
                .build();
    }

    /**
     * The role GitHub Actions assumes with OIDC (no access key anywhere). Trusted only for jobs of this repository
     * that run in the GitHub Environment "dev": a job with {@code environment: dev} gets the subject
     * {@code repo:OWNER/REPO:environment:dev}, NOT {@code ...:ref:refs/heads/main}.
     */
    private Role githubDeployRole(EnvConfig config) {
        IOidcProvider github = config.githubOidcProviderArn()
                .<IOidcProvider>map(arn -> OidcProviderNative.fromOidcProviderArn(this, "GitHubOidc", arn))
                .orElseGet(() -> OidcProviderNative.Builder.create(this, "GitHubOidc")
                        .url(GITHUB_OIDC_URL)
                        .clientIds(List.of("sts.amazonaws.com"))
                        .build());

        Role role = Role.Builder.create(this, "GitHubDeployRole")
                .roleName(config.name("github-deploy"))
                .description("GitHub Actions (OIDC) deploys Jars: push images, run cdk deploy")
                .maxSessionDuration(Duration.hours(1))
                .assumedBy(new WebIdentityPrincipal(
                        github.getOidcProviderArn(),
                        Map.of(
                                "StringEquals",
                                Map.of(
                                        "token.actions.githubusercontent.com:aud",
                                        "sts.amazonaws.com",
                                        "token.actions.githubusercontent.com:sub",
                                        "repo:" + config.githubRepo() + ":environment:" + config.envName()))))
                .build();

        // cdk deploy works by assuming the bootstrap roles; this role itself can change nothing else
        role.addToPolicy(PolicyStatement.Builder.create()
                .sid("AssumeCdkBootstrapRoles")
                .actions(List.of("sts:AssumeRole"))
                // pseudo parameters, not getAccount(): the template (and the cdk-nag finding acknowledged below)
                // stays the same whether or not the CLI knows the account at synth time
                .resources(List.of(BOOTSTRAP_ROLES))
                .build());
        // docker push of the jars-api image (built outside CDK, with the AOT-cache training run)
        role.addToPolicy(PolicyStatement.Builder.create()
                .sid("EcrLogin")
                .actions(List.of("ecr:GetAuthorizationToken"))
                .resources(List.of("*")) // this action has no resource-level permissions
                .build());
        role.addToPolicy(PolicyStatement.Builder.create()
                .sid("PushJarsApiImage")
                .actions(List.of(
                        "ecr:BatchCheckLayerAvailability",
                        "ecr:InitiateLayerUpload",
                        "ecr:UploadLayerPart",
                        "ecr:CompleteLayerUpload",
                        "ecr:PutImage",
                        "ecr:BatchGetImage"))
                .resources(List.of(apiRepository.getRepositoryArn()))
                .build());

        // only the findings cdk-nag reports for this role, each with a reason a reviewer can challenge
        Validations.of(role)
                .acknowledge(
                        Acknowledgment.builder()
                                .id("AwsSolutions::AwsSolutions-IAM5[Resource::arn:aws:iam::<AWS::AccountId>"
                                        + ":role/cdk-hnb659fds-*-<AWS::AccountId>-<AWS::Region>]")
                                .reason("The four CDK bootstrap roles (deploy, file-publishing, image-publishing,"
                                        + " lookup) of this account and region; the qualifier pins them to the"
                                        + " default bootstrap stack. cdk deploy needs to assume them.")
                                .build(),
                        Acknowledgment.builder()
                                .id("AwsSolutions::AwsSolutions-IAM5[Resource::*]")
                                .reason("ecr:GetAuthorizationToken has no resource-level permissions (it returns a"
                                        + " registry login token); the push actions are limited to jars/jars-api.")
                                .build());
        return role;
    }

    public Repository apiRepository() {
        return apiRepository;
    }

    public Topic alarms() {
        return alarms;
    }

    private void output(String id, String value, String description) {
        CfnOutput.Builder.create(this, id).value(value).description(description).build();
    }
}
