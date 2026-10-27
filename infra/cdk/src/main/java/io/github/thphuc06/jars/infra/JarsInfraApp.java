package io.github.thphuc06.jars.infra;

import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import io.github.cdklabs.cdknag.NagPackProps;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.Validations;

/** Entry point of the CDK app. Stacks are added phase by phase (see docs/02-architecture/aws-infrastructure.md). */
public final class JarsInfraApp {

    public static void main(String[] args) {
        App app = new App();
        EnvConfig config = EnvConfig.from(app.getNode(), System.getenv("CDK_DEFAULT_ACCOUNT"));

        new FoundationStack(
                app,
                "JarsFoundation",
                StackProps.builder()
                        .env(config.awsEnvironment())
                        .stackName(config.name("foundation"))
                        .description("Jars Platform - ECR, GitHub OIDC deploy role, alarm topic, cost budget")
                        .build(),
                config);
        new NetworkStack(
                app,
                "JarsNetwork",
                StackProps.builder()
                        .env(config.awsEnvironment())
                        .stackName(config.name("network"))
                        .description("Jars Platform - VPC without NAT, security groups")
                        .build(),
                config);
        new AuthStack(
                app,
                "JarsAuth",
                StackProps.builder()
                        .env(config.awsEnvironment())
                        .stackName(config.name("auth"))
                        .description("Jars Platform - Cognito user pool, managed login, API resource server")
                        .build(),
                config);

        applyCommonTags(app, config);
        // cdk-nag v3 runs as a CDK policy-validation plugin: synth fails on AWS Solutions rule violations
        // unless a finding is acknowledged with a reason (Validations.of(construct).acknowledge(...))
        Validations.of(app)
                .addPlugins(new AwsSolutionsChecks(
                        app, NagPackProps.builder().verbose(true).build()));

        app.synth();
    }

    static void applyCommonTags(App app, EnvConfig config) {
        Tags.of(app).add("Project", "jars");
        Tags.of(app).add("Env", config.envName());
        Tags.of(app).add("ManagedBy", "cdk");
        Tags.of(app).add("Owner", "thphuc06");
    }

    private JarsInfraApp() {}
}
