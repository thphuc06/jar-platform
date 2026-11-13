package io.github.thphuc06.jars.imports.internal.jobs;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.github.thphuc06.jars.imports.internal.batch.ImportLauncher;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives "Object Created" events of the statements bucket (S3 -> EventBridge rule {@code statement-uploaded} -> SQS
 * {@code jars-imports}) and runs the import of the uploaded file. Only in the {@code aws} profile, like the jobs
 * listener.
 *
 * <p>The message is deleted when {@code onMessage} returns: after the import finished (successfully or with the file
 * rejected, which a retry would not change). An infrastructure failure throws, so SQS delivers the message again and,
 * after {@code maxReceiveCount} attempts, moves it to the DLQ; a delivery while the import still runs is a no-op.
 */
@Component
@Profile("aws")
class StatementUploadedListener {

    private static final Logger log = LoggerFactory.getLogger(StatementUploadedListener.class);

    /** uploads/{userId}/{importId}.csv, as built by StatementImport.objectKeyOf */
    static final Pattern KEY = Pattern.compile("^uploads/([0-9a-f-]{36})/([0-9a-f-]{36})\\.csv$");

    private final ImportLauncher launcher;
    private final JsonMapper json;

    StatementUploadedListener(ImportLauncher launcher, JsonMapper json) {
        this.launcher = launcher;
        this.json = json;
    }

    @SqsListener("${jars.imports.queue-name}")
    void onMessage(String payload) {
        UUID importId = importIdOf(json, payload);
        if (importId == null) {
            log.warn("Ignoring a message that is not the upload of a statement: {}", payload);
            return;
        }
        launcher.run(importId);
    }

    /** The import id from an EventBridge "Object Created" event, or null for anything else. */
    static UUID importIdOf(JsonMapper json, String payload) {
        JsonNode event = json.readTree(payload);
        String key = event.path("detail").path("object").path("key").asString("");
        Matcher m = KEY.matcher(key);
        return m.matches() ? UUID.fromString(m.group(2)) : null;
    }
}
