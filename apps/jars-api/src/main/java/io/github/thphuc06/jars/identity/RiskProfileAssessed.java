package io.github.thphuc06.jars.identity;

import java.util.UUID;

/**
 * Published when a user completes the risk questionnaire (again). The advisor module (Phase 4) uses the level as
 * context; the answers themselves stay in the identity module.
 */
public record RiskProfileAssessed(UUID userId, RiskLevel riskLevel, int score, int questionnaireVersion) {}
