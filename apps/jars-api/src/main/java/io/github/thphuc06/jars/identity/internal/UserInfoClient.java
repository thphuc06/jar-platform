package io.github.thphuc06.jars.identity.internal;

/**
 * Reads profile attributes the access token does not carry. Cognito access tokens have no {@code email} claim, so the
 * OIDC userInfo endpoint is called once, when the internal user is created.
 */
interface UserInfoClient {

    UserInfo fetch(String accessToken);

    record UserInfo(String sub, String email) {}
}
