package io.github.thphuc06.jars.ledger.internal;

/** Result of an idempotent operation; {@code replayed} = stored result of an earlier request with the same key. */
public record Idempotent<T>(T value, boolean replayed) {}
