package io.github.thphuc06.jars.imports.internal.storage;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param storage {@code local} (a folder on this machine, the default for development and tests) or {@code s3} (the aws
 *     profile: presigned PUT to the statements bucket, then S3 -> EventBridge -> SQS starts the import)
 * @param bucket the statements bucket (s3 only)
 * @param queueName the SQS queue that receives the "Object Created" events (s3 only)
 * @param maxFileSize a larger file is rejected before it is parsed
 * @param uploadTtl how long an upload URL stays valid
 * @param localDir where the local store keeps files; default {@code <java.io.tmpdir>/jars-statements} (a placeholder
 *     in {@code @DefaultValue} is not resolved, so the default is applied here)
 */
@ConfigurationProperties("jars.imports")
public record ImportProperties(
        @DefaultValue("local") String storage,
        @DefaultValue("") String bucket,
        @DefaultValue("") String queueName,
        @DefaultValue("5MB") DataSize maxFileSize,
        @DefaultValue("10m") Duration uploadTtl,
        Path localDir) {

    public ImportProperties {
        if (localDir == null) {
            localDir = Path.of(System.getProperty("java.io.tmpdir"), "jars-statements");
        }
    }
}
