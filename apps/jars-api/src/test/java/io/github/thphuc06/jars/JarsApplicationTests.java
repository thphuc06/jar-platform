package io.github.thphuc06.jars;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Boots the whole application context once and checks the endpoints infrastructure depends on. */
@SpringBootTest
@AutoConfigureMockMvc
class JarsApplicationTests {

    @Autowired
    MockMvcTester mvc;

    @Test
    void readinessProbe_isUp() {
        assertThat(mvc.get().uri("/actuator/health/readiness"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void livenessProbe_isUp() {
        assertThat(mvc.get().uri("/actuator/health/liveness")).hasStatus(HttpStatus.OK);
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
