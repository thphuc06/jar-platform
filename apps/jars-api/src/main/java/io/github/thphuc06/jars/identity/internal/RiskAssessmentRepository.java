package io.github.thphuc06.jars.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, UUID> {

    /** The current profile: the newest submission (index {@code ix_risk_assessments_user_assessed}). */
    Optional<RiskAssessment> findFirstByUserIdOrderByAssessedAtDescIdDesc(UUID userId);

    long countByUserId(UUID userId);
}
