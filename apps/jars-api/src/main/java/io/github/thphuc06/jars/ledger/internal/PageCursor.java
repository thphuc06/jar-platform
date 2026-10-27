package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.shared.BusinessRuleViolationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Position in the Money Inbox for keyset pagination: the sort key {@code (occurredAt, id)} of the last row of a page.
 * The next page is "rows strictly after this key", which the index {@code ix_transactions_user_occurred} answers
 * directly, however deep the page. Encoded as opaque base64url so clients do not build or depend on it.
 */
record PageCursor(Instant occurredAt, UUID id) {

    static PageCursor after(LedgerTransaction last) {
        return new PageCursor(last.occurredAt(), last.id());
    }

    String encode() {
        String raw = occurredAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static PageCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            return new PageCursor(
                    Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) { // bad base64, no separator, bad instant or uuid: all the same to the client
            throw new BusinessRuleViolationException("invalid-cursor", "The cursor is not one returned by this API");
        }
    }
}
