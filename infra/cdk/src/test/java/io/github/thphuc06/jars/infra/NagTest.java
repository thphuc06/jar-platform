package io.github.thphuc06.jars.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cdk-nag AwsSolutions pack over the whole app. Any finding that is not acknowledged with a reason
 * next to its construct fails the synth, and with it these tests (see {@link InfraFixture#synth}).
 *
 * <p>All three variants of the app are checked, because each adds resources: the default one, the RDS
 * fallback ({@code -c dbMode=rds}) and the one that uploads the web build ({@code -c deployWeb=true}).
 */
class NagTest {

    @Test
    void defaultAppHasNoUnacknowledgedFindings() {
        assertThat(InfraFixture.aurora().app()).isNotNull();
    }

    @Test
    void rdsFallbackHasNoUnacknowledgedFindings() {
        assertThat(InfraFixture.rds().app()).isNotNull();
    }

    @Test
    void appWithWebDeploymentHasNoUnacknowledgedFindings(@TempDir Path webDist) throws IOException {
        Files.writeString(webDist.resolve("index.html"), "<!doctype html><title>jars</title>");
        assertThat(InfraFixture.withWeb(webDist).app()).isNotNull();
    }
}
