package io.github.thphuc06.jars.advisor.internal.web;

import io.github.thphuc06.jars.advisor.internal.service.AdvisorService;
import io.github.thphuc06.jars.advisor.internal.service.AdvisorService.MessageView;
import io.github.thphuc06.jars.advisor.internal.service.AdvisorService.SessionView;
import io.github.thphuc06.jars.advisor.internal.service.AdvisorService.Turn;
import io.github.thphuc06.jars.shared.ProblemTypes;
import io.github.thphuc06.jars.shared.UserId;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The AI advisor (api-contract.md section 3). Sending a message answers with {@code text/event-stream}: {@code tool}
 * events while the model looks up data, {@code token} events with the answer, an optional {@code replace} when the
 * output guardrail masked it, then {@code done} (or {@code error}).
 */
@RestController
@RequestMapping("/api/v1/chat/sessions")
class AdvisorController {

    private static final Logger log = LoggerFactory.getLogger(AdvisorController.class);
    /** A turn with a few tool calls takes seconds; this only guards against a hung stream. */
    private static final long STREAM_TIMEOUT_MS = 120_000;

    record CreateSessionRequest(@Size(max = 80) String title) {}

    record SendMessageRequest(String content) {}

    private final AdvisorService advisor;

    AdvisorController(AdvisorService advisor) {
        this.advisor = advisor;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SessionView create(UserId userId, @RequestBody(required = false) CreateSessionRequest request) {
        return advisor.createSession(userId, request == null ? null : request.title());
    }

    @GetMapping
    List<SessionView> sessions(UserId userId) {
        return advisor.sessions(userId);
    }

    @GetMapping("/{sessionId}/messages")
    List<MessageView> messages(UserId userId, @PathVariable UUID sessionId) {
        return advisor.messages(userId, sessionId);
    }

    @PostMapping(value = "/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter send(UserId userId, @PathVariable UUID sessionId, @RequestBody SendMessageRequest request) {
        // 404, 422, 429 and 503 are answered here, as ordinary problem responses, before the stream starts
        Turn turn = advisor.prepare(userId, sessionId, request.content());
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AdvisorService.Events events = new AdvisorService.Events() {
            @Override
            public void send(String event, Object data) {
                try {
                    emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
                } catch (IOException e) {
                    // the client went away; the turn is still stored
                    log.debug("Advisor stream closed by the client: {}", e.getMessage());
                }
            }

            @Override
            public void complete() {
                emitter.complete();
            }
        };
        Thread.ofVirtual().name("advisor-" + sessionId).start(() -> {
            try {
                advisor.stream(turn, events);
            } catch (RuntimeException e) {
                log.warn("Advisor turn failed", e);
                events.send(
                        "error",
                        Map.of(
                                "type",
                                ProblemTypes.INTERNAL_ERROR.uri(),
                                "title",
                                "Trợ lý AI gặp lỗi",
                                "status",
                                502,
                                "detail",
                                "Không trả lời được lúc này. Hãy thử lại sau ít phút."));
                emitter.complete();
            }
        });
        return emitter;
    }
}
