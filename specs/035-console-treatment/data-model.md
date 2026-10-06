# Data Model: Console Treatment

Nothing here is stored. These are the shapes the package passes between its build, its pages,
its layout and its tests. Wire types are unchanged (`client/schemas.ts`).

## Tokens (`src/ui/tokens.ts`)

The one source of the look. Written to `theme.generated.css` by the build, read by the contrast
test and the docs block.

```ts
interface Theme {
  mesh: { base: Hex; slate: Hex; glow: Hex; glowAlpha: Alpha; peach: Hex; peachAlpha: Alpha; dot: Rgba };
  surface: { glass: Rgba; glassStrong: Rgba; glassLine: Rgba; tile: Rgba; tileLine: Rgba; node: Rgba; nodeLine: Rgba; inset: Rgba; edge: Rgba };
  ink: { ink: Hex; ink2: Hex; ink3: Hex };
  accent: { amber: Hex; amberInk: Hex; green: Hex; red: Hex; redWash: Rgba };
  shadow: string;
}
interface Tokens {
  dark: Theme;                       // leads: emitted in @theme
  light: Theme;                      // emitted on :root:has(.ac-light), chosen by a host with <Shell theme="light">
  radius: { panel: Px; card: Px; control: Px; node: Px };
  font: { sans: string; mono: string };
  layout: { rail: Px; panel: Px; inspector: Px; bar: Px; narrow: Px; phone: Px };
}
```

Rules:
- Every value becomes `--ac-<group>-<name>` (`--ac-color-ink`, `--ac-color-glow`,
  `--ac-radius-card`, `--ac-font-sans`). Names are stable: a host overrides them.
- `glowAlpha` and `peachAlpha` are the **limit**: the contrast test composites at exactly these;
  the docs state them as the brightest a host may set.
- `ink3` is used only on `glass`, `tile` and `inset` surfaces; the test checks it there.

## Contrast (test/contrast.test.ts)

```
backdropPeak(theme)  = over(theme.mesh.base, theme.mesh.glow @ glowAlpha)      // the brightest point
surfaceAt(s, theme)  = over(backdropPeak(theme), s)                            // s: an Rgba surface
ratio(ink, surface)  = WCAG 2.x contrast of relative luminances
assert ratio(ink | ink2, surfaceAt(any surface)) >= 4.5
assert ratio(ink3, surfaceAt(glass | tile | inset)) >= 4.5
```
A failing pair is reported as `dark: ink-3 on tile at the glow's peak is 4.21:1 (--ac-color-glow)`.

## Shell page data (`ConsolePageData.shell`)

```ts
type Area = "organizations" | "projects" | "services" | "members" | "tokens";
interface ShellData {
  area: Area;                                       // the rail's mark
  crumbs: Crumb[];                                  // { label, to? }, as Breadcrumbs today
  primary?: { label: string; to: string; operation?: Operation };   // the bar's action; absent on some pages
  organization?: { id: string; name: string };     // what the rail's members/tokens open; absent on the front page
  panel?: {
    kind: "services" | "projects";
    title: string;                                  // "checkout" / "acme"
    items: PanelItem[];                             // in name order
    current?: string;                               // the id/name of the one being read
  };
}
type PanelItem =
  | { kind: "service"; name: string; to: string; lifecycle: string; confirmed: boolean; ready: number; desired: number }
  | { kind: "project"; id: string; name: string; to: string; services: number };
```

Which page supplies what:

| Page | area | panel | organization |
|---|---|---|---|
| front | organizations | — | — |
| organization-new | organizations | — | — |
| organization | organizations | projects of it | it |
| members, tokens | members, tokens | projects of the organization | it |
| project-new | projects | projects of the organization | it |
| project | projects | services of it | its organization |
| service, logs, topology, history, service-apply | services | services of the project | the project's |

The rail's `members` and `tokens` links are rendered as links when `organization` is present and
as `aria-disabled` spans with a title when it is absent (the front page and `organization-new`).

## The shape (`Shape.tsx` props)

Derived from `ServiceStatus` on the page; no new wire type.

```ts
interface ShapeProps {
  service: ServiceStatus;             // name, generation, image, readyInstances, desiredInstances, exposed, hostname, database, paused, lifecycle
  path: string;                       // the service's own path, for the tab's forms and links
  shows(operation: Operation): boolean;
}
```
Parts and their rows:
1. **address** — present iff `exposed`; label `hostname ?? "Exposed; no address yet"`.
2. **service** — always; tab: logs (link), pause or resume (form), restart (form); inset: HTTP
   and the lifecycle mark.
3. **instances** — always; `readyInstances of desiredInstances`; a service not yet deployed shows
   `0 of n`.
4. **database** — always; `database ?? "Not reported yet"`.
Edges: address→service (straight, omitted when no address); service→{instances, database}
(split). The row of two collapses to one column under the phone width and its split edge is
hidden.

## Sections of a service

```ts
type Section = "overview" | "topology" | "logs" | "history";
sections(path) = [ {overview, path}, {topology, `${path}/topology`}, {logs, `${path}/logs`}, {history, `${path}/history`} ]
```
Rendered by `SegmentedLinks` with `aria-current="page"` on the current one. The history page's
loader is the service page's `history` call alone plus `shellData`.

## Shell parts (what a host composes)

| Part | Renders | Needs |
|---|---|---|
| `Backdrop` | the fixed mesh + dot grid behind everything; sets `.ac-root` | nothing |
| `Bar` | wordmark/children (a host's nav), centred crumbs, primary operation, person, sign out | `useConsole()` for crumbs/primary/principal; children |
| `Rail` | the five areas + sign out, current marked | `useConsole()` |
| `Panel` | the listing, scrollable, current marked | `useConsole()` |
| `Inspector` | an `aside` the page fills; placed by the grid | children |
| `Shell` | the grid; accepts any subset of the above as children plus `<Outlet/>` | children |
