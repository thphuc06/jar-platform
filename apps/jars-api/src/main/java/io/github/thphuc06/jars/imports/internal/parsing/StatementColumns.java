package io.github.thphuc06.jars.imports.internal.parsing;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which column holds what, recognised from the header line. Ported from {@code ingest_statement_vn} of the hackathon
 * backend ({@code _DATE_KEYS}, {@code _AMOUNT_KEYS}, ...), with two changes:
 *
 * <ul>
 *   <li>headers are compared without Vietnamese diacritics, so "Ngày giao dịch" matches the legacy key
 *       {@code ngay_giao_dich} (the legacy code only matched the ASCII spelling);
 *   <li>candidates are tried in a fixed order (the legacy sets had none), and a few headers of Vietnamese bank exports
 *       were added ({@code ghi_no}, {@code ghi_co}, {@code dien_giai}, {@code mo_ta}).
 * </ul>
 *
 * @param amount a signed amount column, or -1
 * @param debit a money-out column, or -1
 * @param credit a money-in column, or -1
 * @param counterparty -1 when there is none (rows are still accepted)
 * @param direction an explicit debit/credit column, or -1
 */
public record StatementColumns(int date, int amount, int debit, int credit, int counterparty, int direction) {

    static final List<String> DATE_KEYS =
            List.of("date", "transaction_date", "posted_date", "ngay", "ngay_giao_dich", "ngay_gd", "ngay_hach_toan");
    static final List<String> AMOUNT_KEYS = List.of("amount", "so_tien", "value");
    static final List<String> DEBIT_KEYS =
            List.of("debit", "withdrawal", "chi", "outflow", "ghi_no", "so_tien_ghi_no", "tien_ra");
    static final List<String> CREDIT_KEYS =
            List.of("credit", "deposit", "thu", "inflow", "ghi_co", "so_tien_ghi_co", "tien_vao");
    static final List<String> COUNTERPARTY_KEYS =
            List.of("counterparty", "merchant", "description", "memo", "narrative", "noi_dung", "dien_giai", "mo_ta");
    static final List<String> DIRECTION_KEYS = List.of("direction", "type", "dr_cr");

    /**
     * @throws InvalidStatementException when there is no date column or no way to read an amount
     */
    public static StatementColumns detect(List<String> header) {
        List<String> keys = new ArrayList<>();
        for (String h : header) {
            keys.add(normalizeKey(h));
        }
        int date = find(keys, DATE_KEYS);
        int amount = find(keys, AMOUNT_KEYS);
        int debit = find(keys, DEBIT_KEYS);
        int credit = find(keys, CREDIT_KEYS);
        if (date < 0) {
            throw new InvalidStatementException(
                    "Không tìm thấy cột ngày (date, ngay_giao_dich, ...) trong dòng tiêu đề.");
        }
        if (amount < 0 && debit < 0 && credit < 0) {
            throw new InvalidStatementException(
                    "Không tìm thấy cột số tiền (amount, so_tien, hoặc cặp ghi nợ/ghi có) trong dòng tiêu đề.");
        }
        return new StatementColumns(
                date, amount, debit, credit, find(keys, COUNTERPARTY_KEYS), find(keys, DIRECTION_KEYS));
    }

    /** {@code _normalize_key} of the legacy code (trim, lower case, spaces to underscores) after removing diacritics. */
    static String normalizeKey(String header) {
        return withoutDiacritics(header).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    static String withoutDiacritics(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D');
    }

    private static int find(List<String> keys, List<String> candidates) {
        for (String candidate : candidates) {
            int index = keys.indexOf(candidate);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }
}
