package io.github.thphuc06.jars.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class NetworkStackTest {

    private static Template template() {
        NetworkStack stack = new NetworkStack(
                new App(),
                "JarsNetwork",
                StackProps.builder().env(TestConfig.CONFIG.awsEnvironment()).build(),
                TestConfig.CONFIG);
        return Template.fromStack(stack);
    }

    @Test
    void noNatGateway_andNoElasticIp() {
        Template template = template();

        template.resourceCountIs("AWS::EC2::NatGateway", 0); // ADR-0013: ~$43/month each
        template.resourceCountIs("AWS::EC2::EIP", 0);
        template.resourceCountIs("AWS::EC2::InternetGateway", 1); // CloudFront VPC origins need one
    }

    @Test
    void sixSubnetsInTwoAzs_andOnlyThePublicOnesRouteToTheInternet() {
        Template template = template();

        TreeSet<String> cidrs = new TreeSet<>();
        template.findResources("AWS::EC2::Subnet")
                .values()
                .forEach(subnet -> cidrs.add(
                        ((Map<?, ?>) subnet.get("Properties")).get("CidrBlock").toString()));
        assertThat(cidrs)
                .containsExactly(
                        "10.20.0.0/24", "10.20.1.0/24", // public: ECS tasks
                        "10.20.2.0/24", "10.20.3.0/24", // lb: internal ALB, CloudFront VPC origin ENIs
                        "10.20.4.0/24", "10.20.5.0/24"); // data: Aurora
        template.resourceCountIs("AWS::EC2::Route", 2); // 0.0.0.0/0 -> IGW, one per public subnet
        template.allResourcesProperties("AWS::EC2::Subnet", Map.of("MapPublicIpOnLaunch", false));
    }

    @Test
    void defaultSecurityGroupIsEmptied() {
        template().resourceCountIs("Custom::VpcRestrictDefaultSG", 1);
    }

    @Test
    void nothingIsOpenToTheInternetInbound() {
        assertThat(template().toJSON().toString()).doesNotContain("CidrIp=0.0.0.0/0, IpProtocol=tcp, FromPort=8080");
        Template template = template();
        template.findResources("AWS::EC2::SecurityGroupIngress")
                .values()
                .forEach(rule -> assertThat(((Map<?, ?>) rule.get("Properties")).containsKey("SourceSecurityGroupId"))
                        .as("every inbound rule names a source security group, never a CIDR")
                        .isTrue());
        template.findResources("AWS::EC2::SecurityGroup")
                .values()
                .forEach(sg -> assertThat(
                                String.valueOf(((Map<?, ?>) sg.get("Properties")).get("SecurityGroupIngress")))
                        .doesNotContain("0.0.0.0/0"));
    }

    @Test
    void tiersOnlyTalkToTheNextTier() {
        Template template = template();

        template.hasResourceProperties(
                "AWS::EC2::SecurityGroupIngress",
                Map.of("IpProtocol", "tcp", "FromPort", 8080, "ToPort", 8080, "Description", "from the ALB"));
        template.hasResourceProperties(
                "AWS::EC2::SecurityGroupIngress",
                Map.of("IpProtocol", "tcp", "FromPort", 5432, "ToPort", 5432, "Description", "from jars-api"));
        // the app may leave only on 443 (AWS APIs) and 5432 (to the database security group)
        template.hasResourceProperties(
                "AWS::EC2::SecurityGroup",
                Map.of(
                        "GroupName",
                        "jars-test-app",
                        "SecurityGroupEgress",
                        List.of(Map.of(
                                "CidrIp", "0.0.0.0/0",
                                "IpProtocol", "tcp",
                                "FromPort", 443,
                                "ToPort", 443,
                                "Description", "HTTPS to AWS APIs and Cognito"))));
        // the database starts no connection to anything
        template.hasResourceProperties(
                "AWS::EC2::SecurityGroup",
                Map.of(
                        "GroupName",
                        "jars-test-db",
                        "SecurityGroupEgress",
                        List.of(Match.objectLike(Map.of("CidrIp", "255.255.255.255/32")))));
    }

    @Test
    void cdkNag_passesWithOnlyTheDocumentedAcknowledgements() {
        App app = new App();
        new NetworkStack(
                app,
                "JarsNetwork",
                StackProps.builder().env(TestConfig.CONFIG.awsEnvironment()).build(),
                TestConfig.CONFIG);
        Validations.of(app).addPlugins(new AwsSolutionsChecks(app));

        assertThatCode(app::synth).doesNotThrowAnyException();
    }
}
