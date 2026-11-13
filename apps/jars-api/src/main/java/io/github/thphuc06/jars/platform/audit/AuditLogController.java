package io.github.thphuc06.jars.platform.audit;

import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.KeysetCursor;
import io.github.thphuc06.jars.shared.ProblemTypes;
import io.github.thphuc06.jars.shared.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own audit trail, newest first. The actor is the user from the token and nothing in the request can
 * change it, so there is no way to ask for someone else's rows; rows written by the system (scheduled jobs) are not
 * the caller's and are not listed.
 */
@RestController
@RequestMapping("/api/v1/audit-log")
class AuditLogController {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 100;

    private final AuditStore store;

    AuditLogController(AuditStore store) {
        this.store = store;
    }

    @GetMapping
    AuditPage auditLog(
            UserId userId,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditEntityType entityType,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : limit;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new DomainException(ProblemTypes.BAD_REQUEST, "limit phải từ 1 đến " + MAX_PAGE_SIZE + ".");
        }
        KeysetCursor position = cursor == null || cursor.isBlank() ? null : KeysetCursor.decode(cursor);

        // one row more than the page tells whether there is a next page without a second query
        List<AuditStore.Row> rows = store.page(
                userId.toString(),
                action,
                entityType,
                position == null ? null : position.at(),
                position == null ? null : position.id(),
                pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<AuditStore.Row> pageRows = hasMore ? rows.subList(0, pageSize) : rows;
        String next = hasMore
                ? new KeysetCursor(
                                pageRows.getLast().occurredAt(),
                                pageRows.getLast().id())
                        .encode()
                : null;
        return new AuditPage(pageRows.stream().map(AuditEntryView::of).toList(), next);
    }

    /** {@code nextCursor} is null on the last page. */
    record AuditPage(List<AuditEntryView> items, String nextCursor) {}

    /**
     * One audit row. {@code payload} holds a few non-sensitive facts (ids, amounts as strings), never free text.
     * The keys depend on the action.
     */
    record AuditEntryView(
            UUID id,
            Instant occurredAt,
            String action,
            String entityType,
            UUID entityId,
            String traceId,
            Map<String, Object> payload) {
        static AuditEntryView of(AuditStore.Row row) {
            return new AuditEntryView(
                    row.id(),
                    row.occurredAt(),
                    row.action(),
                    row.entityType(),
                    row.entityId(),
                    row.traceId(),
                    row.payload());
        }
    }
}
