package io.github.thphuc06.jars.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** Locally there is no chat model (spring.ai.model.chat=none): sessions work, a message is a clear 503. */
class AdvisorWithoutModelTest extends ApiTestSupport {

    @Test
    void sendingAMessageWithoutAModelIs503ProblemJson() {
        JwtRequestPostProcessor user = newUser();
        String session =
                post(user, "/api/v1/chat/sessions", null, null).json().get("id").asString();

        Resp sent = post(user, "/api/v1/chat/sessions/" + session + "/messages", null, "{\"content\":\"xin chào\"}");

        assertThat(sent.status()).isEqualTo(503);
        assertThat(sent.json().get("type").asString()).endsWith("advisor-unavailable");
    }
}
