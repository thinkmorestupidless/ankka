/**
 * Access tokens, in this instance's memory, keyed by a digest of the refresh token that obtained
 * them. Nothing depends on an entry being here: a miss costs one refresh. Bounded, least recently
 * used first out, so a busy instance's memory does not grow with the number of browsers it has seen.
 */
import { createHash } from "node:crypto";

export interface AccessTokenEntry {
  accessToken: string;
  /** Epoch milliseconds, already less the skew. */
  expiresAt: number;
  /** The newest refresh token for this session: equal to the key's when the provider did not rotate it. */
  refreshToken: string;
}

export const digest = (refreshToken: string) => createHash("sha256").update(refreshToken).digest("base64url");

export class TokenCache {
  readonly #entries = new Map<string, AccessTokenEntry>();
  readonly #capacity: number;
  readonly #skewMs: number;

  constructor(options: { capacity?: number; skewSeconds?: number } = {}) {
    this.#capacity = options.capacity ?? 10_000;
    this.#skewMs = (options.skewSeconds ?? 30) * 1000;
  }

  /** The entry for this refresh token if its access token has not expired. */
  get(refreshToken: string, now: number = Date.now()): AccessTokenEntry | undefined {
    const key = digest(refreshToken);
    const entry = this.#entries.get(key);
    if (!entry) return undefined;
    this.#entries.delete(key);
    if (entry.expiresAt <= now) return undefined;
    this.#entries.set(key, entry);
    return entry;
  }

  /** Stores an access token that expires in `expiresInSeconds`, under every refresh token given. */
  put(refreshTokens: string[], entry: Omit<AccessTokenEntry, "expiresAt">, expiresInSeconds: number, now: number = Date.now()) {
    const stored: AccessTokenEntry = { ...entry, expiresAt: now + expiresInSeconds * 1000 - this.#skewMs };
    for (const rt of new Set(refreshTokens)) {
      const key = digest(rt);
      this.#entries.delete(key);
      this.#entries.set(key, stored);
    }
    while (this.#entries.size > this.#capacity) {
      const oldest = this.#entries.keys().next().value as string;
      this.#entries.delete(oldest);
    }
  }

  forget(refreshToken: string) {
    this.#entries.delete(digest(refreshToken));
  }

  get size(): number {
    return this.#entries.size;
  }
}
