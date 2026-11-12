package io.github.thphuc06.jars.infra;

import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import io.github.cdklabs.cdknag.NagPackProps;
import io.github.thphuc06.jars.infra.config.EnvConfig;
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

        Tags.of(app).add("Project", EnvConfig.PROJECT);
        Tags.of(app).add("Env", cfg.env());
        Tags.of(app).add("ManagedBy", "cdk");
        Tags.of(app).add("Owner", EnvConfig.OWNER);

        enableNag(app);

        return new JarsStacks(foundation, network);
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

    /** The stacks of the app, for tests and for readers of {@link #build(App)}. */
    public record JarsStacks(FoundationStack foundation, NetworkStack network) {}
}
