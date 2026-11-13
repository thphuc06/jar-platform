package io.github.thphuc06.jars.infra;

import static io.github.thphuc06.jars.infra.T.like;
import static io.github.thphuc06.jars.infra.T.map;
import static io.github.thphuc06.jars.infra.T.properties;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

/** The Bedrock guardrail of the advisor and the task role's permission to call the model (ADR-0011, ADR-0023). */
class AdvisorResourcesTest {

    private static final String PROFILE =
            "arn:aws:bedrock:ap-southeast-1:626212071763:inference-profile/global.anthropic.claude-haiku-4-5-20251001-v1:0";

    private final Template template = InfraFixture.aurora().app();

    @Test
    void theGuardrailDeniesSpecificInvestmentAdviceAndBlocksPromptAttacksOnInputOnly() {
        template.hasResourceProperties(
                "AWS::Bedrock::Guardrail",
                like(
                        "Name", "jars-dev-advisor",
                        "TopicPolicyConfig",
                                like("TopicsConfig", List.of(like("Name", "SpecificInvestmentAdvice", "Type", "DENY"))),
                        "ContentPolicyConfig",
                                like(
                                        "FiltersConfig",
                                        Match.arrayWith(List.of(like(
                                                "Type", "PROMPT_ATTACK",
                                                "InputStrength", "HIGH",
                                                "OutputStrength", "NONE")))),
                        "SensitiveInformationPolicyConfig",
                                like(
                                        "PiiEntitiesConfig",
                                        List.of(like("Type", "CREDIT_DEBIT_CARD_NUMBER", "Action", "ANONYMIZE")))));
        template.resourceCountIs("AWS::Bedrock::GuardrailVersion", 1);
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
