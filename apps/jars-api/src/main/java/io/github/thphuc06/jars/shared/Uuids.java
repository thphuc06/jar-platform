package io.github.thphuc06.jars.shared;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562): 48 bits of Unix time in milliseconds followed by random bits, so ids sort by creation time and
 * B-tree indexes stay compact. {@code java.util.UUID} has no v7 factory before JDK 26. Entities get their id from
 * Hibernate's {@code @UuidGenerator(style = VERSION_7)}; this class is for the few inserts written as native SQL.
 */
public final class Uuids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuids() {}

    public static UUID v7() {
        return v7(System.currentTimeMillis());
    }

    static UUID v7(long epochMillis) {
        long randA = RANDOM.nextLong() & 0x0FFFL; // 12 random bits
        long randB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL; // 62 random bits
        long msb = ((epochMillis & 0xFFFF_FFFF_FFFFL) << 16) | 0x7000L | randA; // version 7
        long lsb = 0x8000_0000_0000_0000L | randB; // variant 10
        return new UUID(msb, lsb);
    }

    /** Milliseconds since the epoch encoded in a v7 id. */
    public static long timestampOf(UUID v7) {
        return v7.getMostSignificantBits() >>> 16;
    }
}
