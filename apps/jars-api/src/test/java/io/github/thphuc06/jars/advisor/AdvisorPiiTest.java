package io.github.thphuc06.jars.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.ApiTestSupport;
import io.github.thphuc06.jars.advisor.internal.guard.ContentGuard;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import reactor.core.publisher.Flux;

/**
 * What happens to sensitive data the user types (ADR-0026): the guardrail masks it before anything is stored or sent
 * to the model. The guardrail is the only fake besides the model, and it behaves like Bedrock does: a card number is
 * masked in a message that passes, and also in one that is blocked for another reason.
 */
@Import(AdvisorPiiTest.Fakes.class)
class AdvisorPiiTest extends ApiTestSupport {

    static final String CARD = "4111 1111 1111 1111";
    static final String MASKED_CARD = "{CREDIT_DEBIT_CARD_NUMBER}";
    static final String TOPIC_REPLY = "Tôi không đưa ra khuyến nghị mua bán chứng khoán cụ thể.";

    @TestConfiguration
    static class Fakes {

        /** What the model received, for assertions. */
        static final List<Prompt> PROMPTS = new CopyOnWriteArrayList<>();

        @Bean
        ChatModel scriptedChatModel() {
            return new ChatModel() {
                @Override
                public ChatOptions getOptions() {
                    return ToolCallingChatOptions.builder().build();
                }

                @Override
                public ChatResponse call(Prompt prompt) {
                    throw new UnsupportedOperationException("the advisor streams");
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    PROMPTS.add(prompt);
                    // a model that repeats what it was told, which is what the user asks it to do with their data
                    String answer = prompt.getUserMessage().getText().contains("ECHO")
                            ? "Thẻ của bạn là " + CARD + "."
                            : "Mình đã ghi nhận.";
                    return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
                }
            };
        }

        @Bean
        @Primary
        ContentGuard maskingGuardrail() {
            return new ContentGuard() {
                @Override
                public Verdict checkInput(String text) {
                    if (text.contains("BOOM")) {
                        throw new IllegalStateException("the guardrail is unavailable");
                    }
                    String masked = text.replace(CARD, MASKED_CARD);
                    if (text.contains("cổ phiếu")) {
                        return Verdict.blocked(TOPIC_REPLY, masked);
                    }
                    return masked.equals(text) ? Verdict.pass(text) : Verdict.masked(masked);
                }

                @Override
                public Verdict checkOutput(String text) {
                    String masked = text.replace(CARD, MASKED_CARD);
                    return masked.equals(text) ? Verdict.pass(text) : Verdict.masked(masked);
                }
            };
        }
    }

    @Test
    void aCardNumberIsStoredMasked_andTheModelNeverSeesIt() {
        JwtRequestPostProcessor user = newUser();
        String session = newSession(user);

        String stream = send(user, session, "Thẻ của tôi là " + CARD + ", hãy nhớ giúp tôi.");

        assertThat(stream).contains("event:done").doesNotContain("4111");
        Prompt prompt = Fakes.PROMPTS.getLast();
        assertThat(prompt.getUserMessage().getText())
                .isEqualTo("Thẻ của tôi là " + MASKED_CARD + ", hãy nhớ giúp tôi.");
        assertThat(prompt.getContents()).doesNotContain("4111");
        var messages =
                get(user, "/api/v1/chat/sessions/" + session + "/messages").json();
        assertThat(messages.get(0).get("role").asString()).isEqualTo("USER");
        assertThat(messages.get(0).get("content").asString())
                .isEqualTo("Thẻ của tôi là " + MASKED_CARD + ", hãy nhớ giúp tôi.");
        assertThat(storedTextOf(session)).doesNotContain("4111");
    }

    @Test
    void theHistoryOfLaterTurnsHoldsTheMaskedText() {
        JwtRequestPostProcessor user = newUser();
        String session = newSession(user);
        send(user, session, "Thẻ của tôi là " + CARD);

        send(user, session, "Câu hai");

        Prompt second = Fakes.PROMPTS.getLast();
        assertThat(second.getInstructions().stream().map(m -> m.getText()).toList())
                .contains("Thẻ của tôi là " + MASKED_CARD);
        assertThat(second.getContents()).doesNotContain("4111");
    }

    @Test
    void aBlockedMessageIsStoredMaskedToo_andTheReplyNamesTheReasonThatApplied() {
        JwtRequestPostProcessor user = newUser();
        String session = newSession(user);
        int before = Fakes.PROMPTS.size();

        String stream = send(user, session, "Có nên mua cổ phiếu FPT không? Thẻ " + CARD);

        assertThat(stream).contains(TOPIC_REPLY).doesNotContain("4111");
        assertThat(Fakes.PROMPTS).as("the model was not called").hasSize(before);
        var messages =
                get(user, "/api/v1/chat/sessions/" + session + "/messages").json();
        assertThat(messages.get(0).get("content").asString())
                .isEqualTo("Có nên mua cổ phiếu FPT không? Thẻ " + MASKED_CARD);
        assertThat(messages.get(1).get("content").asString()).isEqualTo(TOPIC_REPLY);
        assertThat(storedTextOf(session)).doesNotContain("4111");
    }

    @Test
    void sensitiveDataInTheAnswerIsReplacedAndStoredMasked() {
        JwtRequestPostProcessor user = newUser();
        String session = newSession(user);

        String stream = send(user, session, "ECHO lại giúp tôi");

        assertThat(stream).contains("event:replace").contains(MASKED_CARD);
        var messages =
                get(user, "/api/v1/chat/sessions/" + session + "/messages").json();
        assertThat(messages.get(1).get("content").asString()).isEqualTo("Thẻ của bạn là " + MASKED_CARD + ".");
        assertThat(storedTextOf(session)).doesNotContain("4111");
    }

    @Test
    void theTitleOfAConversationIsCheckedLikeAMessage() {
        JwtRequestPostProcessor user = newUser();

        Resp masked = post(user, "/api/v1/chat/sessions", null, "{\"title\":\"Thẻ " + CARD + " của tôi\"}");
        Resp blocked = post(user, "/api/v1/chat/sessions", null, "{\"title\":\"Có nên mua cổ phiếu FPT không?\"}");

        assertThat(masked.json().get("title").asString()).isEqualTo("Thẻ " + MASKED_CARD + " của tôi");
        assertThat(blocked.json().get("title").asString()).isEqualTo("Cuộc trò chuyện mới");
        assertThat(get(user, "/api/v1/chat/sessions").json().toString()).doesNotContain("4111");
    }

    @Test
    void ifTheGuardrailFails_theTurnFailsAndNothingIsStored() {
        JwtRequestPostProcessor user = newUser();
        String session = newSession(user);

        String stream = send(user, session, "BOOM Thẻ " + CARD);

        assertThat(stream).contains("event:error").doesNotContain("4111").doesNotContain("event:done");
        assertThat(get(user, "/api/v1/chat/sessions/" + session + "/messages").json())
                .isEmpty();
    }

    /** Every message of the session as stored, straight from the table. */
    private String storedTextOf(String session) {
        return String.join(
                "\n",
                jdbc.sql("SELECT content FROM chat_messages WHERE session_id = ?::uuid")
                        .param(session)
                        .query(String.class)
                        .list());
    }

    private String newSession(JwtRequestPostProcessor user) {
        Resp created = post(user, "/api/v1/chat/sessions", null, "{\"title\":\"Hỏi về hũ\"}");
        assertThat(created.status()).isEqualTo(201);
        return created.json().get("id").asString();
    }

    private String send(JwtRequestPostProcessor user, String session, String content) {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/chat/sessions/" + session + "/messages")
                .with(user)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(mapper.writeValueAsString(Map.of("content", content)))
                .exchange(Duration.ofSeconds(10));
        assertThat(result).hasStatusOk();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
