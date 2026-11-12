package io.github.thphuc06.jars.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thphuc06.jars.infra.JarsInfraApp.JarsStacks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awscdk.App;
import software.amazon.awscdk.AppProps;
import software.amazon.awscdk.assertions.Template;

/**
 * Builds the real app (same wiring as {@code cdk synth}, including cdk-nag) once per variant and
 * shares it between the tests: synthesizing starts a jsii process and takes seconds.
 *
 * <p>The context is the one of cdk.json (feature flags included, so the tested templates have the
 * same shape as the deployed ones) plus the values that cdk.json deliberately leaves empty.
 */
final class InfraFixture {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Synthesized aurora;
    private static Synthesized rds;
    private static Synthesized withWeb;

    private InfraFixture() {}

    /** Default configuration: Aurora, one task, no web upload. */
    static synchronized Synthesized aurora() {
        if (aurora == null) {
            aurora = synth(Map.of());
        }
        return aurora;
    }

    /** {@code -c dbMode=rds}: the RDS PostgreSQL fallback of ADR-0008. */
    static synchronized Synthesized rds() {
        if (rds == null) {
            rds = synth(Map.of("dbMode", "rds"));
        }
        return rds;
    }

    /** {@code -c deployWeb=true}: JarsApp uploads the built SPA. */
    static synchronized Synthesized withWeb(Path webDist) {
        if (withWeb == null) {
            withWeb = synth(Map.of("deployWeb", true, "webDistDir", webDist.toAbsolutePath().toString()));
        }
        return withWeb;
    }

    /** Builds and synthesizes an app. A cdk-nag finding or a validation error fails with the report. */
    static Synthesized synth(Map<String, Object> overrides) {
        Map<String, Object> context = new HashMap<>(cdkJsonContext());
        context.put("imageTag", "test-tag");
        // a syntactically valid address on a reserved domain: never a real mailbox
        context.put("alertEmail", "alerts@example.test");
        context.putAll(overrides);

        App app = new App(AppProps.builder().context(context).build());
        JarsStacks stacks = JarsInfraApp.build(app);
        try {
            app.synth();
        } catch (RuntimeException e) {
            throw new AssertionError(
                    "Synthesis failed: " + e.getMessage() + System.lineSeparator() + validationReport(app), e);
        }
        return new Synthesized(app, stacks);
    }

    static String validationReport(App app) {
        Path report = Path.of(app.getOutdir(), "validation-report.json");
        try {
            return Files.exists(report)
                    ? Files.readString(report)
                    : "(no validation report in " + app.getOutdir() + ")";
        } catch (IOException e) {
            return "(could not read " + report + ": " + e.getMessage() + ")";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cdkJsonContext() {
        try {
            Map<String, Object> cdkJson = JSON.readValue(Path.of("cdk.json").toFile(), Map.class);
            return (Map<String, Object>) cdkJson.get("context");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read cdk.json from the module directory", e);
        }
    }

    /** A synthesized app and its stacks. */
    record Synthesized(App app, JarsStacks stacks) {

        Template foundation() {
            return Template.fromStack(stacks.foundation());
        }

        Template network() {
            return Template.fromStack(stacks.network());
        }
    }
}
