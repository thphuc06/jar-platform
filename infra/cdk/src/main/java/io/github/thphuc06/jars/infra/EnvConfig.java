package io.github.thphuc06.jars.infra;

import java.util.Objects;
import java.util.Optional;
import software.amazon.awscdk.Environment;
import software.constructs.Node;

/**
 * Deployment settings read from cdk.json context (override with {@code -c key=value}).
 * The AWS account is never hard-coded: it comes from the credentials the CLI runs with (CDK_DEFAULT_ACCOUNT).
 *
 * @param githubRepo "owner/name" of the repository whose GitHub Actions may deploy (OIDC trust)
 * @param githubOidcProviderArn the account's existing GitHub OIDC provider, if any: an account can have only ONE
 *     provider per URL, so when another project already created it, it is imported instead of created
 * @param monthlyBudgetUsd AWS Budget for everything tagged Project=jars
 */
public record EnvConfig(
        String envName,
        String account,
        String region,
        String authDomainPrefix,
        String localWebOrigin,
        String githubRepo,
        Optional<String> githubOidcProviderArn,
        int monthlyBudgetUsd) {

    public static EnvConfig from(Node node, String defaultAccount) {
        return new EnvConfig(
                required(node, "jars:env"),
                defaultAccount,
                required(node, "jars:region"),
                required(node, "jars:authDomainPrefix"),
                required(node, "jars:localWebOrigin"),
                required(node, "jars:githubRepo"),
                Optional.ofNullable(node.tryGetContext("jars:githubOidcProviderArn"))
                        .map(Object::toString)
                        .filter(arn -> !arn.isBlank()),
                Integer.parseInt(required(node, "jars:monthlyBudgetUsd")));
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
