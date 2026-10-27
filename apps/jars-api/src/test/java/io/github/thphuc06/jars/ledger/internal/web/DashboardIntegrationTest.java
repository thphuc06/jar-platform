package io.github.thphuc06.jars.ledger.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.identity.CurrentUser;
import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.ledger.TransactionType;
import io.github.thphuc06.jars.ledger.internal.PostingService;
import io.github.thphuc06.jars.shared.BusinessTime;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * GET /dashboard and GET /balances/daily on PostgreSQL 17. The data straddles month boundaries on purpose: a month
 * is a VIETNAMESE month, so 2026-08-31T17:30Z (00:30 on 1 September in Vietnam) belongs to September.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DashboardIntegrationTest {

    static final SimpleGrantedAuthority READ = new SimpleGrantedAuthority("SCOPE_jars-api/read");
    static final UUID FOOD = UUID.fromString("01900000-0000-7000-8000-000000000001");
    static final UUID FUN = UUID.fromString("01900000-0000-7000-8000-000000000008");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PostingService posting;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    IdentityApi identity;

    UUID userId;

    @BeforeEach
    void signIn() {
        userId = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, cognito_sub, email) VALUES (?, ?, ?)")
                .params(userId, "sub-" + userId, userId + "@example.com")
                .update();
        given(identity.currentUser()).willAnswer(call -> new CurrentUser(userId, "u@example.com", null, "vi-VN", null));
    }

    /** August and September 2026, plus one expense on each side of the September boundary (Vietnam time). */
    private void seedAugustAndSeptember() {
        income("8000000", "2026-08-05T03:00:00Z");
        transfer("POOL", "NEC", "3000000", "2026-08-05T04:00:00Z");
        expense("NEC", FOOD, "500000", "2026-08-20T05:00:00Z");
        expense("NEC", FOOD, "100000", "2026-08-31T17:30:00Z"); // 1 Sep 00:30 in Vietnam → September

        income("10000000", "2026-09-05T03:00:00Z");
        transfer("POOL", "PLAY", "2000000", "2026-09-05T04:00:00Z");
        expense("PLAY", FUN, "280000", "2026-09-10T05:00:00Z");
        expense("PLAY", null, "50000", "2026-09-12T05:00:00Z"); // no category
        expense("NEC", FOOD, "200000", "2026-09-15T05:00:00Z");
        expense("NEC", FOOD, "70000", "2026-09-30T17:00:00Z"); // 1 Oct 00:00 in Vietnam → October
    }

    @Test
    void dashboard_aggregatesTheVietnameseMonth() {
        seedAugustAndSeptember();

        assertThat(get("/api/v1/dashboard?month=2026-09"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"month": "2026-09", "currency": "VND",
                         "income": "10000000.00", "expense": "630000.00", "net": "9370000.00",
                         "balances": {"total": "16800000.00", "unallocated": "13000000.00", "allocated": "3800000.00"},
                         "byCategory": [{"name": "Ăn uống", "amount": "300000.00"},
                                        {"name": "Giải trí", "amount": "280000.00"},
                                        {"categoryId": null, "name": null, "amount": "50000.00"}],
                         "trend": [{"month": "2026-04", "income": "0.00", "expense": "0.00"},
                                   {"month": "2026-05", "income": "0.00", "expense": "0.00"},
                                   {"month": "2026-06", "income": "0.00", "expense": "0.00"},
                                   {"month": "2026-07", "income": "0.00", "expense": "0.00"},
                                   {"month": "2026-08", "income": "8000000.00", "expense": "500000.00"},
                                   {"month": "2026-09", "income": "10000000.00", "expense": "630000.00"}]}
                        """);
    }

    @Test
    void dashboard_spentPerJar_everyJarListed_poolHidden() {
        seedAugustAndSeptember();

        MvcTestResult result = get("/api/v1/dashboard?month=2026-09");

        assertThat(result).bodyJson().isLenientlyEqualTo("""
                        {"byJar": [{"code": "NEC", "spent": "300000.00", "balance": "2130000.00"},
                                   {"code": "FFA", "spent": "0.00"}, {"code": "LTSS", "spent": "0.00"},
                                   {"code": "EDU", "spent": "0.00"},
                                   {"code": "PLAY", "spent": "330000.00", "balance": "1670000.00"},
                                   {"code": "GIVE", "spent": "0.00"}]}
                        """);
        assertThat(result).bodyJson().extractingPath("$.byJar.length()").isEqualTo(6);
    }

    @Test
    void dashboard_nextMonthGetsTheExpenseRecordedJustBeforeMidnightUtc() {
        seedAugustAndSeptember();

        assertThat(get("/api/v1/dashboard?month=2026-10")).bodyJson().isLenientlyEqualTo("""
                        {"income": "0.00", "expense": "70000.00"}
                        """);
    }

    @Test
    void dashboard_newUser_isAllZeros_withSixEmptyMonths() {
        assertThat(get("/api/v1/dashboard?month=2026-09"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"income": "0.00", "expense": "0.00", "balances": {"total": "0.00"}, "byCategory": []}
                        """);
        assertThat(get("/api/v1/dashboard?month=2026-09"))
                .bodyJson()
                .extractingPath("$.trend.length()")
                .isEqualTo(6);
    }

    @Test
    void dashboard_withoutMonth_usesTheCurrentVietnameseMonth() {
        assertThat(get("/api/v1/dashboard"))
                .bodyJson()
                .extractingPath("$.month")
                .isEqualTo(YearMonth.from(BusinessTime.today(Clock.systemUTC())).toString());
    }

    @Test
    void dailyBalances_openingBalancePlusRunningSum_endsAtTheJarTotal() {
        LocalDate today = BusinessTime.today(Clock.systemUTC());
        income("500000", noonInVietnam(today.minusDays(30))); // before the window: part of the opening balance
        income("1000000", noonInVietnam(today.minusDays(5)));
        transfer("POOL", "PLAY", "1000000", noonInVietnam(today.minusDays(5)));
        expense("PLAY", FUN, "200000", noonInVietnam(today.minusDays(2)));

        MvcTestResult result = get("/api/v1/balances/daily?days=7");

        assertThat(result).hasStatus(HttpStatus.OK).bodyJson().isLenientlyEqualTo("""
                        {"currency": "VND", "items": [
                            {"date": "%s", "balance": "500000.00"},
                            {"date": "%s", "balance": "1500000.00"},
                            {"balance": "1500000.00"}, {"balance": "1500000.00"},
                            {"date": "%s", "balance": "1300000.00"},
                            {"balance": "1300000.00"},
                            {"date": "%s", "balance": "1300000.00"}]}
                        """.formatted(
                        today.minusDays(6), today.minusDays(5), today.minusDays(2), today));
        // the last point of the curve must equal the sum of the jar balances (same money, two ways)
        assertThat(get("/api/v1/jars")).bodyJson().extractingPath("$.total").isEqualTo("1300000.00");
    }

    @Test
    void invalidParameters_areValidationErrors() {
        assertThat(get("/api/v1/dashboard?month=2026-13")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(get("/api/v1/balances/daily?days=400")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // --- helpers ---

    private MvcTestResult get(String uri) {
        return mvc.get().uri(uri).with(jwt().authorities(READ)).exchange();
    }

    private static String noonInVietnam(LocalDate day) {
        return day.atTime(LocalTime.NOON).atZone(BusinessTime.ZONE).toInstant().toString();
    }

    private void income(String amount, String at) {
        posting.post(
                userId,
                UUID.randomUUID(),
                new PostingService.PostTransaction(
                        TransactionType.INCOME, new BigDecimal(amount), null, null, null, null, Instant.parse(at)));
    }

    private void expense(String jarCode, UUID categoryId, String amount, String at) {
        posting.post(
                userId,
                UUID.randomUUID(),
                new PostingService.PostTransaction(
                        TransactionType.EXPENSE,
                        new BigDecimal(amount),
                        jarId(jarCode),
                        categoryId,
                        null,
                        null,
                        Instant.parse(at)));
    }

    private void transfer(String from, String to, String amount, String at) {
        posting.transfer(
                userId,
                UUID.randomUUID(),
                new PostingService.Transfer(jarId(from), jarId(to), new BigDecimal(amount), null, Instant.parse(at)));
    }

    private UUID jarId(String code) {
        return jdbc.sql("SELECT id FROM jars WHERE user_id = ? AND code = ?")
                .params(userId, code)
                .query(UUID.class)
                .single();
    }
}
