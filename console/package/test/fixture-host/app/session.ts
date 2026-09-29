// docs:start session-store
import { randomBytes } from "node:crypto";
import type { Session, SessionStore } from "ankka-console/server";

/**
 * Sessions kept on the server, keyed by a random id the cookie carries: the shape of a host that has
 * a database. A Map stands in for one here; a real host keeps them where its other state is.
 */
export class MemorySessionStore implements SessionStore {
  readonly #sessions = new Map<string, Session>();
  readonly #cookie = "fixture_sid";

  async read(request: Request): Promise<Session | null> {
    const id = /(?:^|;\s*)fixture_sid=([^;]+)/.exec(request.headers.get("cookie") ?? "")?.[1];
    return (id && this.#sessions.get(id)) || null;
  }

  async write(session: Session, headers: Headers): Promise<void> {
    const id = randomBytes(24).toString("base64url");
    this.#sessions.set(id, session);
    headers.append("set-cookie", `${this.#cookie}=${id}; Path=/; HttpOnly; SameSite=Lax`);
  }

  async clear(headers: Headers): Promise<void> {
    headers.append("set-cookie", `${this.#cookie}=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax`);
  }

  get size(): number {
    return this.#sessions.size;
  }
}
// docs:end session-store
