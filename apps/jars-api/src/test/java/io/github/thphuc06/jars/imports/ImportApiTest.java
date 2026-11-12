package io.github.thphuc06.jars.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.thphuc06.jars.ApiTestSupport;
import io.github.thphuc06.jars.imports.internal.batch.ImportLauncher;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * The statement import end to end with the local store: create an import, upload the file to the returned target,
 * Spring Batch posts the rows to the ledger. The S3 and SQS parts are the only ones replaced (LocalStatementStore and
 * the local upload endpoint stand in for presigned PUT + S3 event); the job, the ledger and the outbox are the real ones.
 */
class ImportApiTest extends ApiTestSupport {

    private static final String STATEMENT = """
            date,description,amount
            2026-10-01,CONG TY ABC TRA LUONG T10,"15,000,000"
            2026-10-02,GRAB *TRIP,-45000
            2026-10-03,HIGHLANDS COFFEE,-55.000
            not-a-date,SHOPEE,-1000
            2026-10-05,ZERO ROW,0
            2026-10-06,EVN HCMC,-350000
            """;

    @Autowired
    ImportLauncher launcher;

    @Test
    void aStatementIsPostedToTheLedger_rowByRow_withTheBadRowsListed() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        UUID play = jarId(user, "PLAY");

        JsonNode created = create(user, "sao-ke-thang-10.csv");
        assertThat(created.at("/statementImport/status").asString()).isEqualTo("AWAITING_UPLOAD");
        assertThat(created.at("/upload/method").asString()).isEqualTo("PUT");
        assertThat(created.at("/upload/sameOrigin").asBoolean()).isTrue();
        String importId = created.at("/statementImport/id").asString();

        JsonNode done = upload(user, created.at("/upload/url").asString(), STATEMENT);

        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("totalRows").asInt()).isEqualTo(6);
        assertThat(done.get("postedRows").asInt()).isEqualTo(4);
        assertThat(done.get("skippedRows").asInt()).isEqualTo(2);
        JsonNode errors = get(user, "/api/v1/imports/" + importId).json().get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0).get("line").asInt()).isEqualTo(5);
        assertThat(errors.get(0).get("reason").asString()).contains("Ngày không hợp lệ");
        assertThat(errors.get(1).get("line").asInt()).isEqualTo(6);

        // grab + electricity from the essentials jar, coffee from PLAY; the salary is income without a jar
        assertThat(balanceOf(nec)).isEqualByComparingTo(new BigDecimal("-395000"));
        assertThat(balanceOf(play)).isEqualByComparingTo(new BigDecimal("-55000"));
        assertBalanceMatchesEntries(nec);
        assertThat(jdbc.sql("SELECT count(*) FROM transactions t JOIN jars j ON j.id = ? WHERE t.user_id = j.user_id"
                                + " AND t.source = 'IMPORT'")
                        .param(nec)
                        .query(Integer.class)
                        .single())
                .isEqualTo(4);
    }

    @Test
    void theSameFileCannotBeImportedTwice() {
        JwtRequestPostProcessor user = newUser();
        jarId(user, "NEC");
        upload(user, create(user, "a.csv").at("/upload/url").asString(), STATEMENT);

        JsonNode again =
                upload(user, create(user, "a-copy.csv").at("/upload/url").asString(), STATEMENT);

        assertThat(again.get("status").asString()).isEqualTo("FAILED");
        assertThat(again.get("failureReason").asString()).contains("đã được nhập");
        assertThat(again.get("postedRows").asInt()).isZero();
    }

    @Test
    void aFileWithoutUsableColumnsFailsWithAReasonForTheUser() {
        JwtRequestPostProcessor user = newUser();

        JsonNode done = upload(user, create(user, "x.csv").at("/upload/url").asString(), "foo,bar\n1,2\n");

        assertThat(done.get("status").asString()).isEqualTo("FAILED");
        assertThat(done.get("failureReason").asString()).contains("cột ngày");
    }

    @Test
    void aFailedImportRestartsAsTheSameJobInstance_andPostsEveryRowOnce() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        JsonNode created = create(user, "late.csv");
        UUID importId = UUID.fromString(created.at("/statementImport/id").asString());

        // the import starts before its file exists (a lost S3 object, say): the job instance fails
        launcher.run(importId);
        assertThat(get(user, "/api/v1/imports/" + importId).json().get("status").asString())
                .isEqualTo("FAILED");

        // the file arrives: the same job instance restarts and completes
        JsonNode done = upload(user, created.at("/upload/url").asString(), STATEMENT);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("""
                                SELECT count(DISTINCT e.job_instance_id) FROM batch_job_execution e
                                JOIN batch_job_execution_params p ON p.job_execution_id = e.job_execution_id
                                WHERE p.parameter_name = 'importId' AND p.parameter_value = ?
                                """).param(importId.toString()).query(Integer.class).single())
                .as("one job instance, two executions")
                .isEqualTo(1);

        // running it once more changes nothing: completed imports are not run again
        assertThat(launcher.run(importId)).isFalse();
        assertThat(balanceOf(nec)).isEqualByComparingTo(new BigDecimal("-395000"));
    }

    @Test
    void anImportOfAnotherUserIsNotFound() {
        JwtRequestPostProcessor owner = newUser();
        JwtRequestPostProcessor other = newUser();
        String importId = create(owner, "mine.csv").at("/statementImport/id").asString();

        assertThat(get(other, "/api/v1/imports/" + importId).status()).isEqualTo(404);
        assertThat(get(other, "/api/v1/imports").json()).isEmpty();
        assertThat(get(owner, "/api/v1/imports").json()).hasSize(1);
    }

    @Test
    void onlyCsvFilesAreAccepted() {
        Resp rejected = post(newUser(), "/api/v1/imports", null, "{\"fileName\":\"statement.pdf\"}");

        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.json().get("type").asString()).endsWith("invalid-import");
    }

    @Test
    void theUserIsNotifiedWhenTheImportEnds() {
        JwtRequestPostProcessor user = newUser();
        jarId(user, "NEC");
        upload(user, create(user, "notify.csv").at("/upload/url").asString(), STATEMENT.replace("CONG TY", "CTY"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode items = get(user, "/api/v1/notifications").json().get("items");
            assertThat(items).anySatisfy(n -> {
                assertThat(n.get("type").asString()).isEqualTo("IMPORT_COMPLETED");
                assertThat(n.get("body").asString()).contains("4 giao dịch").contains("2 dòng");
            });
        });
    }

    private JsonNode create(JwtRequestPostProcessor user, String fileName) {
        Resp created = post(user, "/api/v1/imports", null, "{\"fileName\":\"" + fileName + "\"}");
        assertThat(created.status()).isEqualTo(201);
        return created.json();
    }

    private JsonNode upload(JwtRequestPostProcessor user, String url, String content) {
        MockHttpServletResponse response = mvc.perform(MockMvcRequestBuilders.put(url)
                        .with(user)
                        .contentType("text/csv")
                        .content(content.getBytes(StandardCharsets.UTF_8)))
                .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return mapper.readTree(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8));
    }
}
