package io.github.thphuc06.jars.ledger.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface JarRepository extends JpaRepository<Jar, UUID> {

    boolean existsByUserId(UUID userId);

    List<Jar> findByUserId(UUID userId);
}
