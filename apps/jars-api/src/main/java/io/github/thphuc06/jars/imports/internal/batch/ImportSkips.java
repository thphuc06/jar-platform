package io.github.thphuc06.jars.imports.internal.batch;

import io.github.thphuc06.jars.imports.internal.persistence.ImportErrorStore;
import io.github.thphuc06.jars.shared.DomainException;
import org.springframework.batch.core.listener.SkipListener;

/**
 * Records every skipped row with the reason the user will see. Called inside the chunk's transaction, so the error rows
 * commit with the chunk: a restarted import does not list a row twice.
 */
class ImportSkips implements SkipListener<ImportRow.Raw, ImportRow.Mapped> {

    private final ImportErrorStore errors;
    private final ImportContext context;

    ImportSkips(ImportErrorStore errors, ImportContext context) {
        this.errors = errors;
        this.context = context;
    }

    @Override
    public void onSkipInProcess(ImportRow.Raw item, Throwable t) {
        errors.record(context.importId(), item.line(), reasonOf(t));
    }

    @Override
    public void onSkipInWrite(ImportRow.Mapped item, Throwable t) {
        errors.record(context.importId(), item.line(), reasonOf(t));
    }

    /** Messages of row and ledger rules are written for users; anything else is not shown as is. */
    static String reasonOf(Throwable t) {
        if (t instanceof io.github.thphuc06.jars.imports.internal.parsing.InvalidRowException
                || t instanceof DomainException) {
            return t.getMessage();
        }
        return "Không ghi được dòng này (lỗi hệ thống).";
    }
}
