package io.github.thphuc06.jars.imports.internal.parsing;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Guesses the jar and category of an imported row from its counterparty. Port of {@code _heuristic_category} of the
 * hackathon backend, mapped onto this project's six jars and system categories (V4 migration):
 *
 * <pre>
 * legacy                         here
 * credit -> jar_income/income    income, no jar (unallocated), category by keyword (salary, bonus, interest) else "Khác"
 * RENT, LANDLORD, MORTGAGE       NEC, "Tiền thuê nhà"
 * ELECTRIC, WATER, INTERNET, ... NEC, "Nhà cửa &amp; hóa đơn"
 * GRAB, GOJEK, UBER, TAXI, ...   NEC, "Di chuyển"
 * COFFEE, HIGHLANDS, CAFE, ...   PLAY, "Ăn uống"
 * anything else (jar_misc)       NEC, "Khác"
 * </pre>
 *
 * Vietnamese keywords were added next to the English ones (the counterparty is compared upper case, without
 * diacritics, like the legacy {@code _normalize_counterparty}). Short words such as "BE" or "EVN" match whole words
 * only, so "BE" does not match "BEEF".
 */
public final class Categorizer {

    public enum JarCode {
        NEC,
        PLAY
    }

    /** {@code jar} is null for an income: the user divides it over jars later (or with an allocation). */
    public record Guess(JarCode jar, UUID categoryId) {}

    // system category ids (V4__ledger.sql)
    static final UUID FOOD = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    static final UUID TRANSPORT = UUID.fromString("018f0000-0000-7000-8000-000000000003");
    static final UUID HOUSING_BILLS = UUID.fromString("018f0000-0000-7000-8000-000000000004");
    static final UUID RENT = UUID.fromString("018f0000-0000-7000-8000-000000000005");
    static final UUID OTHER_EXPENSE = UUID.fromString("018f0000-0000-7000-8000-00000000000e");
    static final UUID SALARY = UUID.fromString("018f0000-0000-7000-8000-000000000101");
    static final UUID BONUS = UUID.fromString("018f0000-0000-7000-8000-000000000103");
    static final UUID INTEREST = UUID.fromString("018f0000-0000-7000-8000-000000000104");
    static final UUID OTHER_INCOME = UUID.fromString("018f0000-0000-7000-8000-000000000105");

    private record Rule(List<String> terms, Set<String> words, Guess guess) {}

    private static final List<Rule> EXPENSE_RULES = List.of(
            rule(List.of("RENT", "LANDLORD", "MORTGAGE", "THUE NHA", "TIEN NHA"), Set.of(), JarCode.NEC, RENT),
            rule(
                    List.of("ELECTRIC", "WATER", "INTERNET", "UTILITY", "TIEN DIEN", "TIEN NUOC", "VNPT", "VIETTEL"),
                    Set.of("EVN", "FPT"),
                    JarCode.NEC,
                    HOUSING_BILLS),
            rule(
                    List.of("GRAB", "GOJEK", "UBER", "TAXI", "TRANSPORT", "XANH SM"),
                    Set.of("BE"),
                    JarCode.NEC,
                    TRANSPORT),
            rule(
                    List.of("COFFEE", "HIGHLANDS", "STARBUCKS", "CAFE", "PHUC LONG", "KATINAT"),
                    Set.of(),
                    JarCode.PLAY,
                    FOOD));

    private static final List<Rule> INCOME_RULES = List.of(
            rule(List.of("SALARY", "PAYROLL", "LUONG"), Set.of(), null, SALARY),
            rule(List.of("BONUS", "THUONG"), Set.of(), null, BONUS),
            rule(List.of("INTEREST", "TIEN LAI", "TRA LAI"), Set.of(), null, INTEREST));

    private Categorizer() {}

    public static Guess guess(String counterparty, boolean income) {
        String normalized = normalize(counterparty);
        Set<String> words = Arrays.stream(normalized.split(" ")).collect(Collectors.toSet());
        for (Rule rule : income ? INCOME_RULES : EXPENSE_RULES) {
            boolean termMatches = rule.terms().stream().anyMatch(normalized::contains);
            boolean wordMatches = rule.words().stream().anyMatch(words::contains);
            if (termMatches || wordMatches) {
                return rule.guess();
            }
        }
        return income ? new Guess(null, OTHER_INCOME) : new Guess(JarCode.NEC, OTHER_EXPENSE);
    }

    /** {@code _normalize_counterparty}: upper case, letters and digits only, single spaces; diacritics removed first. */
    static String normalize(String counterparty) {
        if (counterparty == null) {
            return "";
        }
        String plain = StatementColumns.withoutDiacritics(counterparty).toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (char c : plain.toCharArray()) {
            out.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        return out.toString().trim().replaceAll(" +", " ");
    }

    private static Rule rule(List<String> terms, Set<String> words, JarCode jar, UUID category) {
        return new Rule(terms, words, new Guess(jar, category));
    }
}
