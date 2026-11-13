package io.github.thphuc06.jars.advisor.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.ApiTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The two catalogue tools as the model calls them (through their {@link ToolCallback}, with a {@link ToolContext}),
 * against the seeded catalogue (ADR-0027): the compact list, the sheets, the cap, and a readable answer for what does
 * not exist.
 */
class CatalogToolsTest extends ApiTestSupport {

    @Autowired
    CatalogTools tools;

    @Autowired
    AdvisorTools userTools;

    private ToolCallback tool(String name) {
        for (ToolCallback callback : ToolCallbacks.from(tools)) {
            if (callback.getToolDefinition().name().equals(name)) {
                return callback;
            }
        }
        throw new AssertionError("no tool " + name);
    }

    private String raw(String name, String arguments) {
        return tool(name).call(arguments, new ToolContext(Map.of("test", "x")));
    }

    private JsonNode call(String name, String arguments) {
        return mapper.readTree(raw(name, arguments));
    }

    @Test
    void theModelIsOfferedTwoReadOnlyTools_withoutAUserParameter_andTheCapIsStated() {
        List<String> names = new ArrayList<>();
        for (ToolCallback callback : ToolCallbacks.from(tools)) {
            names.add(callback.getToolDefinition().name());
            assertThat(callback.getToolDefinition().inputSchema())
                    .as("reference data, not user data: no user parameter")
                    .doesNotContain("userId")
                    .doesNotContain("user");
        }

        assertThat(names).containsExactlyInAnyOrder("listProducts", "getProductDetails");
        assertThat(tool("getProductDetails").getToolDefinition().description())
                .contains("tối đa " + CatalogTools.MAX_SHEETS)
                .contains("unknownCodes");
        assertThat(tool("listProducts").getToolDefinition().description()).contains("TẤT CẢ");
        assertThat(AdvisorService.SYSTEM_PROMPT).contains("listProducts", "getProductDetails");
    }

    @Test
    void thePromptNamesEveryToolTheModelHas_andNoOtherToolName() {
        List<String> names = new ArrayList<>();
        for (ToolCallback callback : ToolCallbacks.from(tools, userTools)) {
            names.add(callback.getToolDefinition().name());
        }

        assertThat(names).hasSize(7);
        names.forEach(name -> assertThat(AdvisorService.SYSTEM_PROMPT).contains(name));
        // a name like getFoo in the prompt that is not a tool would send the model to a call that does not exist
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(?:get|list)[A-Z]\\w+").matcher(AdvisorService.SYSTEM_PROMPT);
        while (m.find()) {
            assertThat(names).as(m.group()).contains(m.group());
        }
    }

    @Test
    void listProducts_returnsAllEighteenCompactly_markedIllustrativeWithTheDate() {
        JsonNode result = call("listProducts", "{}");

        assertThat(result.get("notice").asString())
                .contains("MINH HỌA")
                .contains("hư cấu")
                .contains("2026-11-13");
        assertThat(result.get("message").isNull()).isTrue();
        JsonNode products = result.get("products");
        assertThat(products).hasSize(18);
        JsonNode term = products.get(0);
        assertThat(term.get("code").asString()).isEqualTo("LBB-TD-ONL");
        assertThat(term.get("group").asString()).isEqualTo("DEPOSIT");
        assertThat(term.get("riskLevel").asString()).isEqualTo("1");
        assertThat(term.get("riskKind").asString()).isEqualTo("INVESTMENT");
        assertThat(term.get("minAmountVnd").asLong()).isEqualTo(1_000_000L);
        assertThat(term.get("headline").asString()).contains("6,60");
        assertThat(term.get("profiles"))
                .extracting(JsonNode::asString)
                .containsExactly("CONSERVATIVE", "BALANCED", "GROWTH");
        // the compact list does not carry the long text of a sheet
        assertThat(term.has("description")).isFalse();
        assertThat(term.has("cautions")).isFalse();
        assertThat(products.get(10).get("riskLevel").asString())
                .as("the SIP follows the fund")
                .isEqualTo("2-5");
    }

    @Test
    void listProducts_canBeFilteredByGroup_ignoringCase_andAnUnknownGroupIsExplained() {
        JsonNode funds = call("listProducts", "{\"group\":\"fund\"}");
        JsonNode loans = call("listProducts", "{\"group\":\" LOAN \"}");
        JsonNode bogus = call("listProducts", "{\"group\":\"GOLD\"}");

        assertThat(funds.get("products"))
                .hasSize(6)
                .allSatisfy(p -> assertThat(p.get("group").asString()).isEqualTo("FUND"));
        assertThat(loans.get("products")).hasSize(4);
        assertThat(bogus.get("products")).isEmpty();
        assertThat(bogus.get("message").asString()).contains("GOLD").contains("DEPOSIT, FUND, LOAN, CARD, INSURANCE");
        assertThat(call("listProducts", "{\"group\":\"\"}").get("products")).hasSize(18);
    }

    @Test
    void getProductDetails_returnsTheFullSheet() {
        JsonNode result = call("getProductDetails", "{\"codes\":[\"LBB-TD-ONL\"]}");

        assertThat(result.get("unknownCodes")).isEmpty();
        assertThat(result.get("message").isNull()).isTrue();
        JsonNode sheet = result.get("products").get(0);
        assertThat(sheet.get("code").asString()).isEqualTo("LBB-TD-ONL");
        assertThat(sheet.get("description").asString()).contains("kỳ hạn");
        assertThat(sheet.get("cautions")).hasSize(4);
        assertThat(sheet.get("cautions").get(0).asString()).contains("0,10%");
        assertThat(sheet.get("eligibility").asString()).contains("18 tuổi");
        assertThat(sheet.get("suits").asString()).isNotBlank();
        assertThat(sheet.get("notSuits").asString()).isNotBlank();
        assertThat(sheet.get("rateTable").get("online").get(4).asDouble()).isEqualTo(6.40);
        assertThat(sheet.get("rateTable").get("tenorMonths").get(4).asInt()).isEqualTo(12);
    }

    @Test
    void getProductDetails_isTolerantOfCaseSpacesAndDuplicates_andKeepsCatalogueOrder() {
        JsonNode result = call("getProductDetails", "{\"codes\":[\" lbf-bal \",\"LBB-TD-ONL\",\"LBF-BAL\"]}");

        assertThat(result.get("products"))
                .extracting(p -> p.get("code").asString())
                .containsExactly("LBB-TD-ONL", "LBF-BAL");
        assertThat(result.get("unknownCodes")).isEmpty();
    }

    @Test
    void getProductDetails_returnsAtMostFour_andSaysWhichWereLeftOut() {
        JsonNode result = call(
                "getProductDetails",
                "{\"codes\":[\"LBF-EQ\",\"LBF-BAL\",\"LBF-BOND\",\"LBF-LIQ\",\"LBB-SIP\",\"LBB-OD\"]}");

        assertThat(result.get("products")).hasSize(CatalogTools.MAX_SHEETS);
        assertThat(result.get("products"))
                .extracting(p -> p.get("code").asString())
                .containsExactly("LBF-LIQ", "LBF-BOND", "LBF-BAL", "LBF-EQ");
        assertThat(result.get("message").asString())
                .contains("Chỉ trả 4 mã đầu tiên")
                .contains("LBB-SIP")
                .contains("LBB-OD");
        assertThat(result.get("unknownCodes")).isEmpty();
    }

    @Test
    void getProductDetails_reportsUnknownCodes_withTheValidOnes_insteadOfFailing() {
        JsonNode mixed = call("getProductDetails", "{\"codes\":[\"LBF-EQ\",\"VNM-STOCK\"]}");
        JsonNode none = call("getProductDetails", "{\"codes\":[\"BITCOIN\"]}");

        assertThat(mixed.get("products")).hasSize(1);
        assertThat(mixed.get("unknownCodes")).extracting(JsonNode::asString).containsExactly("VNM-STOCK");
        assertThat(mixed.get("message").asString())
                .contains("Không có mã: VNM-STOCK")
                .contains("LBB-TD-ONL");
        assertThat(none.get("products")).isEmpty();
        assertThat(none.get("unknownCodes")).extracting(JsonNode::asString).containsExactly("BITCOIN");
        assertThat(none.get("message").asString()).contains("LBB-INS-LIFE");
    }

    @Test
    void getProductDetails_withoutCodes_saysSo_andListsTheValidOnes() {
        for (String arguments : List.of("{\"codes\":[]}", "{\"codes\":[\" \",\"\"]}", "{}")) {
            JsonNode result = call("getProductDetails", arguments);

            assertThat(result.get("products")).as(arguments).isEmpty();
            assertThat(result.get("message").asString())
                    .as(arguments)
                    .contains("Chưa có mã")
                    .contains("LBF-IDX30");
        }
    }

    @Test
    void bothToolsAnnounceThemselves_andReportTheirResultForTheNumberCheck() {
        List<String> events = new ArrayList<>();
        List<Object> results = new ArrayList<>();
        BiConsumer<String, Object> sink = (name, result) -> {
            events.add(name + ":" + ("started".equals(result) ? "started" : "done"));
            if (!"started".equals(result)) {
                results.add(result);
            }
        };
        ToolContext context = new ToolContext(Map.of(AdvisorTools.EVENTS, sink));

        tool("listProducts").call("{}", context);
        tool("getProductDetails").call("{\"codes\":[\"LBB-TD-ONL\"]}", context);

        assertThat(events)
                .containsExactly(
                        "listProducts:started",
                        "listProducts:done",
                        "getProductDetails:started",
                        "getProductDetails:done");
        assertThat(results).hasSize(2);
    }

    @Test
    void figuresQuotedFromTheTools_countAsGrounded() {
        String sources = "Lãi suất gửi 12 tháng là bao nhiêu?\n" + raw("listProducts", "{}") + "\n"
                + raw("getProductDetails", "{\"codes\":[\"LBB-TD-ONL\",\"LBB-SAV-FLEX\"]}");

        assertThat(Grounding.ungrounded(
                        "Gửi 12 tháng online lãi 6,40%/năm, tối thiểu 1.000.000 ₫; tiết kiệm linh hoạt cần 5.000.000 ₫.",
                        sources))
                .isEmpty();
        assertThat(Grounding.ungrounded("Gửi 12 tháng lãi 7%, tối thiểu 3.000.000 ₫.", sources))
                .containsExactly("3000000");
    }

    /** Sizes the advisor pays for: about 2 characters per token for this Vietnamese JSON, so the list stays near 3,000 tokens. */
    @Test
    void theResultsStayWithinTheSizeTheTokenBudgetAssumes() {
        int list = raw("listProducts", "{}").length();
        List<Integer> sheets = new ArrayList<>();
        for (String code : jdbc.sql("SELECT code FROM catalog_products ORDER BY sort_order")
                .query(String.class)
                .list()) {
            sheets.add(
                    raw("getProductDetails", "{\"codes\":[\"" + code + "\"]}").length());
        }
        int four = raw("getProductDetails", "{\"codes\":[\"LBF-LIQ\",\"LBF-BOND\",\"LBF-BAL\",\"LBF-EQ\"]}")
                .length();

        System.out.printf(
                "catalogue sizes (characters): list=%d, sheets min=%d max=%d, four sheets=%d%n",
                list,
                sheets.stream().min(Integer::compare).orElseThrow(),
                sheets.stream().max(Integer::compare).orElseThrow(),
                four);
        assertThat(list).isBetween(8_000, 14_000);
        assertThat(sheets).allSatisfy(size -> assertThat(size).isBetween(1_500, 6_000));
        assertThat(four).isLessThan(16_000);
    }
}
