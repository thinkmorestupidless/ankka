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
extensions the middleware was given, so panels and actions render in the browser as well as on the server,
and in the console's shell.

The shell is parts you compose. `Shell` is the grid and the console's root; `Backdrop` is the mesh behind
every surface; `Bar` is the wordmark, where the person is, the page's primary operation and who is signed in;
`Rail` is the column of areas (organizations, projects, services, members, deploy tokens) with signing out at
its foot; `Listing` is what sits beside a page, such as a project's services. Each package page brings its
own body and its inspector, the column of what it offers to be done. Mount the backdrop and the bar at
least, and the rest where you have content for them. A grid column that has no part takes no room. This
host mounts the backdrop and the bar, puts its own wordmark in the bar, and chooses the light theme:

```tsx
import { Outlet } from "react-router";
import { Backdrop, Bar, ConsoleProvider, Shell } from "ankka-console";
import { extensions } from "./extensions.tsx";

/**
 * A product's layout: the console's backdrop and bar around every page, its own pages and the
 * package's alike. It has no rail, listing or inspector of its own; the package's pages bring their
 * inspector with them. It chooses the light theme.
 */
export default function ProductLayout() {
  return (
    <ConsoleProvider extensions={extensions}>
      <Shell className="product" theme="light">
        <Backdrop />
        <Bar wordmark={<span data-product-chrome>A product built on ankka</span>} />
        <Outlet />
      </Shell>
    </ConsoleProvider>
  );
}
```

`useConsole()` gives any component under the layout the mount, the signed-in principal, the shell's data
for the page and whether an operation is shown. A page of your own goes inside `Page`, which gives it the
page's column and, if you pass `inspector`, a column of operations beside it. `Bar` takes `children` for
your own navigation beside the wordmark and `end` for what you show in place of the signed-in person, such
as a sign-in link for a visitor.

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
    headers.append("set-cookie", `${this.#cookie}=${id}; Path=/; HttpOnly; SameSite=Lax`);
  }

  async clear(headers: Headers): Promise<void> {
    headers.append("set-cookie", `${this.#cookie}=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax`);
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
`project-secret.set`, `project-secret.unset`, `service.apply`, `service.pause`, `service.resume`, `service.restart`, `service.rollback`, `service.expose`, `service.unexpose`,
`service.delete`, `service.logs`.

## Restyle the pages

Every colour, face, radius and size the pages use is a custom property prefixed `--ac-`, read through
`var()` by every rule. Import `ankka-console/styles.css`, then your own stylesheet, and set the properties
you want on your root or anywhere above it:

```css
.product {
  --ac-color-ink: #20124d;
  --ac-color-amber: #ff5a36;
}
```

Your declaration wins wherever you make it and whatever its specificity, because every rule in the
package's stylesheet sits in a cascade layer and yours does not. Set properties, not rules: the classes
prefixed `ac-` and the utilities prefixed `ac:` are the package's own and change between releases. The
stylesheet resets nothing outside the console's root, so your own pages keep their styles.

The console is dark. `<Shell theme="light">` chooses the light theme, which redefines the colour properties
below; a property you set wins in either theme. The console does not follow the browser's preference,
because a browser reports a preference for light when nobody has stated one.

The surfaces are translucent and the backdrop shows through them, so how readable text is depends on what
is behind it. The backdrop is brightest where `--ac-color-glow` peaks, at the centre of the screen. The
package's own values keep every ink at 4.5:1 against every surface at that point in both themes, which is
the level of contrast WCAG 2.1 AA requires; its build fails if they do not. A host that sets the glow, the
mesh colours or a surface's tint takes that on itself: keep the glow's alpha at or below the package's, or
check the contrast of your text at the centre of the screen. A browser that cannot blur what is behind a
surface is shown each surface drawn over `--ac-color-mesh-base` instead, so the same values hold there.

The face is Inter, served from the console's own origin: the package's stylesheet names the font files
beside it, and a bundler that copies a stylesheet's assets (Vite does) serves them from your origin with no
configuration, so nothing is fetched from anywhere else. A host that sets `--ac-font-sans` serves its own
face the same way.

A host that runs Tailwind itself can import `ankka-console/theme.css` for the properties alone.

| Property | What it is | Dark | Light |
|---|---|---|---|
| `--ac-color-mesh-base` | The backdrop's darker colour, and what a surface is drawn over where blur is unavailable. | `#111f26` | `#e6eeee` |
| `--ac-color-mesh-slate` | The backdrop's lighter colour. | `#1e2e3a` | `#cfe0dc` |
| `--ac-color-glow` | The glow at the centre of the screen. Its alpha is the brightest the backdrop gets: the limit. | `rgba(212, 128, 88, 0.34)` | `rgba(224, 165, 38, 0.30)` |
| `--ac-color-peach` | The warmth in the lower corner. | `rgba(222, 170, 132, 0.16)` | `rgba(241, 213, 194, 0.80)` |
| `--ac-color-dot` | The dot grid across the backdrop. | `rgba(255, 255, 255, 0.08)` | `rgba(19, 52, 59, 0.10)` |
| `--ac-color-glass` | The rail, the bar, the listing and the inspector. | `rgba(255, 255, 255, 0.05)` | `rgba(255, 255, 255, 0.50)` |
| `--ac-color-glass-strong` | A button, and the item being read in the listing. | `rgba(255, 255, 255, 0.09)` | `rgba(255, 255, 255, 0.85)` |
| `--ac-color-glass-line` | The edge of the glass surfaces. | `rgba(255, 255, 255, 0.2)` | `rgba(19, 52, 59, 0.14)` |
| `--ac-color-tile` | A card on the page, and an item in the listing. | `rgba(255, 255, 255, 0.06)` | `rgba(255, 255, 255, 0.50)` |
| `--ac-color-tile-line` | A card's edge and the rules in its tables. | `rgba(255, 255, 255, 0.14)` | `rgba(19, 52, 59, 0.10)` |
| `--ac-color-node` | A part of a service's shape and of its topology. | `rgba(255, 255, 255, 0.1)` | `rgba(255, 255, 255, 0.62)` |
| `--ac-color-node-line` | A part's edge. | `rgba(255, 255, 255, 0.38)` | `rgba(19, 52, 59, 0.22)` |
| `--ac-color-inset` | Fields, logs, the section links and a part's inset row. | `rgba(0, 0, 0, 0.2)` | `rgba(19, 52, 59, 0.06)` |
| `--ac-color-edge` | The joins between parts. | `rgba(255, 255, 255, 0.7)` | `rgba(19, 52, 59, 0.55)` |
| `--ac-color-ink` | Text. | `#f5f8f7` | `#13343b` |
| `--ac-color-ink-2` | Secondary text: labels, hints, counts. | `#dfe8e6` | `#33504f` |
| `--ac-color-ink-3` | Section titles and placeholders. | `#ccd9d6` | `#4a6465` |
| `--ac-color-amber` | The accent: focus, the current area, toggles, the primary operation. | `#f0b83c` | `#e0a526` |
| `--ac-color-amber-ink` | Text on the accent. | `#13343b` | `#13343b` |
| `--ac-color-green` | A ready lifecycle's mark. | `#8fe0ad` | `#1f6b3a` |
| `--ac-color-red` | Failure: a failed lifecycle, a destructive button, a field's error. | `#ffc4bc` | `#a8231b` |
| `--ac-color-red-wash` | Behind a refusal. | `rgba(255, 138, 128, 0.16)` | `#fbe9e7` |
| `--ac-shadow-part` | The shadow under a part. | `0 18px 50px rgba(5, 15, 18, 0.28)` | `0 18px 50px rgba(19, 52, 59, 0.10)` |
| `--ac-radius-panel` | The shell's surfaces. | `20px` | the same |
| `--ac-radius-card` | Cards. | `14px` | the same |
| `--ac-radius-control` | Fields and listing items. | `10px` | the same |
| `--ac-radius-node` | A part of the shape. | `18px` | the same |
| `--ac-font-sans` | The face; the package serves Inter itself. | `"Inter Variable", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif` | the same |
| `--ac-font-mono` | Logs, descriptors and secrets. | `ui-monospace, "SF Mono", Menlo, Consolas, monospace` | the same |
| `--ac-size-rail` | The rail's width. | `56px` | the same |
| `--ac-size-panel` | The listing's width. | `232px` | the same |
| `--ac-size-inspector` | The inspector's width. | `300px` | the same |
| `--ac-size-bar` | The bar's height. | `64px` | the same |
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
