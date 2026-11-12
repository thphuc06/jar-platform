package io.github.thphuc06.jars.ledger.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.ProblemTypes;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorTest {

    @Test
    void encodeThenDecode_roundTripsWithMicrosecondPrecision() {
        Cursor original = new Cursor(Instant.parse("2026-11-12T03:21:09.123456Z"), UUID.randomUUID());

        Cursor back = Cursor.decode(original.encode());

        assertThat(back).isEqualTo(original);
    }

    @Test
    void encodedForm_isUrlSafeAndOpaque() {
        String encoded = new Cursor(Instant.now(), UUID.randomUUID()).encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void garbage_isRejectedAsABadRequest() {
        for (String bad : new String[] {"not base64!!", "bm90LWEtY3Vyc29y", "", "MTIzOm5vdC1hLXV1aWQ"}) {
            assertThatThrownBy(() -> Cursor.decode(bad))
                    .isInstanceOfSatisfying(
                            DomainException.class, e -> assertThat(e.type()).isEqualTo(ProblemTypes.BAD_REQUEST));
        }
    }
}
