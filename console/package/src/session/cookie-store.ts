/**
 * Sessions as sealed cookies. The console keeps no session store of its own: a session is the
 * refresh token the identity provider issued, sealed into a cookie script cannot read, plus what the
 * identity provider holds. Any instance can open any cookie, so any instance serves any session.
 */
import { cookieName, readCookie, serializeCookie } from "./cookies.ts";
import { deriveKey, seal, unseal, type SealKey } from "./seal.ts";

/** What a session is to the console: enough to obtain a fresh access token, and nothing more. */
export interface Session {
  refreshToken: string;
  /** When this value was written, in epoch seconds. */
  issuedAt: number;
}

/** Where sessions are kept. The sealed cookie is the default; a host with a database may keep them there. */
export interface SessionStore {
  read(request: Request): Promise<Session | null>;
  /** Appends whatever the response needs — a `Set-Cookie`, typically — to `headers`. */
  write(session: Session, headers: Headers): Promise<void>;
  clear(headers: Headers): Promise<void>;
}

export interface SealedCookieSessionStoreOptions {
  secret: string;
  /** `true` in a cluster: the cookie is `__Host-`-prefixed and `Secure`. */
  secure: boolean;
  /** The realm's longest SSO session, in seconds; a cookie older than this is not opened. Default 36000. */
  maxAgeSeconds?: number;
  name?: string;
}

interface SealedSession {
  v: 1;
  rt: string;
  iat: number;
}

export class SealedCookieSessionStore implements SessionStore {
  readonly name: string;
  readonly #secure: boolean;
  readonly #maxAge: number;
  readonly #key: Promise<SealKey>;

  constructor(options: SealedCookieSessionStoreOptions) {
    this.name = cookieName(options.name ?? "ankka_console", options.secure);
    this.#secure = options.secure;
    this.#maxAge = options.maxAgeSeconds ?? 36_000;
    this.#key = deriveKey(options.secret, "ankka-console session v1");
  }

  async read(request: Request): Promise<Session | null> {
    const raw = readCookie(request, this.name);
    if (!raw) return null;
    const value = await unseal<SealedSession>(raw, await this.#key, this.name);
    if (!value || value.v !== 1 || typeof value.rt !== "string") return null;
    if (Date.now() / 1000 - value.iat > this.#maxAge) return null;
    return { refreshToken: value.rt, issuedAt: value.iat };
  }

  async write(session: Session, headers: Headers): Promise<void> {
    const value: SealedSession = { v: 1, rt: session.refreshToken, iat: session.issuedAt };
    const sealed = await seal(value, await this.#key, this.name);
    headers.append("set-cookie", serializeCookie(this.name, sealed, { maxAge: this.#maxAge, secure: this.#secure }));
  }

  async clear(headers: Headers): Promise<void> {
    headers.append("set-cookie", serializeCookie(this.name, "", { maxAge: 0, secure: this.#secure }));
  }
}

/** A sign-in in progress: what the callback needs to finish it, sealed into a short-lived cookie. */
export interface PendingLogin {
  state: string;
  nonce: string;
  verifier: string;
  returnTo: string;
  iat: number;
}

/** A value shown on exactly one page after a redirect — a deploy token's secret, say. */
export class SealedFlash<T> {
  readonly name: string;
  readonly #secure: boolean;
  readonly #maxAge: number;
  readonly #key: Promise<SealKey>;

  constructor(options: { secret: string; secure: boolean; name: string; maxAgeSeconds: number }) {
    this.name = cookieName(options.name, options.secure);
    this.#secure = options.secure;
    this.#maxAge = options.maxAgeSeconds;
    this.#key = deriveKey(options.secret, `ankka-console ${options.name} v1`);
  }

  async write(value: T, headers: Headers): Promise<void> {
    const sealed = await seal({ value, iat: Math.floor(Date.now() / 1000) }, await this.#key, this.name);
    headers.append("set-cookie", serializeCookie(this.name, sealed, { maxAge: this.#maxAge, secure: this.#secure }));
  }

  /** The value, if one was written and has not expired; the cookie is cleared either way. */
  async take(request: Request, headers: Headers): Promise<T | null> {
    const raw = readCookie(request, this.name);
    if (!raw) return null;
    headers.append("set-cookie", serializeCookie(this.name, "", { maxAge: 0, secure: this.#secure }));
    const opened = await unseal<{ value: T; iat: number }>(raw, await this.#key, this.name);
    if (!opened || Date.now() / 1000 - opened.iat > this.#maxAge) return null;
    return opened.value;
  }
}
