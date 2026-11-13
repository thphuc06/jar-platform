package io.github.thphuc06.jars.advisor.internal.guard;

/**
 * Bedrock Guardrails around the advisor (ADR-0023): the user's message before the model sees it, the answer after it
 * was streamed. Without a configured guardrail (local, tests) everything passes.
 */
public interface ContentGuard {

    /** {@code text} is what to show instead when the guardrail intervened, else the original text. */
    record Verdict(boolean intervened, String text) {

        public static Verdict pass(String text) {
            return new Verdict(false, text);
        }
    }

    Verdict checkInput(String text);

    Verdict checkOutput(String text);
}
