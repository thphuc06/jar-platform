package io.github.thphuc06.jars.advisor.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.ApiTestSupport;
import io.github.thphuc06.jars.advisor.internal.persistence.ProductCatalog.Sheet;
import io.github.thphuc06.jars.advisor.internal.persistence.ProductCatalog.Summary;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The catalogue as Flyway V12 seeded it, read through the repository the tools use (ADR-0027): all 18 products of the
 * research are there, the figures follow the rules the research states, and everything is marked illustrative.
 */
class ProductCatalogTest extends ApiTestSupport {

    static final List<String> CODES = List.of(
            "LBB-TD-ONL",
            "LBB-CA-DEMAND",
            "LBB-SAV-FLEX",
            "LBB-SAV-ACCUM",
            "LBB-CD-RETAIL",
            "LBF-LIQ",
            "LBF-BOND",
            "LBF-BAL",
            "LBF-IDX30",
            "LBF-EQ",
            "LBB-SIP",
            "LBB-LN-UNSEC",
            "LBB-LN-HOME",
            "LBB-LN-AUTO",
            "LBB-OD",
            "LBB-CC-CASHBACK",
            "LBB-INS-HEALTH",
            "LBB-INS-LIFE");

    @Autowired
    ProductCatalog catalog;

    private Map<String, Summary> byCode() {
        return catalog.summaries(null).stream().collect(Collectors.toMap(Summary::code, s -> s));
    }

    @Test
    void allEighteenProductsAreSeeded_inCatalogueOrder() {
        assertThat(catalog.summaries(null)).extracting(Summary::code).containsExactlyElementsOf(CODES);
        assertThat(catalog.codes()).containsExactlyElementsOf(CODES);
    }

    @Test
    void everyProductIsMarkedIllustrative_andCarriesTheDateOfItsFigures() {
        assertThat(catalog.summaries(null)).allSatisfy(s -> assertThat(s.asOf()).isEqualTo(LocalDate.of(2026, 11, 13)));
        assertThat(jdbc.sql("SELECT count(*) FROM catalog_products WHERE NOT illustrative")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void groupsHaveTheirProducts() {
        assertThat(catalog.summaries("DEPOSIT")).hasSize(5);
        assertThat(catalog.summaries("FUND")).hasSize(6);
        assertThat(catalog.summaries("LOAN")).hasSize(4);
        assertThat(catalog.summaries("CARD")).extracting(Summary::code).containsExactly("LBB-CC-CASHBACK");
        assertThat(catalog.summaries("INSURANCE"))
                .extracting(Summary::code)
                .containsExactly("LBB-INS-HEALTH", "LBB-INS-LIFE");
        assertThat(catalog.summaries("GOLD")).isEmpty();
    }

    @Test
    void theRiskScaleFollowsTheResearch() {
        Map<String, Summary> p = byCode();
        assertThat(List.of(
                        "LBB-TD-ONL",
                        "LBB-CA-DEMAND",
                        "LBB-SAV-FLEX",
                        "LBB-SAV-ACCUM",
                        "LBB-CD-RETAIL",
                        "LBB-INS-HEALTH"))
                .allSatisfy(code -> assertThat(p.get(code).riskLevel()).isEqualTo(1));
        assertThat(p.get("LBF-LIQ").riskLevel()).isEqualTo(2);
        assertThat(p.get("LBF-BOND").riskLevel()).isEqualTo(2);
        assertThat(p.get("LBF-BAL").riskLevel()).isEqualTo(3);
        assertThat(p.get("LBF-IDX30").riskLevel()).isEqualTo(4);
        assertThat(p.get("LBF-EQ").riskLevel()).isEqualTo(5);
        // the SIP takes the risk of the fund chosen, the card is riskier when a balance is carried
        assertThat(p.get("LBB-SIP").riskLevel()).isEqualTo(2);
        assertThat(p.get("LBB-SIP").riskLevelMax()).isEqualTo(5);
        assertThat(p.get("LBB-CC-CASHBACK").riskLevel()).isEqualTo(3);
        assertThat(p.get("LBB-CC-CASHBACK").riskLevelMax()).isEqualTo(4);
        // a bond fund is never riskier than an equity fund
        assertThat(p.get("LBF-BOND").riskLevel()).isLessThan(p.get("LBF-EQ").riskLevel());
        assertThat(p.values().stream()
                        .filter(s -> s.group().equals("LOAN") || s.group().equals("CARD")))
                .allSatisfy(s -> assertThat(s.riskKind()).isEqualTo("DEBT"));
    }

    @Test
    void theRiskProfileMappingHolds_riskierInvestmentsAreNotListedForCautiousProfiles() {
        Map<String, Summary> p = byCode();
        for (Summary s : p.values()) {
            if (!s.riskKind().equals("INVESTMENT")) {
                continue;
            }
            if (s.riskLevel() >= 3) {
                assertThat(s.suitableProfiles())
                        .as("%s is level %d: not a core product for CONSERVATIVE", s.code(), s.riskLevel())
                        .doesNotContain("CONSERVATIVE")
                        .isNotEmpty();
            }
            if (s.riskLevel() == 5) {
                assertThat(s.suitableProfiles()).as(s.code()).containsExactly("GROWTH");
            }
        }
        assertThat(p.get("LBF-IDX30").suitableProfiles()).containsExactly("BALANCED", "GROWTH");
        assertThat(p.get("LBF-BAL").suitableProfiles()).containsExactly("BALANCED", "GROWTH");
        // the unsecured loan suits no profile as an investment: it is the last resort
        assertThat(p.get("LBB-LN-UNSEC").suitableProfiles()).isEmpty();
        assertThat(p.values().stream()
                        .flatMap(s -> s.suitableProfiles().stream())
                        .distinct())
                .containsExactlyInAnyOrder("CONSERVATIVE", "BALANCED", "GROWTH");
    }

    @Test
    void minimumsAreTheOnesInTheSheets() {
        Map<String, Summary> p = byCode();
        assertThat(p.get("LBB-TD-ONL").minAmountVnd()).isEqualTo(1_000_000L);
        assertThat(p.get("LBB-SAV-FLEX").minAmountVnd()).isEqualTo(5_000_000L);
        assertThat(p.get("LBB-CD-RETAIL").minAmountVnd()).isEqualTo(20_000_000L);
        assertThat(p.get("LBF-LIQ").minAmountVnd()).isEqualTo(10_000L);
        assertThat(p.get("LBB-SIP").minAmountVnd()).isEqualTo(100_000L);
        assertThat(p.get("LBB-SIP").minAmountUnit()).isEqualTo("mỗi tháng");
        assertThat(p.get("LBB-LN-UNSEC").minMonthlyIncomeVnd()).isEqualTo(8_000_000L);
        assertThat(p.get("LBB-LN-HOME").minMonthlyIncomeVnd()).isEqualTo(15_000_000L);
        assertThat(p.get("LBB-LN-AUTO").minMonthlyIncomeVnd()).isEqualTo(12_000_000L);
        assertThat(p.get("LBB-OD").minMonthlyIncomeVnd()).isEqualTo(7_000_000L);
        assertThat(p.get("LBB-CC-CASHBACK").minMonthlyIncomeVnd()).isEqualTo(8_000_000L);
        assertThat(p.get("LBB-INS-LIFE").minMonthlyIncomeVnd()).isEqualTo(15_000_000L);
        assertThat(p.get("LBF-EQ").minMonthlyIncomeVnd()).isNull();
    }

    @Test
    void theTermDepositRateTableIsConsistent() {
        Sheet sheet = catalog.sheets(List.of("LBB-TD-ONL")).getFirst();

        @SuppressWarnings("unchecked")
        Map<String, Object> table = (Map<String, Object>) sheet.rateTable();
        @SuppressWarnings("unchecked")
        List<Number> tenors = (List<Number>) table.get("tenorMonths");
        @SuppressWarnings("unchecked")
        List<Number> online = (List<Number>) table.get("online");
        @SuppressWarnings("unchecked")
        List<Number> counter = (List<Number>) table.get("counter");
        assertThat(tenors).extracting(Number::intValue).containsExactly(1, 3, 6, 9, 12, 13, 18, 24);
        assertThat(online).hasSameSizeAs(tenors);
        assertThat(counter).hasSameSizeAs(tenors);
        for (int i = 0; i < tenors.size(); i++) {
            if (i > 0) {
                assertThat(online.get(i).doubleValue())
                        .as("a longer term never pays less")
                        .isGreaterThanOrEqualTo(online.get(i - 1).doubleValue());
            }
            assertThat(counter.get(i).doubleValue())
                    .isCloseTo(online.get(i).doubleValue() - 0.30, org.assertj.core.data.Offset.offset(0.001));
            if (tenors.get(i).intValue() < 6) {
                assertThat(online.get(i).doubleValue())
                        .as("the 4.75% cap below six months")
                        .isLessThanOrEqualTo(4.75);
            }
        }
        assertThat(online.get(4).doubleValue()).isEqualTo(6.40); // 12 months, the figure the advisor is asked for
        assertThat(sheet.figures()).anyMatch(f -> f.contains("1.000.000 VND online"));
        assertThat(catalog.sheets(List.of("LBF-BAL")).getFirst().rateTable()).isNull();
    }

    @Test
    void everySheetHasItsCustomerTextAndTheCautionsTheAdvisorMustMention() {
        List<Sheet> sheets = catalog.sheets(CODES);

        assertThat(sheets).hasSize(18);
        assertThat(sheets).allSatisfy(s -> {
            assertThat(s.description()).as(s.summary().code()).hasSizeGreaterThan(100);
            assertThat(s.eligibility()).isNotBlank();
            assertThat(s.suits()).isNotBlank();
            assertThat(s.notSuits()).isNotBlank();
            assertThat(s.liquidity()).isNotBlank();
            assertThat(s.figures()).isNotEmpty();
            assertThat(s.cautions()).as(s.summary().code()).hasSizeBetween(3, 5);
            assertThat(s.summary().summary()).isNotBlank();
            assertThat(s.summary().headline()).isNotBlank();
        });
        // funds say that returns are historical, and the bank's products say what leaving early costs
        Map<String, Sheet> p =
                sheets.stream().collect(Collectors.toMap(s -> s.summary().code(), s -> s));
        assertThat(p.get("LBF-BAL").cautions()).anyMatch(c -> c.contains("không bảo đảm"));
        assertThat(p.get("LBB-TD-ONL").cautions().getFirst()).contains("0,10%");
        assertThat(p.get("LBB-INS-LIFE").cautions()).anyMatch(c -> c.contains("21 ngày"));
        assertThat(p.get("LBB-LN-HOME").figures()).anyMatch(f -> f.contains("9,6%") && f.contains("12 months"));
    }

    @Test
    void unknownCodesGiveNoSheet_andNoException() {
        assertThat(catalog.sheets(List.of("NOPE"))).isEmpty();
        assertThat(catalog.sheets(List.of())).isEmpty();
        assertThat(catalog.sheets(List.of("NOPE", "LBF-EQ")))
                .extracting(s -> s.summary().code())
                .containsExactly("LBF-EQ");
    }
}
