package io.github.thphuc06.jars;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
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

    @Autowired
    DataSource dataSource;

    @Test
    void readinessProbe_isUpAndDoesNotDependOnDatabase() {
        // Aurora Serverless v2 can only auto-pause if nothing polls the database (ADR-0008)
        assertThat(mvc.get().uri("/actuator/health/readiness"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .satisfies(json ->
                        assertThat(json.getJson()).contains("\"status\":\"UP\"").doesNotContain("\"db\""));
    }

    /**
     * The other half of letting Aurora pause: an idle application holds no connection. Hikari's default keeps 10 idle
     * connections forever (measured in P3.3), and Aurora pauses only after 5 minutes without any connection.
     */
    @Test
    void connectionPool_keepsNoIdleConnection() {
        HikariDataSource pool = (HikariDataSource) dataSource;

        assertThat(pool.getMinimumIdle()).isZero();
        assertThat(pool.getIdleTimeout()).isEqualTo(30_000);
        assertThat(pool.getKeepaliveTime()).isZero();
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
