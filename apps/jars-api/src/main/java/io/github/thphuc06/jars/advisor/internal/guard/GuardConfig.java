package io.github.thphuc06.jars.advisor.internal.guard;

import io.github.thphuc06.jars.advisor.internal.service.AdvisorProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/** A Bedrock guardrail when {@code jars.advisor.guardrail-id} is set (JarsApp sets it), else one that lets all pass. */
@Configuration(proxyBeanMethods = false)
class GuardConfig {

    @Bean
    @ConditionalOnExpression("!'${jars.advisor.guardrail-id:}'.isBlank()")
    ContentGuard bedrockGuardrail(
            AdvisorProperties properties, @Value("${spring.cloud.aws.region.static:ap-southeast-1}") String region) {
        BedrockRuntimeClient client =
                BedrockRuntimeClient.builder().region(Region.of(region)).build();
        return new BedrockGuardrail(client, properties.guardrailId(), properties.guardrailVersion());
    }

    @Bean
    @ConditionalOnMissingBean(ContentGuard.class)
    ContentGuard noGuardrail() {
        return new ContentGuard() {
            @Override
            public Verdict checkInput(String text) {
                return Verdict.pass(text);
            }

            @Override
            public Verdict checkOutput(String text) {
                return Verdict.pass(text);
            }
        };
    }
}
