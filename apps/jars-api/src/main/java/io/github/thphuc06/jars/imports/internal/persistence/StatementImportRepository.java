package io.github.thphuc06.jars.imports.internal.persistence;

import io.github.thphuc06.jars.imports.internal.domain.ImportStatus;
import io.github.thphuc06.jars.imports.internal.domain.StatementImport;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementImportRepository extends JpaRepository<StatementImport, UUID> {

    /** Always by user too: someone else's import is simply "not found" (anti-IDOR). */
    Optional<StatementImport> findByIdAndUserId(UUID id, UUID userId);

    List<StatementImport> findByUserIdOrderByCreatedAtDesc(UUID userId, Limit limit);

    /** Another finished import of the same file by the same user. */
    boolean existsByUserIdAndFileSha256AndStatusAndIdNot(UUID userId, String sha256, ImportStatus status, UUID id);
}
