package io.github.thphuc06.jars.advisor.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.advisor.internal.persistence.ChatStore.Message;
import io.github.thphuc06.jars.shared.Uuids;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the model is shown of the earlier turns: complete, alternating, never a blocked question. */
class HistoryMessagesTest {

    @Test
    void completeTurns_areKeptInOrder() {
        List<Message> history = List.of(user("q1"), assistant("a1"), user("q2"), assistant("a2"));

        assertThat(texts(history)).containsExactly("USER:q1", "ASSISTANT:a1", "USER:q2", "ASSISTANT:a2");
    }

    @Test
    void noHistory_isEmpty() {
        assertThat(AdvisorService.historyMessages(List.of())).isEmpty();
    }

    @Test
    void aQuestionWhoseAnswerFailed_isDroppedInTheMiddle() {
        // q2 was stored, then the model call failed; the user asked q3 next
        List<Message> history = List.of(user("q1"), assistant("a1"), user("q2"), user("q3"), assistant("a3"));

        assertThat(texts(history)).containsExactly("USER:q1", "ASSISTANT:a1", "USER:q3", "ASSISTANT:a3");
    }

    @Test
    void aQuestionWhoseAnswerFailed_isDroppedAtTheEnd() {
        List<Message> history = List.of(user("q1"), assistant("a1"), user("q2"));

        assertThat(texts(history)).containsExactly("USER:q1", "ASSISTANT:a1");
    }

    @Test
    void severalFailedQuestionsInARow_areAllDropped() {
        List<Message> history = List.of(user("q1"), user("q2"), user("q3"), assistant("a3"));

        assertThat(texts(history)).containsExactly("USER:q3", "ASSISTANT:a3");
    }

    @Test
    void aTurnBlockedByTheInputGuardrail_neverReachesTheModel() {
        List<Message> history = List.of(
                user("q1"),
                assistant("a1"),
                user("blocked text"),
                assistantWith("Xin lỗi, tôi không thể trả lời.", "INPUT"),
                user("q3"),
                assistant("a3"));

        assertThat(texts(history)).containsExactly("USER:q1", "ASSISTANT:a1", "USER:q3", "ASSISTANT:a3");
    }

    @Test
    void aReplyTheOutputGuardrailReplaced_isKept() {
        List<Message> history = List.of(user("q1"), assistantWith("safe text", "OUTPUT"));

        assertThat(texts(history)).containsExactly("USER:q1", "ASSISTANT:safe text");
    }

    @Test
    void aWindowThatStartsWithAnAnswer_dropsIt() {
        // historyMessages is a count, so the window can cut a turn in two
        List<Message> history = List.of(assistant("a0"), user("q1"), assistant("a1"));

        assertThat(texts(history)).containsExactly("USER:q1", "ASSISTANT:a1");
    }

    private static List<String> texts(List<Message> history) {
        return AdvisorService.historyMessages(history).stream()
                .map(m -> m.getMessageType() + ":" + m.getText())
                .toList();
    }

    private static Message user(String text) {
        return new Message(Uuids.v7(), "USER", text, null, null, null, Instant.now());
    }

    private static Message assistant(String text) {
        return assistantWith(text, null);
    }

    private static Message assistantWith(String text, String guardrail) {
        return new Message(Uuids.v7(), "ASSISTANT", text, 1, 1, guardrail, Instant.now());
    }
}
