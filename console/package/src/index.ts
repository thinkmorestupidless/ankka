// The browser-safe entry point: what a host's layout and its own components use. Everything that
// runs only on the server — the routes table, the middleware, sessions, the server itself — is
// `ankka-console/server`, so a host's browser bundle never resolves a Node module.
export { ConsoleProvider, useConsole, ConsoleLink, ConsoleForm } from "./ui/console.tsx";
export type { UseConsole } from "./ui/console.tsx";
export type { Principal } from "./context.ts";
export { operations } from "./extensions/types.ts";
export type { Action, ConsoleExtensions, Operation, Panel, PanelKind, PanelLoadContext } from "./extensions/types.ts";
