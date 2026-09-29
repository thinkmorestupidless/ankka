/** Reading and writing cookies on web `Request`/`Headers`, with the attributes the console needs. */

export function readCookie(request: Request, name: string): string | undefined {
  const header = request.headers.get("cookie");
  if (!header) return undefined;
  for (const part of header.split(";")) {
    const eq = part.indexOf("=");
    if (eq < 0) continue;
    if (part.slice(0, eq).trim() === name) return part.slice(eq + 1).trim();
  }
  return undefined;
}

export interface CookieAttributes {
  maxAge: number;
  secure: boolean;
  /** `Lax` for the session: sent on top-level navigation to the console, never on a cross-site POST. */
  sameSite?: "Lax" | "Strict";
  path?: string;
}

export function serializeCookie(name: string, value: string, attributes: CookieAttributes): string {
  const parts = [
    `${name}=${value}`,
    `Path=${attributes.path ?? "/"}`,
    `Max-Age=${Math.max(0, Math.floor(attributes.maxAge))}`,
    "HttpOnly",
    `SameSite=${attributes.sameSite ?? "Lax"}`,
  ];
  if (attributes.secure) parts.push("Secure");
  return parts.join("; ");
}

/**
 * The name a cookie gets. `__Host-` over HTTPS binds it to exactly this origin — no `Domain`, path
 * `/`, `Secure` — which browsers enforce; a plain-HTTP development console cannot use the prefix.
 */
export function cookieName(base: string, secure: boolean): string {
  return secure ? `__Host-${base}` : base;
}
