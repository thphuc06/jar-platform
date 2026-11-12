package io.github.thphuc06.jars.infra;

import static io.github.thphuc06.jars.infra.T.like;
import static io.github.thphuc06.jars.infra.T.properties;
import static io.github.thphuc06.jars.infra.T.values;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.assertions.Template;

class NetworkStackTest {

    private final Template template = InfraFixture.aurora().network();

    @Test
    void vpcUsesTheDocumentedCidr() {
        template.hasResourceProperties("AWS::EC2::VPC", like("CidrBlock", "10.20.0.0/16", "EnableDnsHostnames", true));
    }

    @Test
    void subnetsFollowTheDocumentedLayout() {
        assertThat(values(template, "AWS::EC2::Subnet", "CidrBlock"))
                .containsExactlyInAnyOrder(
                        "10.20.0.0/24",
                        "10.20.1.0/24",
                        "10.20.10.0/24",
                        "10.20.11.0/24",
                        "10.20.20.0/24",
                        "10.20.21.0/24");
    }

    @Test
    void twoAvailabilityZonesAreUsed() {
        assertThat(values(template, "AWS::EC2::Subnet", "AvailabilityZone"))
                .containsOnly("ap-southeast-1a", "ap-southeast-1b");
    }

    @Test
    void albSecurityGroupAcceptsPort80OnlyFromTheCloudFrontPrefixList() {
        // the group itself carries no inline ingress; its only ingress rule is the prefix list rule
        List<Map<String, Object>> onAlb = properties(template, "AWS::EC2::SecurityGroupIngress").stream()
                .filter(p -> Integer.valueOf(80).equals(p.get("FromPort")))
                .collect(Collectors.toList());
        assertThat(onAlb).hasSize(1);
        assertThat(onAlb.get(0))
                .containsEntry("SourcePrefixListId", "pl-31a34658")
                .containsEntry("IpProtocol", "tcp")
                .containsEntry("ToPort", 80)
                .doesNotContainKeys("CidrIp", "CidrIpv6", "SourceSecurityGroupId");
        assertThat(properties(template, "AWS::EC2::SecurityGroup").stream()
                        .filter(p -> "jars-dev-alb".equals(p.get("GroupName"))))
                .allSatisfy(p -> assertThat(p).doesNotContainKey("SecurityGroupIngress"));
    }

    @Test
    void appSecurityGroupAcceptsPort8080OnlyFromTheAlbSecurityGroup() {
        List<Map<String, Object>> on8080 = ingressOnPort(8080);
        assertThat(on8080).hasSize(1);
        assertThat(on8080.get(0)).containsKey("SourceSecurityGroupId").doesNotContainKeys("CidrIp", "CidrIpv6");
    }

    @Test
    void dbSecurityGroupAcceptsPort5432OnlyFromTheAppSecurityGroup() {
        List<Map<String, Object>> on5432 = ingressOnPort(5432);
        assertThat(on5432).hasSize(1);
        assertThat(on5432.get(0)).containsKey("SourceSecurityGroupId").doesNotContainKeys("CidrIp", "CidrIpv6");
    }

    @Test
    void appSecurityGroupMayOnlyCallHttpsAnywhereAndTheDatabase() {
        template.hasResourceProperties(
                "AWS::EC2::SecurityGroup",
                like(
                        "GroupName",
                        "jars-dev-app",
                        "SecurityGroupEgress",
                        List.of(like("CidrIp", "0.0.0.0/0", "IpProtocol", "tcp", "FromPort", 443, "ToPort", 443))));
        template.hasResourceProperties(
                "AWS::EC2::SecurityGroupEgress", like("FromPort", 5432, "ToPort", 5432, "Description", "to Aurora"));
    }

    private List<Map<String, Object>> ingressOnPort(int port) {
        return properties(template, "AWS::EC2::SecurityGroupIngress").stream()
                .filter(p -> Integer.valueOf(port).equals(p.get("FromPort")))
                .collect(Collectors.toList());
    }
}
