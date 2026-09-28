/**
 * The signed-in person's access token for this request. The only thing a page's data ever needs
 * from a session, and the seam a host with its own sign-in replaces.
 */
import type { Tokens } from "../auth/oidc.ts";
import { SessionEnded } from "../auth/oidc.ts";

/** The one thing a token source asks of the identity provider. */
export interface Refresher {
  refresh(refreshToken: string): Promise<Tokens>;
}
import type { SessionStore } from "./cookie-store.ts";
import { digest, type TokenCache } from "./token-cache.ts";

export interface TokenSource {
  /**
   * The bearer for this request, obtaining a fresh one when `refresh` is set or none is held;
   * `null` means nobody is signed in and the person is to be sent to sign in. `headers` is where a
   * session that changed on the way — a rotated refresh token, a cleared cookie — is written.
   */
  accessToken(request: Request, headers: Headers, options?: { refresh?: boolean }): Promise<string | null>;
}

/**
 * Tokens from the package's own sign-in: the refresh token from the session store, access tokens
 * cached in this instance's memory, a refresh when there is none. A refresh that rotates the
 * refresh token caches under both and writes the new one back, so a request still carrying the old
 * cookie — on this instance or another — keeps working until the old token's own expiry.
 */
export class SessionTokenSource implements TokenSource {
  readonly #store: SessionStore;
  readonly #cache: TokenCache;
  readonly #issuer: Refresher;
  readonly #inFlight = new Map<string, Promise<Tokens>>();

  constructor(options: { store: SessionStore; cache: TokenCache; issuer: Refresher }) {
    this.#store = options.store;
    this.#cache = options.cache;
    this.#issuer = options.issuer;
  }

  async accessToken(request: Request, headers: Headers, options: { refresh?: boolean } = {}): Promise<string | null> {
    const session = await this.#store.read(request);
    if (!session) return null;
    const cached = this.#cache.get(session.refreshToken);
    if (cached && !options.refresh) return cached.accessToken;

    // The newest refresh token this instance knows for the session: the cookie may be one rotation behind.
    const latest = cached?.refreshToken ?? session.refreshToken;
    let tokens: Tokens;
    try {
      tokens = await this.#refreshOnce(latest);
    } catch (e) {
      if (e instanceof SessionEnded) {
        this.#cache.forget(session.refreshToken);
        await this.#store.clear(headers);
        return null;
      }
      throw e;
    }
    this.#cache.put([session.refreshToken, latest, tokens.refreshToken], { accessToken: tokens.accessToken, refreshToken: tokens.refreshToken }, tokens.expiresIn);
    if (tokens.refreshToken !== session.refreshToken) {
      await this.#store.write({ refreshToken: tokens.refreshToken, issuedAt: Math.floor(Date.now() / 1000) }, headers);
    }
    return tokens.accessToken;
  }

  /** Remembers a freshly signed-in session's access token, so its first page needs no refresh. */
  remember(tokens: Tokens) {
    this.#cache.put([tokens.refreshToken], { accessToken: tokens.accessToken, refreshToken: tokens.refreshToken }, tokens.expiresIn);
  }

  forget(refreshToken: string) {
    this.#cache.forget(refreshToken);
  }

  /** Two requests from one browser arriving together refresh once, not twice. */
  #refreshOnce(refreshToken: string): Promise<Tokens> {
    const key = digest(refreshToken);
    const existing = this.#inFlight.get(key);
    if (existing) return existing;
    const pending = this.#issuer.refresh(refreshToken).finally(() => this.#inFlight.delete(key));
    this.#inFlight.set(key, pending);
    return pending;
  }
}
