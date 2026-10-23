package io.github.thphuc06.jars.infra;

import java.util.List;
import software.amazon.awscdk.Acknowledgment;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.services.cognito.AccountRecovery;
import software.amazon.awscdk.services.cognito.AuthFlow;
import software.amazon.awscdk.services.cognito.AutoVerifiedAttrs;
import software.amazon.awscdk.services.cognito.CfnManagedLoginBranding;
import software.amazon.awscdk.services.cognito.CognitoDomainOptions;
import software.amazon.awscdk.services.cognito.FeaturePlan;
import software.amazon.awscdk.services.cognito.ManagedLoginVersion;
import software.amazon.awscdk.services.cognito.Mfa;
import software.amazon.awscdk.services.cognito.MfaSecondFactor;
import software.amazon.awscdk.services.cognito.OAuthFlows;
import software.amazon.awscdk.services.cognito.OAuthScope;
import software.amazon.awscdk.services.cognito.OAuthSettings;
import software.amazon.awscdk.services.cognito.PasswordPolicy;
import software.amazon.awscdk.services.cognito.ResourceServerScope;
import software.amazon.awscdk.services.cognito.SignInAliases;
import software.amazon.awscdk.services.cognito.StandardAttribute;
import software.amazon.awscdk.services.cognito.StandardAttributes;
import software.amazon.awscdk.services.cognito.UserPool;
import software.amazon.awscdk.services.cognito.UserPoolClient;
import software.amazon.awscdk.services.cognito.UserPoolClientIdentityProvider;
import software.amazon.awscdk.services.cognito.UserPoolClientOptions;
import software.amazon.awscdk.services.cognito.UserPoolDomain;
import software.amazon.awscdk.services.cognito.UserPoolDomainOptions;
import software.amazon.awscdk.services.cognito.UserPoolResourceServer;
import software.amazon.awscdk.services.cognito.UserPoolResourceServerOptions;
import software.constructs.Construct;

/**
 * Identity for Jars Platform (ADR-0010): one user pool with Cognito managed login, a resource server for the API
 * scopes, and a public SPA client using Authorization Code + PKCE. Long-lived: users must survive app teardown.
 */
public class AuthStack extends Stack {

    public static final String API_IDENTIFIER = "jars-api";

    private final UserPool userPool;
    private final UserPoolClient localWebClient;

    public AuthStack(Construct scope, String id, StackProps props, EnvConfig config) {
        super(scope, id, props);

        userPool = UserPool.Builder.create(this, "UserPool")
                .userPoolName(config.name("users"))
                .featurePlan(FeaturePlan.ESSENTIALS)
                .selfSignUpEnabled(true)
                .signInAliases(SignInAliases.builder().email(true).build())
                .autoVerify(AutoVerifiedAttrs.builder().email(true).build())
                .standardAttributes(StandardAttributes.builder()
                        .email(StandardAttribute.builder()
                                .required(true)
                                .mutable(true)
                                .build())
                        .build())
                .passwordPolicy(PasswordPolicy.builder()
                        .minLength(10)
                        .requireLowercase(true)
                        .requireUppercase(true)
                        .requireDigits(true)
                        .requireSymbols(true)
                        .build())
                .mfa(Mfa.OPTIONAL)
                .mfaSecondFactor(MfaSecondFactor.builder().otp(true).sms(false).build())
                .accountRecovery(AccountRecovery.EMAIL_ONLY)
                // users are data: keep the pool if the stack is ever deleted
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        UserPoolDomain domain = userPool.addDomain(
                "Domain",
                UserPoolDomainOptions.builder()
                        .cognitoDomain(CognitoDomainOptions.builder()
                                .domainPrefix(config.authDomainPrefix())
                                .build())
                        .managedLoginVersion(ManagedLoginVersion.NEWER_MANAGED_LOGIN)
                        .build());

        ResourceServerScope readScope = ResourceServerScope.Builder.create()
                .scopeName("read")
                .scopeDescription("Read the signed-in user's own data")
                .build();
        ResourceServerScope writeScope = ResourceServerScope.Builder.create()
                .scopeName("write")
                .scopeDescription("Create or change the signed-in user's own data")
                .build();
        UserPoolResourceServer api = userPool.addResourceServer(
                "ApiResourceServer",
                UserPoolResourceServerOptions.builder()
                        .identifier(API_IDENTIFIER)
                        .userPoolResourceServerName(config.name("api"))
                        .scopes(List.of(readScope, writeScope))
                        .build());

        // Public client (no secret) for the React SPA on localhost; the CloudFront client is added in Phase 3
        String origin = config.localWebOrigin();
        localWebClient = userPool.addClient(
                "LocalWebClient",
                UserPoolClientOptions.builder()
                        .userPoolClientName(config.name("web-local"))
                        .generateSecret(false)
                        // ExplicitAuthFlows must be explicit: an empty list makes Cognito fall back to its defaults
                        // (SRP + CUSTOM_AUTH). Keep SRP (password never leaves the client) + refresh token only.
                        .authFlows(AuthFlow.builder().userSrp(true).build())
                        .oAuth(OAuthSettings.builder()
                                .flows(OAuthFlows.builder()
                                        .authorizationCodeGrant(true)
                                        .build())
                                .scopes(List.of(
                                        OAuthScope.OPENID,
                                        OAuthScope.EMAIL,
                                        OAuthScope.PROFILE,
                                        OAuthScope.resourceServer(api, readScope),
                                        OAuthScope.resourceServer(api, writeScope)))
                                .callbackUrls(List.of(origin + "/auth/callback"))
                                .logoutUrls(List.of(origin + "/"))
                                .build())
                        .supportedIdentityProviders(List.of(UserPoolClientIdentityProvider.COGNITO))
                        .preventUserExistenceErrors(true)
                        .enableTokenRevocation(true)
                        .accessTokenValidity(Duration.minutes(60))
                        .idTokenValidity(Duration.minutes(60))
                        .refreshTokenValidity(Duration.days(30))
                        .build());

        // the L2 construct cannot create a managed login style yet; use the default Cognito-provided look
        CfnManagedLoginBranding branding = CfnManagedLoginBranding.Builder.create(this, "LocalWebClientBranding")
                .userPoolId(userPool.getUserPoolId())
                .clientId(localWebClient.getUserPoolClientId())
                .useCognitoProvidedValues(true)
                .build();
        branding.getNode().addDependency(domain);

        String issuer = "https://cognito-idp." + getRegion() + ".amazonaws.com/" + userPool.getUserPoolId();
        output("UserPoolId", userPool.getUserPoolId(), "Cognito user pool id");
        output("LocalWebClientId", localWebClient.getUserPoolClientId(), "SPA client id (localhost)");
        output("IssuerUri", issuer, "spring.security.oauth2.resourceserver.jwt.issuer-uri");
        output("ManagedLoginBaseUrl", domain.baseUrl(), "Managed login / OAuth2 endpoints base URL");

        // acknowledge only findings cdk-nag actually reports; each one needs a reason that a reviewer can challenge
        Validations.of(userPool)
                .acknowledge(
                        acknowledge(
                                "AwsSolutions::AwsSolutions-COG8",
                                "Plus feature plan (threat protection) is paid per MAU; Essentials is used for cost"
                                        + " reasons in the dev environment (ADR-0010). Revisit before production."),
                        acknowledge(
                                "AwsSolutions::AwsSolutions-COG2",
                                "MFA is OPTIONAL (TOTP) to keep sign-up friction low for a personal-finance demo;"
                                        + " users can enable it themselves. Revisit before production."));
    }

    public UserPool userPool() {
        return userPool;
    }

    public UserPoolClient localWebClient() {
        return localWebClient;
    }

    private void output(String id, String value, String description) {
        CfnOutput.Builder.create(this, id).value(value).description(description).build();
    }

    private static Acknowledgment acknowledge(String ruleId, String reason) {
        return Acknowledgment.builder().id(ruleId).reason(reason).build();
    }
}
