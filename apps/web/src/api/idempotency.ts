import { useState } from "react";

/** RFC 4122 version 4 UUID. `crypto.randomUUID` exists only in secure contexts (https, localhost). */
export function uuidv4(source: Crypto = globalThis.crypto): string {
  if (typeof source.randomUUID === "function") return source.randomUUID();
  const bytes = source.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * One `Idempotency-Key` per user intent (api-contract.md section 4).
 *
 * Retrying the same intent after a network error or a 5xx must send the SAME key, so the server returns the stored
 * result instead of booking the money twice. If the user edits the form after a failure it is a different request:
 * reusing the key would be answered with 422 `idempotency-key-reused`, so a new key is made. The form's values are
 * the fingerprint; call {@link clear} after the intent succeeded.
 */
export class IntentKey {
  private key: string | null = null;
  private fingerprint: string | null = null;

  constructor(private readonly generate: () => string = uuidv4) {}

  get(fingerprint: string): string {
    if (this.key === null || this.fingerprint !== fingerprint) {
      this.key = this.generate();
      this.fingerprint = fingerprint;
    }
    return this.key;
  }

  clear(): void {
    this.key = null;
    this.fingerprint = null;
  }
}

/** One {@link IntentKey} per form instance. */
export function useIntentKey(): IntentKey {
  const [intent] = useState(() => new IntentKey());
  return intent;
}
