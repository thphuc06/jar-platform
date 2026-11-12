package io.github.thphuc06.jars.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thphuc06.jars.infra.JarsInfraApp.JarsStacks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.function.Supplier;
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

    private static final Memo AURORA = new Memo(() -> synth(Map.of()));
    private static final Memo RDS = new Memo(() -> synth(Map.of("dbMode", "rds")));
    private static final Map<String, Memo> WEB = new HashMap<>();

    private InfraFixture() {}

    /** Default configuration: Aurora, one task, no web upload. */
    static Synthesized aurora() {
        return AURORA.get();
    }

    /** {@code -c dbMode=rds}: the RDS PostgreSQL fallback of ADR-0008. */
    static Synthesized rds() {
        return RDS.get();
    }

    /** {@code -c deployWeb=true}: JarsApp uploads the built SPA. */
    static synchronized Synthesized withWeb(Path webDist) {
        String dir = webDist.toAbsolutePath().toString();
        return WEB.computeIfAbsent(dir, d -> new Memo(() -> synth(Map.of("deployWeb", true, "webDistDir", d))))
                .get();
    }

    /** Runs a synth once and replays its result, including a failure, so a broken app is reported once. */
    private static final class Memo {
        private final Supplier<Synthesized> supplier;
        private Synthesized value;
        private RuntimeException failure;

        Memo(Supplier<Synthesized> supplier) {
            this.supplier = supplier;
        }

        synchronized Synthesized get() {
            if (value == null && failure == null) {
                try {
                    value = supplier.get();
                } catch (RuntimeException | AssertionError e) {
                    failure = e instanceof RuntimeException re ? re : new IllegalStateException(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
            return value;
        }
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

    /**
     * Condensed validation report of an app: one line per unacknowledged finding with the rule id and
     * the construct paths, so a failing test names exactly what to fix or acknowledge.
     */
    static String validationReport(App app) {
        Path report = Path.of(app.getOutdir(), "validation-report.json");
        if (!Files.exists(report)) {
            return "(no validation report in " + app.getOutdir() + ")";
        }
        try {
            StringBuilder summary = new StringBuilder("Unacknowledged findings:").append(System.lineSeparator());
            for (JsonNode pluginReport : JSON.readTree(report.toFile()).path("pluginReports")) {
                for (JsonNode violation : pluginReport.path("violations")) {
                    summary.append("  [")
                            .append(pluginReport.path("pluginName").asText())
                            .append("] ")
                            .append(violation.path("severity").asText())
                            .append(' ')
                            .append(violation.path("ruleName").asText())
                            .append(": ")
                            .append(abbreviate(violation.path("description").asText()))
                            .append(System.lineSeparator());
                    for (JsonNode construct : violation.path("violatingConstructs")) {
                        summary.append("      at ")
                                .append(construct.path("constructPath").asText())
                                .append(System.lineSeparator());
                    }
                }
            }
            return summary.toString();
        } catch (IOException e) {
            return "(could not read " + report + ": " + e.getMessage() + ")";
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 140 ? text : text.substring(0, 140) + "...";
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

        Template auth() {
            return Template.fromStack(stacks.auth());
        }

        Template data() {
            return Template.fromStack(stacks.data());
        }
    }
}
