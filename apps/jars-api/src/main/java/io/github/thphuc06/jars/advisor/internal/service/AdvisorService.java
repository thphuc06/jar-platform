package io.github.thphuc06.jars.advisor.internal.service;

import io.github.thphuc06.jars.advisor.internal.guard.ContentGuard;
import io.github.thphuc06.jars.advisor.internal.persistence.ChatStore;
import io.github.thphuc06.jars.advisor.internal.persistence.ChatStore.Message;
import io.github.thphuc06.jars.advisor.internal.persistence.ChatStore.Session;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.UserId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * One turn of the advisor chat: rate limit, input guardrail, the model with read-only tools over the user's data,
 * streamed to the client as Server-Sent Events, output guardrail, then the turn is stored (ADR-0011, ADR-0023).
 *
 * <p>{@link #prepare} runs in the request thread and fails with a normal HTTP problem (404, 422, 429, 503) before any
 * event is sent; {@link #stream} runs on a virtual thread and reports failures as an {@code error} event.
 */
@Service
public class AdvisorService {

    private static final Logger log = LoggerFactory.getLogger(AdvisorService.class);

    static final int MAX_MESSAGE_LENGTH = 2000;
    static final String DISCLAIMER =
            "Thông tin mang tính tham khảo, dựa trên dữ liệu trong ứng dụng; không phải khuyến nghị đầu tư.";

    static final String SYSTEM_PROMPT = """
            Bạn là trợ lý tài chính cá nhân của ứng dụng Jars, giúp người dùng quản lý tiền theo phương pháp 6 hũ \
            (NEC thiết yếu, FFA tự do tài chính, LTSS tiết kiệm dài hạn, EDU giáo dục, PLAY hưởng thụ, GIVE cho đi).

            Quy tắc:
            - Luôn trả lời bằng tiếng Việt, ngắn gọn (tối đa khoảng 150 từ), thân thiện.
            - Viết văn bản thường, không dùng Markdown (không **, không #, không bảng); nếu cần liệt kê thì mỗi ý \
            một dòng bắt đầu bằng "- ".
            - Mọi con số về tiền của người dùng phải lấy từ công cụ (getJars, getMonthlyCashflow, getGoals, \
            getOpenAnomalyCount). Không tự bịa hoặc tự tính ra số mới; được phép làm tròn khi diễn giải.
            - Số tiền là VND; viết có dấu chấm ngăn cách hàng nghìn, ví dụ 1.500.000 ₫.
            - Không khuyến nghị mua hoặc bán một cổ phiếu, quỹ hay tài sản cụ thể.
            - Nếu dữ liệu không đủ để trả lời, nói rõ là không đủ dữ liệu.
            """;

    /** What the controller sends: SSE event name and payload. */
    public interface Events {
        void send(String event, Object data);

        void complete();
    }

    public record SessionView(UUID id, String title, java.time.Instant createdAt, java.time.Instant updatedAt) {}

    public record MessageView(UUID id, String role, String content, java.time.Instant createdAt) {}

    /** A turn that passed the checks and is ready to stream. */
    public record Turn(UserId userId, Session session, String content) {}

    private final ChatStore chats;
    private final RateLimiter limiter;
    private final ContentGuard guard;
    private final AdvisorTools tools;
    private final AdvisorProperties properties;
    private final ObjectProvider<ChatModel> chatModel;
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final JsonMapper json;
    private final Clock clock;

    AdvisorService(
            ChatStore chats,
            RateLimiter limiter,
            ContentGuard guard,
            AdvisorTools tools,
            AdvisorProperties properties,
            ObjectProvider<ChatModel> chatModel,
            ObservationRegistry observations,
            MeterRegistry meters,
            JsonMapper json,
            Clock clock) {
        this.chats = chats;
        this.limiter = limiter;
        this.guard = guard;
        this.tools = tools;
        this.properties = properties;
        this.chatModel = chatModel;
        this.observations = observations;
        this.meters = meters;
        this.json = json;
        this.clock = clock;
    }

    public SessionView createSession(UserId userId, String title) {
        String name = title == null || title.isBlank() ? "Cuộc trò chuyện mới" : title.strip();
        if (name.length() > 80) {
            name = name.substring(0, 80);
        }
        return view(chats.createSession(userId.value(), name, clock.instant()));
    }

    public List<SessionView> sessions(UserId userId) {
        return chats.sessions(userId.value(), 30).stream()
                .map(AdvisorService::view)
                .toList();
    }

    public List<MessageView> messages(UserId userId, UUID sessionId) {
        Session session = session(userId, sessionId);
        return chats.lastMessages(session.id(), 100).stream()
                .map(m -> new MessageView(m.id(), m.role(), m.content(), m.createdAt()))
                .toList();
    }

    /** The checks that answer with an HTTP status, before the stream starts. */
    public Turn prepare(UserId userId, UUID sessionId, String content) {
        if (chatModel.getIfAvailable() == null) {
            throw new DomainException(AdvisorProblems.ADVISOR_UNAVAILABLE, "Trợ lý AI chưa được bật ở môi trường này.");
        }
        String text = content == null ? "" : content.strip();
        if (text.isEmpty() || text.length() > MAX_MESSAGE_LENGTH) {
            throw new DomainException(
                    AdvisorProblems.INVALID_MESSAGE, "Tin nhắn cần từ 1 đến " + MAX_MESSAGE_LENGTH + " ký tự.");
        }
        Session session = session(userId, sessionId);
        limiter.take(userId);
        return new Turn(userId, session, text);
    }

    /** Streams one answer. Every outcome ends the stream with {@code done} or {@code error}. */
    public void stream(Turn turn, Events events) {
        List<Message> history = chats.lastMessages(turn.session().id(), properties.historyMessages());
        chats.append(turn.session().id(), "USER", turn.content(), null, null, null, clock.instant());

        ContentGuard.Verdict input = guard.checkInput(turn.content());
        if (input.intervened()) {
            Message saved =
                    chats.append(turn.session().id(), "ASSISTANT", input.text(), null, null, "INPUT", clock.instant());
            events.send("token", Map.of("text", input.text()));
            finish(events, saved, 0, 0, "blocked_input");
            return;
        }

        StringBuilder answer = new StringBuilder();
        StringBuilder sources = new StringBuilder(turn.content());
        AtomicReference<Usage> usage = new AtomicReference<>();
        BiConsumer<String, Object> toolEvents = (name, result) -> {
            if ("started".equals(result)) {
                events.send("tool", Map.of("name", name, "status", "started"));
            } else {
                sources.append('\n').append(json.writeValueAsString(result));
                events.send("tool", Map.of("name", name, "status", "done"));
            }
        };
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(AdvisorTools.USER, turn.userId());
        toolContext.put(AdvisorTools.EVENTS, toolEvents);

        ChatClient client = ChatClient.create(chatModel.getObject(), observations);
        client
                .prompt()
                .system(SYSTEM_PROMPT)
                .messages(historyMessages(history))
                .user(turn.content())
                .tools(tools)
                .toolContext(toolContext)
                .stream()
                .chatResponse()
                .doOnNext(response -> {
                    String text = textOf(response);
                    if (!text.isEmpty()) {
                        answer.append(text);
                        events.send("token", Map.of("text", text));
                    }
                    if (response.getMetadata() != null
                            && response.getMetadata().getUsage() != null
                            && response.getMetadata().getUsage().getTotalTokens() > 0) {
                        usage.set(response.getMetadata().getUsage());
                    }
                })
                .blockLast();

        String finalText = answer.toString();
        String outcome = "answered";
        String guardrail = null;
        ContentGuard.Verdict output = guard.checkOutput(finalText);
        if (output.intervened()) {
            finalText = output.text();
            guardrail = "OUTPUT";
            outcome = "blocked_output";
            events.send("replace", Map.of("text", finalText));
        } else {
            Set<String> ungrounded = Grounding.ungrounded(finalText, sources.toString());
            if (!ungrounded.isEmpty()) {
                meters.counter("jars.advisor.ungrounded").increment();
                log.info("Advisor answer with amounts not found in the tool results: {}", ungrounded);
            }
        }
        int in = usage.get() == null ? 0 : usage.get().getPromptTokens();
        int out = usage.get() == null ? 0 : usage.get().getCompletionTokens();
        Message saved = chats.append(turn.session().id(), "ASSISTANT", finalText, in, out, guardrail, clock.instant());
        finish(events, saved, in, out, outcome);
    }

    private void finish(Events events, Message saved, int inputTokens, int outputTokens, String outcome) {
        meters.counter("jars.advisor.messages", "outcome", outcome).increment();
        meters.counter("jars.advisor.tokens", "direction", "input").increment(inputTokens);
        meters.counter("jars.advisor.tokens", "direction", "output").increment(outputTokens);
        events.send(
                "done",
                Map.of(
                        "messageId", saved.id(),
                        "usage", Map.of("inputTokens", inputTokens, "outputTokens", outputTokens),
                        "disclaimer", DISCLAIMER));
        events.complete();
    }

    private static List<org.springframework.ai.chat.messages.Message> historyMessages(List<Message> history) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        for (Message m : history) {
            messages.add("USER".equals(m.role()) ? new UserMessage(m.content()) : new AssistantMessage(m.content()));
        }
        return messages;
    }

    private static String textOf(ChatResponse response) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private Session session(UserId userId, UUID sessionId) {
        return chats.session(userId.value(), sessionId).orElseThrow(() -> DomainException.notFound("cuộc trò chuyện"));
    }

    private static SessionView view(Session s) {
        return new SessionView(s.id(), s.title(), s.createdAt(), s.updatedAt());
    }
}
