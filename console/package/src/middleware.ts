/**
 * The console's middleware, installed on the host's root route. It builds the request's context,
 * refuses a state-changing request from another site, forbids caching of anything a signed-in person
 * sees, writes back a session that changed while the request was served, and logs one line.
 */
import type { MiddlewareFunction } from "react-router";
import { consoleContext, createConsoleContext } from "./context.ts";
import { createRuntime, type ConsoleRuntime } from "./runtime.ts";
import type { ConsoleOptions } from "./options.ts";

const SAFE = new Set(["GET", "HEAD", "OPTIONS"]);

/**
 * Whether a state-changing request came from the console's own pages. `SameSite=Lax` already keeps
 * the session cookie off a cross-site POST; this is the second lock, and the one that holds for a
 * host whose sessions are not cookies.
 */
export function crossSite(request: Request, publicOrigin: string): boolean {
  if (SAFE.has(request.method)) return false;
  if (request.headers.get("sec-fetch-site") === "cross-site") return true;
  const origin = request.headers.get("origin");
  if (!origin || origin === "null") return origin === "null";
  return origin !== new URL(publicOrigin).origin && origin !== new URL(request.url).origin;
}

export function consoleMiddleware(options: ConsoleOptions | (() => ConsoleOptions)): MiddlewareFunction<Response> {
  let runtime: ConsoleRuntime | undefined;
  const get = () => (runtime ??= createRuntime(typeof options === "function" ? options() : options));

  return async ({ request, context }, next) => {
    const rt = get();
    const started = performance.now();
    const path = new URL(request.url).pathname;
    if (crossSite(request, rt.options.publicOrigin)) {
      rt.log({ at: new Date().toISOString(), method: request.method, path, status: 403, reason: "cross-site" });
      return new Response("cross-site request refused", { status: 403 });
    }
    const ctx = createConsoleContext(rt, request);
    context.set(consoleContext, ctx);

    const response = await next();

    for (const cookie of ctx.headers.getSetCookie()) response.headers.append("set-cookie", cookie);
    // Nothing a signed-in person was shown may be kept by any cache, including the browser's back
    // button after sign-out.
    response.headers.set("cache-control", "no-store");
    rt.log({
      at: new Date().toISOString(),
      method: request.method,
      path,
      status: response.status,
      durationMs: Math.round(performance.now() - started),
      subject: ctx.knownPrincipal()?.subject,
    });
    return response;
  };
}
