package io.github.thphuc06.jars.infra.config;

import java.util.Locale;
import software.constructs.Node;

/**
 * Environment configuration read from CDK context (cdk.json or {@code -c key=value}).
 *
 * <p>CLI values arrive as strings and cdk.json values keep their JSON type, so every accessor
 * accepts both. Keys are documented in docs/05-operations/deployment.md section 3.
 *
 * @param env name prefix and tag value (only {@code dev} exists)
 * @param account AWS account id the stacks are bound to
 * @param region AWS region the stacks are bound to
 * @param apiDesiredCount ECS desired count of jars-api (0 = SLEEP mode)
 * @param alertEmail address subscribed to the alarm topic; blank means no subscription
 * @param imageTag tag of the jars-api image in ECR; blank is a synth-time error for JarsApp
 * @param dbMode database implementation
 * @param deployWeb whether JarsApp uploads the built SPA with a BucketDeployment
 * @param webDistDir directory with the built SPA, only read when {@code deployWeb} is true
 * @param cloudFrontPrefixListId id of the AWS managed prefix list
 *     com.amazonaws.global.cloudfront.origin-facing in {@code region}
 */
public record EnvConfig(
        String env,
        String account,
        String region,
        int apiDesiredCount,
        String alertEmail,
        String imageTag,
        DbMode dbMode,
        boolean deployWeb,
        String webDistDir,
        String cloudFrontPrefixListId) {

    public static final String PROJECT = "jars";
    public static final String OWNER = "thphuc06";

    /** Prefix list ids differ per region and the id cannot be derived, so known regions are listed. */
    private static final String CLOUDFRONT_PREFIX_LIST_AP_SOUTHEAST_1 = "pl-31a34658";

    public enum DbMode {
        AURORA,
        RDS
    }

    public static EnvConfig from(Node node) {
        String region = text(node, "region", "ap-southeast-1");
        return new EnvConfig(
                text(node, "env", "dev"),
                text(node, "account", "626212071763"),
                region,
                integer(node, "apiDesiredCount", 1),
                text(node, "alertEmail", System.getenv().getOrDefault("JARS_ALERT_EMAIL", "")),
                text(node, "imageTag", ""),
                DbMode.valueOf(text(node, "dbMode", "aurora").toUpperCase(Locale.ROOT)),
                bool(node, "deployWeb", false),
                text(node, "webDistDir", "../../apps/web/dist"),
                text(node, "cloudFrontPrefixListId", defaultPrefixListId(region)));
    }

    /** Resource name prefix, for example {@code jars-dev}. */
    public String prefix() {
        return PROJECT + "-" + env;
    }

    /** Resource name such as {@code jars-dev-alarms}. */
    public String name(String component) {
        return prefix() + "-" + component;
    }

    private static String defaultPrefixListId(String region) {
        if ("ap-southeast-1".equals(region)) {
            return CLOUDFRONT_PREFIX_LIST_AP_SOUTHEAST_1;
        }
        throw new IllegalArgumentException("No known CloudFront origin-facing prefix list id for region " + region
                + ". Find it with: aws ec2 describe-managed-prefix-lists --region " + region
                + " --filters Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing"
                + " and pass it with -c cloudFrontPrefixListId=pl-xxxx");
    }

    private static String text(Node node, String key, String fallback) {
        Object value = node.tryGetContext(key);
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? fallback : s;
    }

    private static int integer(Node node, String key, int fallback) {
        Object value = node.tryGetContext(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Context '" + key + "' must be a number but was '" + value + "'", e);
        }
    }

    private static boolean bool(Node node, String key, boolean fallback) {
        Object value = node.tryGetContext(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value).trim());
    }
}
