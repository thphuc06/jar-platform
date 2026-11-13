package io.github.thphuc06.jars.infra;

import io.github.thphuc06.jars.infra.config.EnvConfig;
import io.github.thphuc06.jars.infra.config.EnvConfig.DbMode;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Acknowledgment;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.DefaultStackSynthesizer;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.services.cloudfront.AllowedMethods;
import software.amazon.awscdk.services.cloudfront.BehaviorOptions;
import software.amazon.awscdk.services.cloudfront.CachePolicy;
import software.amazon.awscdk.services.cloudfront.Distribution;
import software.amazon.awscdk.services.cloudfront.FunctionAssociation;
import software.amazon.awscdk.services.cloudfront.FunctionCode;
import software.amazon.awscdk.services.cloudfront.FunctionEventType;
import software.amazon.awscdk.services.cloudfront.FunctionRuntime;
import software.amazon.awscdk.services.cloudfront.HeadersFrameOption;
import software.amazon.awscdk.services.cloudfront.HeadersReferrerPolicy;
import software.amazon.awscdk.services.cloudfront.HttpVersion;
import software.amazon.awscdk.services.cloudfront.OriginProtocolPolicy;
import software.amazon.awscdk.services.cloudfront.OriginRequestPolicy;
import software.amazon.awscdk.services.cloudfront.PriceClass;
import software.amazon.awscdk.services.cloudfront.ResponseHeadersContentSecurityPolicy;
import software.amazon.awscdk.services.cloudfront.ResponseHeadersContentTypeOptions;
import software.amazon.awscdk.services.cloudfront.ResponseHeadersFrameOptions;
import software.amazon.awscdk.services.cloudfront.ResponseHeadersPolicy;
import software.amazon.awscdk.services.cloudfront.ResponseHeadersReferrerPolicy;
import software.amazon.awscdk.services.cloudfront.ResponseHeadersStrictTransportSecurity;
import software.amazon.awscdk.services.cloudfront.ResponseSecurityHeadersBehavior;
import software.amazon.awscdk.services.cloudfront.ViewerProtocolPolicy;
import software.amazon.awscdk.services.cloudfront.origins.S3BucketOrigin;
import software.amazon.awscdk.services.cloudfront.origins.VpcOrigin;
import software.amazon.awscdk.services.cloudfront.origins.VpcOriginWithEndpointProps;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.Metric;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
import software.amazon.awscdk.services.cognito.AuthFlow;
import software.amazon.awscdk.services.cognito.CfnManagedLoginBranding;
import software.amazon.awscdk.services.cognito.OAuthFlows;
import software.amazon.awscdk.services.cognito.OAuthScope;
import software.amazon.awscdk.services.cognito.OAuthSettings;
import software.amazon.awscdk.services.cognito.UserPoolClient;
import software.amazon.awscdk.services.cognito.UserPoolClientIdentityProvider;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinition;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.CpuArchitecture;
import software.amazon.awscdk.services.ecs.DeploymentCircuitBreaker;
import software.amazon.awscdk.services.ecs.FargatePlatformVersion;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.LinuxParameters;
import software.amazon.awscdk.services.ecs.LoadBalancerTargetOptions;
import software.amazon.awscdk.services.ecs.LogDrivers;
import software.amazon.awscdk.services.ecs.MountPoint;
import software.amazon.awscdk.services.ecs.OperatingSystemFamily;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.PropagatedTagSource;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.RuntimePlatform;
import software.amazon.awscdk.services.ecs.Secret;
import software.amazon.awscdk.services.ecs.Volume;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationTargetsProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.BaseApplicationListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.CfnBucket;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.CacheControl;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.amazon.awscdk.services.sns.ITopic;
import software.amazon.awscdk.services.sns.Topic;
import software.constructs.Construct;
import software.constructs.IConstruct;

/**
 * JarsApp: everything that costs money while it runs and is destroyed when nobody uses the demo
 * (docs/02-architecture/aws-infrastructure.md sections 2 and 4, ADR-0006, ADR-0007).
 *
 * <p>ECS Fargate service jars-api behind an internal ALB, reached by CloudFront through a VPC
 * origin on {@code /api/*}; the SPA comes from a private S3 bucket through Origin Access Control on
 * everything else. Users and the database live in other stacks and survive a teardown of this one.
 */
public final class AppStack extends Stack {

    static final int HTTP_PORT = 80;
    static final String CONTAINER_NAME = "api";
    static final String HEALTH_CHECK_PATH = "/actuator/health/readiness";

    /**
     * Viewer-request function of the default (S3) behavior: any path without a file extension is a
     * client-side route of the SPA, so it is served as /index.html. This replaces the custom error
     * responses (403/404 to /index.html) of the first design: those apply to the whole distribution,
     * so every 404 or 403 of the API (for example the 404 that hides a resource of another user)
     * would have been rewritten into the SPA page with status 200.
     */
    private static final String SPA_REWRITE_FUNCTION =
            """
            function handler(event) {
              var request = event.request;
              var uri = request.uri;
              var lastSegment = uri.substring(uri.lastIndexOf('/') + 1);
              if (lastSegment.indexOf('.') === -1) {
                request.uri = '/index.html';
              }
              return request;
            }
            """;

    private final ApplicationLoadBalancer loadBalancer;
    private final Distribution distribution;
    private final UserPoolClient spaClient;
    private final FargateService service;

    public AppStack(
            Construct scope,
            String id,
            StackProps props,
            EnvConfig cfg,
            FoundationStack foundation,
            NetworkStack network,
            AuthStack auth,
            DataStack data,
            MessagingStack messaging) {
        super(scope, id, props);

        if (cfg.imageTag().isBlank()) {
            // JarsInfraApp does not create this stack without a tag; this guards other callers
            throw new IllegalArgumentException("Context 'imageTag' is required for JarsApp: pass -c imageTag=<git sha of"
                    + " the jars-api image in ECR> (docs/05-operations/deployment.md section 3).");
        }

        // rules of these groups are owned by JarsNetwork; here they are only references
        ISecurityGroup albSecurityGroup =
                NetworkStack.immutable(this, "AlbSecurityGroupRef", network.albSecurityGroup());
        ISecurityGroup appSecurityGroup =
                NetworkStack.immutable(this, "AppSecurityGroupRef", network.appSecurityGroup());
        ITopic alarmTopic = Topic.fromTopicArn(this, "AlarmTopic", foundation.alarmTopic().getTopicArn());

        // Construction order follows the dependencies, not the request path:
        // ALB -> CloudFront (VPC origin) -> SPA client (callback URL) -> task (client id) -> service -> listener
        this.loadBalancer = ApplicationLoadBalancer.Builder.create(this, "Alb")
                .loadBalancerName(cfg.name("api-alb"))
                .vpc(network.vpc())
                // internal: no public IP, reachable only through the CloudFront VPC origin
                .internetFacing(false)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetGroupName(NetworkStack.SUBNET_GROUP_LB)
                        .build())
                .securityGroup(albSecurityGroup)
                .dropInvalidHeaderFields(true)
                .deletionProtection(false)
                .build();

        Bucket webBucket = Bucket.Builder.create(this, "WebBucket")
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .enforceSsl(true)
                .encryption(BucketEncryption.S3_MANAGED)
                // the SPA is rebuilt from source: nothing to keep when the stack is destroyed
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build();
        this.distribution = distribution(cfg, webBucket);
        if (cfg.deployWeb()) {
            BucketDeployment.Builder.create(this, "WebDeployment")
                    .sources(List.of(Source.asset(cfg.webDistDir())))
                    .destinationBucket(webBucket)
                    .distribution(distribution)
                    .distributionPaths(List.of("/*"))
                    .cacheControl(List.of(CacheControl.fromString("public, max-age=0, must-revalidate")))
                    .build();
        }

        this.spaClient = spaClient(cfg, auth);
        this.service = apiService(cfg, foundation, network, auth, data, messaging, appSecurityGroup);

        loadBalancer
                .addListener(
                        "Http",
                        BaseApplicationListenerProps.builder()
                                .port(HTTP_PORT)
                                .protocol(ApplicationProtocol.HTTP)
                                // never open the listener to 0.0.0.0/0: sg-alb only trusts CloudFront
                                .open(false)
                                .build())
                .addTargets(
                        "Api",
                        AddApplicationTargetsProps.builder()
                                .port(NetworkStack.APP_PORT)
                                .protocol(ApplicationProtocol.HTTP)
                                .targets(List.of(service.loadBalancerTarget(LoadBalancerTargetOptions.builder()
                                        .containerName(CONTAINER_NAME)
                                        .containerPort(NetworkStack.APP_PORT)
                                        .build())))
                                .healthCheck(HealthCheck.builder()
                                        .path(HEALTH_CHECK_PATH)
                                        .interval(Duration.seconds(15))
                                        .healthyThresholdCount(2)
                                        .build())
                                // default 300 s makes every deploy wait; responses last under a second
                                .deregistrationDelay(Duration.seconds(30))
                                .build());

        if (cfg.dbMode() == DbMode.AURORA) {
            addAuroraNeverPausesAlarm(cfg, data, alarmTopic);
        }
        ApiObservability.addDashboardAndAlarms(
                this,
                cfg,
                loadBalancer,
                service,
                data.identifier(),
                alarmTopic);

        acknowledgeDocumentedDeviations(webBucket, service);
        if (cfg.deployWeb()) {
            acknowledgeBucketDeploymentProvider(webBucket);
        }

        CfnOutput.Builder.create(this, "CloudFrontDomain")
                .description("Domain of the distribution: https://<this>/ is the web app, /api/* the API")
                .value(distribution.getDistributionDomainName())
                .build();
        CfnOutput.Builder.create(this, "DistributionId")
                .value(distribution.getDistributionId())
                .build();
        CfnOutput.Builder.create(this, "SpaClientId")
                .description("Cognito app client of the SPA (Authorization Code + PKCE, no secret)")
                .value(spaClient.getUserPoolClientId())
                .build();
        CfnOutput.Builder.create(this, "WebBucketName")
                .value(webBucket.getBucketName())
                .build();
        CfnOutput.Builder.create(this, "EcsClusterName")
                .value(service.getCluster().getClusterName())
                .build();
        CfnOutput.Builder.create(this, "EcsServiceName")
                .value(service.getServiceName())
                .build();
    }

    /**
     * cdk-nag findings that are deliberate: each one is a documented deviation of the dev environment
     * (docs/02-architecture/well-architected-review.md section 9) with its own reason.
     */
    private void acknowledgeDocumentedDeviations(Bucket webBucket, FargateService apiService) {
        Validations.of(distribution)
                .acknowledge(
                        Acknowledgment.builder()
                                .id("AwsSolutions-CFR1")
                                .reason("No geo restriction: the demo has to be reachable by recruiters and interviewers"
                                        + " from any country, and the app holds no data that is licensed per region.")
                                .build(),
                        Acknowledgment.builder()
                                .id("AwsSolutions-CFR2")
                                .reason("No AWS WAF in dev: a web ACL plus rules is a fixed monthly cost that does not fit the"
                                        + " budget (cost-and-budget.md). It is the optional Phase 6 item of"
                                        + " aws-infrastructure.md section 2; until then the API checks every token and the"
                                        + " ALB is unreachable except through CloudFront.")
                                .build(),
                        Acknowledgment.builder()
                                .id("AwsSolutions-CFR3")
                                .reason("No CloudFront access logs in dev: they need a dedicated log bucket with ACLs and a"
                                        + " lifecycle for a demo with almost no traffic. Metrics, the app log group and"
                                        + " CloudTrail cover the dev need.")
                                .build(),
                        Acknowledgment.builder()
                                .id("AwsSolutions-CFR4")
                                .reason("The default *.cloudfront.net certificate cannot enforce a minimum TLS version (CloudFront"
                                        + " applies its own floor). A minimum protocol needs a custom domain and an ACM"
                                        + " certificate, which ADR-0007 keeps optional.")
                                .build());

        Validations.of(webBucket)
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions-S1")
                        .reason("No server access logs: the bucket is private, only the CloudFront origin access control"
                                + " can read it, and it holds the public build output of the SPA, nothing sensitive."
                                + " A log bucket would add cost and one more resource to empty on teardown.")
                        .build());

        Validations.of(loadBalancer)
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions-ELB2")
                        .reason("No ALB access logs in dev: they need an S3 bucket with a regional logging policy. The ALB is"
                                + " internal and only reachable from the CloudFront VPC origin, so CloudFront and the"
                                + " application log hold the request history.")
                        .build());

        Validations.of(apiService.getCluster())
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions-ECS4")
                        .reason("Container Insights is billed per task and per metric. The default ECS, ALB and"
                                + " Aurora metrics are free and enough for one task (cost-and-budget.md).")
                        .build());

        Validations.of(apiService.getTaskDefinition())
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions-ECS2")
                        .reason("The environment only holds non-secret configuration (profile name, JDBC URL without"
                                + " credentials, issuer URI, client ids, queue name, JVM flag). The database user and"
                                + " password are injected through ECS secrets from Secrets Manager.")
                        .build());

        Validations.of(apiService.getTaskDefinition().getExecutionRole())
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions-IAM5[Resource::*]")
                        .reason("ecr:GetAuthorizationToken has no resource-level permissions in IAM. Every other statement"
                                + " (image pull, log write, secret read) is scoped to one resource ARN.")
                        .build());
    }

    /**
     * BucketDeployment runs a CDK-managed singleton Lambda during `cdk deploy` (copy the SPA build into
     * the bucket, invalidate CloudFront). Its role and policy are generated by the construct, not written
     * by hand, so the findings about it are acknowledged one by one, with the exact finding ids.
     */
    private void acknowledgeBucketDeploymentProvider(Bucket webBucket) {
        CfnBucket cfnBucket = (CfnBucket) webBucket.getNode().getDefaultChild();
        String webBucketLogicalId = (String) resolve(getLogicalId(cfnBucket));
        String provider = "CDK-managed singleton Lambda of BucketDeployment (exists only with deployWeb=true): ";
        Acknowledgment[] acknowledgments = {
            ack("AwsSolutions-L1", provider + "its runtime is pinned by the aws-cdk-lib release and cannot be set from here."),
            ack(
                    "AwsSolutions-IAM4[Policy::arn:<AWS::Partition>:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole]",
                    provider + "AWSLambdaBasicExecutionRole (write its own logs) is the provider's default role."),
            ack(
                    "AwsSolutions-IAM5[Resource::*]",
                    provider + "CloudFront CreateInvalidation and GetInvalidation do not support resource-level permissions."),
            ack(
                    "AwsSolutions-IAM5[Resource::arn:aws:s3:::cdk-" + DefaultStackSynthesizer.DEFAULT_QUALIFIER
                            + "-assets-" + getAccount() + "-" + getRegion() + "/*]",
                    provider + "it reads the zipped build from the CDK bootstrap assets bucket of this account and region."),
            ack(
                    "AwsSolutions-IAM5[Resource::<" + webBucketLogicalId + ".Arn>/*]",
                    provider + "it writes and prunes the objects of the web bucket, and only of that bucket."),
            ack("AwsSolutions-IAM5[Action::s3:GetObject*]", provider + "read the build from the assets bucket."),
            ack("AwsSolutions-IAM5[Action::s3:GetBucket*]", provider + "read the bucket location of the assets bucket."),
            ack("AwsSolutions-IAM5[Action::s3:List*]", provider + "list objects to prune files that are gone from the build."),
            ack("AwsSolutions-IAM5[Action::s3:DeleteObject*]", provider + "prune files that are gone from the build."),
            ack("AwsSolutions-IAM5[Action::s3:Abort*]", provider + "abort multipart uploads of large files."),
        };
        for (IConstruct child : getNode().getChildren()) {
            if (child.getNode().getId().startsWith("Custom::CDKBucketDeployment")) {
                Validations.of(child).acknowledge(acknowledgments);
            }
        }
    }

    private static Acknowledgment ack(String id, String reason) {
        return Acknowledgment.builder().id(id).reason(reason).build();
    }

    /** One distribution: the SPA from S3 (OAC) by default, the API on /api/* through a VPC origin. */
    private Distribution distribution(EnvConfig cfg, Bucket webBucket) {
        ResponseHeadersPolicy securityHeaders = ResponseHeadersPolicy.Builder.create(this, "SecurityHeaders")
                .responseHeadersPolicyName(cfg.name("security-headers"))
                .comment("HSTS, nosniff, no framing (security.md section 6)")
                .securityHeadersBehavior(ResponseSecurityHeadersBehavior.builder()
                        .strictTransportSecurity(ResponseHeadersStrictTransportSecurity.builder()
                                .accessControlMaxAge(Duration.days(365))
                                .includeSubdomains(true)
                                .override(true)
                                .build())
                        .contentTypeOptions(ResponseHeadersContentTypeOptions.builder()
                                .override(true)
                                .build())
                        .frameOptions(ResponseHeadersFrameOptions.builder()
                                .frameOption(HeadersFrameOption.DENY)
                                .override(true)
                                .build())
                        .referrerPolicy(ResponseHeadersReferrerPolicy.builder()
                                .referrerPolicy(HeadersReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                                .override(true)
                                .build())
                        .contentSecurityPolicy(ResponseHeadersContentSecurityPolicy.builder()
                                .contentSecurityPolicy("frame-ancestors 'none'")
                                .override(true)
                                .build())
                        .build())
                .build();

        software.amazon.awscdk.services.cloudfront.Function spaRewrite =
                software.amazon.awscdk.services.cloudfront.Function.Builder.create(this, "SpaRewrite")
                        .functionName(cfg.name("spa-rewrite"))
                        .comment("Serve client-side routes of the SPA as /index.html")
                        .runtime(FunctionRuntime.JS_2_0)
                        .code(FunctionCode.fromInline(SPA_REWRITE_FUNCTION))
                        .build();

        return Distribution.Builder.create(this, "Distribution")
                .comment(cfg.name("web-and-api"))
                .defaultRootObject("index.html")
                .httpVersion(HttpVersion.HTTP2_AND_3)
                .priceClass(PriceClass.PRICE_CLASS_200)
                .defaultBehavior(BehaviorOptions.builder()
                        .origin(S3BucketOrigin.withOriginAccessControl(webBucket))
                        .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                        .allowedMethods(AllowedMethods.ALLOW_GET_HEAD)
                        .cachePolicy(CachePolicy.CACHING_OPTIMIZED)
                        .responseHeadersPolicy(securityHeaders)
                        .compress(true)
                        .functionAssociations(List.of(FunctionAssociation.builder()
                                .function(spaRewrite)
                                .eventType(FunctionEventType.VIEWER_REQUEST)
                                .build()))
                        .build())
                .additionalBehaviors(Map.of(
                        "/api/*",
                        BehaviorOptions.builder()
                                .origin(VpcOrigin.withApplicationLoadBalancer(
                                        loadBalancer,
                                        VpcOriginWithEndpointProps.builder()
                                                .vpcOriginName(cfg.name("api-alb"))
                                                // TLS ends at CloudFront; between CloudFront and the ALB the
                                                // traffic stays inside the AWS network (ADR-0007)
                                                .protocolPolicy(OriginProtocolPolicy.HTTP_ONLY)
                                                .httpPort(HTTP_PORT)
                                                .readTimeout(Duration.seconds(30))
                                                .build()))
                                .viewerProtocolPolicy(ViewerProtocolPolicy.HTTPS_ONLY)
                                .allowedMethods(AllowedMethods.ALLOW_ALL)
                                .cachePolicy(CachePolicy.CACHING_DISABLED)
                                // forwards every viewer header except Host, Authorization included
                                .originRequestPolicy(OriginRequestPolicy.ALL_VIEWER_EXCEPT_HOST_HEADER)
                                .responseHeadersPolicy(securityHeaders)
                                .build()))
                .build();
    }

    /** The SPA app client: it needs the CloudFront domain, which is why it is not in JarsAuth. */
    private UserPoolClient spaClient(EnvConfig cfg, AuthStack auth) {
        String origin = "https://" + distribution.getDistributionDomainName();
        UserPoolClient client = UserPoolClient.Builder.create(this, "SpaClient")
                .userPool(auth.userPool())
                .userPoolClientName(cfg.name("web"))
                // public client: a secret in a browser bundle is no secret (security.md section 1)
                .generateSecret(false)
                .authFlows(AuthFlow.builder().userSrp(true).build())
                .oAuth(OAuthSettings.builder()
                        .flows(OAuthFlows.builder().authorizationCodeGrant(true).build())
                        .scopes(List.of(OAuthScope.OPENID, OAuthScope.EMAIL, OAuthScope.PROFILE))
                        .callbackUrls(List.of(origin + "/auth/callback", "http://localhost:5173/auth/callback"))
                        .logoutUrls(List.of(origin + "/", "http://localhost:5173/"))
                        .build())
                .supportedIdentityProviders(List.of(UserPoolClientIdentityProvider.COGNITO))
                .preventUserExistenceErrors(true)
                .enableTokenRevocation(true)
                .accessTokenValidity(Duration.minutes(60))
                .idTokenValidity(Duration.minutes(60))
                .refreshTokenValidity(Duration.days(30))
                .build();
        // managed login (v2) renders no page for a client without a branding resource
        CfnManagedLoginBranding branding = CfnManagedLoginBranding.Builder.create(this, "SpaClientBranding")
                .userPoolId(auth.userPool().getUserPoolId())
                .clientId(client.getUserPoolClientId())
                .useCognitoProvidedValues(true)
                .build();
        branding.getNode().addDependency(auth.domain());
        return client;
    }

    private FargateService apiService(
            EnvConfig cfg,
            FoundationStack foundation,
            NetworkStack network,
            AuthStack auth,
            DataStack data,
            MessagingStack messaging,
            ISecurityGroup appSecurityGroup) {
        // the task role is deliberately almost empty for now
        Role taskRole = Role.Builder.create(this, "ApiTaskRole")
                .roleName(cfg.name("api-task"))
                .description("jars-api application code (least privilege, extended phase by phase)")
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .build();
        // GetQueueUrl is needed on top of the four documented actions: the app is configured with the
        // queue name and Spring Cloud AWS resolves the URL from it
        taskRole.addToPolicy(PolicyStatement.Builder.create()
                .sid("ConsumeJobsQueue")
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "sqs:ReceiveMessage",
                        "sqs:DeleteMessage",
                        "sqs:ChangeMessageVisibility",
                        "sqs:GetQueueAttributes",
                        "sqs:GetQueueUrl"))
                .resources(List.of(messaging.jobsQueue().getQueueArn()))
                .build());
        // Statement import (Phase 2 WP3). By name, not through the constructs, to add no cross-stack exports: the
        // bucket and the queue have fixed names. PutObject is what the presigned upload URL is signed with, so the
        // browser can write the one key of an import; GetObject/HEAD reads it back for the batch job.
        String statementsBucket = DataStack.statementsBucketName(cfg, getAccount());
        taskRole.addToPolicy(PolicyStatement.Builder.create()
                .sid("StatementUploads")
                .effect(Effect.ALLOW)
                .actions(List.of("s3:PutObject", "s3:GetObject"))
                .resources(List.of("arn:" + getPartition() + ":s3:::" + statementsBucket + "/uploads/*"))
                .build());
        taskRole.addToPolicy(PolicyStatement.Builder.create()
                .sid("ConsumeImportsQueue")
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "sqs:ReceiveMessage",
                        "sqs:DeleteMessage",
                        "sqs:ChangeMessageVisibility",
                        "sqs:GetQueueAttributes",
                        "sqs:GetQueueUrl"))
                .resources(List.of("arn:" + getPartition() + ":sqs:" + getRegion() + ":" + getAccount() + ":"
                        + MessagingStack.IMPORTS_QUEUE_NAME))
                .build());
        // Nightly ledger reconciliation. By name, like the imports queue: the queue has a fixed name, so JarsApp needs no
        // new export from JarsMessaging (StableExportsTest).
        taskRole.addToPolicy(PolicyStatement.Builder.create()
                .sid("ConsumeReconciliationQueue")
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "sqs:ReceiveMessage",
                        "sqs:DeleteMessage",
                        "sqs:ChangeMessageVisibility",
                        "sqs:GetQueueAttributes",
                        "sqs:GetQueueUrl"))
                .resources(List.of("arn:" + getPartition() + ":sqs:" + getRegion() + ":" + getAccount() + ":"
                        + MessagingStack.RECONCILIATION_QUEUE_NAME))
                .build());
        Validations.of(taskRole)
                .acknowledge(Acknowledgment.builder()
                        .id("AwsSolutions-IAM5[Resource::arn:aws:s3:::" + statementsBucket + "/uploads/*]")
                        .reason("Each import writes and reads its own key under uploads/<user>/<import>.csv; the keys"
                                + " are only known at run time. Nothing outside uploads/ is reachable.")
                        .build());
        Role executionRole = Role.Builder.create(this, "ApiExecutionRole")
                .roleName(cfg.name("api-exec"))
                .description("ECS agent: pull the image, write logs, read the DB secret")
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .build();

        LogGroup logGroup = LogGroup.Builder.create(this, "ApiLogGroup")
                .logGroupName("/jars/" + cfg.env() + "/api")
                .retention(RetentionDays.TWO_WEEKS)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Cluster cluster = Cluster.Builder.create(this, "Cluster")
                .clusterName(cfg.prefix())
                .vpc(network.vpc())
                .build();

        FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder.create(this, "ApiTaskDefinition")
                .family(cfg.name("api"))
                .cpu(512)
                .memoryLimitMiB(1024)
                .runtimePlatform(RuntimePlatform.builder()
                        .cpuArchitecture(CpuArchitecture.ARM64)
                        .operatingSystemFamily(OperatingSystemFamily.LINUX)
                        .build())
                .taskRole(taskRole)
                .executionRole(executionRole)
                .build();
        // Tomcat needs a writable /tmp while the root file system is read-only
        taskDefinition.addVolume(Volume.builder().name("tmp").build());

        // AI advisor (Phase 2 WP4): the guardrail the API applies, and its permission to call the model
        AdvisorResources.Guardrail guardrail = AdvisorResources.guardrail(this, cfg);
        AdvisorResources.allowModelAndGuardrail(
                this,
                taskRole,
                "arn:" + getPartition() + ":bedrock:" + getRegion() + ":" + getAccount() + ":guardrail/" + guardrail.id());

        ContainerDefinition container = taskDefinition.addContainer(
                CONTAINER_NAME,
                ContainerDefinitionOptions.builder()
                        .containerName(CONTAINER_NAME)
                        .image(ContainerImage.fromEcrRepository(foundation.apiRepository(), cfg.imageTag()))
                        .essential(true)
                        // a hard limit so the JVM (MaxRAMPercentage=75) sizes its heap from this container's share
                        // of the task, not from the whole 1 GB that it shares with the collector sidecar
                        .memoryLimitMiB(ApiObservability.API_MEMORY_MIB)
                        .portMappings(List.of(PortMapping.builder()
                                .containerPort(NetworkStack.APP_PORT)
                                .protocol(Protocol.TCP)
                                .build()))
                        // not secrets: names, URLs and ids. The DB credentials come from `secrets` below.
                        .environment(Map.ofEntries(
                                Map.entry("SPRING_PROFILES_ACTIVE", "aws"),
                                Map.entry(
                                        "SPRING_DATASOURCE_URL",
                                        "jdbc:postgresql://" + data.endpointHostname() + ":" + NetworkStack.DB_PORT + "/"
                                                + DataStack.DATABASE_NAME + "?sslmode=require"),
                                Map.entry(
                                        "JARS_ISSUER_URI",
                                        "https://cognito-idp." + getRegion() + ".amazonaws.com/"
                                                + auth.userPool().getUserPoolId()),
                                Map.entry(
                                        "JARS_ALLOWED_CLIENT_IDS",
                                        spaClient.getUserPoolClientId() + ","
                                                + auth.smokeClient().getUserPoolClientId()),
                                Map.entry("JARS_JOBS_QUEUE", MessagingStack.JOBS_QUEUE_NAME),
                                Map.entry("JARS_RECONCILIATION_QUEUE", MessagingStack.RECONCILIATION_QUEUE_NAME),
                                Map.entry("JARS_IMPORTS_BUCKET", DataStack.statementsBucketName(cfg, getAccount())),
                                Map.entry("JARS_IMPORTS_QUEUE", MessagingStack.IMPORTS_QUEUE_NAME),
                                Map.entry("JARS_GUARDRAIL_ID", guardrail.id()),
                                Map.entry("JARS_GUARDRAIL_VERSION", guardrail.version()),
                                // the JVM sizes its heap from the container limit (aws-infrastructure.md section 4)
                                Map.entry("JAVA_TOOL_OPTIONS", "-XX:MaxRAMPercentage=75")))
                        .secrets(Map.of(
                                "SPRING_DATASOURCE_USERNAME",
                                Secret.fromSecretsManager(data.secret(), "username"),
                                "SPRING_DATASOURCE_PASSWORD",
                                Secret.fromSecretsManager(data.secret(), "password")))
                        .logging(LogDrivers.awsLogs(AwsLogDriverProps.builder()
                                .logGroup(logGroup)
                                .streamPrefix(CONTAINER_NAME)
                                .build()))
                        .readonlyRootFilesystem(true)
                        // longer than the 20 s graceful shutdown of Spring, then SIGKILL
                        .stopTimeout(Duration.seconds(30))
                        .linuxParameters(LinuxParameters.Builder.create(this, "ApiLinuxParameters")
                                .initProcessEnabled(true)
                                .build())
                        .build());
        container.addMountPoints(MountPoint.builder()
                .sourceVolume("tmp")
                .containerPath("/tmp")
                .readOnly(false)
                .build());
        ApiObservability.addCollector(this, cfg, taskDefinition, container, logGroup, taskRole);

        FargateService apiService = FargateService.Builder.create(this, "ApiService")
                .serviceName("jars-api")
                .cluster(cluster)
                .taskDefinition(taskDefinition)
                .desiredCount(cfg.apiDesiredCount())
                .platformVersion(FargatePlatformVersion.LATEST)
                // no NAT: the task reaches ECR, Logs, Secrets Manager and Cognito over its own public IP
                .assignPublicIp(true)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetGroupName(NetworkStack.SUBNET_GROUP_PUBLIC)
                        .build())
                .securityGroups(List.of(appSecurityGroup))
                // JVM + Spring Boot + Flyway need time before the first healthy answer
                .healthCheckGracePeriod(Duration.seconds(90))
                .circuitBreaker(DeploymentCircuitBreaker.builder().rollback(true).build())
                .minHealthyPercent(100)
                .maxHealthyPercent(200)
                // the Project tag must reach the tasks for the tag-filtered budget to see their cost
                .propagateTags(PropagatedTagSource.SERVICE)
                .enableEcsManagedTags(true)
                .build();
        // CloudFormation orders the service after the role, not after the role's policy: without this the
        // first task can start before it may pull the image or read the secret
        apiService.getNode().addDependency(executionRole);
        return apiService;
    }

    /**
     * Aurora only pauses when no connection is open; a forgotten session costs 0.5 ACU around the clock
     * (about 73 USD a month, ADR-0008). Six hours in a row whose lowest capacity is still above zero
     * mean the database never paused.
     */
    private void addAuroraNeverPausesAlarm(EnvConfig cfg, DataStack data, ITopic alarmTopic) {
        Alarm alarm = Alarm.Builder.create(this, "AuroraNeverPausesAlarm")
                .alarmName(cfg.name("aurora-not-paused"))
                .alarmDescription("Aurora ServerlessDatabaseCapacity stayed above 0 for 6 hours: something holds a"
                        + " connection, see the runbook section on checking whether Aurora is paused.")
                .metric(Metric.Builder.create()
                        .namespace("AWS/RDS")
                        .metricName("ServerlessDatabaseCapacity")
                        .dimensionsMap(Map.of("DBClusterIdentifier", data.identifier()))
                        .statistic("Minimum")
                        .period(Duration.hours(1))
                        .build())
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .threshold(0)
                .evaluationPeriods(6)
                .datapointsToAlarm(6)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();
        alarm.addAlarmAction(new SnsAction(alarmTopic));
    }

    public ApplicationLoadBalancer loadBalancer() {
        return loadBalancer;
    }

    public Distribution distribution() {
        return distribution;
    }

    public UserPoolClient spaClient() {
        return spaClient;
    }

    public FargateService service() {
        return service;
    }
}
