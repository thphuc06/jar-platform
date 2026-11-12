package io.github.thphuc06.jars.platform.idempotency;

import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.ProblemTypes;
import io.github.thphuc06.jars.shared.UserId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes a state-changing request safe to repeat (docs/03-api/api-contract.md §4).
 *
 * <p>{@link #execute} must be called <em>inside</em> the business transaction. It first tries to insert the key. If
 * that works, the action runs and its response is stored in the same transaction. If the key already exists the
 * insert has waited for any in-flight transaction with that key, so the stored response is complete and is replayed
 * (or rejected when the key is reused for a different request). A rollback removes the key as well, so the client
 * can simply retry.
 */
@Service
public class IdempotencyService {

    /** What the controller should return. {@code replayed} is true when the body came from an earlier request. */
    public record Result<T>(T body, int status, boolean replayed) {}

    public static final Duration TTL = Duration.ofHours(24);
    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,128}$");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    IdempotencyService(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> Result<T> execute(
            UserId userId,
            String key,
            String requestHash,
            Class<T> responseType,
            int successStatus,
            Supplier<T> action) {
        requireValidKey(key);
        Instant now = clock.instant();
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys (user_id, key, request_hash, created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (user_id, key) DO NOTHING
                        """)
                .params(userId.value(), key, requestHash, utc(now), utc(now.plus(TTL)))
                .update();

        if (inserted == 0) {
            return replay(userId, key, requestHash, responseType);
        }

        T body = action.get();
        jdbc.sql("""
                        UPDATE idempotency_keys SET status_code = ?, response_body = ?::jsonb
                        WHERE user_id = ? AND key = ?
                        """)
                .params(successStatus, json.writeValueAsString(body), userId.value(), key)
                .update();
        return new Result<>(body, successStatus, false);
    }

    /** SHA-256 over the parts that define "the same request" (method, path, body). */
    public static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Deletes expired keys; called by the monthly job rather than a scheduler so Aurora can still pause. */
    @Transactional
    public int purgeExpired() {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at < ?")
                .param(utc(clock.instant()))
                .update();
    }

    private <T> Result<T> replay(UserId userId, String key, String requestHash, Class<T> responseType) {
        StoredResponse stored = jdbc.sql("""
                        SELECT request_hash, status_code, response_body::text AS body
                        FROM idempotency_keys WHERE user_id = ? AND key = ?
                        """)
                .params(userId.value(), key)
                .query((rs, n) -> new StoredResponse(
                        rs.getString("request_hash"), rs.getInt("status_code"), rs.getString("body")))
                .single();
        if (!stored.requestHash().equals(requestHash)) {
            throw new DomainException(
                    ProblemTypes.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key này đã được dùng cho một request khác.");
        }
        return new Result<>(json.readValue(stored.body(), responseType), stored.statusCode(), true);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void requireValidKey(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new DomainException(
                    ProblemTypes.IDEMPOTENCY_KEY_INVALID,
                    "Idempotency-Key phải dài 8–128 ký tự gồm chữ, số, '-' hoặc '_' (ví dụ một UUID).");
        }
    }

    private record StoredResponse(String requestHash, int statusCode, String body) {}
}
