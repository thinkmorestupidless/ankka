// The browser-safe entry point: what a host's layout and its own components use. Everything that
// runs only on the server — the routes table, the middleware, sessions, the server itself — is
// `ankka-console/server`, so a host's browser bundle never resolves a Node module.
export { ConsoleProvider, useConsole, ConsoleLink, ConsoleForm, Breadcrumbs, Field, Submit, OperationForm } from "./ui/console.tsx";
export type { UseConsole } from "./ui/console.tsx";
export { Shell, Backdrop, Bar, Rail, Listing, Inspector, Page, SectionTitle, SegmentedLinks, ServiceSections } from "./ui/shell.tsx";
export type { SegmentLink, ServiceSection } from "./ui/shell.tsx";
export { Shape } from "./ui/shape.tsx";
export { Lifecycle } from "./ui/status.tsx";
export { button } from "./ui/primitives/button.ts";
export { Switch } from "./ui/primitives/switch.tsx";
export { Select } from "./ui/primitives/select.tsx";
export type { Area, Crumb, ListingItem, Principal, ShellData } from "./context.ts";
export { operations } from "./extensions/types.ts";
export type { Action, ConsoleExtensions, Operation, Panel, PanelKind, PanelLoadContext } from "./extensions/types.ts";
