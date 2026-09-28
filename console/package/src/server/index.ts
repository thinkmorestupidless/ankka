// The server-side entry point: mounting the console in a host, and running it.

// The routes table, for the host's `app/routes.ts`.
export { consoleRoutes } from "../routes.ts";
export type { ConsoleRoutesOptions, RouteConfigEntry } from "../routes.ts";

// The middleware and its options, for the host's root route.
export { consoleMiddleware } from "../middleware.ts";
export { consoleOptionsFromEnv } from "../options.ts";
export type { ConsoleOptions } from "../options.ts";
export { consoleContext, useConsoleContext } from "../context.ts";
export type { ConsoleContext } from "../context.ts";

// Keeping sessions another way.
export { SealedCookieSessionStore } from "../session/cookie-store.ts";
export type { Session, SessionStore } from "../session/cookie-store.ts";
export { SessionTokenSource } from "../session/token-source.ts";
export type { TokenSource } from "../session/token-source.ts";

// The process.
export { createConsoleServer, runConsoleServer, GatewayUri } from "./create-console-server.ts";
export type { ConsoleServer, ConsoleServerOptions } from "./create-console-server.ts";
export { tlsFetch, RotatingFiles } from "./transport.ts";
export type { FetchLike, TlsFiles } from "./transport.ts";
