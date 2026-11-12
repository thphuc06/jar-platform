package io.github.thphuc06.jars.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import software.amazon.awscdk.Acknowledgment;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.services.s3.Bucket;

/** Guards the guard: proves that cdk-nag really stops a synth when a rule is violated. */
class NagWiringTest {

    @Test
    void synthFailsWhenAConstructViolatesAwsSolutionsRules() {
        App app = new App();
        JarsInfraApp.enableNag(app);
        Stack stack = new Stack(app, "Bad");
        // no access logs, no SSL enforcement: AwsSolutions-S1 and AwsSolutions-S10
        Bucket.Builder.create(stack, "Bucket").build();

        assertThatThrownBy(app::synth).hasMessageContaining("Validation failed");
        // the report names the rules, so a failing guard test tells which control was violated
        assertThat(InfraFixture.validationReport(app)).contains("AwsSolutions-S10");
    }

    @Test
    void anAcknowledgedFindingDoesNotFailTheSynth() {
        App app = new App();
        JarsInfraApp.enableNag(app);
        Stack stack = new Stack(app, "Acknowledged");
        Bucket bucket = Bucket.Builder.create(stack, "Bucket").build();
        Validations.of(bucket)
                .acknowledge(
                        Acknowledgment.builder().id("AwsSolutions-S1").reason("test").build(),
                        Acknowledgment.builder().id("AwsSolutions-S10").reason("test").build());

        app.synth();
    }
}
