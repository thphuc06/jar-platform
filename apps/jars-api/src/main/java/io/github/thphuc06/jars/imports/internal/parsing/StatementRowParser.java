package io.github.thphuc06.jars.imports.internal.parsing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns one CSV record into a {@link ParsedRow}. The rules are those of {@code ingest_statement_vn} in the hackathon
 * backend (date formats, signed amount or debit/credit columns, direction words, missing counterparty allowed), with
 * one deliberate fix: the legacy {@code _safe_float} read "1.000" as 1.0, while in a Vietnamese statement it is one
 * thousand dong. Amounts are read as Vietnamese or international grouping, never as floating point.
 */
public final class StatementRowParser {

    /** One usable row: a positive amount and which way it went. */
    public record ParsedRow(int line, LocalDate date, BigDecimal amount, boolean income, String counterparty) {}

    // legacy order: %Y-%m-%d, %d/%m/%Y, %d-%m-%Y, %m/%d/%Y, %Y/%m/%d (day first wins when both could match)
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            strict("uuuu-MM-dd"), strict("d/M/uuuu"), strict("d-M-uuuu"), strict("M/d/uuuu"), strict("uuuu/M/d"));

    private static final Pattern DOT_GROUPS = Pattern.compile("^\\d{1,3}(\\.\\d{3})+$");
    private static final Pattern COMMA_GROUPS = Pattern.compile("^\\d{1,3}(,\\d{3})+$");
    private static final int MAX_COUNTERPARTY = 200;

    private final StatementColumns columns;

    public StatementRowParser(StatementColumns columns) {
        this.columns = columns;
    }

    public ParsedRow parse(CsvRecords.Record record) {
        LocalDate date = parseDate(field(record, columns.date()));
        if (date == null) {
            throw new InvalidRowException("Ngày không hợp lệ: '" + field(record, columns.date()) + "'.");
        }

        BigDecimal signed = parseAmount(field(record, columns.amount()));
        if (signed == null || signed.signum() == 0) {
            BigDecimal debit = parseAmount(field(record, columns.debit()));
            BigDecimal credit = parseAmount(field(record, columns.credit()));
            if (debit != null && debit.signum() != 0) {
                signed = debit.abs().negate();
            } else if (credit != null && credit.signum() != 0) {
                signed = credit.abs();
            }
        }
        if (signed == null || signed.signum() == 0) {
            throw new InvalidRowException("Số tiền trống, bằng 0 hoặc không đọc được.");
        }
        if (signed.stripTrailingZeros().scale() > 2) {
            throw new InvalidRowException("Số tiền có quá 2 chữ số thập phân: " + signed.toPlainString() + ".");
        }

        Boolean income = direction(field(record, columns.direction()));
        if (income == null) {
            income = signed.signum() > 0;
        }

        String counterparty = field(record, columns.counterparty()).strip();
        if (counterparty.length() > MAX_COUNTERPARTY) {
            counterparty = counterparty.substring(0, MAX_COUNTERPARTY);
        }
        return new ParsedRow(record.line(), date, signed.abs(), income, counterparty.isEmpty() ? null : counterparty);
    }

    /** {@code _try_parse_date}: the date formats in legacy order, then ISO date-time; a time after a space is ignored. */
    static LocalDate parseDate(String text) {
        String value = text.strip();
        if (value.isEmpty()) {
            return null;
        }
        String datePart = value.split("[ T]", 2)[0];
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(datePart, format);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        try {
            return LocalDateTime.parse(value).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * A money string as BigDecimal, or null. Accepts "-1500000", "1,500,000", "1.500.000", "1.500.000,50",
     * "1,500,000.50" and "(1,500)" (accounting negative); spaces, "VND" and "đ" are ignored.
     */
    static BigDecimal parseAmount(String text) {
        String value = text.strip()
                .replace("VND", "")
                .replace("vnd", "")
                .replace("đ", "")
                .replace("₫", "")
                .replaceAll("\\s", "");
        if (value.isEmpty()) {
            return null;
        }
        boolean negative = false;
        if (value.startsWith("(") && value.endsWith(")")) {
            negative = true;
            value = value.substring(1, value.length() - 1);
        }
        if (value.startsWith("-")) {
            negative = !negative;
            value = value.substring(1);
        } else if (value.startsWith("+")) {
            value = value.substring(1);
        }
        int lastDot = value.lastIndexOf('.');
        int lastComma = value.lastIndexOf(',');
        String plain;
        if (lastDot >= 0 && lastComma >= 0) {
            // both: whichever comes last is the decimal separator
            plain = lastComma > lastDot ? value.replace(".", "").replace(',', '.') : value.replace(",", "");
        } else if (lastDot >= 0) {
            plain = DOT_GROUPS.matcher(value).matches() ? value.replace(".", "") : value;
        } else if (lastComma >= 0) {
            plain = COMMA_GROUPS.matcher(value).matches() ? value.replace(",", "") : value.replace(',', '.');
        } else {
            plain = value;
        }
        try {
            BigDecimal amount = new BigDecimal(plain);
            return negative ? amount.negate() : amount;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The legacy direction words plus Vietnamese ones; null when the column is absent or says nothing usable. */
    static Boolean direction(String text) {
        String value = StatementColumns.withoutDiacritics(text).strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "debit", "dr", "out", "expense", "chi", "no", "ghi no" -> false;
            case "credit", "cr", "in", "income", "thu", "co", "ghi co" -> true;
            default -> null;
        };
    }

    private static String field(CsvRecords.Record record, int index) {
        return index >= 0 && index < record.fields().size() ? record.fields().get(index) : "";
    }

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }
}
