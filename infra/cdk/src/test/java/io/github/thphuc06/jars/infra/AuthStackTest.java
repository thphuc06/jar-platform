package io.github.thphuc06.jars.infra;

import static io.github.thphuc06.jars.infra.T.like;
import static io.github.thphuc06.jars.infra.T.map;
import static io.github.thphuc06.jars.infra.T.properties;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.CfnElement;
import software.amazon.awscdk.assertions.Template;

class AuthStackTest {

    private final Template template = InfraFixture.aurora().auth();

    @Test
    void userPoolUsesEssentialsPlanEmailSignInAndSelfSignUp() {
        template.hasResourceProperties(
                "AWS::Cognito::UserPool",
                like(
                        "UserPoolName", "jars-dev-users",
                        "UserPoolTier", "ESSENTIALS",
                        "UsernameAttributes", List.of("email"),
                        "AutoVerifiedAttributes", List.of("email"),
                        "AdminCreateUserConfig", like("AllowAdminCreateUserOnly", false),
                        "MfaConfiguration", "OFF"));
    }

    @Test
    void githubDeployRoleMayManageSmokeTestUsersOfThisPoolOnly() {
        template.hasResourceProperties(
                "AWS::IAM::Policy",
                like(
                        "PolicyDocument",
                        like(
                                "Statement",
                                List.of(like(
                                        "Sid", "SmokeTestUsers",
                                        "Effect", "Allow",
                                        "Action",
                                                List.of(
                                                        // CDK writes the actions in alphabetical order
                                                        "cognito-idp:AdminCreateUser",
                                                        "cognito-idp:AdminDeleteUser",
                                                        "cognito-idp:AdminInitiateAuth",
                                                        "cognito-idp:AdminSetUserPassword"),
                                        "Resource", map("Fn::GetAtt", List.of(userPoolLogicalId(), "Arn"))))),
                        "Roles", List.of("jars-dev-github-deploy")));
    }

    private static String userPoolLogicalId() {
        AuthStack auth = InfraFixture.aurora().stacks().auth();
        return auth.getLogicalId((CfnElement) auth.userPool().getNode().getDefaultChild());
    }

    @Test
    void authStackIsDeployedAfterFoundationBecauseItExtendsTheDeployRole() {
        assertThat(InfraFixture.aurora().stacks().auth().getDependencies())
                .contains(InfraFixture.aurora().stacks().foundation());
    }

    @Test
    void userPoolSendsMailThroughTheDefaultCognitoSender() {
        template.hasResourceProperties(
                "AWS::Cognito::UserPool", like("EmailConfiguration", like("EmailSendingAccount", "COGNITO_DEFAULT")));
    }

    @Test
    void userPoolIsRetainedSoUsersSurviveStackDeletion() {
        template.hasResource(
                "AWS::Cognito::UserPool", like("DeletionPolicy", "Retain", "UpdateReplacePolicy", "Retain"));
    }

    @Test
    void managedLoginDomainUsesTheDocumentedPrefix() {
        template.hasResourceProperties(
                "AWS::Cognito::UserPoolDomain", like("Domain", "jars-dev-thphuc06", "ManagedLoginVersion", 2));
    }

    @Test
    void resourceServerExposesReadAndWriteScopes() {
        template.hasResourceProperties(
                "AWS::Cognito::UserPoolResourceServer",
                like(
                        "Identifier", "jars-api",
                        "Scopes",
                                List.of(
                                        like("ScopeName", "read"), like("ScopeName", "write"))));
    }

    @Test
    void smokeClientIsPublicAndOnlyAllowsAdminPasswordAndRefreshFlows() {
        template.hasResourceProperties(
                "AWS::Cognito::UserPoolClient",
                like(
                        "ClientName", "jars-dev-smoke",
                        "GenerateSecret", false,
                        "ExplicitAuthFlows", List.of("ALLOW_ADMIN_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"),
                        "AllowedOAuthFlowsUserPoolClient", false));
    }

    @Test
    void thereIsNoSpaClientInThisStack() {
        // it needs the CloudFront domain and therefore lives in JarsApp
        List<Map<String, Object>> clients = properties(template, "AWS::Cognito::UserPoolClient");
        assertThat(clients).hasSize(1);
        assertThat(clients.get(0)).containsEntry("ClientName", "jars-dev-smoke");
    }

    @Test
    void outputsGiveTheSmokeTestWhatItNeeds() {
        template.hasOutput("UserPoolId", map());
        template.hasOutput("SmokeClientId", map());
        template.hasOutput("IssuerUri", map());
        template.hasOutput("ManagedLoginBaseUrl", map());
    }
}
