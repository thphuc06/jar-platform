package io.github.thphuc06.jars.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.s3.Bucket;

class AuthStackTest {

    static final EnvConfig CONFIG =
            new EnvConfig("test", "111111111111", "ap-southeast-1", "jars-test-prefix", "http://localhost:5173");

    private static AuthStack newStack(App app) {
        return new AuthStack(
                app,
                "JarsAuth",
                StackProps.builder().env(CONFIG.awsEnvironment()).build(),
                CONFIG);
    }

    @Test
    void userPool_isEssentialsWithOptionalTotpAndIsRetained() {
        Template template = Template.fromStack(newStack(new App()));

        template.hasResource(
                "AWS::Cognito::UserPool",
                Map.of(
                        "DeletionPolicy",
                        "Retain",
                        "Properties",
                        Map.of(
                                "UserPoolName", "jars-test-users",
                                "UserPoolTier", "ESSENTIALS",
                                "MfaConfiguration", "OPTIONAL")));
    }

    @Test
    void domain_usesNewManagedLogin() {
        Template.fromStack(newStack(new App()))
                .hasResourceProperties(
                        "AWS::Cognito::UserPoolDomain", Map.of("Domain", "jars-test-prefix", "ManagedLoginVersion", 2));
    }

    @Test
    void resourceServer_exposesReadAndWriteScopes() {
        Template.fromStack(newStack(new App()))
                .hasResourceProperties(
                        "AWS::Cognito::UserPoolResourceServer",
                        Map.of(
                                "Identifier",
                                "jars-api",
                                "Scopes",
                                Match.arrayWith(List.of(
                                        Match.objectLike(Map.of("ScopeName", "read")),
                                        Match.objectLike(Map.of("ScopeName", "write"))))));
    }

    @Test
    void spaClient_isPublicCodeFlowOnlyWithExplicitAuthFlows() {
        Template.fromStack(newStack(new App()))
                .hasResourceProperties(
                        "AWS::Cognito::UserPoolClient",
                        Map.of(
                                "GenerateSecret",
                                false,
                                "AllowedOAuthFlows",
                                List.of("code"), // no implicit flow
                                // regression guard: omitting this list makes Cognito enable CUSTOM_AUTH by default
                                "ExplicitAuthFlows",
                                List.of("ALLOW_USER_SRP_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"),
                                "CallbackURLs",
                                List.of("http://localhost:5173/auth/callback"),
                                "PreventUserExistenceErrors",
                                "ENABLED",
                                "EnableTokenRevocation",
                                true));
    }

    @Test
    void cdkNag_passesWithOnlyTheDocumentedAcknowledgements() {
        App app = new App();
        newStack(app);
        Validations.of(app).addPlugins(new AwsSolutionsChecks(app));

        assertThatCode(app::synth).doesNotThrowAnyException();
    }

    /** Guards the guard: an obviously non-compliant resource must make synth fail. */
    @Test
    void cdkNag_failsSynthForNonCompliantResource() {
        App app = new App();
        Stack stack = new Stack(app, "Insecure", StackProps.builder().build());
        new Bucket(stack, "PlainBucket"); // no SSL enforcement, no access logs
        Validations.of(app).addPlugins(new AwsSolutionsChecks(app));

        assertThatThrownBy(app::synth).isInstanceOf(RuntimeException.class);
    }

    @Test
    void commonTags_areAppliedToEveryTaggableResource() {
        App app = new App();
        AuthStack stack = newStack(app);
        JarsInfraApp.applyCommonTags(app, CONFIG);

        Map<String, Map<String, Object>> pools = Template.fromStack(stack).findResources("AWS::Cognito::UserPool");
        assertThat(pools.values().iterator().next().toString())
                .contains("Project=jars")
                .contains("Env=test")
                .contains("ManagedBy=cdk");
    }
}
