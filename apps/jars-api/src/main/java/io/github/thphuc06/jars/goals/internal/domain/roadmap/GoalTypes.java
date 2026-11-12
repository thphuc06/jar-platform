package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Guesses the kind of a goal from its name, the way the legacy {@code roadmap_context.py} guessed it from the prompt
 * ({@code GOAL_TYPE_KEYWORDS}). Differences: the keywords are matched as whole words (the legacy substring match made
 * "xe" match "xem"), accents are ignored so "Mua nhà" and "mua nha" are the same, and Vietnamese words were added.
 */
public final class GoalTypes {

    public static final String GENERAL_SAVINGS = "general_savings";

    /** In this order: the first kind with a matching word wins, as in the legacy dictionary. */
    private static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put(
                "vehicle_purchase",
                List.of("car", "vehicle", "motorbike", "motorcycle", "bike", "xe", "xe may", "oto", "o to", "auto"));
        KEYWORDS.put(
                "emergency_fund", List.of("emergency", "buffer", "rainy day", "quy khan cap", "khan cap", "du phong"));
        KEYWORDS.put(
                "home_purchase",
                List.of("house", "home", "apartment", "deposit", "down payment", "nha", "chung cu", "can ho"));
        KEYWORDS.put("travel", List.of("travel", "trip", "vacation", "du lich"));
        KEYWORDS.put("education", List.of("education", "course", "tuition", "study", "hoc", "hoc phi", "du hoc"));
        KEYWORDS.put("wedding", List.of("wedding", "marry", "dam cuoi", "cuoi"));
    }

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    private GoalTypes() {}

    /** The kind of goal, or {@link #GENERAL_SAVINGS} when no keyword matches. */
    public static String classify(String goalName) {
        String text = " " + normalize(goalName) + " ";
        for (Map.Entry<String, List<String>> kind : KEYWORDS.entrySet()) {
            for (String keyword : kind.getValue()) {
                if (text.contains(" " + keyword + " ")) {
                    return kind.getKey();
                }
            }
        }
        return GENERAL_SAVINGS;
    }

    /** Lower case, no accents, every run of other characters becomes one space. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT).replace('đ', 'd');
        String plain = DIACRITICS
                .matcher(Normalizer.normalize(lower, Normalizer.Form.NFD))
                .replaceAll("");
        return plain.replaceAll("[^a-z0-9]+", " ").trim();
    }
}
