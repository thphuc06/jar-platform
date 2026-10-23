package io.github.thphuc06.jars;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Boots the whole application (with a real Postgres) and checks the endpoints infrastructure depends on. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JarsApplicationTests {

    @Autowired
    MockMvcTester mvc;

    @Test
    void readinessProbe_isUpAndDoesNotDependOnDatabase() {
        // Aurora Serverless v2 can only auto-pause if nothing polls the database (ADR-0008)
        assertThat(mvc.get().uri("/actuator/health/readiness"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .satisfies(json -> assertThat(json.getJson()).contains("\"status\":\"UP\"").doesNotContain("\"db\""));
    }

    @Test
    void livenessProbe_isUp() {
        assertThat(mvc.get().uri("/actuator/health/liveness")).hasStatus(HttpStatus.OK);
    }

    @Test
    void fullHealth_includesDatabase() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .extractingPath("$.components.db.status")
                .isEqualTo("UP");
    }

    @Test
    void publicHealth_isUp() {
        assertThat(mvc.get().uri("/api/v1/health"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }
}
