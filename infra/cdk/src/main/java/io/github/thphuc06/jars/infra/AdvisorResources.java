package io.github.thphuc06.jars.infra;

import io.github.thphuc06.jars.infra.config.EnvConfig;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.services.bedrock.CfnGuardrail;
import software.amazon.awscdk.services.bedrock.CfnGuardrailVersion;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.Policy;
import software.amazon.awscdk.services.iam.PolicyStatement;

/**
 * What the AI advisor needs on AWS (ADR-0011, ADR-0023): a Bedrock guardrail the API applies before and after the
 * model, and the task role's permission to call the model through the global cross-region inference profile.
 *
 * <p>The guardrail lives in JarsApp: it costs nothing while unused (billed per text unit checked) and nothing refers to
 * it outside the API, so it can come and go with the app.
 */
final class AdvisorResources {

    /** Global cross-region inference profile of Claude Haiku 4.5 (Haiku 5.5 is not enabled for this account). */
    static final String MODEL = "anthropic.claude-haiku-4-5-20251001-v1:0";

    record Guardrail(String id, String version) {}

    private AdvisorResources() {}

    static Guardrail guardrail(Stack stack, EnvConfig cfg) {
        CfnGuardrail guardrail = CfnGuardrail.Builder.create(stack, "AdvisorGuardrail")
                .name(cfg.name("advisor"))
                .description("Jars advisor: no specific buy/sell advice, no card or ID numbers, no prompt attacks")
                .blockedInputMessaging("Xin lỗi, tôi không thể giúp với yêu cầu này. Hãy hỏi về hũ, chi tiêu hoặc"
                        + " mục tiêu của bạn.")
                .blockedOutputsMessaging("Tôi không đưa ra khuyến nghị mua bán chứng khoán hay tài sản cụ thể. Bạn"
                        + " có thể hỏi về ngân sách, chi tiêu và mục tiêu tiết kiệm.")
                .topicPolicyConfig(CfnGuardrail.TopicPolicyConfigProperty.builder()
                        .topicsConfig(List.of(CfnGuardrail.TopicConfigProperty.builder()
                                .name("SpecificInvestmentAdvice")
                                .type("DENY")
                                .definition("Recommending to buy, sell or hold a specific stock, fund, cryptocurrency"
                                        + " or other named financial asset, or predicting its price.")
                                .examples(List.of(
                                        "Should I buy VNM shares now?",
                                        "Tôi có nên mua cổ phiếu FPT không?",
                                        "Bitcoin sẽ lên giá bao nhiêu tháng sau?"))
                                .build()))
                        .build())
                .contentPolicyConfig(CfnGuardrail.ContentPolicyConfigProperty.builder()
                        .filtersConfig(List.of(
                                filter("SEXUAL", "HIGH"),
                                filter("VIOLENCE", "MEDIUM"),
                                filter("HATE", "MEDIUM"),
                                filter("INSULTS", "MEDIUM"),
                                filter("MISCONDUCT", "MEDIUM"),
                                // prompt attacks are an input concern only: Bedrock requires NONE for the output
                                CfnGuardrail.ContentFilterConfigProperty.builder()
                                        .type("PROMPT_ATTACK")
                                        .inputStrength("HIGH")
                                        .outputStrength("NONE")
                                        .build()))
                        .build())
                .sensitiveInformationPolicyConfig(CfnGuardrail.SensitiveInformationPolicyConfigProperty.builder()
                        .piiEntitiesConfig(List.of(CfnGuardrail.PiiEntityConfigProperty.builder()
                                .type("CREDIT_DEBIT_CARD_NUMBER")
                                .action("ANONYMIZE")
                                .build()))
                        // Vietnamese citizen ID (CCCD): 12 digits starting with 0. Bank account numbers are not
                        // matched on purpose: 8-15 digits would also mask the amounts the advisor talks about.
                        .regexesConfig(List.of(CfnGuardrail.RegexConfigProperty.builder()
                                .name("VietnameseCitizenId")
                                .pattern("\\b0\\d{11}\\b")
                                .action("ANONYMIZE")
                                .build()))
                        .build())
                .build();
        CfnGuardrailVersion version = CfnGuardrailVersion.Builder.create(stack, "AdvisorGuardrailVersion")
                .guardrailIdentifier(guardrail.getAttrGuardrailId())
                .description("Version used by jars-api")
                .build();
        return new Guardrail(guardrail.getAttrGuardrailId(), version.getAttrVersion());
    }

    /**
     * The three statements AWS documents for a global cross-region inference profile (the profile in this Region, the
     * model in this Region, the model in any Region), for both invoke actions (Converse and ConverseStream), plus the
     * guardrail. Kept apart from the task role's default policy, like the telemetry permissions.
     */
    static void allowModelAndGuardrail(Stack stack, IRole taskRole, String guardrailArn) {
        String region = stack.getRegion();
        String profileArn =
                "arn:" + stack.getPartition() + ":bedrock:" + region + ":" + stack.getAccount() + ":inference-profile/global."
                        + MODEL;
        List<String> invoke = List.of("bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream");
        Policy.Builder.create(stack, "ApiAdvisorPolicy")
                .statements(List.of(
                        PolicyStatement.Builder.create()
                                .sid("GlobalInferenceProfile")
                                .effect(Effect.ALLOW)
                                .actions(invoke)
                                .resources(List.of(profileArn))
                                .conditions(Map.of("StringEquals", Map.of("aws:RequestedRegion", region)))
                                .build(),
                        PolicyStatement.Builder.create()
                                .sid("ModelInThisRegion")
                                .effect(Effect.ALLOW)
                                .actions(invoke)
                                .resources(List.of(
                                        "arn:" + stack.getPartition() + ":bedrock:" + region + "::foundation-model/" + MODEL))
                                .conditions(Map.of(
                                        "StringEquals",
                                        Map.of("aws:RequestedRegion", region, "bedrock:InferenceProfileArn", profileArn)))
                                .build(),
                        PolicyStatement.Builder.create()
                                .sid("ModelInAnyRegion")
                                .effect(Effect.ALLOW)
                                .actions(invoke)
                                .resources(List.of("arn:" + stack.getPartition() + ":bedrock:::foundation-model/" + MODEL))
                                .conditions(Map.of(
                                        "StringEquals",
                                        Map.of(
                                                "aws:RequestedRegion", "unspecified",
                                                "bedrock:InferenceProfileArn", profileArn)))
                                .build(),
                        PolicyStatement.Builder.create()
                                .sid("ApplyGuardrail")
                                .effect(Effect.ALLOW)
                                .actions(List.of("bedrock:ApplyGuardrail"))
                                .resources(List.of(guardrailArn))
                                .build()))
                .roles(List.of(taskRole))
                .build();
    }

    private static CfnGuardrail.ContentFilterConfigProperty filter(String type, String strength) {
        return CfnGuardrail.ContentFilterConfigProperty.builder()
                .type(type)
                .inputStrength(strength)
                .outputStrength(strength)
                .build();
    }
}
