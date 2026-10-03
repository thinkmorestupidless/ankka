import { fileURLToPath } from "node:url";

/**
 * A route config entry, the shape `@react-router/dev/routes` builds. Declared here rather than
 * imported so that the package's runtime entry point never loads the framework's build tooling.
 */
export interface RouteConfigEntry {
  id?: string;
  path?: string;
  index?: boolean;
  caseSensitive?: boolean;
  file: string;
  children?: RouteConfigEntry[];
}

export interface ConsoleRoutesOptions {
  /**
   * Whether the package's own sign-in routes are included. A host that signs people in itself and
   * supplies a `TokenSource` passes `false`.
   */
  auth?: boolean;
}

// The route modules sit beside this file: `src/routes/*.tsx` when the package is used from source
// (the repository's own host and tests, under the `ankka-source` condition), `dist/routes/*.js` when
// it is installed from npm. Route config entries are file paths, not module specifiers, so each is
// an absolute path into wherever the package is installed.
const here = import.meta.url;
const fromSource = here.endsWith(".ts");
const routesDir = fileURLToPath(new URL("./routes/", here));

function file(name: string, kind: "page" | "resource"): string {
  const ext = fromSource ? (kind === "page" ? ".tsx" : ".ts") : ".js";
  return routesDir + name + ext;
}

function page(path: string | undefined, name: string): RouteConfigEntry {
  return path === undefined
    ? { id: `ankka-console/${name}`, index: true, file: file(name, "page") }
    : { id: `ankka-console/${name}`, path, file: file(name, "page") };
}

function resource(path: string, name: string): RouteConfigEntry {
  return { id: `ankka-console/${name}`, path, file: file(name, "resource") };
}

/**
 * The console's pages and resource routes, to be spread inside a host's layout and under any
 * prefix it chooses:
 *
 * ```ts
 * layout("./layout.tsx", [...prefix("console", consoleRoutes())])
 * ```
 */
export function consoleRoutes(options: ConsoleRoutesOptions = {}): RouteConfigEntry[] {
  const auth = options.auth ?? true;
  return [
    page(undefined, "front"),
    page("organizations/new", "organization-new"),
    page("organizations/:organizationId", "organization"),
    page("organizations/:organizationId/members", "members"),
    page("organizations/:organizationId/tokens", "tokens"),
    page("organizations/:organizationId/projects/new", "project-new"),
    page("projects/:projectId", "project"),
    page("projects/:projectId/services/apply", "service-apply"),
    page("projects/:projectId/services/:name", "service"),
    page("projects/:projectId/services/:name/logs", "logs"),
    page("projects/:projectId/services/:name/topology", "service-topology"),
    resource("stream/projects/:projectId", "stream.project"),
    resource("stream/services/:projectId/:name", "stream.service"),
    ...(auth
      ? [page("auth/sign-in", "auth.sign-in"), page("auth/callback", "auth.callback"), page("auth/sign-out", "auth.sign-out")]
      : []),
  ];
}
