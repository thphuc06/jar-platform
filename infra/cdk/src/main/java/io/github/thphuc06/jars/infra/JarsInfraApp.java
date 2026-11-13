package io.github.thphuc06.jars.infra;

import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import io.github.cdklabs.cdknag.NagPackProps;
import io.github.thphuc06.jars.infra.config.EnvConfig;
import java.util.List;
import software.amazon.awscdk.Annotations;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.services.ec2.SubnetSelection;

/**
 * Entry point of the Jars Platform CDK app.
 *
 * <p>{@link #build(App)} wires every stack and is shared with the tests, so the tests synthesize
 * exactly what {@code cdk synth} synthesizes.
 */
public final class JarsInfraApp {

    private JarsInfraApp() {}

    public static void main(String[] args) {
        App app = new App();
        build(app);
        app.synth();
    }

    public static JarsStacks build(App app) {
        EnvConfig cfg = EnvConfig.from(app.getNode());
        StackProps props = StackProps.builder()
                .env(Environment.builder()
                        .account(cfg.account())
                        .region(cfg.region())
                        .build())
                .build();

        FoundationStack foundation = new FoundationStack(app, "JarsFoundation", props, cfg);
        NetworkStack network = new NetworkStack(app, "JarsNetwork", props, cfg);
        AuthStack auth = new AuthStack(app, "JarsAuth", props, cfg);
        // JarsAuth attaches the smoke test's Cognito permissions to the deploy role of JarsFoundation
        auth.addDependency(foundation);
        DataStack data = new DataStack(app, "JarsData", props, cfg, network);
        MessagingStack messaging = new MessagingStack(app, "JarsMessaging", props, cfg, foundation);
        AppStack application = null;
        if (cfg.imageTag().isBlank()) {
            // A synth error would stop every command, including `cdk deploy JarsAuth`. Leaving the stack out
            // makes `cdk deploy JarsApp` without a tag fail with "no stacks match", and the warning says why.
            Annotations.of(app)
                    .addWarningV2(
                            "jars:no-image-tag",
                            "Context 'imageTag' is empty, so JarsApp is not part of this synth. Pass"
                                    + " -c imageTag=<git sha of the jars-api image in ECR> to include it.");
        } else {
            application = new AppStack(app, "JarsApp", props, cfg, foundation, network, auth, data, messaging);
        }

        keepExportsForJarsApp(foundation, network, auth, data, messaging);

        Tags.of(app).add("Project", EnvConfig.PROJECT);
        Tags.of(app).add("Env", cfg.env());
        Tags.of(app).add("ManagedBy", "cdk");
        Tags.of(app).add("Owner", EnvConfig.OWNER);

        enableNag(app);

        return new JarsStacks(foundation, network, auth, data, messaging, application);
    }

    /**
     * Exports every value JarsApp imports, whether JarsApp is part of this synth or not. CDK creates an
     * export only while its consumer is in the app; without an imageTag JarsApp is left out, so deploying a
     * long-lived stack would drop the exports, and the next {@code cdk deploy JarsApp --exclusively} (the
     * deploy-dev workflow) would fail with "No export named ...". {@code exportValue} uses the same export
     * names as the automatic references. StableExportsTest fails when this list misses one.
     */
    private static void keepExportsForJarsApp(
            FoundationStack foundation,
            NetworkStack network,
            AuthStack auth,
            DataStack data,
            MessagingStack messaging) {
        foundation.exportValue(foundation.apiRepository().getRepositoryArn());
        foundation.exportValue(foundation.apiRepository().getRepositoryName());
        foundation.exportValue(foundation.alarmTopic().getTopicArn());

        network.exportValue(network.vpc().getVpcId());
        for (String group :
                List.of(NetworkStack.SUBNET_GROUP_PUBLIC, NetworkStack.SUBNET_GROUP_LB, NetworkStack.SUBNET_GROUP_DATA)) {
            network.vpc()
                    .selectSubnets(SubnetSelection.builder().subnetGroupName(group).build())
                    .getSubnetIds()
                    .forEach(network::exportValue);
        }
        network.exportValue(network.albSecurityGroup().getSecurityGroupId());
        network.exportValue(network.appSecurityGroup().getSecurityGroupId());
        network.exportValue(network.dbSecurityGroup().getSecurityGroupId());

        auth.exportValue(auth.userPool().getUserPoolId());
        auth.exportValue(auth.smokeClient().getUserPoolClientId());

        data.exportValue(data.endpointHostname());
        data.exportValue(data.secret().getSecretArn());

        messaging.exportValue(messaging.jobsQueue().getQueueArn());
    }

    /**
     * Registers the cdk-nag AwsSolutions pack as a CDK validation plugin. It runs on every synth: an
     * unacknowledged finding fails {@code cdk synth} and {@code cdk deploy}. Findings are acknowledged
     * next to the construct they concern with {@code Validations.of(construct).acknowledge(...)}.
     */
    public static void enableNag(App app) {
        Validations.of(app)
                .addPlugins(new AwsSolutionsChecks(
                        app, NagPackProps.builder().verbose(true).build()));
    }

    /** The stacks of the app, for tests and for readers of {@link #build(App)}. {@code app} is null without an imageTag. */
    public record JarsStacks(
            FoundationStack foundation,
            NetworkStack network,
            AuthStack auth,
            DataStack data,
            MessagingStack messaging,
            AppStack app) {}
}
