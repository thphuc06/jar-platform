package io.github.thphuc06.jars.imports.internal.batch;

import io.github.thphuc06.jars.imports.internal.parsing.Categorizer;
import io.github.thphuc06.jars.imports.internal.parsing.InvalidRowException;
import io.github.thphuc06.jars.imports.internal.parsing.StatementRowParser;
import io.github.thphuc06.jars.imports.internal.parsing.StatementRowParser.ParsedRow;
import io.github.thphuc06.jars.ledger.ImportedTransaction;
import io.github.thphuc06.jars.ledger.JarSummary;
import io.github.thphuc06.jars.shared.Money;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * Parses one row and decides where it goes: jar and category from {@link Categorizer}. A row that cannot be imported
 * throws {@link InvalidRowException}, which the step skips and records with its line.
 */
class RowProcessor implements ItemProcessor<ImportRow.Raw, ImportRow.Mapped> {

    /** Statements are in Vietnam time: a row's date is that day in Ho Chi Minh City. */
    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final Map<String, UUID> jarIdsByCode;
    private final Clock clock;

    RowProcessor(List<JarSummary> jars, Clock clock) {
        this.jarIdsByCode =
                jars.stream().collect(Collectors.toMap(JarSummary::code, JarSummary::id, (first, second) -> first));
        this.clock = clock;
    }

    @Override
    public ImportRow.Mapped process(ImportRow.Raw raw) {
        ParsedRow row = new StatementRowParser(raw.columns()).parse(raw.record());
        if (row.date().isAfter(LocalDate.now(clock.withZone(VIETNAM)).plusDays(1))) {
            throw new InvalidRowException("Ngày giao dịch " + row.date() + " ở tương lai.");
        }
        Categorizer.Guess guess = Categorizer.guess(row.counterparty(), row.income());
        UUID jarId = null;
        if (guess.jar() != null) {
            // the guessed jar, else the essentials jar; a user without either cannot take expenses from a statement
            jarId = jarIdsByCode.getOrDefault(guess.jar().name(), jarIdsByCode.get(Categorizer.JarCode.NEC.name()));
            if (jarId == null) {
                throw new InvalidRowException("Không có hũ để ghi khoản chi này (hũ Thiết yếu đã bị lưu trữ).");
            }
        }
        return new ImportRow.Mapped(
                row.line(),
                new ImportedTransaction(
                        row.income(),
                        new Money(row.amount(), Money.VND),
                        jarId,
                        guess.categoryId(),
                        row.counterparty(),
                        row.date().atStartOfDay(VIETNAM).toInstant()));
    }
}
