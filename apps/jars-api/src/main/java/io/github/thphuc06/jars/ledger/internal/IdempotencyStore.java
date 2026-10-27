package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.shared.BusinessRuleViolationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotency-Key (docs/03-api/api-contract.md §4): runs an operation at most once per (user, key) within 24 hours
 * and returns the stored result to every repetition, e.g. when the client re-sends after a network timeout.
 *
 * <p>It works only INSIDE the business transaction ({@code MANDATORY}): the key is reserved first and completed
 * last, so the key and the money movement are committed together or not at all. There is no window in which the
 * money moved but the key was not saved (that would allow a double payment) or the reverse.
 */
@Component
class IdempotencyStore {

    static final Duration TTL = Duration.ofHours(24);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    IdempotencyStore(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param operation name of the endpoint, part of the hash: the same key on another endpoint is a different request
     * @param request the normalized command; equal commands must serialize to equal JSON
     */
    @Transactional(propagation = Propagation.MANDATORY)
    <T> Idempotent<T> run(
            UUID userId, UUID key, String operation, Object request, Class<T> resultType, Supplier<T> work) {
        String requestHash = hash(operation, request);
        if (!reserve(userId, key, requestHash)) {
            return replay(userId, key, requestHash, resultType);
        }
        T result = work.get(); // throws → the whole transaction rolls back, the reservation with it
        jdbc.sql("""
                        UPDATE idempotency_keys SET response_body = CAST(:body AS JSONB)
                        WHERE user_id = :userId AND idempotency_key = :key
                        """)
                .param("body", json.writeValueAsString(result))
                .param("userId", userId)
                .param("key", key)
                .update();
        return new Idempotent<>(result, false);
    }

    /**
     * One statement, race-safe. A second request with the same key BLOCKS here on the primary key while the first
     * transaction is running; when that one commits, this INSERT does nothing (returns 0) and the stored result is
     * replayed; when it rolls back, this INSERT succeeds and the second request does the work itself. An EXPIRED row
     * is taken over by the {@code DO UPDATE ... WHERE}.
     *
     * @return true if this transaction now owns the key and must do the work
     */
    private boolean reserve(UUID userId, UUID key, String requestHash) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int rows = jdbc.sql("""
                        INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, expires_at)
                        VALUES (:userId, :key, :hash, :expiresAt)
                        ON CONFLICT (user_id, idempotency_key) DO UPDATE
                            SET request_hash = EXCLUDED.request_hash,
                                response_body = NULL,
                                created_at = now(),
                                expires_at = EXCLUDED.expires_at
                            WHERE idempotency_keys.expires_at <= :now
                        """)
                .param("userId", userId)
                .param("key", key)
                .param("hash", requestHash)
                .param("expiresAt", now.plus(TTL))
                .param("now", now)
                .update();
        return rows == 1;
    }

    private <T> Idempotent<T> replay(UUID userId, UUID key, String requestHash, Class<T> resultType) {
        Stored stored = jdbc.sql("""
                        SELECT request_hash, response_body::text AS response_body FROM idempotency_keys
                        WHERE user_id = :userId AND idempotency_key = :key
                        """)
                .param("userId", userId)
                .param("key", key)
                .query((rs, n) -> new Stored(rs.getString("request_hash"), rs.getString("response_body")))
                .single();
        if (!stored.requestHash().equals(requestHash)) {
            throw new BusinessRuleViolationException(
                    "idempotency-key-reused", "This Idempotency-Key was already used for a different request");
        }
        if (stored.responseBody() == null) { // cannot happen: the reserving transaction completes it before commit
            throw new IllegalStateException("idempotency key without a stored result");
        }
        return new Idempotent<>(json.readValue(stored.responseBody(), resultType), true);
    }

    private String hash(String operation, Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((operation + "\n" + json.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    private record Stored(String requestHash, String responseBody) {}
}
