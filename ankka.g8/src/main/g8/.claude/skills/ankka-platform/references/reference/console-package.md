# The console package

> Build a web application of your own on the ankka-console npm package — mount its pages under your prefix and layout, keep sessions your way, and add panels and actions.

Source: https://docs.ankka.cloud/reference/console-package/
`ankka-console` is the installation's console as an npm package. It holds a typed client for the control
plane's API, sign-in through the installation's realm, and the console's pages as routes a
[React Router](https://reactrouter.com) application mounts. The installation's own console is one application
built on it; a product built on ankka can be another, mounting the same pages inside its own site and adding
to them.

The package's version is the platform's. Pin the version your installation runs.

## Entry points

| Import | What it holds |
|---|---|
| `ankka-console` | Browser-safe: `ConsoleProvider`, `useConsole`, `ConsoleLink`, `ConsoleForm`, the extension types and `operations`. |
| `ankka-console/server` | Server-only: `consoleRoutes`, `consoleMiddleware`, `consoleOptionsFromEnv`, `SessionStore`, `SealedCookieSessionStore`, `TokenSource`, `createConsoleServer`, `runConsoleServer`. |
| `ankka-console/client` | `ControlPlaneClient`, `ControlPlaneError` and every wire type with its schema. |
| `ankka-console/testing` | `fakeControlPlane` and `fakeIssuer`, for a host's own tests. |
| `ankka-console/styles.css` | The pages' stylesheet. |

The package is ESM only and needs Node 24, React 19 and React Router 8.

## Mount the pages

`consoleRoutes()` returns route config entries. Spread them under any prefix, inside a layout of your own,
beside routes of your own:

```ts
import { layout, prefix, route, type RouteConfig } from "@react-router/dev/routes";
import { consoleRoutes } from "ankka-console/server";

export default [
  layout("./layout.tsx", [
    // The package's pages, under a prefix of this host's choosing.
    ...prefix("x", consoleRoutes()),
    // A page of the host's own, beside them.
    route("x/billing", "./billing.tsx"),
  ]),
] satisfies RouteConfig;
```

List the package in Vite's `ssr.noExternal`, because its route modules are part of your application:

```ts
export default defineConfig({ plugins: [reactRouter()], ssr: { noExternal: ["ankka-console"] } });
```

Every link a page writes is relative to the mount, so the pages work under any prefix. The routes, relative
to the mount:

| Path | Page |
|---|---|
| `/` | Your organizations |
| `/organizations/new` | Create an organization |
| `/organizations/:organizationId` | An organization, its projects and administration |
| `/organizations/:organizationId/members` | Members and invitations |
| `/organizations/:organizationId/tokens` | Deploy tokens |
| `/organizations/:organizationId/projects/new` | Create a project |
| `/projects/:projectId` | A project, its services and registry |
| `/projects/:projectId/services/apply` | Apply a descriptor |
| `/projects/:projectId/services/:name` | A service |
| `/projects/:projectId/services/:name/logs` | Its logs |
| `/stream/projects/:projectId`, `/stream/services/:projectId/:name` | Live updates, as server-sent events |
| `/auth/sign-in`, `/auth/callback`, `/auth/sign-out` | Sign-in, unless `consoleRoutes({ auth: false })` |

## Install the middleware

The middleware goes on your root route. It gives every package page its context, refuses a state-changing
request from another site, keeps every signed-in response out of caches, writes back a session that changed
while a request was served, and logs one line per request:

```tsx
export const middleware = [
  consoleMiddleware(() => ({
    controlPlane: { url: env.FIXTURE_CONTROL_PLANE_URL! },
    auth: { clientId: "ankka-console", clientSecret: "dev", allowInsecure: true },
    publicOrigin: env.FIXTURE_PUBLIC_ORIGIN!,
    mount: "/x",
    // The host keeps sessions its own way; the package's sign-in writes through it.
    session: new MemorySessionStore(),
    sessionSecret: "fixture-secret-for-one-time-values",
    extensions,
  })),
];
```

The options:

| Option | Meaning |
|---|---|
| `controlPlane.url` | The control plane's address. `controlPlane.tls` names a certificate, key and authority to present and trust. |
| `auth` | The realm client: `clientId`, `clientSecret`, and optionally `backchannelUrl`, `ca`, `allowInsecure` and `issuer`. Without `issuer`, it is read from the control plane's `GET /auth`. |
| `publicOrigin` | Your application's origin as a browser sees it. Sign-in returns here, and a state-changing request from any other origin is refused. |
| `mount` | The prefix the routes are mounted under. `/` by default. |
| `sessionSecret` | Seals the default session cookie and the values carried across one redirect, such as a new deploy token's secret. |
| `session` | A `SessionStore`, when sessions are kept somewhere other than the sealed cookie. |
| `tokens` | A `TokenSource`, when your application signs people in itself. |
| `extensions` | Panels, actions and hidden operations. |
| `log` | Receives each request's log line. Standard output by default. |

`consoleOptionsFromEnv(process.env, extensions)` builds the options from the variables the installation's
console is configured by; see [Install and configure the console](../platform/console.md#configuration).

## Wrap the pages in your layout

The layout renders the package's pages through its `<Outlet />`. Wrap it in `ConsoleProvider` with the same
extensions the middleware was given, so panels and actions render in the browser as well as on the server:

```tsx
import { Outlet } from "react-router";
import { ConsoleProvider } from "ankka-console";
import { extensions } from "./extensions.tsx";

export default function ProductLayout() {
  return (
    <ConsoleProvider extensions={extensions}>
      <div className="product ac-root">
        <header data-product-chrome>A product built on ankka</header>
        <Outlet />
      </div>
    </ConsoleProvider>
  );
}
```

`useConsole()` gives any component under the layout the mount, the signed-in principal and whether an
operation is shown.

## Keep sessions your way

By default a session is a sealed cookie carrying the person's refresh token, which needs no store. A host with
a database can keep sessions there instead by implementing `SessionStore`; the package's sign-in writes
through it and reads from it:

```ts
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
    headers.append("set-cookie", `\${this.#cookie}=\${id}; Path=/; HttpOnly; SameSite=Lax`);
  }

  async clear(headers: Headers): Promise<void> {
    headers.append("set-cookie", `\${this.#cookie}=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax`);
  }

  get size(): number {
    return this.#sessions.size;
  }
}
```

A host that signs people in itself supplies a `TokenSource` instead: its `accessToken(request, headers,
{ refresh })` returns the bearer for the request, or `null` to have the person sent to sign in. Mount the
routes with `consoleRoutes({ auth: false })` so the package's own sign-in pages are left out.

Whatever holds them, access tokens are never given to the browser.

## Extend the pages

Extensions add to the pages without changing them:

```tsx
import type { ConsoleExtensions } from "ankka-console";

/**
 * What this host adds to the package's pages. The same object goes to the middleware (for each
 * panel's `load`, which runs on the server) and to the layout's provider (for rendering).
 */
export const extensions: ConsoleExtensions = {
  panels: {
    organization: [
      {
        id: "plan",
        title: "Plan",
        // Runs on the server with the page's own data; a failure here is shown in the panel's place.
        load: async (_context, organization) => {
          if (organization.id.startsWith("broken")) throw new Error("the billing service did not answer");
          return { plan: "Team", organization: organization.id };
        },
        Component: ({ data }) => <p data-plan>{(data as { plan: string } | undefined)?.plan ?? "No plan"}</p>,
      },
    ],
  },
  actions: {
    "organization.create": [{ id: "checkout", label: "Start a subscription", href: () => "/x/billing" }],
  },
  hidden: ["organization.delete"],
};
```

- **Panels** add a section to an organization's, project's or service's page. `load` runs on the server with
  the page's entity and a way to obtain the person's access token; `Component` renders its result. A panel
  whose `load` fails or whose component throws shows its failure in its own place, and the page around it
  works.
- **Actions** add a link beside a named operation.
- **Hidden** operations are not shown. Hiding is presentation only: the control plane's rules still decide
  whether an operation posted anyway succeeds.

The operations, by name: `organization.create`, `organization.rename`, `organization.delete`,
`organization.disable`, `organization.enable`, `organization.quota.set`, `organization.quota.clear`,
`member.invite`, `member.role`, `member.remove`, `invitation.withdraw`, `member.repair`, `token.create`,
`token.revoke`, `project.create`, `project.rename`, `project.delete`, `registry.set`, `registry.clear`,
`service.apply`, `service.pause`, `service.resume`, `service.restart`, `service.expose`, `service.unexpose`,
`service.delete`, `service.logs`.

## Restyle the pages

The pages use classes prefixed `ac-` and take every colour, face and space from custom properties on
`.ac-root`. Import `ankka-console/styles.css`, then your own stylesheet, and override the properties:

```css
.product {
  --ac-ink: #20124d;
  --ac-amber: #ff5a36;
}
```

## Run it

`ankka-console/server` also holds the process the installation's console runs: `runConsoleServer({ build,
clientDir })` serves your built application, TLS with certificate rotation when `ANKKA_CONSOLE_TLS_DIR` is set,
readiness on its own port, and a graceful drain on shutdown. A host may use it, or serve its build with any
server React Router supports.

## Test it

`ankka-console/testing` holds the doubles the package's own tests use. `fakeIssuer()` is an OpenID Connect
provider with a login form, the code and refresh grants, revocation and logout; `fakeControlPlane()` answers
every route the console calls from in-memory state, with the control plane's rules and refusals. Start both,
point your host at them, and drive it with a browser or `fetch`.
