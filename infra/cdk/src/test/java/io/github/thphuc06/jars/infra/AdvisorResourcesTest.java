package io.github.thphuc06.jars.infra;

import static io.github.thphuc06.jars.infra.T.like;
import static io.github.thphuc06.jars.infra.T.map;
import static io.github.thphuc06.jars.infra.T.properties;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

/** The Bedrock guardrail of the advisor and the task role's permission to call the model (ADR-0011, ADR-0023). */
class AdvisorResourcesTest {

    private static final String PROFILE =
            "arn:aws:bedrock:ap-southeast-1:626212071763:inference-profile/global.anthropic.claude-haiku-4-5-20251001-v1:0";

    private final Template template = InfraFixture.aurora().app();

    @Test
    void theGuardrailDeniesSpecificInvestmentAdviceOnInputOnly_andBlocksPromptAttacksOnInputOnly() {
        template.hasResourceProperties(
                "AWS::Bedrock::Guardrail",
                like(
                        "Name", "jars-dev-advisor",
                        "TopicPolicyConfig",
                                like(
                                        "TopicsConfig",
                                        List.of(like(
                                                "Name", "SpecificInvestmentAdvice",
                                                "Type", "DENY",
                                                "InputEnabled", true,
                                                "InputAction", "BLOCK",
                                                "OutputEnabled", false))),
                        "ContentPolicyConfig",
                                like(
                                        "FiltersConfig",
                                        Match.arrayWith(List.of(like(
                                                "Type", "PROMPT_ATTACK",
                                                "InputStrength", "HIGH",
                                                "OutputStrength", "NONE"))))));
        template.resourceCountIs("AWS::Bedrock::GuardrailVersion", 1);
    }

    @Test
    void theTopicDefinitionFitsBedrocksLimits() {
        // Bedrock rejects a definition over 200 characters and more than 5 examples of 100 characters at deploy time
        @SuppressWarnings("unchecked")
        Map<String, Object> topic = (Map<String, Object>) ((List<Object>) ((Map<String, Object>)
                                properties(template, "AWS::Bedrock::Guardrail").get(0).get("TopicPolicyConfig"))
                        .get("TopicsConfig"))
                .get(0);
        assertThat(topic.get("Definition").toString().length()).isLessThanOrEqualTo(200);
        @SuppressWarnings("unchecked")
        List<String> examples = (List<String>) topic.get("Examples");
        assertThat(examples).hasSizeLessThanOrEqualTo(5).allSatisfy(e -> assertThat(e.length()).isLessThanOrEqualTo(100));
    }

    @Test
    void everySensitiveDataRuleMasksWhatTheUserTypes_notOnlyWhatTheModelAnswers() {
        // regression: rules with only "Action" were applied to the answer and not to the user's message (ADR-0026)
        Map<String, Object> policy = sensitivePolicy();
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.addAll(rules(policy, "PiiEntitiesConfig"));
        rules.addAll(rules(policy, "RegexesConfig"));

        assertThat(rules).hasSize(5);
        assertThat(rules).allSatisfy(rule -> assertThat(rule)
                .containsEntry("InputEnabled", true)
                .containsEntry("InputAction", "ANONYMIZE"));
        assertThat(rules(policy, "PiiEntitiesConfig")).extracting(r -> r.get("Type"))
                .containsExactlyInAnyOrder("CREDIT_DEBIT_CARD_NUMBER", "EMAIL");
        assertThat(rules(policy, "RegexesConfig")).extracting(r -> r.get("Name"))
                .containsExactlyInAnyOrder("VietnameseCitizenId", "VietnamesePhone", "BankAccountNumber");
        // only the bank account rule is input-only; the others also mask the answer
        assertThat(rules).filteredOn(r -> !"BankAccountNumber".equals(r.get("Name")))
                .allSatisfy(rule -> assertThat(rule)
                        .containsEntry("OutputEnabled", true)
                        .containsEntry("OutputAction", "ANONYMIZE"));
        assertThat(rules).filteredOn(r -> "BankAccountNumber".equals(r.get("Name")))
                .allSatisfy(rule -> assertThat(rule).containsEntry("OutputEnabled", false));
    }

    @Test
    void thePatternsMatchWhatTheyAreFor_andNotTheAmountsAndDatesOfTheAdvisor() {
        Map<String, String> patterns = new HashMap<>();
        rules(sensitivePolicy(), "RegexesConfig")
                .forEach(r -> patterns.put((String) r.get("Name"), (String) r.get("Pattern")));
        // the template carries the same strings as the constants
        assertThat(patterns)
                .containsEntry("VietnameseCitizenId", AdvisorResources.CITIZEN_ID_PATTERN)
                .containsEntry("VietnamesePhone", AdvisorResources.PHONE_PATTERN)
                .containsEntry("BankAccountNumber", AdvisorResources.BANK_ACCOUNT_PATTERN);

        Pattern citizenId = Pattern.compile(patterns.get("VietnameseCitizenId"));
        assertThat(citizenId.matcher("CCCD 012345678901.").find()).isTrue();
        assertThat(citizenId.matcher("0123456789012").find()).as("13 digits").isFalse();

        Pattern phone = Pattern.compile(patterns.get("VietnamesePhone"));
        for (String number : List.of("0912345678", "0912 345 678", "0912.345.678", "+84 912 345 678", "091-234-5678")) {
            assertThat(phone.matcher("gọi " + number + " nhé").find()).as(number).isTrue();
        }
        Pattern bank = Pattern.compile(patterns.get("BankAccountNumber"));
        for (String text : List.of("Số tài khoản của tôi là 0123456789012", "STK: 1234567890 BIDV", "số tài khoản: 0123 4567 8901 2")) {
            assertThat(bank.matcher(text).find()).as(text).isTrue();
        }

        // what the advisor and the user write all day: amounts with dots or without, dates, percentages
        List<String> ordinary = List.of(
                "Hũ Thiết yếu có 2.500.000 ₫, đã chi 1.350.000 ₫ ngày 05/11/2026.",
                "Lương 15000000 đồng, còn 2500000 đồng, mục tiêu 100.000.000.000 ₫, tăng 12,5%.",
                "Số tài khoản của tôi đang có 1.500.000 ₫ và tôi muốn chuyển 500000.",
                "Mã giao dịch 0123456789, tháng 11/2026, 25.000.000 ₫.");
        for (String text : ordinary) {
            assertThat(citizenId.matcher(text).find()).as(text).isFalse();
            assertThat(phone.matcher(text).find()).as(text).isFalse();
            assertThat(bank.matcher(text).find()).as(text).isFalse();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sensitivePolicy() {
        return (Map<String, Object>) properties(template, "AWS::Bedrock::Guardrail")
                .get(0)
                .get("SensitiveInformationPolicyConfig");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rules(Map<String, Object> policy, String key) {
        return (List<Map<String, Object>>) policy.get(key);
    }
    @Test
    void theTaskRoleMayInvokeOnlyHaikuThroughTheGlobalProfile_andApplyOnlyThisGuardrail() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> statements = template.findResources("AWS::IAM::Policy").entrySet().stream()
                .filter(e -> e.getKey().startsWith("ApiAdvisorPolicy"))
                .flatMap(e -> ((List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>)
                                                e.getValue().get("Properties"))
                                        .get("PolicyDocument"))
                                .get("Statement"))
                        .stream())
                .toList();
        assertThat(statements).extracting(s -> s.get("Sid"))
                .containsExactly("GlobalInferenceProfile", "ModelInThisRegion", "ModelInAnyRegion", "ApplyGuardrail");
        assertThat(statements.get(0)).containsEntry("Resource", PROFILE);
        assertThat(statements.get(2))
                .containsEntry("Resource", "arn:aws:bedrock:::foundation-model/anthropic.claude-haiku-4-5-20251001-v1:0")
                .containsEntry(
                        "Condition",
                        map(
                                "StringEquals",
                                map("aws:RequestedRegion", "unspecified", "bedrock:InferenceProfileArn", PROFILE)));
        assertThat(statements.get(3).get("Resource").toString()).contains("guardrail/").contains("AdvisorGuardrail");
        // no wildcard model or action
        assertThat(statements).allSatisfy(s -> assertThat(s.toString()).doesNotContain("bedrock:*").doesNotContain("model/*"));
        assertThat(properties(template, "AWS::Bedrock::Guardrail")).hasSize(1);
    }
}
