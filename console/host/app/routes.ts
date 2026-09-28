import { layout, type RouteConfig } from "@react-router/dev/routes";
import { consoleRoutes } from "ankka-console/server";

export default [layout("./layout.tsx", consoleRoutes())] satisfies RouteConfig;
