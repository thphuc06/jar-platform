package io.github.thphuc06.jars.advisor.internal.guard;

import java.util.stream.Collectors;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ApplyGuardrailRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ApplyGuardrailResponse;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailAction;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentSource;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailOutputContent;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailTextBlock;

/**
 * {@code ApplyGuardrail} with the guardrail of JarsApp. Spring AI 2.0 cannot attach a guardrail to the Converse request
 * (no {@code guardrailConfig} in its options), so the advisor calls this before and after the model (ADR-0023).
 */
class BedrockGuardrail implements ContentGuard {

    private final BedrockRuntimeClient client;
    private final String guardrailId;
    private final String guardrailVersion;

    BedrockGuardrail(BedrockRuntimeClient client, String guardrailId, String guardrailVersion) {
        this.client = client;
        this.guardrailId = guardrailId;
        this.guardrailVersion = guardrailVersion;
    }

    @Override
    public Verdict checkInput(String text) {
        return apply(GuardrailContentSource.INPUT, text);
    }

    @Override
    public Verdict checkOutput(String text) {
        return apply(GuardrailContentSource.OUTPUT, text);
    }

    private Verdict apply(GuardrailContentSource source, String text) {
        ApplyGuardrailResponse response = client.applyGuardrail(ApplyGuardrailRequest.builder()
                .guardrailIdentifier(guardrailId)
                .guardrailVersion(guardrailVersion)
                .source(source)
                .content(GuardrailContentBlock.builder()
                        .text(GuardrailTextBlock.builder().text(text).build())
                        .build())
                .build());
        if (response.action() != GuardrailAction.GUARDRAIL_INTERVENED) {
            return Verdict.pass(text);
        }
        String replacement =
                response.outputs().stream().map(GuardrailOutputContent::text).collect(Collectors.joining("\n"));
        return new Verdict(true, replacement);
    }
}
