package io.github.thphuc06.jars.identity.internal;

import io.github.thphuc06.jars.platform.security.JarsSecurityProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls Cognito's OIDC userInfo endpoint. Short timeouts: a slow identity provider must not hang request threads. */
@Component
class CognitoUserInfoClient implements UserInfoClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final RestClient http;

    @Autowired // two constructors: tell Spring which one to use
    CognitoUserInfoClient(JarsSecurityProperties props) {
        this(RestClient.builder().requestFactory(jdkFactoryWithTimeouts()), props);
    }

    /**
     * Lets tests bind a {@code MockRestServiceServer} to the builder. It must not set a request factory itself,
     * otherwise it would replace the mock server's factory and the test would make real HTTP calls.
     */
    CognitoUserInfoClient(RestClient.Builder builder, JarsSecurityProperties props) {
        this.http = builder.baseUrl(props.userInfoUri()).build();
    }

    private static JdkClientHttpRequestFactory jdkFactoryWithTimeouts() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    @Override
    public UserInfo fetch(String accessToken) {
        UserInfo info = http.get()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(UserInfo.class);
        if (info == null || info.email() == null || info.email().isBlank()) {
            throw new IllegalStateException("userInfo returned no email; is the 'email' scope requested?");
        }
        return info;
    }
}
