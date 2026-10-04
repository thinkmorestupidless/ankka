# Implementation Plan: Console Treatment — A Glass Shell for the Console and Its Hosts

**Branch**: `035-console-treatment` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/035-console-treatment/spec.md`

## Summary

The `ankka-console` package's hand-written stylesheet becomes a Tailwind v4 stylesheet compiled at
the package's build (no Tailwind in any host), whose tokens are custom properties generated from
one TypeScript module so a host still restyles by setting `--ac-*` properties and a unit test can
composite ink over every surface at the backdrop's brightest point and fail the build below 4.5:1.
Every page is rendered inside a composable shell — backdrop, bar, rail, panel, inspector — whose
parts a host mounts where it has content for them; the pages render their operations into the
inspector and their state into the body, and a service's overview gains a shape (address →
service → instances and database) drawn in markup and inline SVG. Dark leads, light follows the
browser, Inter ships inside the package, and the two rules feature 017 set — every operation
with scripts off, zero axe violations — are kept by construction and re-proven by the existing
Playwright suite plus cases for both themes, a 400-pixel width, reduced motion, forced colours and
own-origin fetches. The hosted product's website mounts the backdrop and the bar from the same
package with no change to it.

## Technical Context

**Language/Version**: TypeScript 7 on Node 24 (ESM), React 19, React Router 8.4 framework mode,
Vite 8 in the hosts only.

**Primary Dependencies**: `tailwindcss` 4.3 + `@tailwindcss/cli` (package build only, dev
dependency); `@fontsource-variable/inter` (dev; its woff2 copied into `dist/fonts/`);
`@base-ui/react` 1.8 (overlays that enhance, Tooltip first); `lucide-react`;
`class-variance-authority`, `clsx`, `tailwind-merge` (shadcn's recipe helpers). shadcn's CLI is
run once to copy components in; it is not a dependency. Nothing new in a host.

**Storage**: none.

**Testing**: `node --test` (tokens and contrast, the fixture host, the pack, the host's size, the
docs block); Playwright with `@axe-core/playwright` (scripts-on and scripts-off projects, both
themes by `emulateMedia`, viewport 400, `reducedMotion`, `forcedColors`, request-origin recording);
`ankka-cloud`'s own suites for SC-005.

**Target Platform**: evergreen browsers (Chromium in the suite); the console image unchanged in
shape (`node:24-bookworm-slim`, the host's build); the website's image likewise.

**Project Type**: web application — the `console/` npm workspace (package, host, e2e), the docs,
and a dependent change in the sibling `ankka-cloud` repository.

**Performance Goals**: no regression of feature 017's SC-004 (server render < 500 ms median,
client navigation < 300 ms median); blur on the shell's surfaces and the page's cards only, never
on a per-row element (a panel item, a table row), which T013 enforces.

**Constraints**: every operation with scripts off (FR-003); zero axe violations in both themes
(FR-004); ink over every surface ≥ 4.5:1 at the glow's peak in both themes (FR-005); every fetch to
the host's origin (FR-007); the host under 500 lines (FR-013); no control plane route and no change
to what a page says or does (FR-015); the package needs no Tailwind in a host (R1).

**Scale/Scope**: (as built: the host is 53 lines of its own source) 12 page routes restyled plus one new (`history`), 5 shell parts, ~45 tokens, 7 new
feature files with 36 scenarios, ~12 new Playwright cases, 4 new unit tests, 2 docs pages, 1 change
in `ankka-cloud`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gates are the rules `CLAUDE.md`
states for this repository, evaluated before research and again after design:

| Rule | Status |
|---|---|
| Every operation works with scripts off; the Playwright suite runs both projects with route parity | Pass by construction: a menu is `<details>`, sections are links to routes, `<select>` and a styled checkbox stay native, the shape is markup. Base UI is used only for overlays that enhance (Tooltip). Nothing in the suite is weakened (SC-002). |
| Zero axe violations, keyboard-complete | Pass: audited in both themes (R10); contrast is a tested property of the tokens, not of each page (R5). |
| A host restyles by overriding properties, not rules; `ankka-console` is browser-safe, `/server` is not | Pass: Tailwind `@theme` emits `var()` references, the prefix makes the variables `--ac-*` as today, and every package rule sits in a cascade layer so a host's unlayered rule wins (R1, R4). The shell parts are exported from the browser-safe entry. |
| Nothing fetched from outside the cluster | Pass: Inter is copied into `dist/fonts/` and referenced relatively; the suite records every request (R3, R10). |
| The host under 500 lines; a second host proves the seam | Pass: the host's layout grows by the shell's parts and nothing else; the fixture host mounts backdrop and bar alone (FR-012, FR-013). |
| Documentation: a page stands alone, reference facts are generated, a new page is in nav and a skill | Pass: the properties table is a generated block owned by a node test, rewritten under `CONSOLE_DOCS_UPDATE=1` as the Scala suites do under `-Dankka.docs.update` (R11). |
| The listings are projections; a test reloads until it shows | Unchanged: the shell's panel is read by the page's loader like any listing; the e2e `afterProjection` helper applies. |
| A test must be able to fail | Pass: the contrast test is shown failing by raising the glow (a scenario); the own-origin test is shown failing by a Google Fonts link in the fixture host (quickstart). |
| No `ThisBuild / version`; the package's version is `0.0.0` in the tree | Unchanged. |
| Tailwind + `react-router build` | Not applicable: no host runs Tailwind; the package compiles its CSS with the CLI (R1). |

**Post-design re-check**: no violation introduced. One deliberate addition is recorded in
Complexity Tracking: a generated CSS file in the package's source tree.

## Project Structure

### Documentation (this feature)

```text
specs/035-console-treatment/
├── plan.md              # This file
├── research.md          # Phase 0: R1–R14
├── data-model.md        # Phase 1: tokens, shell page data, the shape, the sections
├── quickstart.md        # Phase 1: how to run and prove it
├── contracts/
│   ├── package-api.md   # what a host imports and mounts; what a page's loader supplies
│   └── properties.md    # the --ac-* properties, their contract, the generated docs block
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
console/
├── package/
│   ├── package.json                 # + deps; exports gains "./theme.css"; files: dist (fonts included)
│   ├── scripts/build.ts             # tokens → theme.generated.css → tailwind CLI → dist/styles.css; tsc; copy fonts
│   ├── src/
│   │   ├── styles.css               # @layer order; @import tailwind theme + utilities prefix(ac); @import theme.generated.css; base, components
│   │   ├── theme.generated.css      # written by the build from tokens.ts (gitignored)
│   │   ├── ui/
│   │   │   ├── tokens.ts            # the one source of colours, mesh, radii, faces (R5)
│   │   │   ├── shell/               # Backdrop, Bar, Rail, Panel, Inspector, Shell, SegmentedLinks (R6)
│   │   │   ├── shape/               # Shape.tsx: parts, ports, edges (R8)
│   │   │   ├── primitives/          # shadcn recipes on Base UI, owned here: button, badge, card, input, label, textarea, switch (native), table, tooltip
│   │   │   ├── console.tsx          # + shell page data helpers (shellData), Operation controls
│   │   │   ├── status.tsx           # Lifecycle: marks unchanged, classes on the new tokens
│   │   │   └── topology/Graph.tsx   # the shape's node and edge language (R9)
│   │   ├── routes/
│   │   │   ├── service.tsx          # overview: shape, facts; sections as links; inspector
│   │   │   ├── service-history.tsx  # new: the history table as a section of its own
│   │   │   ├── service-topology.tsx # reached as a section; picture restyled
│   │   │   └── *.tsx                # every page: shell data in its loader, body + inspector
│   │   ├── routes.ts                # + history route
│   │   └── index.ts                 # + shell parts, Shape, useShell
│   ├── fonts/                       # (none in the tree; copied from @fontsource-variable/inter at build)
│   └── test/
│       ├── contrast.test.ts         # FR-005: composite ink over surfaces at the glow's peak, both themes
│       ├── docs-properties.test.ts  # FR-011: the docs block matches tokens.ts; rewrites under CONSOLE_DOCS_UPDATE=1
│       ├── fixture-host.test.ts     # + backdrop and bar alone; a property override reaches the page
│       ├── pack.test.ts             # + dist/fonts and dist/theme.css in the tarball
│       └── fixture-host/app/        # layout mounts Backdrop + Bar only; fixture.css sets --ac-color-ink
├── host/app/layout.tsx              # mounts Shell with every part; stays under the budget
├── host/app/host.css                # unchanged in role
└── e2e/tests/
    ├── shell.spec.ts                # landmarks, rail mark, panel, inspector, 400px, front page
    ├── treatment.spec.ts            # both themes audited, own-origin, reduced motion, forced colours
    ├── shape.spec.ts                # the shape's parts, unexposed, unreported db, live update
    └── services.spec.ts             # + sections as links, history page

docs/reference/console-package.md    # "Restyle the pages" rewritten around the generated properties block
docs/operate/console.md              # the shell, the shape, the sections
mkdocs.yml                           # generated: console-properties (kind: external)
.github/workflows/ci.yml             # console job unchanged; CONSOLE_PACK already set

ankka-cloud/ (sibling repository, dependent task)
├── web/host/app/layout.tsx          # mounts Backdrop + Bar; its own nav inside the bar
├── web/host/app/site.css            # overrides properties; the a.ac-button workaround removed
└── web/vendor/ankka-console-<v>.tgz # repacked from this branch for the suite (just vendor-console)
```

**Structure Decision**: Everything stays in the `console/` workspace and the package keeps every
file a second host would otherwise copy, so the host's layout is still a configuration. The
stylesheet is compiled in the package because a host must not need Tailwind to consume it, and
the tokens live in TypeScript because a test and the docs both have to read them.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| `src/theme.generated.css`, a generated file in the source tree (gitignored) | Tailwind's `@import` resolves files beside the stylesheet at build; the tokens have to exist as CSS for the CLI to read, and as TypeScript for the contrast test and the docs block. | Hand-writing both drifts, and a test comparing them is a second copy of the generator. Generating into `dist/` and importing from there puts a build output on the input path. |

## Phase 0 and Phase 1 outputs

- `research.md`: R1 stylesheet engine, R2 components, R3 the face, R4 themes, R5 contrast as a
  test, R6 the composable shell, R7 the panel's data, R8 the shape, R9 the topology picture, R10
  the browser suite, R11 documentation, R12 the website, R13 build and CI, R14 the sections.
- `data-model.md`, `contracts/package-api.md`, `contracts/properties.md`, `quickstart.md`.

## Risks the tasks must retire first

1. **A host's property override reaches a Tailwind utility (R1, R4).** Spike in the fixture host:
   `.product { --ac-color-ink: … }` must change text set by `ac:text-ink`. If the prefix or layer
   order defeats it, the fallback is an unlayered `:where(.ac-root)` block the generator emits.
2. **Vite rebases a dependency's `url()` (R3).** The host's build must emit `dist/fonts/*.woff2`
   as an asset on the host's origin; the own-origin e2e case is the proof. Fallback: the package
   exports the font files and the host copies them (one line in the host, still under budget).
3. **The numbers (R5).** The mock's glow may not pass 4.5:1 for `ink-3` over the lightest surface;
   the contrast test runs before any page is restyled, and the tokens are tuned to it.
4. **`display: contents` and the accessibility tree (R6).** A page's wrapper must be a plain `div`,
   never a landmark; axe in both suites is the check.
5. **The history route (R14).** A new page route must keep parity green and the keyboard case's
   tab order sane; it is the last page restyled.
