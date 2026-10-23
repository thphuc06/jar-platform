package io.github.thphuc06.jars.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.thphuc06.jars.platform.security.JarsSecurityProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CognitoUserInfoClientTest {

    static final String USER_INFO = "https://jars-test.auth.ap-southeast-1.amazoncognito.com/oauth2/userInfo";

    MockRestServiceServer server;
    CognitoUserInfoClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CognitoUserInfoClient(builder, new JarsSecurityProperties("iss", List.of("c"), USER_INFO));
    }

    @Test
    void fetch_sendsBearerTokenAndReadsEmail() {
        server.expect(requestTo(USER_INFO))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer access-token-1"))
                .andRespond(withSuccess("""
                        {"sub": "sub-1", "email": "an@example.com", "email_verified": "true", "username": "x"}
                        """, MediaType.APPLICATION_JSON));

        UserInfoClient.UserInfo info = client.fetch("access-token-1");

        assertThat(info).isEqualTo(new UserInfoClient.UserInfo("sub-1", "an@example.com"));
        server.verify();
    }

    @Test
    void fetch_withoutEmail_failsLoudly() {
        server.expect(requestTo(USER_INFO)).andRespond(withSuccess("{\"sub\": \"sub-1\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetch("t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("email");
    }
}
