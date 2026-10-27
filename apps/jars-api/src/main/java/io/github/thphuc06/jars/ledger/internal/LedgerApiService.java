package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.ledger.JarSummary;
import io.github.thphuc06.jars.ledger.LedgerApi;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LedgerApiService implements LedgerApi {

    private final JarRepository jars;

    LedgerApiService(JarRepository jars) {
        this.jars = jars;
    }

    @Override
    @Transactional(readOnly = true)
    public List<JarSummary> jars(UUID userId) {
        return jars.findByUserId(userId).stream().map(LedgerApiService::summary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JarSummary> jar(UUID userId, UUID jarId) {
        return jars.findByIdAndUserId(jarId, userId).map(LedgerApiService::summary);
    }

    private static JarSummary summary(Jar jar) {
        return new JarSummary(jar.id(), jar.code().name(), jar.name(), jar.balance(), jar.isArchived());
    }
}
