package io.github.thphuc06.jars.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void newId_isVersion7WithRfcVariant() {
        UUID id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2); // IETF variant (10xx)
    }

    @Test
    void newId_encodesTheClockMillisecondsInTheFirst48Bits() {
        Instant at = Instant.parse("2026-10-23T08:00:00.123Z");

        UUID id = Ids.newId(Clock.fixed(at, ZoneOffset.UTC));

        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(at.toEpochMilli());
    }

    @Test
    void newId_laterIdsSortAfterEarlierIds() {
        UUID earlier = Ids.newId(Clock.fixed(Instant.parse("2026-10-23T08:00:00Z"), ZoneOffset.UTC));
        UUID later = Ids.newId(Clock.fixed(Instant.parse("2026-10-23T08:00:00.001Z"), ZoneOffset.UTC));

        // string order == PostgreSQL uuid order, so B-tree inserts land at the right end of the index
        assertThat(later.toString()).isGreaterThan(earlier.toString());
    }

    @Test
    void newId_isUniqueWithinTheSameMillisecond() {
        Clock frozen = Clock.fixed(Instant.parse("2026-10-23T08:00:00Z"), ZoneOffset.UTC);
        Set<UUID> ids = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId(frozen));
        }

        assertThat(ids).hasSize(10_000);
    }
}
