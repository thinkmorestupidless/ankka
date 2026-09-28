/**
 * What every package route receives about the request it is serving: a control plane client that
 * acts as the signed-in person, where the package is mounted, and how to send someone to sign in.
 */
import { createContext, data, redirect, type RouterContextProvider } from "react-router";
import { ControlPlaneClient } from "./client/control-plane.ts";
import { ControlPlaneError, ControlPlaneUnreachable, SignInRequired } from "./client/errors.ts";
import { IdentityProviderUnavailable } from "./auth/oidc.ts";
import type { ConsoleRuntime } from "./runtime.ts";
import type { ConsoleExtensions, Operation } from "./extensions/types.ts";

export interface Principal {
  subject: string;
  name?: string;
  email?: string;
  platformAdmin: boolean;
}

export interface ConsoleContext {
  runtime: ConsoleRuntime;
  request: Request;
  client: ControlPlaneClient;
  /** Where a changed session is written; the middleware appends it to whatever response is sent. */
  headers: Headers;
  mount: string;
  extensions: ConsoleExtensions;
  /** A path under the mount: `href("organizations/acme")`. */
  href(path?: string): string;
  signInUrl(returnTo?: string): string;
  accessToken(): Promise<string | null>;
  principal(): Promise<Principal | null>;
  /** The principal if this request has already obtained a token; never causes a call of its own. */
  knownPrincipal(): Principal | null;
}

export const consoleContext = createContext<ConsoleContext>();

/** The context the console's middleware put on this request; a clear error if the host did not install it. */
export function useConsoleContext(context: Readonly<RouterContextProvider>): ConsoleContext {
  try {
    return context.get(consoleContext);
  } catch {
    throw new Error("the console's middleware is not installed: add consoleMiddleware(options) to the root route's `middleware`");
  }
}

function decodeClaims(token: string): Record<string, unknown> | null {
  try {
    return JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString("utf8")) as Record<string, unknown>;
  } catch {
    return null;
  }
}

export function principalOf(token: string | null): Principal | null {
  if (!token) return null;
  const claims = decodeClaims(token);
  if (!claims || typeof claims.sub !== "string") return null;
  const roles = (claims.realm_access as { roles?: string[] } | undefined)?.roles ?? [];
  return {
    subject: claims.sub,
    name: typeof claims.name === "string" ? claims.name : undefined,
    email: typeof claims.email === "string" ? claims.email : undefined,
    platformAdmin: roles.includes("platform-admin"),
  };
}

/** Only a path within the console is ever returned to after sign-in; anything else lands on the front page. */
export function safeReturnTo(runtime: ConsoleRuntime, value: string | null | undefined): string {
  if (!value || !value.startsWith(runtime.mount) || value.startsWith("//") || value.includes("\\")) return runtime.mount;
  try {
    const url = new URL(value, "http://console.invalid");
    if (url.origin !== "http://console.invalid") return runtime.mount;
    return url.pathname + url.search;
  } catch {
    return runtime.mount;
  }
}

export function createConsoleContext(runtime: ConsoleRuntime, request: Request): ConsoleContext {
  const headers = new Headers();
  let token: Promise<string | null> | undefined;
  let known: string | null = null;
  const accessToken = (refresh = false) => {
    if (refresh || !token) {
      token = runtime.tokens.accessToken(request, headers, { refresh });
      token.then((t) => (known = t)).catch(() => undefined);
    }
    return token;
  };
  const href = (path = "") => runtime.mount + path.replace(/^\/+/, "");
  return {
    runtime,
    request,
    headers,
    mount: runtime.mount,
    extensions: runtime.options.extensions ?? {},
    client: new ControlPlaneClient({
      baseUrl: runtime.options.controlPlane.url,
      transport: runtime.controlPlaneFetch,
      bearer: ({ refresh }) => accessToken(refresh),
    }),
    href,
    signInUrl(returnTo) {
      const url = new URL(request.url);
      const back = returnTo ?? url.pathname.replace(/\.data$/, "") + url.search;
      return `${href("auth/sign-in")}?returnTo=${encodeURIComponent(back)}`;
    },
    accessToken: () => accessToken(false),
    async principal() {
      return principalOf(await accessToken(false));
    },
    knownPrincipal: () => principalOf(known),
  };
}

/** A failure a page shows rather than crashes on, as its error boundary receives it. */
export type ConsoleFailure =
  | { kind: "lost"; reason: string }
  | { kind: "refused"; status: number; reason: string }
  | { kind: "unavailable"; what: "identity provider" | "control plane"; reason: string };

/**
 * Runs a loader's or action's work, turning what the person should see into responses: sign in
 * again, access lost, a refusal, something unavailable. Anything else is a bug and propagates.
 */
export async function guard<T>(ctx: ConsoleContext, work: () => Promise<T>): Promise<T> {
  try {
    return await work();
  } catch (e) {
    throw failureResponse(ctx, e);
  }
}

export function failureResponse(ctx: ConsoleContext, e: unknown): unknown {
  // The middleware adds any changed session to the redirect, as to every response.
  if (e instanceof SignInRequired) return redirect(ctx.signInUrl());
  if (e instanceof IdentityProviderUnavailable) {
    return data<ConsoleFailure>({ kind: "unavailable", what: "identity provider", reason: e.message }, { status: 503, headers: { "retry-after": "5" } });
  }
  if (e instanceof ControlPlaneUnreachable) {
    return data<ConsoleFailure>({ kind: "unavailable", what: "control plane", reason: e.message }, { status: 503, headers: { "retry-after": "5" } });
  }
  if (e instanceof ControlPlaneError) {
    if (e.status === 404) return data<ConsoleFailure>({ kind: "lost", reason: e.reason }, { status: 404 });
    if (e.retryable) return data<ConsoleFailure>({ kind: "unavailable", what: "control plane", reason: e.reason }, { status: e.status });
    return data<ConsoleFailure>({ kind: "refused", status: e.status, reason: e.reason }, { status: e.status });
  }
  return e;
}

/** What an action answers when the control plane refused it: shown beside the form, values kept. */
export interface ActionRefusal {
  intent: string;
  status: number;
  reason: string;
  problems: string[];
  values: Record<string, string>;
}

/**
 * Runs an action's work. A refusal the person can act on — a `400`, `403` or `409` — comes back as
 * data for the page, with the form's values, so the form is shown again with the reason beside it.
 * Everything else is what `guard` makes of it.
 */
export async function act<T>(ctx: ConsoleContext, intent: string, form: FormData, work: () => Promise<T>): Promise<T | ReturnType<typeof data<ActionRefusal>>> {
  try {
    return await work();
  } catch (e) {
    if (e instanceof ControlPlaneError && (e.status === 400 || e.status === 403 || e.status === 409)) {
      const values: Record<string, string> = {};
      for (const [k, v] of form.entries()) if (typeof v === "string" && k !== "password") values[k] = v;
      return data<ActionRefusal>({ intent, status: e.status, reason: e.reason, problems: e.problems, values }, { status: e.status });
    }
    throw failureResponse(ctx, e);
  }
}

export const text = (form: FormData, name: string): string => {
  const v = form.get(name);
  return typeof v === "string" ? v.trim() : "";
};

/** What every package page's loader data carries for the pages and the host's layout. */
export interface ConsolePageData {
  mount: string;
  principal: Principal | null;
  hidden: Operation[];
}

export async function pageData(ctx: ConsoleContext): Promise<ConsolePageData> {
  return { mount: ctx.mount, principal: await ctx.principal(), hidden: ctx.extensions.hidden ?? [] };
}
