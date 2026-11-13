package io.github.thphuc06.jars.advisor.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The product catalogue of the fictional bank (ADR-0027): 18 rows seeded by Flyway, read-only for the application and
 * the same for every user. Plain JDBC like {@link ChatStore}; the compact list and the full sheet are two queries so a
 * listing never reads the long customer text.
 */
@Repository
public class ProductCatalog {

    /** What the compact list shows. */
    public record Summary(
            String code,
            String name,
            String group,
            String category,
            String riskKind,
            int riskLevel,
            int riskLevelMax,
            List<String> suitableProfiles,
            String headline,
            Long minAmountVnd,
            String minAmountUnit,
            Long minMonthlyIncomeVnd,
            String summary,
            LocalDate asOf) {}

    /** The whole sheet of one product. {@code rateTable} is plain JSON data (maps and lists) or null. */
    public record Sheet(
            Summary summary,
            String profileNote,
            String tenor,
            String liquidity,
            String description,
            List<String> figures,
            Object rateTable,
            String eligibility,
            String suits,
            String notSuits,
            List<String> cautions) {}

    private static final String SUMMARY_COLUMNS = """
            code, name, product_group, category, risk_kind, risk_level, risk_level_max, suitable_profiles, headline,
            min_amount_vnd, min_amount_unit, min_monthly_income_vnd, summary, as_of
            """;

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ProductCatalog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** All products in catalogue order, or only those of one group (an unknown group gives an empty list). */
    public List<Summary> summaries(String groupOrNull) {
        String sql = "SELECT " + SUMMARY_COLUMNS + " FROM catalog_products "
                + (groupOrNull == null ? "" : "WHERE product_group = :group ")
                + "ORDER BY sort_order";
        var query = jdbc.sql(sql);
        if (groupOrNull != null) {
            query = query.param("group", groupOrNull);
        }
        return query.query((rs, n) -> summary(rs)).list();
    }

    /** The sheets of the products with these codes, in catalogue order; codes that do not exist are simply absent. */
    public List<Sheet> sheets(Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + """
                        , profile_note, tenor, liquidity, description, figures, rate_table, eligibility, suits,
                        not_suits, cautions
                        FROM catalog_products WHERE code IN (:codes) ORDER BY sort_order
                        """)
                .param("codes", codes)
                .query((rs, n) -> new Sheet(
                        summary(rs),
                        rs.getString("profile_note"),
                        rs.getString("tenor"),
                        rs.getString("liquidity"),
                        rs.getString("description"),
                        json.readValue(rs.getString("figures"), STRINGS),
                        rs.getString("rate_table") == null
                                ? null
                                : json.readValue(rs.getString("rate_table"), Object.class),
                        rs.getString("eligibility"),
                        rs.getString("suits"),
                        rs.getString("not_suits"),
                        json.readValue(rs.getString("cautions"), STRINGS)))
                .list();
    }

    /** The codes of every product, for the message that tells the model which codes exist. */
    public List<String> codes() {
        return jdbc.sql("SELECT code FROM catalog_products ORDER BY sort_order")
                .query(String.class)
                .list();
    }

    private Summary summary(ResultSet rs) throws SQLException {
        return new Summary(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("product_group"),
                rs.getString("category"),
                rs.getString("risk_kind"),
                rs.getInt("risk_level"),
                rs.getInt("risk_level_max"),
                json.readValue(rs.getString("suitable_profiles"), STRINGS),
                rs.getString("headline"),
                (Long) rs.getObject("min_amount_vnd"),
                rs.getString("min_amount_unit"),
                (Long) rs.getObject("min_monthly_income_vnd"),
                rs.getString("summary"),
                rs.getObject("as_of", LocalDate.class));
    }
}
