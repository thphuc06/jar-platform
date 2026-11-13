package io.github.thphuc06.jars.platform.audit;

import io.github.thphuc06.jars.shared.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What one audited call reports: the entity id and a few facts for the {@code payload}. The facts are typed on
 * purpose: ids, enums, money, numbers, flags and dates have overloads, and a string must look like a code, so a
 * free-text note, an e-mail address or a prompt does not fit through by accident. Keys that name sensitive data are
 * rejected too. Money is written as a string, never as a floating-point number.
 */
public final class AuditEntry {

    static final int MAX_LIST = 20;

    private static final Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");
    /** Short identifiers only: letters, digits and _ . : - (a locale, an enum name). No spaces, no at-sign. */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,40}");

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(email|token|secret|password|passwd|authorization|note|prompt|message|content|counterparty|memo|description).*");

    private final AuditEntry enclosing;
    private final Map<String, Object> facts = new LinkedHashMap<>();
    private UUID entityId;
    private boolean unchanged;

    AuditEntry(AuditEntry enclosing) {
        this.enclosing = enclosing;
    }

    // ---------------------------------------------------------------- facts

    public AuditEntry with(String key, UUID value) {
        return put(key, Objects.requireNonNull(value, key).toString());
    }

    public AuditEntry with(String key, Enum<?> value) {
        return put(key, Objects.requireNonNull(value, key).name());
    }

    /** The amount as a plain string; the currency is recorded once as {@code currency}. */
    public AuditEntry with(String key, Money value) {
        Objects.requireNonNull(value, key);
        requireKey(key);
        facts.put("currency", value.currency().getCurrencyCode());
        return put(key, value.amount().toPlainString());
    }

    public AuditEntry with(String key, BigDecimal value) {
        return put(key, Objects.requireNonNull(value, key).toPlainString());
    }

    public AuditEntry with(String key, long value) {
        return put(key, value);
    }

    public AuditEntry with(String key, boolean value) {
        return put(key, value);
    }

    public AuditEntry with(String key, LocalDate value) {
        return put(key, Objects.requireNonNull(value, key).toString());
    }

    public AuditEntry with(String key, YearMonth value) {
        return put(key, Objects.requireNonNull(value, key).toString());
    }

    /** A short code such as a locale or a status; free text is rejected. */
    public AuditEntry withCode(String key, String value) {
        return put(key, requireCode(key, value));
    }

    public AuditEntry withIds(String key, Collection<UUID> values) {
        return putList(key, values.stream().map(UUID::toString).toList());
    }

    /** Names of things that changed, for instance the fields of an update. Each is a code. */
    public AuditEntry withCodes(String key, Collection<String> values) {
        return putList(key, values.stream().map(v -> requireCode(key, v)).toList());
    }

    // ------------------------------------------------------- for the aspect

    UUID entityId() {
        return entityId;
    }

    boolean isUnchanged() {
        return unchanged;
    }

    Map<String, Object> facts() {
        return facts;
    }

    AuditEntry enclosing() {
        return enclosing;
    }

    void entity(UUID id) {
        this.entityId = Objects.requireNonNull(id, "entity id");
    }

    void markUnchanged() {
        this.unchanged = true;
    }

    private AuditEntry put(String key, Object value) {
        requireKey(key);
        facts.put(key, value);
        return this;
    }

    private AuditEntry putList(String key, List<String> values) {
        requireKey(key);
        if (values.size() > MAX_LIST) {
            throw new IllegalArgumentException("Audit fact '" + key + "' has more than " + MAX_LIST + " entries");
        }
        facts.put(key, new ArrayList<>(values));
        return this;
    }

    private static String requireCode(String key, String value) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("Audit fact '" + key + "' must be a short code, not free text");
        }
        return value;
    }

    private static void requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Audit fact keys are lowerCamelCase names: " + key);
        }
        if (SENSITIVE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Audit fact '" + key + "' names data that must not be audited");
        }
    }
}
