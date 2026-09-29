# ankka-console

The ankka console as a package: a typed client for the control plane's API, sign-in through the
installation's realm with sessions a browser script cannot read, and the pages — organizations,
projects, services, members, deploy tokens — as routes a [React Router](https://reactrouter.com)
host mounts under a prefix of its choosing, inside its own layout.

The installation's own console is one host of this package. A product built on ankka can be another,
adding panels and actions to the pages and hiding operations it handles itself.

```ts
// app/routes.ts
import { layout, prefix, type RouteConfig } from "@react-router/dev/routes";
import { consoleRoutes } from "ankka-console/server";

export default [layout("./layout.tsx", [...prefix("console", consoleRoutes())])] satisfies RouteConfig;
```

```ts
// app/root.tsx
import { consoleMiddleware, consoleOptionsFromEnv } from "ankka-console/server";
export const middleware = [consoleMiddleware(() => consoleOptionsFromEnv(process.env))];
```

`ankka-console` is the browser-safe entry point (the provider, hooks and types a layout uses);
`ankka-console/server` is everything that runs only on the server; `ankka-console/client` is the
control plane client; `ankka-console/testing` holds a fake control plane and a fake identity provider
for a host's own tests.

The package's version is the platform's: pin the one your installation runs. See
[the package reference](https://docs.ankka.cloud/reference/console-package/) for what a host supplies
and every extension point.
