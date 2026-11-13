package io.github.thphuc06.jars.infra;

import io.github.thphuc06.jars.infra.config.EnvConfig;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Acknowledgment;
import software.amazon.awscdk.Annotations;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.DefaultStackSynthesizer;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.services.budgets.CfnBudget;
import software.amazon.awscdk.services.ecr.LifecycleRule;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecr.TagMutability;
import software.amazon.awscdk.services.ecr.TagStatus;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.OidcProviderNative;
import software.amazon.awscdk.services.iam.Policy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.WebIdentityPrincipal;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
import software.constructs.Construct;

/**
 * JarsFoundation: things that are created once and kept (docs/02-architecture/aws-infrastructure.md
 * section 1): ECR repository, alarm topic, project budget and the GitHub OIDC deploy role.
 */
public final class FoundationStack extends Stack {

    /**
     * The only GitHub OIDC subject allowed to assume the deploy role. No wildcards (ADR-0016). The repository uses
     * GitHub's immutable subject format, owner and repository with their numeric ids, so a repository re-created later
     * under the same name cannot assume the role; the name-only format was rejected on the first workflow run.
     */
    public static final String GITHUB_SUBJECT = "repo:thphuc06@185325027/jar-platform@1431052736:ref:refs/heads/main";

    private static final String GITHUB_ISSUER = "token.actions.githubusercontent.com";

    private final Repository apiRepository;
    private final Topic alarmTopic;
    private final Role githubDeployRole;

    public FoundationStack(Construct scope, String id, StackProps props, EnvConfig cfg) {
        super(scope, id, props);

        this.apiRepository = Repository.Builder.create(this, "JarsApiRepository")
                .repositoryName("jars/jars-api")
                .imageTagMutability(TagMutability.IMMUTABLE)
                .imageScanOnPush(true)
                // the repository outlives the stack, like the data it protects: images are paid per GB
                // but a stack delete must never drop the rollback window
                .removalPolicy(RemovalPolicy.RETAIN)
                .lifecycleRules(List.of(LifecycleRule.builder()
                        .rulePriority(1)
                        .description("Keep the 10 most recent images (rollback window); storage is paid per GB")
                        .tagStatus(TagStatus.ANY)
                        .maxImageCount(10)
                        .build()))
                .build();

        this.alarmTopic = Topic.Builder.create(this, "Alarms")
                .topicName(cfg.name("alarms"))
                .displayName("Jars alarms")
                .enforceSsl(true)
                .build();
        if (cfg.alertEmail().isBlank()) {
            Annotations.of(this)
                    .addWarningV2(
                            "jars:no-alert-email",
                            "context 'alertEmail' is empty: alarms and budget alerts reach an SNS topic nobody reads."
                                    + " Pass -c alertEmail=<address> or set JARS_ALERT_EMAIL.");
        } else {
            this.alarmTopic.addSubscription(new EmailSubscription(cfg.alertEmail()));
        }
        // AWS Budgets and CloudWatch alarms publish as service principals; the account condition
        // blocks the confused-deputy case
        this.alarmTopic.addToResourcePolicy(PolicyStatement.Builder.create()
                .sid("AllowBudgetsFromThisAccount")
                .effect(Effect.ALLOW)
                .principals(List.of(new ServicePrincipal("budgets.amazonaws.com")))
                .actions(List.of("sns:Publish"))
                .resources(List.of(alarmTopic.getTopicArn()))
                .conditions(Map.of("StringEquals", Map.of("aws:SourceAccount", getAccount())))
                .build());
        this.alarmTopic.addToResourcePolicy(PolicyStatement.Builder.create()
                .sid("AllowCloudWatchAlarmsFromThisAccount")
                .effect(Effect.ALLOW)
                .principals(List.of(new ServicePrincipal("cloudwatch.amazonaws.com")))
                .actions(List.of("sns:Publish"))
                .resources(List.of(alarmTopic.getTopicArn()))
                .conditions(Map.of("StringEquals", Map.of("aws:SourceAccount", getAccount())))
                .build());

        addBudget(cfg);
        this.githubDeployRole = addGitHubDeployRole(cfg);

        CfnOutput.Builder.create(this, "EcrRepositoryUri")
                .description("docker push target for jars-api images")
                .value(apiRepository.getRepositoryUri())
                .build();
        CfnOutput.Builder.create(this, "AlarmTopicArn")
                .description("SNS topic that receives budget and CloudWatch alarms")
                .value(alarmTopic.getTopicArn())
                .build();
        CfnOutput.Builder.create(this, "GitHubDeployRoleArn")
                .description("role-to-assume for aws-actions/configure-aws-credentials")
                .value(githubDeployRole.getRoleArn())
                .build();

        Validations.of(alarmTopic)
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions-SNS2")
                        .reason("Server-side encryption needs a customer managed KMS key (about 1 USD a month):"
                                + " the AWS managed key alias/aws/sns cannot be used by CloudWatch alarms and AWS"
                                + " Budgets. The topic carries alarm text only, no customer data."
                                + " TLS is enforced instead (enforceSSL).")
                        .build());
    }

    private void addBudget(EnvConfig cfg) {
        // Budgets filters on cost allocation tags as "user:<key>$<value>". The tag must be activated
        // once in Billing (docs/05-operations/deployment.md section 1, step 4).
        List<CfnBudget.NotificationWithSubscribersProperty> notifications = List.of(
                notification("ACTUAL", 50),
                notification("ACTUAL", 80),
                notification("ACTUAL", 100),
                notification("FORECASTED", 100));
        CfnBudget.Builder.create(this, "MonthlyBudget")
                .budget(CfnBudget.BudgetDataProperty.builder()
                        .budgetName(cfg.name("monthly"))
                        .budgetType("COST")
                        .timeUnit("MONTHLY")
                        .budgetLimit(CfnBudget.SpendProperty.builder()
                                .amount(30)
                                .unit("USD")
                                .build())
                        .costFilters(Map.of("TagKeyValue", List.of("user:Project$" + EnvConfig.PROJECT)))
                        .build())
                .notificationsWithSubscribers(notifications)
                .build();
    }

    private CfnBudget.NotificationWithSubscribersProperty notification(String type, int thresholdPercent) {
        return CfnBudget.NotificationWithSubscribersProperty.builder()
                .notification(CfnBudget.NotificationProperty.builder()
                        .notificationType(type)
                        .comparisonOperator("GREATER_THAN")
                        .thresholdType("PERCENTAGE")
                        .threshold(thresholdPercent)
                        .build())
                .subscribers(List.of(CfnBudget.SubscriberProperty.builder()
                        .subscriptionType("SNS")
                        .address(alarmTopic.getTopicArn())
                        .build()))
                .build();
    }

    private Role addGitHubDeployRole(EnvConfig cfg) {
        OidcProviderNative provider = OidcProviderNative.Builder.create(this, "GitHubOidc")
                .url("https://" + GITHUB_ISSUER)
                .clientIds(List.of("sts.amazonaws.com"))
                .build();

        Role role = Role.Builder.create(this, "GitHubDeployRole")
                .roleName(cfg.name("github-deploy"))
                .description("GitHub Actions (OIDC) deploys Jars by assuming the CDK bootstrap roles")
                .maxSessionDuration(Duration.hours(1))
                .assumedBy(new WebIdentityPrincipal(
                        provider.getOidcProviderArn(),
                        Map.of(
                                "StringEquals",
                                Map.of(
                                        GITHUB_ISSUER + ":aud", "sts.amazonaws.com",
                                        GITHUB_ISSUER + ":sub", GITHUB_SUBJECT))))
                .build();

        // the only permission: assume the four CDK bootstrap roles (security.md section 3).
        // Everything else (CloudFormation, ECR push for image assets, S3) happens inside those roles.
        String qualifier = DefaultStackSynthesizer.DEFAULT_QUALIFIER;
        List<String> bootstrapRoleArns = List.of("deploy-role", "file-publishing-role", "image-publishing-role", "lookup-role")
                .stream()
                .map(kind -> "arn:" + getPartition() + ":iam::" + getAccount() + ":role/cdk-" + qualifier + "-" + kind
                        + "-" + getAccount() + "-" + getRegion())
                .toList();
        role.addToPolicy(PolicyStatement.Builder.create()
                .sid("AssumeCdkBootstrapRoles")
                .effect(Effect.ALLOW)
                .actions(List.of("sts:AssumeRole"))
                .resources(bootstrapRoleArns)
                .build());

        // What deploy-dev.yml does outside CloudFormation, kept apart from the policy above so that the
        // two concerns can be read (and tested) separately: push the image it just built, read the
        // outputs of the Jars stacks, and let the smoke test check that the ALB is internal.
        // Cognito permissions for the smoke test users live in JarsAuth, next to the user pool ARN.
        Policy runtime = Policy.Builder.create(this, "GitHubDeployRuntimePolicy")
                .statements(List.of(
                        PolicyStatement.Builder.create()
                                .sid("EcrLogin")
                                .effect(Effect.ALLOW)
                                .actions(List.of("ecr:GetAuthorizationToken"))
                                .resources(List.of("*"))
                                .build(),
                        PolicyStatement.Builder.create()
                                .sid("PushApiImage")
                                .effect(Effect.ALLOW)
                                .actions(List.of(
                                        "ecr:BatchCheckLayerAvailability",
                                        "ecr:InitiateLayerUpload",
                                        "ecr:UploadLayerPart",
                                        "ecr:CompleteLayerUpload",
                                        "ecr:PutImage",
                                        "ecr:BatchGetImage",
                                        "ecr:DescribeImages"))
                                .resources(List.of(apiRepository.getRepositoryArn()))
                                .build(),
                        PolicyStatement.Builder.create()
                                .sid("ReadJarsStackOutputs")
                                .effect(Effect.ALLOW)
                                .actions(List.of("cloudformation:DescribeStacks"))
                                .resources(List.of(jarsStacksArn()))
                                .build(),
                        PolicyStatement.Builder.create()
                                .sid("SmokeTestAlbScheme")
                                .effect(Effect.ALLOW)
                                .actions(List.of("elasticloadbalancing:DescribeLoadBalancers"))
                                .resources(List.of("*"))
                                .build()))
                .roles(List.of(role))
                .build();
        Validations.of(runtime)
                .acknowledge(
                        Acknowledgment.builder()
                                .id("AwsSolutions-IAM5[Resource::*]")
                                .reason("ecr:GetAuthorizationToken and elasticloadbalancing:DescribeLoadBalancers have no"
                                        + " resource-level permissions in IAM; both are read or login only.")
                                .build(),
                        Acknowledgment.builder()
                                .id("AwsSolutions-IAM5[Resource::" + jarsStacksArn() + "]")
                                .reason("DescribeStacks on the stacks of this project (names start with Jars) to read"
                                        + " their outputs; the stack id suffix is random, so it cannot be listed.")
                                .build());
        return role;
    }

    /** All CloudFormation stacks of the project in this account and region (JarsFoundation, JarsApp, ...). */
    private String jarsStacksArn() {
        return "arn:" + getPartition() + ":cloudformation:" + getRegion() + ":" + getAccount() + ":stack/Jars*/*";
    }

    public Repository apiRepository() {
        return apiRepository;
    }

    public Topic alarmTopic() {
        return alarmTopic;
    }

    public Role githubDeployRole() {
        return githubDeployRole;
    }
}
