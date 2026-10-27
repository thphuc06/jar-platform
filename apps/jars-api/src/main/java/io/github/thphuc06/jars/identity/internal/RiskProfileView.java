package io.github.thphuc06.jars.identity.internal;

import io.github.thphuc06.jars.identity.RiskLevel;
import java.time.Instant;

/** The current risk profile of a user: the answers and what they scored. */
public record RiskProfileView(
        RiskQuestionnaire.Answers answers,
        int score,
        RiskLevel riskLevel,
        boolean capped,
        int questionnaireVersion,
        Instant assessedAt) {}
