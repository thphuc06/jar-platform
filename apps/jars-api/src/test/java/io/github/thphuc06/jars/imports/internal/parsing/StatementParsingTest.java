package io.github.thphuc06.jars.imports.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.imports.internal.parsing.Categorizer.JarCode;
import io.github.thphuc06.jars.imports.internal.parsing.StatementRowParser.ParsedRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The statement rules ported from {@code ingest_statement_vn}, {@code _safe_float}, {@code _try_parse_date} and
 * {@code _heuristic_category} of the hackathon backend. Expected values follow the legacy behaviour except where a
 * test says "fix".
 */
class StatementParsingTest {

    // ---------------------------------------------------------------- CSV

    @Test
    void csv_handlesQuotesEmbeddedDelimitersLineBreaksBomAndBlankLines() {
        String text = "﻿date,description,amount\r\n"
                + "2026-10-01,\"Grab, chuyến sáng\",-45000\r\n"
                + "\r\n"
                + "2026-10-02,\"nói \"\"cảm ơn\"\"\nnhiều dòng\",-1\n";

        List<CsvRecords.Record> records = CsvRecords.parse(text, ',');

        assertThat(records).hasSize(3);
        assertThat(records.get(0).fields()).containsExactly("date", "description", "amount");
        assertThat(records.get(1).fields()).containsExactly("2026-10-01", "Grab, chuyến sáng", "-45000");
        assertThat(records.get(2).fields().get(1)).isEqualTo("nói \"cảm ơn\"\nnhiều dòng");
        assertThat(records.get(2).line())
                .as("line numbers count the blank line")
                .isEqualTo(4);
    }

    @Test
    void csv_detectsTabSeparatedFiles() {
        assertThat(CsvRecords.delimiterOf("ngay\tso_tien\tnoi_dung\n")).isEqualTo('\t');
        assertThat(CsvRecords.delimiterOf("date,amount\n")).isEqualTo(',');
    }

    // ---------------------------------------------------------------- columns

    @Test
    void columns_matchLegacyKeysCaseAndSpaceInsensitively() {
        StatementColumns c = StatementColumns.detect(List.of("Transaction Date", "Memo", "Amount", "Type"));

        assertThat(c).isEqualTo(new StatementColumns(0, 2, -1, -1, 1, 3));
    }

    @Test
    void columns_matchVietnameseHeadersWithDiacritics() {
        StatementColumns c =
                StatementColumns.detect(List.of("Ngày giao dịch", "Nội dung", "Ghi nợ", "Ghi có", "Số dư"));

        assertThat(c.date()).isZero();
        assertThat(c.counterparty()).isEqualTo(1);
        assertThat(c.debit()).isEqualTo(2);
        assertThat(c.credit()).isEqualTo(3);
        assertThat(c.amount()).isEqualTo(-1);
    }

    @Test
    void columns_withoutADateOrAnAmountRejectTheWholeFile() {
        assertThatThrownBy(() -> StatementColumns.detect(List.of("amount", "memo")))
                .isInstanceOf(InvalidStatementException.class)
                .hasMessageContaining("cột ngày");
        assertThatThrownBy(() -> StatementColumns.detect(List.of("date", "memo")))
                .isInstanceOf(InvalidStatementException.class)
                .hasMessageContaining("cột số tiền");
    }

    // ---------------------------------------------------------------- values

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "2026-10-31|2026-10-31",
                "31/10/2026|2026-10-31",
                "31-10-2026|2026-10-31",
                // day first, like the legacy format order: 03/04 is the 3rd of April
                "03/04/2026|2026-04-03",
                // only month-first can read it
                "12/31/2026|2026-12-31",
                "2026/10/31|2026-10-31",
                "31/10/2026 08:15:00|2026-10-31",
                "2026-10-31T08:15:00|2026-10-31",
            })
    void dates_followTheLegacyFormatOrder(String text, LocalDate expected) {
        assertThat(StatementRowParser.parseDate(text)).isEqualTo(expected);
    }

    @Test
    void dates_thatDoNotExistAreRejected() {
        assertThat(StatementRowParser.parseDate("31/02/2026")).isNull();
        assertThat(StatementRowParser.parseDate("yesterday")).isNull();
        assertThat(StatementRowParser.parseDate("")).isNull();
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "-1500000|-1500000",
                "1,500,000|1500000",
                // fix: the legacy _safe_float read "1.000" as 1.0; in a Vietnamese statement it is one thousand
                "1.000|1000",
                "1.500.000|1500000",
                "1.500.000,50|1500000.50",
                "1,500,000.50|1500000.50",
                "12.5|12.5",
                "(45,000)|-45000",
                "45 000 VND|45000",
                "45.000đ|45000",
            })
    void amounts_readVietnameseAndInternationalGrouping(String text, BigDecimal expected) {
        assertThat(StatementRowParser.parseAmount(text)).isEqualByComparingTo(expected);
    }

    @Test
    void amounts_thatAreNotNumbersAreNull() {
        assertThat(StatementRowParser.parseAmount("")).isNull();
        assertThat(StatementRowParser.parseAmount("abc")).isNull();
    }

    // ---------------------------------------------------------------- rows

    @Test
    void row_withASignedAmountTakesItsDirectionFromTheSign() {
        StatementRowParser parser = parser("date", "description", "amount");

        ParsedRow out = parser.parse(record(2, "2026-10-01", "GRAB *TRIP", "-45,000"));
        ParsedRow in = parser.parse(record(3, "2026-10-05", "CONG TY ABC LUONG T10", "15,000,000"));

        assertThat(out)
                .isEqualTo(new ParsedRow(2, LocalDate.of(2026, 10, 1), new BigDecimal("45000"), false, "GRAB *TRIP"));
        assertThat(in.income()).isTrue();
        assertThat(in.amount()).isEqualByComparingTo("15000000");
    }

    @Test
    void row_withDebitAndCreditColumnsUsesWhicheverIsFilled() {
        StatementRowParser parser = parser("ngay", "noi_dung", "ghi_no", "ghi_co");

        assertThat(parser.parse(record(2, "01/10/2026", "TIEN DIEN", "350.000", ""))
                        .income())
                .isFalse();
        assertThat(parser.parse(record(3, "02/10/2026", "NHAN CK", "", "2.000.000"))
                        .income())
                .isTrue();
    }

    @Test
    void row_explicitDirectionWinsOverTheSign() {
        StatementRowParser parser = parser("date", "memo", "amount", "type");

        // a statement that lists every amount as positive and says the direction in its own column
        assertThat(parser.parse(record(2, "2026-10-01", "Highlands", "55000", "debit"))
                        .income())
                .isFalse();
        assertThat(parser.parse(record(3, "2026-10-01", "Refund", "55000", "CR"))
                        .income())
                .isTrue();
    }

    @Test
    void row_missingCounterpartyIsAccepted_asInTheLegacyParser() {
        assertThat(parser("date", "amount")
                        .parse(record(2, "2026-10-01", "-1000"))
                        .counterparty())
                .isNull();
    }

    @Test
    void row_withABadDateOrNoAmountIsRejectedWithAMessageForTheUser() {
        StatementRowParser parser = parser("date", "memo", "amount");

        assertThatThrownBy(() -> parser.parse(record(2, "not a date", "x", "-1000")))
                .isInstanceOf(InvalidRowException.class)
                .hasMessageContaining("Ngày không hợp lệ");
        assertThatThrownBy(() -> parser.parse(record(3, "2026-10-01", "x", "0")))
                .isInstanceOf(InvalidRowException.class)
                .hasMessageContaining("Số tiền");
        assertThatThrownBy(() -> parser.parse(record(4, "2026-10-01", "x", "1.0005")))
                .isInstanceOf(InvalidRowException.class)
                .hasMessageContaining("thập phân");
    }

    // ---------------------------------------------------------------- categories

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        // legacy rules
        "RENT OCT 2026, NEC, 018f0000-0000-7000-8000-000000000005",
        "EVN HCMC ELECTRIC, NEC, 018f0000-0000-7000-8000-000000000004",
        "GRAB *TRIP 123, NEC, 018f0000-0000-7000-8000-000000000003",
        "HIGHLANDS COFFEE Q1, PLAY, 018f0000-0000-7000-8000-000000000001",
        // Vietnamese additions
        "Thanh toán tiền điện tháng 10, NEC, 018f0000-0000-7000-8000-000000000004",
        "Be Group - chuyến xe, NEC, 018f0000-0000-7000-8000-000000000003",
        "Phúc Long Coffee & Tea, PLAY, 018f0000-0000-7000-8000-000000000001",
        // short words match whole words only: BEEF is not the ride-hailing app Be
        "BEEF STEAK HOUSE, NEC, 018f0000-0000-7000-8000-00000000000e",
        // legacy jar_misc / cat_other
        "SHOPEE ORDER, NEC, 018f0000-0000-7000-8000-00000000000e",
    })
    void expenses_areCategorizedByTheLegacyKeywords(String counterparty, JarCode jar, String category) {
        assertThat(Categorizer.guess(counterparty, false))
                .isEqualTo(new Categorizer.Guess(jar, java.util.UUID.fromString(category)));
    }

    @Test
    void incomes_goToNoJarAndAreCategorizedByKeyword() {
        assertThat(Categorizer.guess("CONG TY ABC TRA LUONG T10", true))
                .isEqualTo(new Categorizer.Guess(null, Categorizer.SALARY));
        assertThat(Categorizer.guess("Thưởng Tết", true)).isEqualTo(new Categorizer.Guess(null, Categorizer.BONUS));
        assertThat(Categorizer.guess("NHAN CK TU BAN", true))
                .isEqualTo(new Categorizer.Guess(null, Categorizer.OTHER_INCOME));
        assertThat(Categorizer.guess(null, true)).isEqualTo(new Categorizer.Guess(null, Categorizer.OTHER_INCOME));
    }

    private static StatementRowParser parser(String... header) {
        return new StatementRowParser(StatementColumns.detect(List.of(header)));
    }

    private static CsvRecords.Record record(int line, String... fields) {
        return new CsvRecords.Record(line, List.of(fields));
    }
}
