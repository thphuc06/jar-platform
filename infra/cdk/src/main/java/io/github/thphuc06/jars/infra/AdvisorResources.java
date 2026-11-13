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

    /** Vietnamese citizen ID (CCCD): 12 digits starting with 0. */
    static final String CITIZEN_ID_PATTERN = "\\b0\\d{11}\\b";

    /**
     * Vietnamese mobile number: +84 or 0, then 3, 5, 7, 8 or 9 and eight more digits, written with or without single
     * spaces, dots or dashes. The built-in PHONE entity finds the spaced and dotted forms but not "0912345678".
     */
    static final String PHONE_PATTERN = "(?:\\+84|\\b0)[ .-]?[35789](?:[ .-]?\\d){8}\\b";

    /**
     * A bank account number only after the words that introduce one: 6 to 16 digits alone would also match the
     * amounts the advisor talks about, so a number without such words is not masked. Only for what the user types:
     * the advisor's answers are full of amounts and never contain account numbers.
     */
    static final String BANK_ACCOUNT_PATTERN =
            "(?i)(?:stk|số tài khoản|số tk|account (?:no|number))[^\\d\\n]{0,15}\\d(?: ?\\d){5,15}";

    static Guardrail guardrail(Stack stack, EnvConfig cfg) {
        CfnGuardrail guardrail = CfnGuardrail.Builder.create(stack, "AdvisorGuardrail")
                .name(cfg.name("advisor"))
                .description("Jars advisor: no buy/sell tips on stocks, crypto, gold or currency asked, no card, ID, e-mail or phone numbers"
                        + " kept, no prompt attacks")
                .blockedInputMessaging("Xin lỗi, tôi không thể giúp với yêu cầu này. Hãy hỏi về hũ, chi tiêu hoặc"
                        + " mục tiêu của bạn.")
                // one message for every output block; the investment-advice message is chosen by BedrockGuardrail
                .blockedOutputsMessaging("Xin lỗi, tôi không thể hiển thị câu trả lời này. Bạn hãy thử hỏi theo cách"
                        + " khác.")
                .topicPolicyConfig(CfnGuardrail.TopicPolicyConfigProperty.builder()
                        .topicsConfig(List.of(CfnGuardrail.TopicConfigProperty.builder()
                                .name("SpecificInvestmentAdvice")
                                .type("DENY")
                                .definition("Asking or telling whether to buy, sell or hold a named listed stock, crypto,"
                                        + " gold or foreign currency, or predicting its price. Not a bank's own"
                                        + " savings, loans or funds (VN30 index fund included).")
                                .examples(List.of(
                                        "Should I buy VNM shares now?",
                                        "Tôi có nên mua cổ phiếu FPT không?",
                                        "Bitcoin sẽ lên giá bao nhiêu tháng sau?",
                                        "Có nên mua vàng SJC lúc này không?",
                                        "Cuối năm USD tăng hay giảm, tôi có nên mua không?"))
                                // what the user asks is refused. The answer is not checked by the topic: the system
                                // prompt forbids advice, and the topic flagged a harmless refusal of the model as
                                // advice (ADR-0026). The advisor recommends the bank's own products (ADR-0027), so the
                                // definition leaves them out and asks about stocks, crypto, gold and currency only
                                .inputEnabled(true)
                                .inputAction("BLOCK")
                                .outputEnabled(false)
                                .outputAction("NONE")
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
                // Sensitive data is masked (not blocked) in both directions, so a user who pastes a card number is
                // still helped. Input and output are set explicitly: a rule with only "action" was applied to the
                // answer and not to the user's message, so PII was stored and sent to the model as typed (ADR-0026).
                .sensitiveInformationPolicyConfig(CfnGuardrail.SensitiveInformationPolicyConfigProperty.builder()
                        .piiEntitiesConfig(List.of(pii("CREDIT_DEBIT_CARD_NUMBER"), pii("EMAIL")))
                        .regexesConfig(List.of(
                                regex("VietnameseCitizenId", CITIZEN_ID_PATTERN, true),
                                regex("VietnamesePhone", PHONE_PATTERN, true),
                                regex("BankAccountNumber", BANK_ACCOUNT_PATTERN, false)))
                        .build())
                .build();        CfnGuardrailVersion version = CfnGuardrailVersion.Builder.create(stack, "AdvisorGuardrailVersion")
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

    private static CfnGuardrail.PiiEntityConfigProperty pii(String type) {
        return CfnGuardrail.PiiEntityConfigProperty.builder()
                .type(type)
                .action("ANONYMIZE")
                .inputEnabled(true)
                .inputAction("ANONYMIZE")
                .outputEnabled(true)
                .outputAction("ANONYMIZE")
                .build();
    }

    private static CfnGuardrail.RegexConfigProperty regex(String name, String pattern, boolean onOutput) {
        return CfnGuardrail.RegexConfigProperty.builder()
                .name(name)
                .pattern(pattern)
                .action("ANONYMIZE")
                .inputEnabled(true)
                .inputAction("ANONYMIZE")
                .outputEnabled(onOutput)
                .outputAction(onOutput ? "ANONYMIZE" : "NONE")
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
