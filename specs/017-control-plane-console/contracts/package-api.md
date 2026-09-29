# Contract: the `ankka-console` package

What a host imports, what it must supply, and what it may add. This is the surface
`reference/console-package.md` documents and the fixture host (FR-048) exercises.

## Entry points

| Import | Exports |
|---|---|
| `ankka-console` | `consoleRoutes`, `consoleMiddleware`, `ConsoleOptions`, `ConsoleExtensions`, `Panel`, `Action`, `Operation`, `SessionStore`, `SealedCookieSessionStore`, `TokenSource`, `useConsole` |
| `ankka-console/client` | `ControlPlaneClient`, `ControlPlaneError`, every wire type and its zod schema (`ServiceStatus`, `serviceStatusSchema`, …) |
| `ankka-console/server` | `createConsoleServer` (the Node server of R3: TLS, rotation, probe, request log, graceful shutdown), for a host that wants the same server as this repository's |
| `ankka-console/testing` | `fakeControlPlane`, `fakeIssuer`, `scenarios` (the spec-scenario map the Playwright suite checks) |
| `ankka-console/styles.css` | The pages' stylesheet |

## Mounting

```ts
// app/routes.ts in a host
import { layout, prefix, type RouteConfig } from "@react-router/dev/routes";
import { consoleRoutes } from "ankka-console";

export default [
  layout("./layout.tsx", [
    ...prefix("console", consoleRoutes()),   // any prefix, or none
    // the host's own routes beside the package's
  ]),
] satisfies RouteConfig;
```

```ts
// app/root.tsx in a host
import { consoleMiddleware } from "ankka-console";
export const middleware = [consoleMiddleware(options)];
```

Rules the package guarantees:

- Every link a package page renders is relative to the mount it was matched under (`useConsole().mount`).
- A page renders inside the host's layout through `<Outlet />`; the package owns nothing outside its
  routes' element.
- The routes the package owns, relative to the mount:

| Path | Page |
|---|---|
| `/` | Front page: identity and the organizations the person sees |
| `/organizations/new` | Create an organization |
| `/organizations/:organizationId` | Organization: projects, panels, rename/delete, admin controls |
| `/organizations/:organizationId/members` | Members and invitations |
| `/organizations/:organizationId/tokens` | Deploy tokens |
| `/organizations/:organizationId/projects/new` | Create a project |
| `/projects/:projectId` | Project: services, registry, rename/delete |
| `/projects/:projectId/services/apply` | Apply a descriptor |
| `/projects/:projectId/services/:name` | Service: status, history, operations |
| `/projects/:projectId/services/:name/logs` | Logs |
| `/auth/sign-in` | Starts the code flow (`?returnTo=`) |
| `/auth/callback` | Finishes it |
| `/auth/sign-out` | `POST`: ends the session and the identity provider's |
| `/stream/projects/:projectId` | SSE, listing updates |
| `/stream/services/:projectId/:name` | SSE, status and log updates |

## What the host supplies

`ConsoleOptions` (see `data-model.md`). Required: `controlPlane.url`, `auth.clientId`,
`auth.clientSecret`, `publicOrigin`, and a session secret for the default store. Everything else has
a default.

### `SessionStore`

```ts
interface SessionStore {
  read(request: Request): Promise<Session | null>;
  write(session: Session, headers: Headers): Promise<void>;   // appends Set-Cookie or its equivalent
  clear(headers: Headers): Promise<void>;
}
```

### `TokenSource`

```ts
interface TokenSource {
  /** The bearer for this request, refreshing if needed; `null` means "send them to sign in". */
  accessToken(request: Request, headers: Headers): Promise<string | null>;
}
```

The default `TokenSource` is built from the `SessionStore` and the realm client. A host with its own
sign-in supplies a `TokenSource` and the package never renders or handles `/auth/*`; those routes are
omitted from `consoleRoutes({ auth: false })`.

## Extensions

```ts
const extensions: ConsoleExtensions = {
  panels: {
    organization: [{
      id: "billing",
      title: "Billing",
      load: async (ctx, organization) => billing.summary(organization.id),
      Component: ({ entity, data }) => <BillingPanel organization={entity} summary={data} />,
    }],
  },
  actions: {
    "organization.create": [{ id: "checkout", label: "Start a subscription", href: () => "/checkout" }],
  },
  hidden: new Set(["organization.create"]),
};
```

Guarantees: a panel's `load` rejection or `Component` throw renders that panel's failure in place
and leaves the page intact; `hidden` removes the control, never the route's server behaviour; an
action's `href` is used as given (the host owns its own links).

## Operations (the closed set)

`organization.create`, `organization.rename`, `organization.delete`, `organization.disable`,
`organization.enable`, `organization.quota.set`, `organization.quota.clear`, `member.invite`,
`member.role`, `member.remove`, `invitation.withdraw`, `member.repair`, `token.create`,
`token.revoke`, `project.create`, `project.rename`, `project.delete`, `registry.set`,
`registry.clear`, `service.apply`, `service.pause`, `service.resume`, `service.restart`,
`service.expose`, `service.unexpose`, `service.delete`, `service.logs`.

## Versioning

The package's version is the platform's release version. A host pins it exactly as ankka-cloud pins
`ankka-*` libraries. Within a release the wire types the package decodes are those the fixtures of
`contracts/fixtures.md` describe; unknown fields in a response are ignored, so a newer control plane
answers an older package.
