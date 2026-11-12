package io.github.thphuc06.jars.infra;

import io.github.thphuc06.jars.infra.config.EnvConfig;
import java.util.List;
import software.amazon.awscdk.Acknowledgment;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.services.ec2.CfnSubnet;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.ISubnet;
import software.amazon.awscdk.services.ec2.IpAddresses;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SecurityGroupImportOptions;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.constructs.Construct;

/**
 * JarsNetwork: VPC and security groups (docs/02-architecture/aws-infrastructure.md section 3).
 *
 * <p>There is deliberately no NAT gateway (ADR-0013): tasks live in public subnets with a public IP
 * and are locked down by security groups, the ALB and the database live in subnets without any route
 * to the internet.
 *
 * <p>This stack owns every security group <em>rule</em>. Other stacks receive the groups as immutable
 * references (see {@link #immutable}), so a consumer can never add a rule from another stack and
 * every ingress rule of the platform can be reviewed in this one file.
 */
public final class NetworkStack extends Stack {

    public static final String SUBNET_GROUP_PUBLIC = "public";
    public static final String SUBNET_GROUP_LB = "lb";
    public static final String SUBNET_GROUP_DATA = "data";

    public static final int APP_PORT = 8080;
    public static final int DB_PORT = 5432;

    private final Vpc vpc;
    private final SecurityGroup albSecurityGroup;
    private final SecurityGroup appSecurityGroup;
    private final SecurityGroup dbSecurityGroup;

    public NetworkStack(Construct scope, String id, StackProps props, EnvConfig cfg) {
        super(scope, id, props);

        // Explicit AZs: no context lookup, so `cdk synth` needs no AWS credentials (CI has none)
        List<String> azs = List.of(cfg.region() + "a", cfg.region() + "b");

        this.vpc = Vpc.Builder.create(this, "Vpc")
                .vpcName(cfg.name("vpc"))
                .ipAddresses(IpAddresses.cidr("10.20.0.0/16"))
                .availabilityZones(azs)
                // the CDK default is one NAT gateway per AZ, about 43 USD a month each (ADR-0013)
                .natGateways(0)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder()
                                .name(SUBNET_GROUP_PUBLIC)
                                .subnetType(SubnetType.PUBLIC)
                                .cidrMask(24)
                                // ECS assigns the public IP to the task ENI explicitly
                                .mapPublicIpOnLaunch(false)
                                .build(),
                        // PRIVATE_WITH_EGRESS requires NAT in CDK, so both groups without NAT are isolated
                        SubnetConfiguration.builder()
                                .name(SUBNET_GROUP_LB)
                                .subnetType(SubnetType.PRIVATE_ISOLATED)
                                .cidrMask(24)
                                .build(),
                        SubnetConfiguration.builder()
                                .name(SUBNET_GROUP_DATA)
                                .subnetType(SubnetType.PRIVATE_ISOLATED)
                                .cidrMask(24)
                                .build()))
                .build();
        pinSubnetCidrs();

        this.albSecurityGroup = SecurityGroup.Builder.create(this, "AlbSecurityGroup")
                .vpc(vpc)
                .securityGroupName(cfg.name("alb"))
                .description("Internal ALB: inbound only from CloudFront (VPC origin)")
                .allowAllOutbound(false)
                .build();
        this.appSecurityGroup = SecurityGroup.Builder.create(this, "AppSecurityGroup")
                .vpc(vpc)
                .securityGroupName(cfg.name("app"))
                .description("jars-api tasks: inbound only from the ALB")
                .allowAllOutbound(false)
                .build();
        this.dbSecurityGroup = SecurityGroup.Builder.create(this, "DbSecurityGroup")
                .vpc(vpc)
                .securityGroupName(cfg.name("db"))
                .description("Aurora PostgreSQL: inbound only from jars-api")
                .allowAllOutbound(false)
                .build();

        // sg-alb: HTTP from the CloudFront origin-facing managed prefix list, nothing else
        albSecurityGroup.addIngressRule(
                Peer.prefixList(cfg.cloudFrontPrefixListId()),
                Port.tcp(80),
                "HTTP from CloudFront origin-facing prefix list (VPC origin)");
        albSecurityGroup.addEgressRule(appSecurityGroup, Port.tcp(APP_PORT), "to jars-api");

        // sg-app: 8080 only from sg-alb; out to AWS APIs / Cognito JWKS (443) and Aurora (5432)
        appSecurityGroup.addIngressRule(albSecurityGroup, Port.tcp(APP_PORT), "from the ALB");
        appSecurityGroup.addEgressRule(
                Peer.anyIpv4(), Port.tcp(443), "HTTPS to AWS APIs, ECR and Cognito (no NAT, public IP)");
        appSecurityGroup.addEgressRule(dbSecurityGroup, Port.tcp(DB_PORT), "to Aurora");

        // sg-db: 5432 only from sg-app, no egress
        dbSecurityGroup.addIngressRule(appSecurityGroup, Port.tcp(DB_PORT), "from jars-api");

        Validations.of(vpc)
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions-VPC7")
                        .reason("VPC flow logs cost CloudWatch ingestion per GB and nothing consumes them in dev."
                                + " Security group rules are the control (ADR-0013); flow logs come with the prod"
                                + " upgrade path.")
                        .build());

        CfnOutput.Builder.create(this, "VpcId").value(vpc.getVpcId()).build();
    }

    /**
     * The CDK allocates subnet CIDRs one after the other (10.20.0.0, .1.0, .2.0 ...), the documented
     * layout is 10.20.0.x public, 10.20.10.x lb and 10.20.20.x data (aws-infrastructure.md section 3),
     * so the lb and data CIDRs are pinned on the CloudFormation resources.
     */
    private void pinSubnetCidrs() {
        pin(SUBNET_GROUP_LB, List.of("10.20.10.0/24", "10.20.11.0/24"));
        pin(SUBNET_GROUP_DATA, List.of("10.20.20.0/24", "10.20.21.0/24"));
    }

    private void pin(String subnetGroupName, List<String> cidrs) {
        List<ISubnet> subnets = vpc.selectSubnets(
                        SubnetSelection.builder().subnetGroupName(subnetGroupName).build())
                .getSubnets();
        for (int i = 0; i < cidrs.size(); i++) {
            CfnSubnet cfnSubnet = (CfnSubnet) subnets.get(i).getNode().getDefaultChild();
            cfnSubnet.setCidrBlock(cidrs.get(i));
        }
    }

    /**
     * Imports a security group of this stack as an immutable reference: calls such as
     * {@code connections.allowFrom(...)} on the result silently add nothing, so rules stay here.
     */
    public static ISecurityGroup immutable(Construct scope, String id, SecurityGroup securityGroup) {
        return SecurityGroup.fromSecurityGroupId(
                scope,
                id,
                securityGroup.getSecurityGroupId(),
                SecurityGroupImportOptions.builder().mutable(false).build());
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
