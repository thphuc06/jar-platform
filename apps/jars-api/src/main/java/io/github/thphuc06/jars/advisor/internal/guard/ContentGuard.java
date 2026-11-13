package io.github.thphuc06.jars.advisor.internal.guard;

/**
 * Bedrock Guardrails around the advisor (ADR-0023, ADR-0026): the user's message before the model sees it, the answer
 * after it was streamed. Without a configured guardrail (local, tests) everything passes.
 */
public interface ContentGuard {

    enum Action {
        /** Nothing found. */
        PASS,
        /** Sensitive data was found and replaced by placeholders; the text is safe to continue with. */
        MASKED,
        /** The guardrail refused the text: show {@link Verdict#reply()} instead. */
        BLOCKED
    }

    /**
     * @param reply what to show instead when {@code action} is {@link Action#BLOCKED}, else {@code null}. It names the
     *     reason that actually applied (the investment-advice topic, or the guardrail's generic message).
     * @param text the checked text with every detected PII value masked, whatever the action; the original text when
     *     nothing was found. This is the only form of the text that may be stored or sent to the model.
     */
    record Verdict(Action action, String reply, String text) {

        public static Verdict pass(String text) {
            return new Verdict(Action.PASS, null, text);
        }

        public static Verdict masked(String maskedText) {
            return new Verdict(Action.MASKED, null, maskedText);
        }

        public static Verdict blocked(String reply, String maskedText) {
            return new Verdict(Action.BLOCKED, reply, maskedText);
        }

        public boolean blocked() {
            return action == Action.BLOCKED;
        }
    }

    Verdict checkInput(String text);

    Verdict checkOutput(String text);
}
