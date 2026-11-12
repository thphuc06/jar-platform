package io.github.thphuc06.jars.identity.internal.persistence;

import io.github.thphuc06.jars.identity.internal.domain.RiskAssessment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, UUID> {

    Optional<RiskAssessment> findFirstByUserIdOrderByAssessedAtDesc(UUID userId);

    boolean existsByUserId(UUID userId);
}
