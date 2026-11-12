package io.github.thphuc06.jars.infra;

import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import io.github.cdklabs.cdknag.NagPackProps;
import io.github.thphuc06.jars.infra.config.EnvConfig;
import software.amazon.awscdk.Annotations;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.Validations;

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

        Tags.of(app).add("Project", EnvConfig.PROJECT);
        Tags.of(app).add("Env", cfg.env());
        Tags.of(app).add("ManagedBy", "cdk");
        Tags.of(app).add("Owner", EnvConfig.OWNER);

        enableNag(app);

        return new JarsStacks(foundation, network, auth, data, messaging, application);
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
