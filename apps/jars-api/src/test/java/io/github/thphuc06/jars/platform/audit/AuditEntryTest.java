package io.github.thphuc06.jars.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.shared.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What may go into a payload: typed facts, money as a string, no free text and no keys that name secrets. */
class AuditEntryTest {

    private final AuditEntry entry = new AuditEntry(null);

    @Test
    void facts_areTyped_andMoneyIsAString() {
        UUID id = UUID.randomUUID();

        entry.with("jarId", id)
                .with("type", AuditEntityType.JAR)
                .with("amount", Money.vnd("85000.5"))
                .with("pct", new BigDecimal("12.50"))
                .with("count", 3L)
                .with("flag", true)
                .with("date", LocalDate.of(2026, 11, 13))
                .with("month", YearMonth.of(2026, 11))
                .withCode("locale", "vi-VN")
                .withIds("jarIds", List.of(id))
                .withCodes("changed", List.of("name", "targetAmount"));

        assertThat(entry.facts())
                .containsEntry("jarId", id.toString())
                .containsEntry("type", "JAR")
                .containsEntry("amount", "85000.50")
                .containsEntry("currency", "VND")
                .containsEntry("pct", "12.50")
                .containsEntry("count", 3L)
                .containsEntry("flag", true)
                .containsEntry("date", "2026-11-13")
                .containsEntry("month", "2026-11")
                .containsEntry("locale", "vi-VN")
                .containsEntry("jarIds", List.of(id.toString()))
                .containsEntry("changed", List.of("name", "targetAmount"));
    }

    @Test
    void freeTextDoesNotFitThroughACode() {
        for (String text : List.of(
                "ghi chu: mua ca phe voi Lan",
                "an@example.com",
                "x".repeat(41),
                "",
                "eyJhbGciOiJSUzI1NiJ9.payload.signature-that-is-a-token-and-long")) {
            assertThatThrownBy(() -> entry.withCode("status", text)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> entry.withCode("status", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry.withCodes("changed", List.of("ok", "not ok")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysThatNameSensitiveData_areRejected() {
        for (String key : List.of(
                "email",
                "userEmail",
                "accessToken",
                "token",
                "secret",
                "password",
                "note",
                "notes",
                "prompt",
                "llmPrompt",
                "message",
                "content",
                "counterparty",
                "memo",
                "description",
                "authorization")) {
            assertThatThrownBy(() -> entry.with(key, true))
                    .as(key)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be audited");
        }
    }

    @Test
    void keysMustBeLowerCamelCaseNames() {
        for (String key : List.of("", "Upper", "with space", "snake_case", "1st", "a".repeat(41), "dot.ted")) {
            assertThatThrownBy(() -> entry.with(key, 1L)).as(key).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> entry.with(null, 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsAreBounded() {
        List<UUID> many = new ArrayList<>();
        for (int i = 0; i <= AuditEntry.MAX_LIST; i++) {
            many.add(UUID.randomUUID());
        }

        assertThatThrownBy(() -> entry.withIds("jarIds", many)).isInstanceOf(IllegalArgumentException.class);
        assertThat(entry.facts()).isEmpty();
    }

    @Test
    void theEntityIdIsRequiredToBeNonNull() {
        assertThatThrownBy(() -> entry.entity(null)).isInstanceOf(NullPointerException.class);
    }
}
