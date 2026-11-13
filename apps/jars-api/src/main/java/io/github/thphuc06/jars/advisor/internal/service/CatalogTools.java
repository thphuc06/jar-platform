package io.github.thphuc06.jars.advisor.internal.service;

import io.github.thphuc06.jars.advisor.internal.persistence.ProductCatalog;
import io.github.thphuc06.jars.advisor.internal.persistence.ProductCatalog.Sheet;
import io.github.thphuc06.jars.advisor.internal.persistence.ProductCatalog.Summary;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The product catalogue as two read-only tools (ADR-0027): a compact list of all products to choose from, and the full
 * sheets of a few chosen ones. They read reference data that is the same for every user, so they take no user, but they
 * announce themselves like the tools over the user's data ({@link AdvisorTools#run}) and the model gets a readable
 * message, never an exception, for a code or group that does not exist. The model does the choosing; no code here
 * decides which product suits whom.
 */
@Component
public class CatalogTools {

    /** Most sheets {@link #getProductDetails} returns in one call (each is about 1,000 tokens). */
    static final int MAX_SHEETS = 4;

    static final List<String> GROUPS = List.of("DEPOSIT", "FUND", "LOAN", "CARD", "INSURANCE");

    /** Said once in every result: the bank, the fund company, the insurer and the figures are not real. */
    static final String NOTICE =
            "Danh mục MINH HỌA của Ngân hàng TMCP Lục Bình (ngân hàng, công ty quản lý quỹ và công ty"
                    + " bảo hiểm đều là hư cấu); số liệu chỉ mang tính minh họa, tính đến ";

    /** One line per product. riskLevel is 1 to 5, "2-5" when it depends on the fund chosen (SIP). */
    public record ProductBrief(
            String code,
            String name,
            String group,
            String riskKind,
            String riskLevel,
            List<String> profiles,
            String headline,
            Long minAmountVnd,
            String minAmountUnit,
            Long minMonthlyIncomeVnd,
            String summary) {}

    public record ProductList(String notice, List<ProductBrief> products, String message) {}

    public record ProductSheet(
            String code,
            String name,
            String group,
            String category,
            String riskKind,
            String riskLevel,
            List<String> profiles,
            String profileNote,
            String headline,
            Long minAmountVnd,
            String minAmountUnit,
            Long minMonthlyIncomeVnd,
            String tenor,
            String liquidity,
            String description,
            List<String> figures,
            Object rateTable,
            String eligibility,
            String suits,
            String notSuits,
            List<String> cautions) {}

    public record ProductSheets(
            String notice, List<ProductSheet> products, List<String> unknownCodes, String message) {}

    private final ProductCatalog catalog;

    CatalogTools(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    @Tool(
            name = "listProducts",
            description =
                    "Danh sách gọn TẤT CẢ sản phẩm của danh mục minh họa Ngân hàng Lục Bình (18 sản phẩm, nhóm"
                            + " DEPOSIT tiền gửi, FUND quỹ, LOAN vay, CARD thẻ, INSURANCE bảo hiểm): mã, tên, mức rủi ro 1 đến 5"
                            + " (riskKind: INVESTMENT là rủi ro đầu tư, DEBT là rủi ro nợ, PROTECTION là bảo vệ), hồ sơ rủi ro"
                            + " phù hợp, khoảng lãi suất hoặc lợi suất, mức tối thiểu, thu nhập tối thiểu và một dòng mô tả."
                            + " KHÔNG có lãi suất theo từng kỳ hạn, phí hay điều kiện: những thứ đó chỉ có ở getProductDetails.")
    public ProductList listProducts(
            @ToolParam(
                            required = false,
                            description =
                                    "Chỉ lấy một nhóm: DEPOSIT, FUND, LOAN, CARD hoặc INSURANCE; bỏ trống để lấy tất cả")
                    String group,
            ToolContext context) {
        return AdvisorTools.run(context, "listProducts", () -> {
            String wanted =
                    group == null || group.isBlank() ? null : group.strip().toUpperCase(Locale.ROOT);
            if (wanted != null && !GROUPS.contains(wanted)) {
                return new ProductList(
                        NOTICE.trim(),
                        List.of(),
                        "Không có nhóm \"" + wanted + "\". Các nhóm: " + String.join(", ", GROUPS) + ".");
            }
            List<Summary> found = catalog.summaries(wanted);
            return new ProductList(
                    notice(found), found.stream().map(CatalogTools::brief).toList(), null);
        });
    }

    @Tool(
            name = "getProductDetails",
            description = "Tờ thông tin đầy đủ của tối đa " + MAX_SHEETS + " sản phẩm theo mã (ví dụ LBB-TD-ONL,"
                    + " LBF-BAL): mô tả, bảng lãi suất, phí, điều kiện, thanh khoản, ai phù hợp và không phù hợp, các lưu"
                    + " ý cần nói với khách (cautions). Mã không tồn tại được liệt kê ở unknownCodes; nếu gửi hơn "
                    + MAX_SHEETS + " mã thì chỉ " + MAX_SHEETS + " mã đầu tiên được trả.")
    public ProductSheets getProductDetails(
            @ToolParam(description = "Các mã sản phẩm lấy từ listProducts, tối đa " + MAX_SHEETS) List<String> codes,
            ToolContext context) {
        return AdvisorTools.run(context, "getProductDetails", () -> {
            Set<String> wanted = new LinkedHashSet<>();
            for (String code : codes == null ? List.<String>of() : codes) {
                if (code != null && !code.isBlank()) {
                    wanted.add(code.strip().toUpperCase(Locale.ROOT));
                }
            }
            if (wanted.isEmpty()) {
                return new ProductSheets(
                        NOTICE.trim(),
                        List.of(),
                        List.of(),
                        "Chưa có mã sản phẩm nào. Mã hợp lệ: " + validCodes() + ".");
            }
            List<String> asked = List.copyOf(wanted);
            List<String> taken = asked.subList(0, Math.min(MAX_SHEETS, asked.size()));
            List<Sheet> sheets = catalog.sheets(taken);
            Set<String> found = new LinkedHashSet<>();
            sheets.forEach(s -> found.add(s.summary().code()));
            List<String> unknown =
                    taken.stream().filter(c -> !found.contains(c)).toList();

            StringBuilder message = new StringBuilder();
            if (asked.size() > MAX_SHEETS) {
                message.append("Chỉ trả ")
                        .append(MAX_SHEETS)
                        .append(" mã đầu tiên; chưa trả: ")
                        .append(String.join(", ", asked.subList(MAX_SHEETS, asked.size())))
                        .append(". Gọi lại riêng nếu cần. ");
            }
            if (!unknown.isEmpty()) {
                message.append("Không có mã: ")
                        .append(String.join(", ", unknown))
                        .append(". Mã hợp lệ: ")
                        .append(validCodes())
                        .append('.');
            }
            return new ProductSheets(
                    sheets.isEmpty()
                            ? NOTICE.trim()
                            : notice(sheets.stream().map(Sheet::summary).toList()),
                    sheets.stream().map(CatalogTools::sheet).toList(),
                    unknown,
                    message.isEmpty() ? null : message.toString().strip());
        });
    }

    private String validCodes() {
        return String.join(", ", catalog.codes());
    }

    private static String notice(List<Summary> products) {
        return products.isEmpty() ? NOTICE.trim() : NOTICE + products.getFirst().asOf() + ".";
    }

    private static String level(Summary s) {
        return s.riskLevel() == s.riskLevelMax()
                ? String.valueOf(s.riskLevel())
                : s.riskLevel() + "-" + s.riskLevelMax();
    }

    private static ProductBrief brief(Summary s) {
        return new ProductBrief(
                s.code(),
                s.name(),
                s.group(),
                s.riskKind(),
                level(s),
                s.suitableProfiles(),
                s.headline(),
                s.minAmountVnd(),
                s.minAmountUnit(),
                s.minMonthlyIncomeVnd(),
                s.summary());
    }

    private static ProductSheet sheet(Sheet p) {
        Summary s = p.summary();
        return new ProductSheet(
                s.code(),
                s.name(),
                s.group(),
                s.category(),
                s.riskKind(),
                level(s),
                s.suitableProfiles(),
                p.profileNote(),
                s.headline(),
                s.minAmountVnd(),
                s.minAmountUnit(),
                s.minMonthlyIncomeVnd(),
                p.tenor(),
                p.liquidity(),
                p.description(),
                p.figures(),
                p.rateTable(),
                p.eligibility(),
                p.suits(),
                p.notSuits(),
                p.cautions());
    }
}
