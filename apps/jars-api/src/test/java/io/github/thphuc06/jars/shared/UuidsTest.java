package io.github.thphuc06.jars.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidsTest {

    @Test
    void v7_hasVersionSevenAndRfcVariant() {
        UUID id = Uuids.v7();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void v7_encodesTheGivenTimestamp() {
        long millis = 1_794_000_000_123L;

        UUID id = Uuids.v7(millis);

        assertThat(Uuids.timestampOf(id)).isEqualTo(millis);
    }

    @Test
    void v7_sortsByCreationTime() {
        UUID earlier = Uuids.v7(1_000_000L);
        UUID later = Uuids.v7(1_000_001L);

        assertThat(earlier.toString().compareTo(later.toString())).isNegative();
    }

    @Test
    void v7_isUniqueEvenWithinTheSameMillisecond() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Uuids.v7(42L));
        }

        assertThat(ids).hasSize(10_000);
    }
}
