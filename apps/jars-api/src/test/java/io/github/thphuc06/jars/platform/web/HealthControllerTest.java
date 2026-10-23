package io.github.thphuc06.jars.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(HealthController.class)
@Import(HealthControllerTest.FixedBuildInfo.class)
class HealthControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void health_returnsUpWithServiceNameAndBuildVersion() {
        assertThat(mvc.get().uri("/api/v1/health"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"status": "UP", "service": "jars-api", "version": "1.2.3-test"}
                        """);
    }

    @TestConfiguration
    static class FixedBuildInfo {

        @Bean
        BuildProperties buildProperties() {
            Properties props = new Properties();
            props.setProperty("version", "1.2.3-test");
            return new BuildProperties(props);
        }
    }
}
