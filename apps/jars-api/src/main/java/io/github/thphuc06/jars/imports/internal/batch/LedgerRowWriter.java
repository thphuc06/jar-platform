package io.github.thphuc06.jars.imports.internal.batch;

import io.github.thphuc06.jars.ledger.ImportedTransaction;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * Records a chunk of rows in the ledger, inside the chunk's transaction ({@link LedgerApi#recordImported}). Each row
 * carries an idempotency key made of the file hash and its line, so a chunk that is retried after a rollback, a
 * restarted import, or the same file uploaded again never records a row twice.
 */
class LedgerRowWriter implements ItemWriter<ImportRow.Mapped> {

    private final LedgerApi ledger;
    private final ImportContext context;

    LedgerRowWriter(LedgerApi ledger, ImportContext context) {
        this.ledger = ledger;
        this.context = context;
    }

    @Override
    public void write(Chunk<? extends ImportRow.Mapped> rows) {
        for (ImportRow.Mapped row : rows) {
            ImportedTransaction t = row.transaction();
            ledger.recordImported(
                    context.userId(),
                    context.keyOf(row.line()),
                    IdempotencyService.hash(
                            "IMPORT",
                            Boolean.toString(t.income()),
                            t.amount().amount().toPlainString(),
                            String.valueOf(t.jarId()),
                            String.valueOf(t.categoryId()),
                            String.valueOf(t.counterparty()),
                            t.occurredAt().toString()),
                    t);
        }
    }
}
