// docs:start mount
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
// docs:end mount
