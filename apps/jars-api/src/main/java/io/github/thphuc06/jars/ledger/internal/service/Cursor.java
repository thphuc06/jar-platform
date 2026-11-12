package io.github.thphuc06.jars.ledger.internal.service;

import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.ProblemTypes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * The opaque keyset cursor handed to clients: the position {@code (occurred_at, id)} of the last row of a page.
 * Opaque so clients cannot depend on its shape and we can change it freely.
 */
record Cursor(Instant occurredAt, UUID id) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    String encode() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, occurredAt);
        return ENCODER.encodeToString((micros + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String value) {
        try {
            String[] parts = new String(DECODER.decode(value), StandardCharsets.UTF_8).split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("bad cursor");
            }
            return new Cursor(
                    Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException e) { // also covers NumberFormatException and bad Base64
            throw new DomainException(ProblemTypes.BAD_REQUEST, "cursor không hợp lệ.");
        }
    }
}
