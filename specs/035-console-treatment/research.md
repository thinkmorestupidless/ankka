# Research: Console Treatment

Each entry is a decision, why, and what was weighed against it. Versions are what npm served on
2026-10-04.

## R1. The stylesheet engine: Tailwind v4 compiled in the package, no preflight, prefixed `ac`

**Decision**: `tailwindcss@4.3` with `@tailwindcss/cli` run by `scripts/build.ts`, input
`src/styles.css`, output `dist/styles.css`. The stylesheet declares the layer order and imports
Tailwind's theme and utilities only:

```css
@layer theme, base, components, utilities;
@import "tailwindcss/theme.css" layer(theme) prefix(ac);
@import "tailwindcss/utilities.css" layer(utilities) prefix(ac);
@import "./theme.generated.css";
```

with `--*: initial` in the generated theme so no default palette, face or radius survives: the
stylesheet holds ankka's tokens and nothing else. Utilities read `ac:flex`, `ac:bg-surface`; the
theme's variables come out as `--ac-color-ink`, `--ac-radius-card`, which is the naming the hosts
already override.

**Rationale**: a host must not need Tailwind to consume the package (R12 mounts it from a tarball),
so the CSS is compiled where the components are. Preflight is a global reset and would restyle a
host's own pages; the package keeps its base rules scoped under `.ac-root` in `@layer base`, as
today. The prefix keeps the `ac-` promise the docs make and stops a host running its own Tailwind
from colliding on `flex`. Cascade layers answer the `ankka-cloud` trap (`.ac-root a` outranking
`.ac-button`): every package rule is layered, so any unlayered host rule wins, whatever its
specificity.

**Alternatives considered**: `@tailwindcss/vite` in the hosts (every host needs Tailwind and the
package ships source CSS — rejected for the website); keeping hand-written CSS and adding tokens
(no component recipes, the specificity trap stays unless layers are added by hand — the smaller
half of this decision, kept); CSS Modules (scoped, but no tokens-to-utilities story and no shadcn).

## R2. Components: shadcn's recipes on Base UI, copied in and owned; overlays only enhance

**Decision**: run `npx shadcn@4 add` once, with `components.json` pointing at
`src/ui/primitives/` and the Base UI style, for: button, badge, card, input, label, textarea,
table, separator, tooltip. Dependencies the recipes need: `@base-ui/react@1.8`,
`class-variance-authority`, `clsx`, `tailwind-merge`, `lucide-react@1.51`. Three recipes are *not*
taken: Switch (Base UI renders `role="switch"` on a button and toggles in JS), Select, and
Dialog/DropdownMenu/Tabs — each is replaced by its native form, styled: a checkbox with a track
(the mock's `.switch`), `<select>`, `<details>`, links. Tooltip is the one Base UI overlay in this
feature, and only where the element already carries its text as `aria-label` or `title`.

**Rationale**: the spec's house rule. Base UI's primitives are accessible and SSR-safe, but an
overlay that only opens with scripts is a broken operation in the scripts-off project, so the
package takes the recipes (the look) and leaves the JS-only interactions behind. Copying in
rather than depending means the package's components are its own code, versioned with it, and a
host never sees shadcn.

**As built**: the shadcn CLI was not run. Its recipes name unprefixed utilities and shadcn's own
tokens (`bg-primary`, `text-muted-foreground`), so every copied file would have been rewritten line
by line against `ac:` and ankka's properties. The button is a `cva` recipe in shadcn's shape over
the `ac-button` classes the documentation promises; the switch and select are native controls. With
every overlay replaced by its native form, nothing used Base UI or tailwind-merge, and neither is a
dependency.

**Alternatives considered**: React Aria Components (deeper a11y, larger bundle, no need for its
dates/colour/tree); Mantine (Provider-driven theming, overlays JS-only, its own look); Radix
flavour of shadcn (supported, but Base UI is the default since July 2026 and the direction).

## R3. The face: Inter, from the package's own `dist/fonts/`

**Decision**: `@fontsource-variable/inter@5.3` (OFL-1.1) as a dev dependency; the build copies
`files/inter-latin-wght-normal.woff2` and `inter-latin-ext-wght-normal.woff2` into `dist/fonts/`
and the stylesheet declares `@font-face { font-family: "Inter Variable"; src: url(./fonts/…)
format("woff2"); font-display: swap; unicode-range: … }` with `--ac-font-sans: "Inter Variable",
system-ui, …`. `files: ["dist"]` already ships them.

**Rationale**: the rule is nothing from outside the cluster, and the font file served from the
console's own origin satisfies it; the host's Vite build rebases a dependency's `url()` and emits
the file as a hashed asset on the host's origin. Two subsets (~100 KB each) cover the console's
text. The pack test asserts the files are in the tarball; the own-origin e2e case asserts the
browser fetched the face from the host.

**Alternatives considered**: Google Fonts (refused by the rule); Geist (offered, not chosen);
system fonts (chosen against: the direction depends on the face).

## R4. Themes: dark always, light kept for a host that chooses it

**Decision**: the generated theme declares the dark values in `@theme`. The light values are
declared on `:root:has(.ac-light)` inside `@layer theme`, and `<Shell theme="light">` puts
`ac-light` on the console's root; `.ac-root.ac-light` sets `color-scheme: light`. The browser's
preference is not followed. The suite audits light by adding the class.

**Rationale**: the first design followed `prefers-color-scheme` with dark leading "when no
preference is stated". Implementation found there is no such state: browsers report `light` when
nobody has chosen, so that design showed most people the light theme. The user chose dark always
(clarification, 2026-10-04). Declaring the light properties on `:root` (through `:has`) rather than
on the root element keeps every property on one element and in one layer, so a host's own,
unlayered declaration wins in either theme wherever it is made.

**Alternatives considered**: following the OS (most people see light first); a member's switch
stored in a cookie (a route and a cookie of its own: a later feature); `@custom-variant dark`
(needs a server or script decision anyway).

## R5. Contrast as a tested property of the tokens

**Decision**: `src/ui/tokens.ts` is the one source: for each theme, the mesh's base and slate
colours, the glow colour and its peak alpha, each surface's tint and alpha (glass, tile, node,
inset), and the inks. `scripts/build.ts` writes `src/theme.generated.css` from it (gitignored).
`test/contrast.test.ts` composites, per theme: backdrop at the glow's peak = base ⊕ glow(alpha);
each surface over that; each ink over each surface; and asserts WCAG relative-luminance contrast
≥ 4.5 for `ink` and `ink-2` on every surface and ≥ 4.5 for `ink-3` on the surfaces it is used on
(panel, tile), naming the pair that fails. A second case raises the glow alpha by 0.2 and asserts
the test fails naming the glow property — the "a glow brighter than the limit fails the build"
scenario, run in-process.

**Rationale**: text on translucent surfaces has no single background; the only honest check is the
worst case, and the worst case is a function of the tokens alone. Running it as a unit test means
the build fails before a page is rendered, and a host reads the limit from the docs (R11).

**As built**: the mock's dark values failed at 1.78:1 (white text on white-tinted glass over a
bright ember glow). The dark theme was tuned against the test: a darker mesh (`#111f26`/`#1e2e3a`),
the glow at 0.34 alpha, thinner white tints (glass 0.05, tile 0.06, node 0.10) and brighter
secondary inks; the lowest ratio is now 4.62:1 dark and 4.58:1 light. The test reads the backdrop at
both mesh colours alone and under the glow and the peach, and leaves the one-pixel dot grid out.

**Alternatives considered**: axe alone (reports `incomplete` for alpha backgrounds, which is a
pass by silence); opaque cards (the first mock; the user chose glass throughout).

## R6. The composable shell

**Decision**: five parts exported from the browser-safe entry — `Backdrop`, `Bar`, `Rail`,
`Panel`, `Inspector` — and a `Shell` grid that lays out whichever are present (CSS grid areas,
collapsing in document order under 1180px and to one column under 720px). The page's own root is a
`div` with `display: contents`, so its body and its `<Inspector>` land in the grid's columns with no
portal and no script. The rail, bar and panel are rendered by the host's layout from shell page
data (R7); the inspector is rendered by the page. A host mounts any subset; the installation's
console mounts all five; the website mounts `Backdrop` and `Bar` (R12).

**Rationale**: the inspector's content is the page's operations, which today are forms in the
page; keeping them in the page's render tree means scripts-off, refusals beside forms and
`useNavigation`'s busy state all work unchanged. `display: contents` on a non-landmark `div` is
safe for the accessibility tree (the known defect is on buttons and table elements); axe is the
check.

**Alternatives considered**: a portal into the layout (renders nothing on the server); the layout
rendering operations from loader data (the forms, refusals and busy state would move out of the
page, a far larger change).

## R7. The panel's data and the shell's page data

**Decision**: `ConsolePageData` gains `shell: ShellData` — `area`, `crumbs`, `primary?`,
`panel?` — computed in each page's loader by a `shellData(ctx, …)` helper beside `pageData`, and
read by the layout through `useConsole()` as `mount` and `principal` are today. The service,
logs, topology and history pages add `listServices(projectId)` to their loaders for the panel
(project pages already have it); members and tokens pages list the organization's projects;
the organization page lists its projects (already loaded); the front page has no panel (`panel`
absent). Loading is parallel with what the page already loads.

**Rationale**: the layout cannot call the control plane itself (it has no loader of its own under
the package's contract), and the page already holds the organization and project; one extra
listing per service page is one request, read as the person.

**Alternatives considered**: a layout loader in the host (puts control plane calls in the host,
against SC-009's spirit); a stream for the panel (the project stream exists; the panel takes its
first state from the loader and may follow the stream later).

## R8. The shape

**Decision**: `Shape.tsx`, a pure component of `ServiceStatus`: parts for the address (when
`exposed`, with `hostname` or "no address yet"), the service (name, generation, image, its tab
with logs/pause-or-resume/restart as the same forms the inspector renders, in miniature), the
instances (`readyInstances` of `desiredInstances`), and the database (`database` or "not reported
yet"). Rows: address; service; instances and database. Edges: an inline SVG per gap,
`viewBox="0 0 100 48" preserveAspectRatio="none"`, paths with `vector-effect: non-scaling-stroke`,
ports as pseudo-elements on the parts; the split edge is hidden under 720px. Long values clip with
`text-overflow: ellipsis` and are whole in the facts list below. With scripts on,
`useServiceStream` already re-renders the page; the shape takes its props from the same state.

**Rationale**: everything comes from what the page loads; no new route (FR-015). SVG with
non-uniform scaling keeps the curves meeting the parts' centres at any width without script.

**Alternatives considered**: a canvas or JS-measured SVG (needs script to draw); instance names
as children (the status carries counts only; names are on the logs page).

## R9. The topology picture takes the shape's language

**Decision**: `Graph.tsx` keeps its layout and semantics; its nodes become `rx=18` rects filled
with `--ac-color-node` (alpha) and stroked with `--ac-color-node-line`, with a soft drop shadow
via an SVG filter, the selected node ringed in amber, declared connections as the shape's edge
colour and observed calls dashed as today. SVG shapes cannot blur what is behind them, so the
picture's nodes are tinted, not frosted; the figure sits on the dot grid.

**Rationale**: the picture is reached as a section beside the overview and should read as the
same product; its semantics (dashed = observed, weight = volume, `!` = warning) are feature 019's
and are not touched.

## R10. The browser suite

**Decision**: new spec files beside the existing ones, each titled by the feature's scenario name:
- `shell.spec.ts` — for every page: rail mark, breadcrumb, primary operation, panel listing and
  mark, inspector holds every `form[method=post]` and the body holds none, the front page has no
  panel; at `viewport 400×800`: `document.documentElement.scrollWidth <= innerWidth`, every
  landmark present.
- `treatment.spec.ts` — the axe page list run twice under `emulateMedia({ colorScheme: "dark" })`
  and `"light"`; `reducedMotion: "reduce"` asserts the live indicator is visible and has no
  running animation; `forcedColors: "active"` asserts each surface's computed `border-style` is
  not `none`; a request listener on a page asserts every URL's origin equals the target's, and
  that at least one request was a `font` resource (the proof it was fetched, not fallen back).
- `shape.spec.ts` — the figure's parts and edges, scripts off; the fake's status changed and the
  ready count observed to change, scripts on (the existing `streams.spec.ts` pattern).
- `services.spec.ts` — sections as links; the history page.
The scripts-off project runs them all except the ones that inspect from inside the page, as today.

**Rationale**: the suite already runs twice and audits every page; the additions are cases, not a
new harness. `emulateMedia` covers themes, motion and forced colours in Chromium without a
toggle in the product.

## R11. Documentation

**Decision**: `docs/reference/console-package.md`'s "Restyle the pages" section carries a
generated block `<!-- generated:start console-properties -->` listing every property from
`tokens.ts` with its role and, for the glow and mesh properties, the limit (the composite
contrast each must keep); `mkdocs.yml` registers `console-properties: { kind: external }`;
`test/docs-properties.test.ts` fails on a stale block and rewrites it under
`CONSOLE_DOCS_UPDATE=1`, as the Scala reference suites do under `-Dankka.docs.update=true`.
`docs/operate/console.md` gains the shell (what the rail, panel and inspector are), the shape,
and the four sections of a service. Both are already in `nav` and in skills.

**Rationale**: the docs rules — reference facts are generated, prose beside them says what each
is — and the scenario "the documentation of the console says how a host restyles it".

## R12. The hosted product's website

**Decision**: a dependent task in `ankka-cloud`: `just vendor-console` from this branch, the
layout mounts `Backdrop` and `Bar` with the website's own nav as the bar's children, `site.css`
loses the `a.ac-button` workaround (layers make it unnecessary) and keeps its property overrides,
and `just test-web` must pass (SC-005). No package change is permitted to make it pass; if one is
needed the seam is wrong and the task comes back here.

**Rationale**: the spec's story 5 and clarification 4.

## R13. Build, dev loop and CI

**Decision**: `scripts/build.ts` becomes: write `theme.generated.css` from tokens → run the
Tailwind CLI (`--minify`) → `tsc` → copy fonts and `theme.generated.css` (as `dist/theme.css`,
exported `./theme.css` for a host that runs its own Tailwind and wants the tokens). `npm run dev`
in the workspace builds the package first, as today; a `watch` script runs the CLI with
`--watch` beside `tsc --watch` for iteration. `.gitignore` gains `theme.generated.css`. The
Dockerfile is unchanged (it runs the package's build). CI's `console` job already runs the unit
tests with `CONSOLE_PACK=1`, the host's typecheck and the Playwright suite; no filter changes
since every path is under `console/` or `docs/`.

**Rationale**: one command still builds the package; the generated file never reaches git.

## R14. Sections of a service

**Decision**: a service has four sections, each a route: overview (`…/services/:name`), topology
(existing), logs (existing), history (new, `…/services/:name/history`, the table that was on the
overview). A `SegmentedLinks` part renders them as links with `aria-current="page"`. The overview
keeps the shape, the facts and the inspector; the history page keeps the generation column and the
"who" attribution unchanged.

**Rationale**: the scripts-off scenario names the four sections as pages of their own; the
history table was the one thing on the overview that is a listing rather than state. Adding a
page route adds no control plane route (parity is unaffected) and changes nothing the console
says — the table moves.

**Alternatives considered**: history stays on the overview (three sections; the scenario would
change); history as a `<details>` (it is reached, not operated).
