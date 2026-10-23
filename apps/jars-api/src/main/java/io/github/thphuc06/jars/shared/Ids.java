package io.github.thphuc06.jars.shared;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Generates UUID version 7 (RFC 9562): 48-bit Unix epoch milliseconds followed by random bits.
 * Ids created later sort after ids created earlier, which keeps B-tree primary-key indexes compact
 * (random UUIDv4 keys scatter inserts across the whole index).
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static UUID newId() {
        return newId(Clock.systemUTC());
    }

    static UUID newId(Clock clock) {
        long millis = clock.millis();
        long randA = RANDOM.nextInt(1 << 12); // 12 random bits after the version
        long randB = RANDOM.nextLong(); // 62 random bits after the variant

        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16 // unix_ts_ms (48 bits)
                | 0x7000L // version 7 (4 bits)
                | randA; // rand_a (12 bits)
        long lsb = (randB & 0x3FFF_FFFF_FFFF_FFFFL) // rand_b (62 bits)
                | 0x8000_0000_0000_0000L; // variant 10xx (2 bits)
        return new UUID(msb, lsb);
    }
}
