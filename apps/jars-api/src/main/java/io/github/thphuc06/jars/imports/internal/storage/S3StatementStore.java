package io.github.thphuc06.jars.imports.internal.storage;

import io.github.thphuc06.jars.imports.internal.domain.StatementImport;
import io.github.thphuc06.jars.imports.internal.parsing.InvalidStatementException;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * AWS: the browser PUTs the file straight to S3 with a presigned URL (the file never passes through the API task), and
 * S3 -> EventBridge -> SQS starts the import. The URL is signed with the task role's credentials, so it can only write
 * the one key of this import, and only for {@code jars.imports.upload-ttl}.
 *
 * <p>A presigned PUT cannot cap the size of what is uploaded; the size is checked here, with a HEAD, before the file is
 * downloaded. Uploads expire after 7 days (bucket lifecycle rule).
 */
@Component
@ConditionalOnProperty(name = "jars.imports.storage", havingValue = "s3")
public class S3StatementStore implements StatementStore {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final ImportProperties properties;

    S3StatementStore(S3Client s3, S3Presigner presigner, ImportProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public UploadTarget uploadTarget(StatementImport statementImport, Instant now) {
        var request = PutObjectPresignRequest.builder()
                .signatureDuration(properties.uploadTtl())
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(statementImport.getObjectKey())
                        .contentType("text/csv")
                        .build())
                .build();
        var presigned = presigner.presignPutObject(request);
        return new UploadTarget(
                presigned.url().toString(), "PUT", Map.of("Content-Type", "text/csv"), presigned.expiration(), false);
    }

    @Override
    public byte[] read(String objectKey) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .build());
            if (head.contentLength() > properties.maxFileSize().toBytes()) {
                throw new InvalidStatementException(
                        "Tệp lớn hơn " + properties.maxFileSize().toMegabytes() + " MB. Hãy tách sao kê theo tháng.");
            }
            return s3.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(objectKey)
                            .build())
                    .asByteArray();
        } catch (NoSuchKeyException e) {
            throw new InvalidStatementException("Không tìm thấy tệp đã tải lên.");
        }
    }
}
