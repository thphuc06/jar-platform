package io.github.thphuc06.jars.ledger.internal.service;

import io.github.thphuc06.jars.ledger.internal.service.ReconciliationService.ReconciliationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a message on the reconciliation queue means. Plain application logic with no AWS types, so tests call it
 * directly; the SQS adapter that feeds it exists only under the {@code aws} profile.
 *
 * <p>The EventBridge schedule sends {@code {"job":"LEDGER_RECONCILIATION"}} once a night. Unknown jobs are ignored (and
 * acknowledged), like in the month-close job, so a newer scheduler cannot wedge an older task. A message that is not
 * JSON is rejected and ends in the DLQ after its receives.
 *
 * <p>Redelivery needs no protection: the run only reads, so a second delivery of the same message repeats the check
 * and reaches the same result (a few seconds of database time, a second set of the same log lines).
 */
@Service
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    public static final String LEDGER_RECONCILIATION = "LEDGER_RECONCILIATION";

    private final ReconciliationService reconciliation;
    private final JsonMapper json;

    ReconciliationJob(ReconciliationService reconciliation, JsonMapper json) {
        this.reconciliation = reconciliation;
        this.json = json;
    }

    /**
     * Mismatches do <em>not</em> fail the message: redelivering it would find the same thing. They are reported
     * through the log and the metric.
     *
     * @return the report, or null when the message names another job
     * @throws IllegalArgumentException for a payload that is not JSON
     * @throws RuntimeException when the database cannot be read, so the message is delivered again
     */
    public ReconciliationReport handle(String payload) {
        JsonNode message;
        try {
            message = json.readTree(payload);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The job message is not valid JSON", e);
        }
        String job = message.hasNonNull("job") ? message.get("job").asString() : "";
        if (!LEDGER_RECONCILIATION.equals(job)) {
            log.warn("Ignoring a message for an unknown job: '{}'", job);
            return null;
        }
        return reconciliation.reconcile();
    }
}
