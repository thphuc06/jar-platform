package io.github.thphuc06.jars.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.infra.JarsInfraApp.JarsStacks;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.assertions.Template;

/**
 * JarsApp reads values of the long-lived stacks through CloudFormation exports. Without an imageTag JarsApp
 * is not part of the synth, and CDK would drop every export that only JarsApp consumes. Deploying a
 * long-lived stack that way, then deploying JarsApp alone (deploy-dev.yml uses --exclusively), fails with
 * "No export named ...". So each stack keeps the exports JarsApp needs with {@code exportValue}, whether
 * JarsApp is in the synth or not.
 */
class StableExportsTest {

    @Test
    void longLivedStacksExportTheSameValuesWithAndWithoutJarsApp() {
        Set<String> withApp = exports(InfraFixture.aurora().stacks());
        Set<String> withoutApp = exports(InfraFixture.synth(Map.of("imageTag", "")).stacks());

        assertThat(withApp).as("JarsApp consumes exports").isNotEmpty();
        assertThat(withoutApp).containsExactlyInAnyOrderElementsOf(withApp);
    }

    private static Set<String> exports(JarsStacks stacks) {
        Set<String> names = new TreeSet<>();
        Stream.<Stack>of(stacks.foundation(), stacks.network(), stacks.auth(), stacks.data(), stacks.messaging())
                .forEach(stack -> Template.fromStack(stack).findOutputs("*").values().forEach(output -> {
                    Object export = ((Map<?, ?>) output).get("Export");
                    if (export instanceof Map<?, ?> e) {
                        names.add(String.valueOf(e.get("Name")));
                    }
                }));
        return names;
    }
}
