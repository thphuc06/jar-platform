package io.github.thphuc06.jars.infra;

import io.github.thphuc06.jars.infra.config.EnvConfig;
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
import software.amazon.awscdk.services.cognito.CognitoDomainOptions;
import software.amazon.awscdk.services.cognito.FeaturePlan;
import software.amazon.awscdk.services.cognito.ManagedLoginVersion;
import software.amazon.awscdk.services.cognito.Mfa;
import software.amazon.awscdk.services.cognito.PasswordPolicy;
import software.amazon.awscdk.services.cognito.ResourceServerScope;
import software.amazon.awscdk.services.cognito.ResourceServerScopeProps;
import software.amazon.awscdk.services.cognito.SignInAliases;
import software.amazon.awscdk.services.cognito.StandardAttribute;
import software.amazon.awscdk.services.cognito.StandardAttributes;
import software.amazon.awscdk.services.cognito.UserPool;
import software.amazon.awscdk.services.cognito.UserPoolClient;
import software.amazon.awscdk.services.cognito.UserPoolClientIdentityProvider;
import software.amazon.awscdk.services.cognito.UserPoolClientOptions;
import software.amazon.awscdk.services.cognito.UserPoolDomain;
import software.amazon.awscdk.services.cognito.UserPoolDomainOptions;
import software.amazon.awscdk.services.cognito.UserPoolEmail;
import software.amazon.awscdk.services.cognito.UserPoolResourceServer;
import software.amazon.awscdk.services.cognito.UserPoolResourceServerOptions;
import software.amazon.awscdk.services.cognito.UserVerificationConfig;
import software.amazon.awscdk.services.cognito.VerificationEmailStyle;
import software.constructs.Construct;

/**
 * JarsAuth: the Cognito user pool (docs/02-architecture/aws-infrastructure.md section 6).
 *
 * <p>The SPA app client is <em>not</em> here: its callback URL is the CloudFront domain, which lives
 * in JarsApp. The pool itself (and its users) is retained, so tearing JarsApp down never loses users.
 *
 * <p>Settings carried over from the first deployed version of this stack (jars-dev-auth, October
 * 2026): pool name, Essentials plan, e-mail as username, e-mail recovery, new managed login (v2),
 * access/id token 60 minutes, refresh token 30 days, token revocation, no user-existence errors.
 */
public final class AuthStack extends Stack {

    private final UserPool userPool;
    private final UserPoolDomain domain;
    private final UserPoolClient smokeClient;

    public AuthStack(Construct scope, String id, StackProps props, EnvConfig cfg) {
        super(scope, id, props);

        this.userPool = UserPool.Builder.create(this, "UserPool")
                .userPoolName(cfg.name("users"))
                .featurePlan(FeaturePlan.ESSENTIALS)
                .signInAliases(SignInAliases.builder().email(true).build())
                // Cognito treats e-mail addresses case-insensitively for sign-in
                .signInCaseSensitive(false)
                .selfSignUpEnabled(true)
                .autoVerify(AutoVerifiedAttrs.builder().email(true).build())
                .userVerification(UserVerificationConfig.builder()
                        .emailStyle(VerificationEmailStyle.CODE)
                        .build())
                // the default Cognito sender (50 e-mails a day) is enough for a demo; SES costs setup
                .email(UserPoolEmail.withCognito())
                .accountRecovery(AccountRecovery.EMAIL_ONLY)
                .standardAttributes(StandardAttributes.builder()
                        .email(StandardAttribute.builder()
                                .required(true)
                                .mutable(true)
                                .build())
                        .build())
                // the Cognito default policy, written out so that it is visible in the template (and to
                // cdk-nag): 8+ characters with lower case, upper case, a digit and a symbol
                .passwordPolicy(PasswordPolicy.builder()
                        .minLength(8)
                        .requireLowercase(true)
                        .requireUppercase(true)
                        .requireDigits(true)
                        .requireSymbols(true)
                        .build())
                .mfa(Mfa.OFF)
                // users must survive `cdk destroy` and replacement
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        // new managed login (branding designer). Without a branding resource per client the pages
        // do not render, so every client that signs users in through managed login needs one.
        this.domain = userPool.addDomain(
                "Domain",
                UserPoolDomainOptions.builder()
                        .cognitoDomain(CognitoDomainOptions.builder()
                                .domainPrefix(cfg.prefix() + "-" + EnvConfig.OWNER)
                                .build())
                        .managedLoginVersion(ManagedLoginVersion.NEWER_MANAGED_LOGIN)
                        .build());

        UserPoolResourceServer resourceServer = userPool.addResourceServer(
                "ApiResourceServer",
                UserPoolResourceServerOptions.builder()
                        .identifier("jars-api")
                        .userPoolResourceServerName(cfg.name("api"))
                        .scopes(List.of(
                                new ResourceServerScope(ResourceServerScopeProps.builder()
                                        .scopeName("read")
                                        .scopeDescription("Read the signed-in user's own data")
                                        .build()),
                                new ResourceServerScope(ResourceServerScopeProps.builder()
                                        .scopeName("write")
                                        .scopeDescription("Create or change the signed-in user's own data")
                                        .build())))
                        .build());

        // Client for the automated smoke test: public (no secret), admin password flow only. The test
        // script creates a throw-away user with the caller's IAM credentials and logs in as that user.
        this.smokeClient = userPool.addClient(
                "SmokeClient",
                UserPoolClientOptions.builder()
                        .userPoolClientName(cfg.name("smoke"))
                        .generateSecret(false)
                        .authFlows(AuthFlow.builder().adminUserPassword(true).build())
                        .disableOAuth(true)
                        .preventUserExistenceErrors(true)
                        .enableTokenRevocation(true)
                        .accessTokenValidity(Duration.minutes(60))
                        .idTokenValidity(Duration.minutes(60))
                        .refreshTokenValidity(Duration.days(30))
                        .supportedIdentityProviders(List.of(UserPoolClientIdentityProvider.COGNITO))
                        .build());
        smokeClient.getNode().addDependency(resourceServer);

        Validations.of(userPool)
                .acknowledge(
                        Acknowledgment.builder()
                                .id("AwsSolutions-COG2")
                                .reason("MFA is off by design for this consumer demo app: users sign themselves up and"
                                        + " a second factor needs SMS (paid) or a TOTP enrolment step that the demo"
                                        + " flow does not include. Prod would require MFA.")
                                .build(),
                        Acknowledgment.builder()
                                .id("AwsSolutions-COG8")
                                .reason("The Plus feature plan (threat protection) is billed per monthly active user;"
                                        + " the pool stays on Essentials to remain inside the free tier"
                                        + " (cost-and-budget.md). Prod would use Plus.")
                                .build());

        CfnOutput.Builder.create(this, "UserPoolId")
                .description("Cognito user pool id")
                .value(userPool.getUserPoolId())
                .build();
        CfnOutput.Builder.create(this, "SmokeClientId")
                .description("App client for the automated smoke test (public, ADMIN_USER_PASSWORD_AUTH)")
                .value(smokeClient.getUserPoolClientId())
                .build();
        CfnOutput.Builder.create(this, "IssuerUri")
                .description("JARS_ISSUER_URI of jars-api (spring.security.oauth2.resourceserver.jwt.issuer-uri)")
                .value("https://cognito-idp." + getRegion() + ".amazonaws.com/" + userPool.getUserPoolId())
                .build();
        CfnOutput.Builder.create(this, "ManagedLoginBaseUrl")
                .description("Managed login and OAuth2 endpoints base URL")
                .value("https://" + domain.getDomainName() + ".auth." + getRegion() + ".amazoncognito.com")
                .build();
    }

    public UserPool userPool() {
        return userPool;
    }

    public UserPoolDomain domain() {
        return domain;
    }

    public UserPoolClient smokeClient() {
        return smokeClient;
    }
}
