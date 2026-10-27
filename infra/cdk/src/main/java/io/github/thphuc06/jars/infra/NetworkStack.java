package io.github.thphuc06.jars.infra;

import java.util.List;
import software.amazon.awscdk.Acknowledgment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.services.ec2.IpAddresses;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.constructs.Construct;

/**
 * VPC without NAT gateways (ADR-0013): ECS tasks sit in public subnets with a public IP and reach AWS APIs directly;
 * nothing can reach them except the ALB, because of the security groups below. The ALB and the database live in
 * subnets with no route to the internet at all. VPC, subnets and security groups cost nothing per hour.
 */
public class NetworkStack extends Stack {

    static final String CIDR = "10.20.0.0/16";

    private final Vpc vpc;
    private final SecurityGroup albSecurityGroup;
    private final SecurityGroup appSecurityGroup;
    private final SecurityGroup dbSecurityGroup;

    public NetworkStack(Construct scope, String id, StackProps props, EnvConfig config) {
        super(scope, id, props);

        vpc = Vpc.Builder.create(this, "Vpc")
                .vpcName(config.name("vpc"))
                .ipAddresses(IpAddresses.cidr(CIDR))
                // explicit AZs instead of maxAzs(2): no AZ lookup at synth time, so no credentials are needed and
                // no cdk.context.json (whose keys contain the account id) has to be committed to a public repo
                .availabilityZones(List.of(config.region() + "a", config.region() + "b"))
                // a NAT gateway is ~$43/month + data processing in Singapore (ADR-0013); tasks use their own public IP
                .natGateways(0)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder()
                                .name("public")
                                .subnetType(SubnetType.PUBLIC)
                                .cidrMask(24)
                                // only resources that ASK for a public IP get one (ECS: assignPublicIp)
                                .mapPublicIpOnLaunch(false)
                                .build(),
                        // PRIVATE_WITH_EGRESS would require a NAT gateway; both private tiers are isolated
                        SubnetConfiguration.builder()
                                .name("lb")
                                .subnetType(SubnetType.PRIVATE_ISOLATED)
                                .cidrMask(24)
                                .build(),
                        SubnetConfiguration.builder()
                                .name("data")
                                .subnetType(SubnetType.PRIVATE_ISOLATED)
                                .cidrMask(24)
                                .build()))
                // the default security group allows all traffic between its members: empty it (CIS benchmark).
                // Written down here, not left to the cdk.json feature flag, which unit tests do not load.
                .restrictDefaultSecurityGroup(true)
                .build();

        albSecurityGroup = SecurityGroup.Builder.create(this, "AlbSecurityGroup")
                .vpc(vpc)
                .securityGroupName(config.name("alb"))
                .description("Internal ALB: inbound only from CloudFront (rule added with the VPC origin, P3.4)")
                .allowAllOutbound(false)
                .build();
        appSecurityGroup = SecurityGroup.Builder.create(this, "AppSecurityGroup")
                .vpc(vpc)
                .securityGroupName(config.name("app"))
                .description("jars-api tasks: inbound only from the ALB")
                .allowAllOutbound(false)
                .build();
        dbSecurityGroup = SecurityGroup.Builder.create(this, "DbSecurityGroup")
                .vpc(vpc)
                .securityGroupName(config.name("db"))
                .description("Aurora PostgreSQL: inbound only from jars-api")
                .allowAllOutbound(false)
                .build();

        albSecurityGroup.addEgressRule(appSecurityGroup, Port.tcp(8080), "to jars-api");
        appSecurityGroup.addIngressRule(albSecurityGroup, Port.tcp(8080), "from the ALB");
        // HTTPS out to AWS APIs (ECR, Secrets Manager, Bedrock, SQS), Cognito JWKS and userInfo
        appSecurityGroup.addEgressRule(Peer.anyIpv4(), Port.tcp(443), "HTTPS to AWS APIs and Cognito");
        appSecurityGroup.addEgressRule(dbSecurityGroup, Port.tcp(5432), "to Aurora");
        dbSecurityGroup.addIngressRule(appSecurityGroup, Port.tcp(5432), "from jars-api");

        Validations.of(vpc)
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions::AwsSolutions-VPC7")
                        .reason("Dev VPC with one task: flow logs to CloudWatch are paid per GB ingested, and"
                                + " internet scanners hitting the tasks' public IPs would fill them with REJECT"
                                + " records. Reachability is checked with security-group tests and VPC Reachability"
                                + " Analyzer when needed. Revisit in the Well-Architected review (P3.9).")
                        .build());
    }

    public Vpc vpc() {
        return vpc;
    }

    public SecurityGroup albSecurityGroup() {
        return albSecurityGroup;
    }

    public SecurityGroup appSecurityGroup() {
        return appSecurityGroup;
    }

    public SecurityGroup dbSecurityGroup() {
        return dbSecurityGroup;
    }
}
