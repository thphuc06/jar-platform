package io.github.thphuc06.jars.infra;

import java.util.Objects;
import software.amazon.awscdk.Environment;
import software.constructs.Node;

/**
 * Deployment settings read from cdk.json context (override with {@code -c key=value}).
 * The AWS account is never hard-coded: it comes from the credentials the CLI runs with (CDK_DEFAULT_ACCOUNT).
 */
public record EnvConfig(String envName, String account, String region, String authDomainPrefix, String localWebOrigin) {

    public static EnvConfig from(Node node, String defaultAccount) {
        return new EnvConfig(
                required(node, "jars:env"),
                defaultAccount,
                required(node, "jars:region"),
                required(node, "jars:authDomainPrefix"),
                required(node, "jars:localWebOrigin"));
    }

    public Environment awsEnvironment() {
        return Environment.builder().account(account).region(region).build();
    }

    /** Resource name convention: jars-{env}-{component}, e.g. jars-dev-users. */
    public String name(String component) {
        return "jars-" + envName + "-" + component;
    }

    private static String required(Node node, String key) {
        Object value = node.tryGetContext(key);
        return Objects.requireNonNull(value, () -> "missing cdk.json context value: " + key)
                .toString();
    }
}
