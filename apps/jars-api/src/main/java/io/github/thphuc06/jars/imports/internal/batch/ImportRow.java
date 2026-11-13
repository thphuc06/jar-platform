package io.github.thphuc06.jars.imports.internal.batch;

import io.github.thphuc06.jars.imports.internal.parsing.CsvRecords;
import io.github.thphuc06.jars.imports.internal.parsing.StatementColumns;
import io.github.thphuc06.jars.ledger.ImportedTransaction;

/** Items of the import step: what the reader hands over, and what the processor turns it into. */
final class ImportRow {

    /** One record of the file with the columns found in its header. */
    record Raw(CsvRecords.Record record, StatementColumns columns) {

        int line() {
            return record.line();
        }
    }

    /** One row ready for the ledger. */
    record Mapped(int line, ImportedTransaction transaction) {}

    private ImportRow() {}
}
