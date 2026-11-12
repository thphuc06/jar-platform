package io.github.thphuc06.jars.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.ApiTestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The OpenAPI document generated from the controllers must equal the committed copy in {@code contracts/openapi}, the
 * file the web client is generated from. An API change therefore shows up as a diff in a pull request instead of
 * silently breaking the UI.
 *
 * <p>After changing the API run {@code ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true} and commit the file.
 *
 * <p>Keys are written in alphabetical order: springdoc lists the properties of a reflected class (here
 * {@code java.util.Currency}) in the order the JVM returns its methods, which differs between operating systems, and the
 * same code produced a different file on a Linux CI runner than on a developer machine.
 */
@TestPropertySource(properties = "springdoc.writer-with-order-by-keys=true")
class OpenApiContractTest extends ApiTestSupport {

    private static final Path CONTRACT = Path.of("../../contracts/openapi/jars-api.yaml");

    @Test
    void theCommittedOpenApiFile_matchesTheControllers() throws IOException {
        String generated = get(null, "/v3/api-docs.yaml").body();
        assertThat(generated).contains("openapi:").contains("/api/v1/transactions");

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, generated, StandardCharsets.UTF_8);
        }

        assertThat(CONTRACT).as("run with -Dopenapi.update=true to create it").exists();
        assertThat(Files.readString(CONTRACT, StandardCharsets.UTF_8))
                .as(
                        "contracts/openapi/jars-api.yaml is out of date: run ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true")
                .isEqualTo(generated);
    }

    @Test
    void userIdIsNeverDocumentedAsARequestParameter() {
        String generated = get(null, "/v3/api-docs.yaml").body();

        assertThat(generated).doesNotContain("userId").doesNotContain("UserId");
    }

    @Test
    void everyMoneyChangingPostDeclaresTheIdempotencyKeyHeader() {
        String generated = get(null, "/v3/api-docs.yaml").body();

        assertThat(generated).contains("Idempotency-Key");
    }
}
