package io.github.thphuc06.jars.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.ApiTestSupport;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** The local profile mints and accepts its own tokens, so a developer needs no Cognito (POST /dev/token). */
class LocalDevTokenTest extends ApiTestSupport {

    @Test
    void aMintedToken_isAcceptedByTheApi_andCreatesTheUser() {
        String token = mint("alice-dev");

        Resp me = call(HttpMethod.GET, "/api/v1/me", null, null, null, token);

        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().get("locale").asString()).isEqualTo("vi-VN");
    }

    @Test
    void theSameSubjectIsTheSameUser_differentSubjectsAreDifferentUsers() {
        String first = call(HttpMethod.GET, "/api/v1/me", null, null, null, mint("same-sub"))
                .json()
                .get("id")
                .asString();
        String again = call(HttpMethod.GET, "/api/v1/me", null, null, null, mint("same-sub"))
                .json()
                .get("id")
                .asString();
        String other = call(HttpMethod.GET, "/api/v1/me", null, null, null, mint("other-sub"))
                .json()
                .get("id")
                .asString();

        assertThat(again).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void aTamperedToken_isRejected() {
        String[] parts = mint("mallory").split("\\.");
        String forgedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        "{\"sub\":\"admin\",\"token_use\":\"access\",\"client_id\":\"local-dev\",\"iss\":\"http://localhost/dev\",\"exp\":4102444800}"
                                .getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(call(HttpMethod.GET, "/api/v1/me", null, null, null, forged).status())
                .isEqualTo(401);
    }

    @Test
    void aTokenFromSomeoneElse_isRejected() {
        assertThat(call(HttpMethod.GET, "/api/v1/me", null, null, null, "not-a-jwt")
                        .status())
                .isEqualTo(401);
    }

    @Test
    void withoutASubject_theDemoUserIsUsed() {
        Resp response = call(HttpMethod.POST, "/dev/token", null, null, "{}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("expiresIn").asInt()).isEqualTo(3600);
    }

    private String mint(String sub) {
        Resp response = call(HttpMethod.POST, "/dev/token", null, null, "{\"sub\":\"" + sub + "\"}");
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        return body.get("accessToken").asString();
    }

    /** Like {@code call} but with a raw bearer token instead of MockMvc's jwt() post-processor. */
    private Resp call(HttpMethod method, String uri, Object ignored, String key, String body, String bearer) {
        try {
            var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(method, uri)
                    .header("Authorization", "Bearer " + bearer);
            var response = mvc.perform(request).getResponse();
            return new Resp(
                    response.getStatus(),
                    new String(response.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8),
                    response,
                    mapper);
        } catch (RuntimeException e) {
            throw e;
        }
    }
}
