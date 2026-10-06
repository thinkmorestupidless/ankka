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

/** The rail's areas, in the order it shows them. */
export const areas = ["organizations", "projects", "services", "members", "tokens"] as const;
export type Area = (typeof areas)[number];

export interface Crumb {
  label: string;
  to?: string;
}

/** One entry of the shell's listing: a project's service, or an organization's project. */
export type ListingItem =
  | { kind: "service"; key: string; name: string; to: string; lifecycle: string; confirmed: boolean; ready: number; desired: number }
  | { kind: "project"; key: string; name: string; to: string; services: number };

/**
 * What the shell around a page shows: the area the rail marks, where the bar says the member is,
 * the page's primary operation, the organization and project the rail's areas open, and the listing
 * beside the page. A page with no listing (the front page) leaves it out.
 */
export interface ShellData {
  area?: Area;
  crumbs: Crumb[];
  primary?: { label: string; to: string; operation?: Operation };
  /** `manages`: the member may manage its members and deploy tokens (an owner, or an administrator). */
  organization?: { id: string; name: string; manages: boolean };
  project?: { id: string; name: string };
  listing?: { title: string; label: string; items: ListingItem[]; current?: string };
}

/** What every package page's loader data carries for the pages and the host's layout. */
export interface ConsolePageData {
  mount: string;
  principal: Principal | null;
  hidden: Operation[];
  shell?: ShellData;
}

const byName = (a: { name: string }, b: { name: string }) => a.name.localeCompare(b.name);

/** The listing of a project's services, in name order, marking the one being read. */
export function servicesListing(
  project: { id: string; name: string },
  services: { name: string; lifecycle: string; confirmed: boolean; readyInstances: number; desiredInstances: number }[],
  current?: string,
): NonNullable<ShellData["listing"]> {
  const base = `projects/${encodeURIComponent(project.id)}/services/`;
  return {
    title: project.name,
    label: `Services in ${project.name}`,
    current,
    items: [...services].sort(byName).map((s) => ({
      kind: "service",
      key: s.name,
      name: s.name,
      to: base + encodeURIComponent(s.name),
      lifecycle: s.lifecycle,
      confirmed: s.confirmed,
      ready: s.readyInstances,
      desired: s.desiredInstances,
    })),
  };
}

/** The listing of an organization's projects, in name order, marking the one being read. */
export function projectsListing(
  organization: { id: string; name: string },
  projects: { id: string; name: string; organizationId: string; services: number }[],
  current?: string,
): NonNullable<ShellData["listing"]> {
  return {
    title: organization.name,
    label: `Projects in ${organization.name}`,
    current,
    items: projects
      .filter((p) => p.organizationId === organization.id)
      .sort(byName)
      .map((p) => ({ kind: "project", key: p.id, name: p.name, to: `projects/${encodeURIComponent(p.id)}`, services: p.services })),
  };
}

/**
 * The page data every package page returns. It is also what makes a page require a session: nobody
 * signed in is `SignInRequired`, which `guard` turns into the trip to sign in, even for a page whose
 * loader asks the control plane nothing.
 */
export async function pageData(ctx: ConsoleContext): Promise<ConsolePageData> {
  const principal = await ctx.principal();
  if (!principal) throw new SignInRequired();
  return { mount: ctx.mount, principal, hidden: ctx.extensions.hidden ?? [] };
}

/** A page's data with the shell around it; loaders call it once everything the shell names is read. */
export function withShell(page: ConsolePageData, shell: ShellData): ConsolePageData {
  return { ...page, shell };
}

type OrganizationLike = { id: string; name: string; role?: "owner" | "member" | null };

/** The shell of a page that belongs to an organization: its projects beside the page. */
export function organizationShell(
  page: ConsolePageData,
  o: OrganizationLike,
  projects: { id: string; name: string; organizationId: string; services: number }[],
  area: Area,
  tail: Crumb[],
  primary?: ShellData["primary"],
): ConsolePageData {
  const orgPath = `organizations/${encodeURIComponent(o.id)}`;
  return withShell(page, {
    area,
    crumbs: [{ label: "Organizations", to: "" }, { label: o.name, to: orgPath }, ...tail],
    primary,
    organization: { id: o.id, name: o.name, manages: o.role === "owner" || (page.principal?.platformAdmin ?? false) },
    listing: projectsListing(o, projects),
  });
}

/** The shell of a page that belongs to a project: its services beside the page. */
export function projectShell(
  page: ConsolePageData,
  o: OrganizationLike,
  p: { id: string; name: string },
  services: Parameters<typeof servicesListing>[1],
  tail: Crumb[],
  primary?: ShellData["primary"],
  current?: string,
): ConsolePageData {
  return withShell(page, {
    area: current === undefined ? "projects" : "services",
    crumbs: [
      { label: "Organizations", to: "" },
      { label: o.name, to: `organizations/${encodeURIComponent(o.id)}` },
      { label: p.name, to: `projects/${encodeURIComponent(p.id)}` },
      ...tail,
    ],
    primary,
    organization: { id: o.id, name: o.name, manages: o.role === "owner" || (page.principal?.platformAdmin ?? false) },
    project: { id: p.id, name: p.name },
    listing: servicesListing(p, services, current),
  });
}

/** A service's pages put applying a new descriptor first. */
export function applyPrimary(projectId: string, name: string): NonNullable<ShellData["primary"]> {
  return {
    label: "Apply a new descriptor",
    to: `projects/${encodeURIComponent(projectId)}/services/apply?name=${encodeURIComponent(name)}`,
    operation: "service.apply",
  };
}
