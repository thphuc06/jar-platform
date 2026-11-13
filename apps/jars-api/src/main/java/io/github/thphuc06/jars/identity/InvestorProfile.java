package io.github.thphuc06.jars.identity;

import java.time.Instant;
import java.util.List;

/**
 * What the advisor may know about the user besides their money (ADR-0027): the risk profile of the onboarding
 * questionnaire and the two facts asked next to it. Built only from codes, numbers and fixed Vietnamese labels of this
 * module, never from text the user typed, so it can be put in a prompt without being an injection path. No name, e-mail
 * or other identifying field is part of it.
 *
 * @param risk the latest questionnaire result, or null when the user has not done it
 * @param ageBand null when not answered (every user who registered before the question existed)
 * @param incomeSituation null when not answered
 */
public record InvestorProfile(Risk risk, AgeBand ageBand, IncomeSituation incomeSituation) {

    /**
     * @param level CONSERVATIVE, BALANCED or GROWTH
     * @param levelLabel the level in words
     * @param score 0 to 100
     * @param assessedAt when the questionnaire was submitted
     * @param answers what each answer means, in question order
     */
    public record Risk(String level, String levelLabel, int score, Instant assessedAt, List<Answer> answers) {}

    /** One question of the questionnaire and what the chosen option says about the user, both fixed texts. */
    public record Answer(String topic, String meaning) {}

    /** Whether all three parts are known: the advisor recommends investment products only then. */
    public boolean complete() {
        return risk != null && ageBand != null && incomeSituation != null;
    }
}
