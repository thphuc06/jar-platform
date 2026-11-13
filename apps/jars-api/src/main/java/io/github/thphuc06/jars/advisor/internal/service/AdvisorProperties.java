package io.github.thphuc06.jars.advisor.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxTokens longest answer, in tokens (cost bound per message)
 * @param historyMessages how many earlier messages of the session are sent with a new one
 * @param messagesPerHour rate limit per user (ADR-0023)
 * @param messagesPerDay rate limit per user
 * @param guardrailId Bedrock guardrail; blank = no guardrail (local, tests)
 * @param guardrailVersion version of that guardrail
 */
@ConfigurationProperties("jars.advisor")
public record AdvisorProperties(
        @DefaultValue("800") int maxTokens,
        @DefaultValue("10") int historyMessages,
        @DefaultValue("20") int messagesPerHour,
        @DefaultValue("60") int messagesPerDay,
        @DefaultValue("") String guardrailId,
        @DefaultValue("DRAFT") String guardrailVersion) {}
