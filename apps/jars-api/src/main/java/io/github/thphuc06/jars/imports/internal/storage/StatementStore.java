package io.github.thphuc06.jars.imports.internal.storage;

import io.github.thphuc06.jars.imports.internal.domain.StatementImport;
import java.time.Instant;
import java.util.Map;

/** Where uploaded statement files live: S3 on AWS, a local folder in development and tests. */
public interface StatementStore {

    /**
     * Where the browser sends the file. For S3 a presigned PUT URL, which the browser calls without the API token;
     * for the local store a same-origin API URL, which the browser calls with its token.
     *
     * @param sameOrigin true when the URL is an API path (the client must add its Authorization header)
     */
    record UploadTarget(
            String url, String method, Map<String, String> headers, Instant expiresAt, boolean sameOrigin) {}

    UploadTarget uploadTarget(StatementImport statementImport, Instant now);

    /**
     * The whole file.
     *
     * @throws io.github.thphuc06.jars.imports.internal.parsing.InvalidStatementException when it is missing or larger
     *     than the configured maximum
     */
    byte[] read(String objectKey);
}
