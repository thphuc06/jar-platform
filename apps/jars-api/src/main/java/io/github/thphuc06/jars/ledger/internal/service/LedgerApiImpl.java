package io.github.thphuc06.jars.ledger.internal.service;

import io.github.thphuc06.jars.ledger.JarSummary;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.shared.UserId;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
class LedgerApiImpl implements LedgerApi {

    private final LedgerQueryService queries;

    LedgerApiImpl(LedgerQueryService queries) {
        this.queries = queries;
    }

    @Override
    public List<JarSummary> jars(UserId userId) {
        return queries.jars(userId).stream()
                .map(j -> new JarSummary(j.id(), j.code(), j.name(), j.targetPct(), j.balance()))
                .toList();
    }
}
