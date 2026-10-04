# Tasks: Console Treatment — A Glass Shell for the Console and Its Hosts

**Input**: Design documents from `specs/035-console-treatment/`

**Prerequisites**: plan.md, spec.md, research.md (R1–R14), data-model.md, contracts/package-api.md,
contracts/properties.md, quickstart.md

**Tests**: included. This repository's rule is that every acceptance scenario ends up as a test
that fails without the feature (`CLAUDE.md`, *Testing*), and the spec's stories each name their
independent test. Test tasks are written first in each phase and must fail before the
implementation task that makes them pass.

**Organization**: by user story, in the spec's priority order. Paths are relative to the
repository root; `console/` is the npm workspace (`package/`, `host/`, `e2e/`).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US5 as in spec.md

---

## Phase 1: Setup (the stylesheet engine, the tokens, the face, the components)

**Purpose**: the build that turns one TypeScript token module into the package's stylesheet, with
the face and the component recipes in place, before any page is touched (R1, R2, R3, R5, R13).

- [X] T001 Add the dependencies to `console/package/package.json`: `@base-ui/react@^1.8`, `lucide-react@^1.51`, `class-variance-authority@^0.7`, `clsx@^2`, `tailwind-merge@^3` as `dependencies`; `tailwindcss@^4.3`, `@tailwindcss/cli@^4.3`, `@fontsource-variable/inter@^5.3` as `devDependencies`; add the `"./theme.css": "./dist/theme.css"` export; run `npm install` in `console/` so the lock file follows
- [X] T002 [P] Write `console/package/src/ui/tokens.ts` per data-model.md: `dark` and `light` themes (mesh, surface, ink, accent, shadow), `radius`, `font`, `layout`, with the Lakeglass mock's values as the starting point, and a `toCss(tokens): string` that emits `@theme { --*: initial; --ac-color-…; --ac-radius-…; --ac-font-…; --ac-size-…; --ac-width-… }` for `dark` and, **inside `@layer theme`**, a `@media (prefers-color-scheme: light) { .ac-root { … } }` block for `light` (R4: layered, so a host's unlayered override wins in light mode too)
- [X] T003 [P] Add `theme.generated.css` to `console/.gitignore`
- [X] T004 Rewrite `console/package/scripts/build.ts`: write `src/theme.generated.css` from `tokens.ts`; run `@tailwindcss/cli -i src/styles.css -o dist/styles.css --minify`; run `tsc -p tsconfig.build.json`; copy `src/theme.generated.css` to `dist/theme.css`; copy `inter-latin-wght-normal.woff2` and `inter-latin-ext-wght-normal.woff2` from `node_modules/@fontsource-variable/inter/files/` to `dist/fonts/`; add a `watch` script to `console/package/package.json` running the CLI with `--watch` beside `tsc --watch`
- [X] T005 Replace `console/package/src/styles.css` with the layered skeleton of R1: `@layer theme, base, components, utilities;`, the two Tailwind imports with `layer(…) prefix(ac)`, `@import "./theme.generated.css"`, the `@font-face` for Inter Variable (two subsets, `font-display: swap`, relative `url(./fonts/…)`), and `@layer base { .ac-root { … } }` holding today's base rules (box-sizing, font, `color-scheme: dark`, focus ring); keep every existing `ac-` component rule for now inside `@layer components` so the build is green before any page changes
- [X] T006 Write `console/package/components.json` for shadcn (style `base-ui`, `tailwind.css: src/styles.css`, `aliases.ui: src/ui/primitives`, `tsx: true`, `rsc: false`) and run `npx shadcn@4 add button badge card input label textarea table separator tooltip` into `console/package/src/ui/primitives/`; add `console/package/src/ui/primitives/cn.ts` (`clsx` + `tailwind-merge`); rewrite each copied file's `@/` imports to relative `.ts`/`.tsx` paths and remove any `"use client"` — *As built: shadcn CLI not run; recipes written in its shape over `ac-` classes (research R2).*
- [X] T007 Add `console/package/src/ui/primitives/switch.tsx`: a native `<input type="checkbox">` with a styled track (the mock's `.switch`), labelled, submitting in a form with scripts off; and `select.tsx`: a styled native `<select>` — the two recipes R2 declines from shadcn
- [X] T008 Run `npm run build -w package` and `npm run typecheck` in `console/`; the build must emit `dist/styles.css`, `dist/theme.css` and `dist/fonts/*.woff2`, and the existing unit tests must still pass (`npm test -w package`)

**Checkpoint**: the package builds its stylesheet from tokens; nothing a host sees has changed yet.

---

## Phase 2: Foundational (the contrast test, the shell's parts, the shell's page data)

**Purpose**: what every story needs: the test that pins the tokens, the five shell parts, and the
page data that drives them (R5, R6, R7). Blocks every user story.

- [X] T009 [P] Write `console/package/test/contrast.test.ts` per data-model.md: WCAG relative luminance, alpha compositing, `backdropPeak`, `surfaceAt`; assert `ink` and `ink2` ≥ 4.5 on every surface and `ink3` ≥ 4.5 on glass, tile and inset, in both themes, failing with the pair and the property named; a second case takes `tokens` with `dark.mesh.glowAlpha + 0.2` and asserts the check throws naming `--ac-color-glow`; print the lowest ratio per theme
- [X] T010 Tune `console/package/src/ui/tokens.ts` until `contrast.test.ts` passes, changing the mesh, glow and surface alphas before any ink; record the final values in `specs/035-console-treatment/contracts/properties.md`
- [X] T011 [P] Extend `ConsolePageData` in `console/package/src/context.ts` with `shell: ShellData` (data-model.md: `area`, `crumbs`, `primary?`, `organization?`, `panel?`, `PanelItem`) and add `shellData(ctx, input)` beside `pageData` that builds it, sorting panel items by name; export the types from `console/package/src/index.ts`
- [X] T012 [P] Write the shell parts in `console/package/src/ui/shell/`: `Backdrop.tsx` (the fixed mesh + dot grid, sets `.ac-root`), `Bar.tsx` (wordmark, children, centred crumbs from `useConsole().shell`, primary operation, person, sign-out form), `Rail.tsx` (the five areas as links with `aria-current="page"` on the current, `members`/`tokens` as `aria-disabled` spans with a title when `shell.organization` is absent, sign out at the foot), `Panel.tsx` (the listing with `Lifecycle` marks and counts, `aria-current` on the current item, scrollable; the current item stays in view by CSS alone, T013), `Inspector.tsx` (an `aside` with `aria-label="Operations"`), `Shell.tsx` (the grid container), `SegmentedLinks.tsx` (links with `aria-current`), `index.ts`; export all from `console/package/src/index.ts`
- [X] T013 Write the shell's styles in `console/package/src/styles.css` under `@layer components` as `ac-shell`, `ac-backdrop`, `ac-rail`, `ac-bar`, `ac-panel`, `ac-inspector`, `ac-page` (`display: contents` on a `div`), `ac-seg`: the grid areas for any subset of parts present, the collapse under `--ac-width-narrow` and the single column under `--ac-width-phone`, glass surfaces with `backdrop-filter` (never on a per-row element), `@supports not (backdrop-filter: blur(1px))` raising each surface's alpha (the no-blur fallback), and inside the scrolling panel `[aria-current="page"] { position: sticky; top: 0; bottom: 0 }` so the item being read stays at an edge of the panel whatever its scroll position, with no script
- [X] T014 Update `console/package/src/ui/console.tsx`: `useConsole()` exposes `shell`; add `Operation` (a form + `Submit` for one intent, used by pages and the shape); keep `Breadcrumbs` exported and export `Lifecycle` from `console/package/src/index.ts` (contracts/package-api.md); move `.ac-button`, `.ac-field`, `.ac-table`, `.ac-facts`, `.ac-status`, `.ac-live`, `.ac-refusal`, `.ac-notice`, `.ac-secret`, `.ac-log`, `.ac-more`, `.ac-danger-zone` to the new tokens (glass tints, radii, the amber accent) in `styles.css`
- [X] T015 Update `console/host/app/layout.tsx` to mount `<Shell><Backdrop/><Rail/><Bar/><Panel/><Outlet/></Shell>` inside `ConsoleProvider`, and `console/host/app/host.css` to its new role (html/body margin only); confirm `console/package/test/host-size.test.ts` still passes
- [X] T016 Run `npm run build && npm run typecheck && npm test -w package` in `console/`; the pages still render in the new shell with empty rail marks and no panel until each supplies `shell`

**Checkpoint**: the shell exists and the tokens are pinned; each page can now be moved into it.

---

## Phase 3: User Story 1 — A member finds their way through the shell (Priority: P1) 🎯 MVP

**Goal**: every page is shown inside the shell with the rail marking its area, the bar saying where
it is and offering its primary operation, the panel listing what sits beside it, and every
operation in its inspector; the front page has no panel; a phone sees everything in order.

**Independent Test**: `console/e2e/tests/shell.spec.ts` on every page, scripts on and off, plus a
400×800 viewport case; `features/console/shell.feature`.

### Tests for User Story 1

- [X] T017 [P] [US1] Write `console/e2e/tests/shell.spec.ts` with one test per scenario of `features/console/shell.feature`, titled by the scenario: for each page in the a11y list assert `nav[aria-label=Console] [aria-current=page]` names the expected area, the bar's breadcrumb text, the bar's primary link, that every `form[method=post]` is inside `aside[aria-label=Operations]` and none outside, that the delete form sits in a `details` at the inspector's foot; the panel's items and `aria-current` on the service page (seed `inventory` with 2 instances of which 1 ready via `seedTenancy`/the fake's `services`); the rail's `members` link resolving to the page's organization; the front page's rail with `members`/`tokens` `aria-disabled` and no `aside[aria-label*=services]`; 40 seeded services with the last one's panel item within the panel's scroll box; an organization page's panel listing projects with counts; and at `viewport 400×800` `scrollWidth <= clientWidth` on `documentElement` and every landmark present — *Shown failing: removing the sticky rule turned the 40-service case red.*

### Implementation for User Story 1

- [X] T018 [P] [US1] Move `console/package/src/routes/front.tsx` into the shell: loader adds `shell: shellData(ctx, { area: "organizations", crumbs: [{ label: "Organizations" }], primary: { label: "Create an organization", to: "organizations/new", operation: "organization.create" } })` (no panel, no organization); the body is the listing as tiles/table on the new tokens; no `Breadcrumbs` in the body
- [X] T019 [P] [US1] Move `console/package/src/routes/organization.tsx` and `organization-new.tsx` into the shell: `area: "organizations"`, panel of the organization's projects (`kind: "projects"`, counts from the listing), `organization` set, primary "Create a project"; the body shows facts and quota; rename, disable/enable, quota set/clear and delete move into `<Inspector>` with delete behind `<details>` at its foot
- [X] T020 [P] [US1] Move `console/package/src/routes/members.tsx` and `tokens.tsx` into the shell: `area: "members"` / `"tokens"`, panel of the organization's projects, `organization` set, primary "Invite a member" / "Create a token"; invite/role/remove/withdraw/repair and create/revoke forms into `<Inspector>`; the listing stays in the body
- [X] T021 [P] [US1] Move `console/package/src/routes/project.tsx` and `project-new.tsx` into the shell: `area: "projects"`, panel of the project's services (lifecycle, ready/desired, `current` unset), `organization` the project's, primary "Apply a descriptor"; registry set/clear, rename and delete into `<Inspector>`; the services table stays in the body
- [X] T022 [US1] Move `console/package/src/routes/service.tsx` into the shell: loader adds `listServices(projectId)` for the panel (`current: name`), `area: "services"`, primary "Apply a new descriptor"; `SegmentedLinks` for overview/topology/logs/history under the heading; facts in a card in the body; pause/resume, restart, expose/unexpose, logs link and delete (behind `<details>`) into `<Inspector>`; the history table removed from this page (T024 gives it a page)
- [X] T023 [P] [US1] Move `console/package/src/routes/logs.tsx`, `service-topology.tsx` and `service-apply.tsx` into the shell with the same `shell` as the service page (`area: "services"`, the panel, `current: name`) and `SegmentedLinks` on logs and topology; the apply form's submit into `<Inspector>`
- [X] T024 [US1] Add `console/package/src/routes/service-history.tsx` (loader: `getService`, `history`, `getProject`, `getOrganization`, `shellData` as the service page; body: the history table from the old service page unchanged in columns and words), register `page("projects/:projectId/services/:name/history", "service-history")` in `console/package/src/routes.ts`, and add the page to the a11y list in `console/e2e/tests/accessibility.spec.ts` and the keyboard case if it tabs through the service page
- [X] T025 [P] [US1] Move `console/package/src/routes/auth.sign-out.tsx` and the failure pages of `console/package/src/ui/errors.tsx` (`ConsoleErrorBoundary`) and `console/package/src/ui/refused.tsx` onto the new tokens: the sign-out page supplies `shell` only when a principal exists (area `organizations`, no panel), the bar and rail rendering empty otherwise as the contract allows; the failure pages render inside the shell as cards with the same words as today
- [X] T026 [US1] Update the existing e2e cases that locate operations or breadcrumbs in the page body (`console/e2e/tests/services.spec.ts`, `projects.spec.ts`, `organizations.spec.ts`, `members.spec.ts`, `tokens.spec.ts`, `admin.spec.ts`) to the shell's placement (`aside[aria-label=Operations]`, the bar's crumbs); no assertion is weakened, only its locator moved
- [X] T027 [US1] Run `npm run e2e` in `console/` (both projects) until `shell.spec.ts` and every existing case pass with route parity

**Checkpoint**: the MVP — every page in the shell, every operation in its inspector, the suite green.

---

## Phase 4: User Story 2 — Nothing stops working: scripts off, keyboard, no violation (Priority: P1)

**Goal**: the gates hold through the restyle: every operation with scripts off, keyboard-complete,
zero axe violations in both themes, contrast at the glow's peak, reduced motion and forced colours.

**Independent Test**: the existing suite unchanged in what it asserts; `treatment.spec.ts` for
themes, motion and forced colours; `contrast.test.ts` (T009) for the tokens;
`features/console/scripts-off.feature` and `accessibility.feature`.

### Tests for User Story 2

- [X] T028 [P] [US2] Add to `console/e2e/tests/services.spec.ts` the scenarios of `features/console/scripts-off.feature` by title: pause with scripts off and the page saying so; the four sections reached as pages (`a[aria-current=page]` moves, URL changes, no script); further operations of a project opening as `<details>` with rename and delete inside; invite with the role `<select>` (scripts off); pause with scripts on completing without a full navigation (as `streams.spec.ts` observes in-place updates)
- [X] T029 [P] [US2] Write `console/e2e/tests/treatment.spec.ts`: the a11y page list audited under `page.emulateMedia({ colorScheme: "dark" })` and again `"light"` (asserting the computed `background-color` of `.ac-root` differs between the two); `reducedMotion: "reduce"` on the service page asserting `.ac-live` is visible and its `::before` has `animation-name: none`; `forcedColors: "active"` asserting every `.ac-rail, .ac-bar, .ac-panel, .ac-inspector, .ac-card` has `border-style` other than `none` and every `button, a.ac-button` an `outline` or border; the lifecycle marks of a Ready and a Failed service have different text content
- [X] T030 [P] [US2] Extend `console/e2e/tests/accessibility.spec.ts`'s keyboard case to tab through the new placements (inspector first or last, the rail's links, the segmented links) and assert each focused element's `outline-style` is not `none`

### Implementation for User Story 2

- [X] T031 [US2] Make the scripts-off cases pass: in every page the operation forms are plain `method="post"` forms (`ConsoleForm`); `SegmentedLinks` are `<a>`; the project's "Rename or delete" is `<details class="ac-more">`; the invite form's role is `primitives/select.tsx`; verify with `npm run e2e -- --project scripts-off`
- [X] T032 [US2] Make `treatment.spec.ts` pass: `@media (prefers-reduced-motion: reduce)` leaves `.ac-live::before` static but visible; `@media (forced-colors: active)` gives every surface `border: 1px solid CanvasText` and every control `outline: 1px solid` in `console/package/src/styles.css`; `Lifecycle` marks unchanged in `console/package/src/ui/status.tsx`
- [X] T033 [US2] Make the keyboard case pass: focus order in the shell's grid follows document order (rail, bar, panel, body, inspector); `:focus-visible` ring on every interactive element including the rail's icon links and the switch's track (`input:focus-visible + .track`) in `console/package/src/styles.css`
- [X] T034 [US2] Run the full `npm run e2e` and `npm test -w package` in `console/`; both projects green, axe clean in both themes, parity reported

**Checkpoint**: feature 017's gates and the new ones all hold on the restyled console.

---

## Phase 5: User Story 3 — A member sees a service's shape (Priority: P2)

**Goal**: the service's overview shows its hostname, the service with its controls, its instances
and its database as joined parts; present with scripts off, changing with scripts on; the topology
picture takes the same language.

**Independent Test**: `console/e2e/tests/shape.spec.ts`; `features/console/shape.feature`; the
topology case in `topology.spec.ts` still passes.

### Tests for User Story 3

- [X] T035 [P] [US3] Write `console/e2e/tests/shape.spec.ts` with one test per scenario of `features/console/shape.feature` by title: for an exposed service with 3/3 instances and a reported database (seed via the fake's `services` with `image`, `instances`, set exposed and the hostname as `services.spec.ts` does) assert `figure[aria-label*=shopping-cart], figure[aria-label*=cart]` contains four `[data-part]` parts (`address`, `service`, `instances`, `database`) in that order and three `svg.ac-edge` paths; the service part's tab offers logs, pause and restart as the same intents the inspector's forms carry; an unexposed service has no `[data-part=address]`; a service whose database is unset shows "Not reported yet"; a NotDeployed service shows "0 of 2"; scripts off the figure is present on first load; scripts on, after the fake pauses then resumes the service (as `streams.spec.ts` does) the instances part's text changes without navigation within 5 s (`expect(...).toHaveText(..., { timeout: 5000 })`, SC-007); the outline rows: a long image and a long hostname are clipped in the part (`scrollWidth > clientWidth` with `text-overflow`) and whole in `.ac-facts`

### Implementation for User Story 3

- [X] T036 [P] [US3] Write `console/package/src/ui/shape/Shape.tsx` per data-model.md and R8: parts with `data-part`, the service part with its tab (`Operation` forms for pause/resume and restart and a link for logs — the three FR-009 names, nothing more), ports as pseudo-elements, the straight and split edges as inline SVG (`viewBox="0 0 100 48"`, `preserveAspectRatio="none"`, `vector-effect: non-scaling-stroke`), `figure` with `aria-label` naming the service; export from `console/package/src/index.ts`
- [X] T037 [P] [US3] Add the shape's styles to `console/package/src/styles.css` under `@layer components`: `ac-shape`, `ac-tree`, `ac-row`, `ac-part` (node tint, blur, `--ac-radius-node`, the selected ring on the service part), `ac-tab`, `ac-port`, `ac-inset`, `ac-edge`; the split edge hidden and the two-column row stacked under `--ac-width-phone`; `text-overflow: ellipsis` on titles and subtitles
- [X] T038 [US3] Render `<Shape>` at the top of the overview in `console/package/src/routes/service.tsx`, fed from the same `useServiceStream` state as the facts so it updates live
- [X] T039 [US3] Restyle `console/package/src/ui/topology/Graph.tsx` per R9: `rx=18`, fills and strokes from `--ac-color-node`/`--ac-color-node-line`/`--ac-color-edge`, an SVG drop-shadow filter, the selected ring in amber; the picture's container on the dot grid; semantics (dashed observed calls, weights, `!`) unchanged; update the topology rules in `console/package/src/styles.css`
- [X] T040 [US3] Run `npm run e2e` in `console/`; `shape.spec.ts`, `topology.spec.ts` and the a11y list (the figure's `aria-label`, the tab's controls labelled) green

**Checkpoint**: the overview is a picture of what the platform runs; the topology reads as the same product.

---

## Phase 6: User Story 4 — Dark and light, and nothing fetched from outside (Priority: P2)

**Goal**: dark by default, light by preference, both on the same tokens; every request a page makes
goes to the host's origin, the face included.

**Independent Test**: the theme and own-origin cases in `treatment.spec.ts`;
`features/console/theme.feature`.

### Tests for User Story 4

- [X] T041 [P] [US4] Add to `console/e2e/tests/treatment.spec.ts` the three scenarios of `features/console/theme.feature` by title: `emulateMedia({ colorScheme: "no-preference" })` renders `.ac-root` with the dark `color-scheme` and the dark backdrop colour; `"light"` renders the light ones; a `page.on("request")` listener on the service page asserting every request URL's origin equals the target's origin and at least one request has `resourceType() === "font"` and a `.woff2` URL on that origin

### Implementation for User Story 4

- [X] T042 [US4] Make the theme cases pass: `toCss` in `console/package/src/ui/tokens.ts` emits `color-scheme: dark` on `.ac-root` in the base block and `color-scheme: light` inside the light media block; `Backdrop.tsx` sets `.ac-root` on its wrapper; verify the host's `root.tsx` keeps `<meta name="color-scheme" content="dark light">` (dark first)
- [X] T043 [US4] Make the own-origin case pass: `@font-face` in `console/package/src/styles.css` references `./fonts/…` relatively and `--ac-font-sans` names `"Inter Variable"` first; confirm the host's build emits the woff2 under `host/build/client/assets/` (Vite rebasing) and that `console/host/app/root.tsx` and `layout.tsx` load no external stylesheet or script; if Vite does not rebase, apply the plan's fallback (export the font files and copy them in the host, one line) and record it in `research.md` R3
- [X] T044 [US4] Run `npm run e2e` in `console/` and the quickstart's "show it can fail" step for the own-origin case (a Google Fonts `<link>` in the fixture host's `root.tsx` makes it fail, then revert)

**Checkpoint**: both themes audited; nothing leaves the origin.

---

## Phase 7: User Story 5 — A second host inherits the chrome (Priority: P3)

**Goal**: a host mounts the parts it has content for, restyles by properties, and the hosted
product's website keeps working on the new package; the docs say how.

**Independent Test**: `console/package/test/fixture-host.test.ts`; `docs-properties.test.ts`;
`just docs`; `ankka-cloud`'s `just test-web`; `features/console/hosts.feature` and
`features/documentation/console.feature`.

### Tests for User Story 5

- [X] T045 [P] [US5] Extend `console/package/test/fixture-host.test.ts` with the scenarios of `features/console/hosts.feature` by title: the fixture host's rendered page contains `.ac-backdrop` and `.ac-bar` and no `.ac-rail`, `.ac-panel` or `aside[aria-label=Operations]` on its own `billing` page; the override is proven on the built stylesheets without a browser: every declaration of `--ac-color-ink` in `console/package/dist/styles.css` (dark and light alike) lies inside an `@layer` block, the fixture host's built CSS declares `--ac-color-ink` unlayered on `.product`, and the rendered page's root carries `class="product ac-root"` — the cascade's layer rule then makes the host's value win in both colour schemes; the built package is unchanged by the fixture (`git diff --quiet console/package/src`)
- [X] T046 [P] [US5] Write `console/package/test/docs-properties.test.ts`: read `docs/reference/console-package.md`, find the `<!-- generated:start console-properties -->` … `end` block, render the expected table from `tokens.ts` (property, role, dark value, light value, and for the glow and mesh properties the limit sentence), fail on a difference naming the first differing line, and under `CONSOLE_DOCS_UPDATE=1` rewrite the block instead

### Implementation for User Story 5

- [X] T047 [US5] Update the fixture host: `console/package/test/fixture-host/app/layout.tsx` mounts `<Shell><Backdrop/><Bar>…</Bar><Outlet/></Shell>` (keeping its `docs:start layout` region), `fixture.css` sets `--ac-color-ink` and `--ac-color-amber` on `.product`; `console/package/test/fixture-host.test.ts` passes
- [X] T048 [US5] Register the block in `mkdocs.yml` (`generated: console-properties: { kind: external }`), rewrite the "Restyle the pages" section of `docs/reference/console-package.md` around it (what a property is, the layers rule, the `ac:` prefix being the package's own, the glow limit and why, the `./theme.css` export for a host with its own Tailwind), run `CONSOLE_DOCS_UPDATE=1 npm test -w package -- --test-name-pattern=docs` to fill it, then `just docs`
- [X] T049 [P] [US5] Update `docs/operate/console.md`: the shell (rail, bar, panel, inspector, what each holds, the front page without a panel), a service's four sections, the shape and what each part says, dark and light; keep every page-standing rule (`docs check`)
- [ ] T050 [US5] **Needs the user: runs in the sibling `ankka-cloud` checkout, not here.** In `ankka-cloud`: `just vendor-console` from this branch; `web/host/app/layout.tsx` mounts `<Shell><Backdrop/><Bar><nav aria-label="Site">…</nav></Bar><Outlet/></Shell>` with its skip link and footer kept; `web/host/app/site.css` drops the `a.ac-button` workaround and the `.wc-bar`/`.wc-nav` rules the bar now covers, keeps its property overrides; run `just test-web` — it must pass with no change under `console/package/`; record the outcome in `specs/035-console-treatment/research.md` R12
- [X] T051 [US5] Run `npm test -w package` and `just docs` from the repository root; both green (T050's outcome is recorded separately and does not gate this)

**Checkpoint**: the seam is proven by a second host and the docs describe the contract.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T052 [P] Extend `console/package/test/pack.test.ts` to assert the tarball holds `dist/styles.css`, `dist/theme.css` and both `dist/fonts/*.woff2`, and that `ankka-console/theme.css` resolves from the smoke app
- [X] T053 [P] Remove every rule from `console/package/src/styles.css` that no page uses any more (the old `.ac-bar`, `.ac-wordmark`, `.ac-crumbs`, the old topology rules) and confirm `npm run e2e` stays green
- [X] T054 [P] Re-run `console/package/test/host-size.test.ts` and record the host's line count in `specs/035-console-treatment/plan.md` under Scale/Scope
- [ ] T055 Run `quickstart.md` end to end, including `just test-console-compose` against the compose stack and both "show it can fail" steps; fix anything it finds
- [X] T056 [P] Add the traps this feature found to `CLAUDE.md`'s *Traps* list (at least: a Tailwind utility reads a property through `var()` only under `@theme`, never `@theme inline`; Base UI's Switch is a button, not a form control; a `display: contents` wrapper must not be a landmark) and update the *Console* section's description of the package's styling
- [ ] T057 Open the pull request from `035-console-treatment` with the spec's summary, the Lakeglass artifact link, and the `ankka-cloud` follow-up named; CI's `console` and `docs` jobs must pass

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: starts immediately; T002/T003 in parallel; T004–T008 sequential on T001–T003.
- **Foundational (Phase 2)**: depends on Phase 1; T009, T011, T012 in parallel; T010 after T009; T013 after T012; T014–T016 sequential. Blocks every story.
- **US1 (Phase 3)**: depends on Phase 2. The MVP.
- **US2 (Phase 4)**: depends on US1 (it gates the restyled pages); its contrast half (T009/T010) is already in Phase 2.
- **US3 (Phase 5)**: depends on US1's service page (T022); independent of US2 and US4.
- **US4 (Phase 6)**: depends on Phase 2 only; may run beside US1.
- **US5 (Phase 7)**: depends on Phase 2 (fixture host) and US1 (docs describe the shell); T050 depends on everything in the package being final.
- **Polish (Phase 8)**: after every story.

### Parallel Opportunities

- Phase 3: T018–T021 and T023 touch different route files and run in parallel; T022 and T024 are sequential (the history table moves).
- Phase 4: T028, T029, T030 (tests) in parallel; then T031–T033 touch different concerns and can be split.
- Phase 5: T036 and T037 in parallel; T035 beside them.
- Phase 6 may run beside Phase 3.
- Phase 7: T045, T046 beside each other; T049 beside T048.

### Parallel Example: User Story 1

```bash
# After T017 (the test) is written and failing:
Task: "T018 front.tsx into the shell"
Task: "T019 organization.tsx, organization-new.tsx into the shell"
Task: "T020 members.tsx, tokens.tsx into the shell"
Task: "T021 project.tsx, project-new.tsx into the shell"
Task: "T023 logs.tsx, service-topology.tsx, service-apply.tsx into the shell"
Task: "T025 auth.sign-out.tsx and the failure pages onto the new tokens"
# then T022 → T024 → T026 → T027
```

---

## Implementation Strategy

### MVP First (Phases 1–3)

1. Setup: the stylesheet is built from tokens and the face ships (T001–T008).
2. Foundational: the contrast test pins the tokens before a page is touched (T009–T010); the shell exists (T011–T016).
3. US1: every page in the shell, every operation in its inspector, the suite green (T017–T027).
4. **Stop and validate**: `npm run e2e` both projects; look at it against the Lakeglass mock.

### Incremental Delivery

- US2 (gates) immediately after US1 — nothing ships without it.
- US4 (themes, own origin) is small and can land with US1.
- US3 (the shape) is the visible payoff and lands next.
- US5 (hosts, docs, `ankka-cloud`) last, once the package is final.

### Notes

- A test task is written first and must fail before its implementation task; T009 and T045 in particular are shown failing (quickstart) before they are made green.
- Nothing a page says or does changes (FR-015): a locator moves, an assertion does not.
- Commit after each task or logical group; the PR is T057.
