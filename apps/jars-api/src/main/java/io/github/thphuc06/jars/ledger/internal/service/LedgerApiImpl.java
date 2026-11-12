package io.github.thphuc06.jars.ledger.internal.service;

import io.github.thphuc06.jars.ledger.AllocationReceipt;
import io.github.thphuc06.jars.ledger.CategorySummary;
import io.github.thphuc06.jars.ledger.IncomeAllocation;
import io.github.thphuc06.jars.ledger.JarSummary;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService.Result;
import io.github.thphuc06.jars.shared.UserId;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
class LedgerApiImpl implements LedgerApi {

    private final LedgerQueryService queries;
    private final LedgerCommandService commands;

    LedgerApiImpl(LedgerQueryService queries, LedgerCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @Override
    public List<JarSummary> jars(UserId userId) {
        return queries.jars(userId).stream()
                .map(j -> new JarSummary(j.id(), j.code(), j.name(), j.targetPct(), j.balance()))
                .toList();
    }

    @Override
    public List<CategorySummary> categories(UserId userId) {
        return queries.categories(userId).stream()
                .map(c -> new CategorySummary(c.id(), c.name(), c.kind()))
                .toList();
    }

    @Override
    public Result<AllocationReceipt> allocateIncome(
            UserId userId, String idempotencyKey, String requestHash, IncomeAllocation allocation) {
        return commands.allocateIncome(userId, idempotencyKey, requestHash, allocation);
    }
}
