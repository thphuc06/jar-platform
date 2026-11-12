package io.github.thphuc06.jars.shared;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * The opaque keyset cursor handed to clients: the position {@code (timestamp, id)} of the last row of a page, for
 * lists ordered newest first. Opaque so clients cannot depend on its shape and it can change freely. Microsecond
 * precision matches what PostgreSQL stores in {@code timestamptz}.
 */
public record KeysetCursor(Instant at, UUID id) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public String encode() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, at);
        return ENCODER.encodeToString((micros + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** @throws DomainException (400) when the value is not a cursor produced by {@link #encode()} */
    public static KeysetCursor decode(String value) {
        try {
            String[] parts = new String(DECODER.decode(value), StandardCharsets.UTF_8).split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("bad cursor");
            }
            return new KeysetCursor(
                    Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException e) { // also covers NumberFormatException and bad Base64
            throw new DomainException(ProblemTypes.BAD_REQUEST, "cursor không hợp lệ.");
        }
    }
}
