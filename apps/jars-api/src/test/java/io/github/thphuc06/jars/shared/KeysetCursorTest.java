package io.github.thphuc06.jars.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KeysetCursorTest {

    @Test
    void encodeThenDecode_roundTripsWithMicrosecondPrecision() {
        KeysetCursor original = new KeysetCursor(Instant.parse("2026-11-12T03:21:09.123456Z"), UUID.randomUUID());

        KeysetCursor back = KeysetCursor.decode(original.encode());

        assertThat(back).isEqualTo(original);
    }

    @Test
    void encodedForm_isUrlSafeAndOpaque() {
        String encoded = new KeysetCursor(Instant.now(), UUID.randomUUID()).encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void garbage_isRejectedAsABadRequest() {
        for (String bad : new String[] {"not base64!!", "bm90LWEtY3Vyc29y", "", "MTIzOm5vdC1hLXV1aWQ"}) {
            assertThatThrownBy(() -> KeysetCursor.decode(bad))
                    .isInstanceOfSatisfying(
                            DomainException.class, e -> assertThat(e.type()).isEqualTo(ProblemTypes.BAD_REQUEST));
        }
    }
}
