package io.github.thphuc06.jars.advisor.internal.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Every number in the answer comes from a tool" (ADR-0011), checked after the fact: the amounts in an answer
 * (numbers of four digits or more, with or without thousands separators) must appear in the tool results or in the
 * user's own message. A violation is counted (metric {@code jars.advisor.ungrounded}) and logged, not blocked: the
 * check is a signal for prompt tuning, and a model may legitimately round ("khoảng 15 triệu").
 */
final class Grounding {

    private static final Pattern AMOUNT = Pattern.compile("\\d{1,3}(?:[.,]\\d{3})+|\\d{4,}");

    private Grounding() {}

    /** The amounts in {@code answer} that appear in none of the {@code sources}, normalised to plain digits. */
    static Set<String> ungrounded(String answer, String sources) {
        Set<String> known = amounts(sources);
        Set<String> missing = new LinkedHashSet<>();
        for (String amount : amounts(answer)) {
            if (!known.contains(amount)) {
                missing.add(amount);
            }
        }
        return missing;
    }

    static Set<String> amounts(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = AMOUNT.matcher(text == null ? "" : text);
        while (m.find()) {
            String digits = m.group().replace(".", "").replace(",", "");
            // years and months ("2026", "2026-10") are not amounts
            if (!(digits.length() == 4 && digits.startsWith("20"))) {
                found.add(digits);
            }
        }
        return found;
    }
}
