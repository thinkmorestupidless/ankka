# Feature Specification: Console Treatment — A Glass Shell for the Console and Its Hosts

**Feature Branch**: `035-console-treatment`

**Created**: 2026-10-04

**Status**: Draft

**Input**: User description: "Console visual treatment: a redesign of the ankka console's look
and feel, as the ankka-console package and its two hosts (the installation's console image and
ankka-cloud's website). The direction is agreed and mocked (the "Lakeglass" artifact,
https://claude.ai/artifact/Fcqts4wbFzBeM718hztArf): a four-column glass shell over a slate mesh
with a central ember glow and a faint dot grid across the whole page — a 56px icon rail
(organizations, projects, services, members, deploy tokens, sign out), a panel listing the
project's services with their lifecycle, the page itself, and an inspector holding the page's
operations and the descriptor's facts; the breadcrumb centred in the top bar beside the primary
action. Every surface is a translucent white tint with backdrop blur; dark leads and light is
kept; the one accent is ankka's amber for focus, selection, toggles and the primary operation; Inter
is shipped inside the package and served from the console's own origin, so nothing is fetched
from outside the cluster. A service page gains a topology tree — route → service → {instances,
database} — drawn as frosted nodes (large line icon, title, subtitle, a darker inset row), the
service itself ringed with a notched top tab holding its controls, edges leaving a port on the
parent and curving into ports on the children, in CSS and inline SVG with no script. Styling
moves from hand-written CSS to Tailwind v4 (tokens declared as CSS custom properties, compiled
into the package's dist/styles.css) with components copied in from shadcn/ui on Base UI and
lucide icons, owned by the package; a host restyles by overriding the custom properties exactly
as today. Two rules hold throughout: every operation keeps working with scripts off — an overlay
is only an enhancement of a page that exists without it, so menus are details elements, tabs are
route links, selects stay native and confirmations are routes — and every page keeps zero axe
violations at WCAG 2.1 AA, which means the mesh's glow is pinned as tokens dark enough that
full-ink text on any glass passes 4.5:1 in both themes. The ankka-cloud website inherits the
tokens and the shell's chrome as the package's second host without a change to the package; its
own pages (landing, pricing, account) are restyled on the same tokens in a later feature."

## Context

The console is the `ankka-console` package (`console/package/`), feature 017: the control plane
client, sign-in and sessions, and the pages as React Router route modules a host mounts. Two hosts
exist. `console/host/` is the installation's console image, 73 lines that mount the package at `/`
and wrap its pages in a bar with the wordmark and the signed-in person. `ankka-cloud/web/host/` is
the hosted product's website, which takes the package's sign-in, sessions and server and none of
its pages, and lays its own pages (landing, pricing, account, operator) on the package's tokens.
Feature 017 decided, in its spec's assumptions, that no component library or design system would
be adopted, and deferred "a design system, theming, or a visual refresh" out of scope. What it
shipped is `console/package/src/styles.css`: 481 lines, every class prefixed `ac-`, every colour,
face and space a custom property on `.ac-root` so a host restyles the pages by overriding
properties rather than rules, no web fonts because a console inside a cluster must fetch nothing
from outside it, birch paper and lake ink by day and the lake at night after dark, one amber
accent for focus and for what is live, red for failure. Pages are a heading, a facts list, a
table, and operations as forms, with the secondary and destructive ones behind a `<details>`
disclosure. Feature 019 added a service's topology page (`routes/service-topology.tsx`,
`ui/topology/Graph.tsx`): a picture of the service's components in columns with declared
connections solid and observed calls dashed, and a table beside it.

Three rules were built into the console and are enforced by its suites, and this feature keeps
every one:

- **Every operation works with scripts off** (017's FR-026). The Playwright suite in
  `console/e2e/` runs every test twice, as the `scripts-on` and `scripts-off` projects, and
  `parity.ts` fails the run unless every control plane route was exercised through the console.
  The website's suite in `ankka-cloud/web/e2e/` does the same.
- **Zero accessibility violations at WCAG 2.1 AA on every page** (017's FR-027a, SC-013), as
  `@axe-core/playwright` checks them, and every operation reachable by keyboard.
- **A host restyles by overriding properties.** `ankka-cloud`'s `site.css` is 198 lines on the
  package's `--ac-*` properties and `ac-` classes; its one trap so far was `.ac-root a { color:
  inherit }` outranking `.ac-button`, a specificity collision the package's flat stylesheet
  invites.

The console is correct and plain, and the hosted product is about to be sold on it. The user
chose an inspiration (a "ChatDash" workflow dashboard by Cloud 2 Voice on Dribbble: frosted glass
panels over a blurred, warm-lit backdrop, a 56px icon rail, a palette panel, a canvas of notched
node cards joined by curved edges, an inspector with uppercase section labels, segmented controls,
toggles, a centred breadcrumb and a pill primary operation) and, after a survey of component
systems, settled a direction in a mock, the "Lakeglass" artifact named in the input. The decisions
that mock records, and that this feature builds:

- **The shell.** Four columns on every console page: a 56px rail of icons for organizations,
  projects, services, members and deploy tokens, with sign out at its foot and the current area
  marked; a 64px bar with the breadcrumb centred and the page's primary operation and the signed-in
  person at its end; a panel listing the project's services with their lifecycle and instance
  counts, marking the one being read; the page; and an inspector holding the page's operations and
  the descriptor's facts, with the destructive operation behind a disclosure at its foot. The page
  body shows state; it offers no operation the inspector does not.
- **Glass over a mesh.** The page's backdrop is a slate mesh with an ember glow at the screen's
  centre and a faint dot grid across the whole page. The rail, bar, panels, cards and tiles are
  translucent white tints with backdrop blur, so the glow reads through everything. The console is
  dark; light is the same construction in frosted white, kept for a host that chooses it.
- **One accent.** Ankka's amber for focus, selection, toggles, the live indicator and the primary
  action; green, amber and red for lifecycle, carried by a shape as well as a colour as today. The
  inspiration's blue is deliberately not adopted.
- **Contrast is a property of the tokens.** Because text sits on translucent surfaces, its
  contrast depends on what is behind it. The mesh's colours and the glow's peak are tokens pinned
  dark enough that full-ink text on any surface passes 4.5:1 at the glow's brightest point, in
  both themes, and a test computes that worst case so a brighter glow fails the build.
- **Inter, shipped in the package.** The face comes from the console's own origin, so a console
  inside a cluster still fetches nothing from outside it. Every request a page makes goes to the
  host's origin.
- **A service's shape.** The service page gains a figure of what the platform runs for the
  service: its route, the service itself, its instances and its database, as frosted nodes with a
  large line icon, a title, a subtitle and a darker inset row, the service ringed and carrying a
  notched tab on its top edge with its own controls, edges leaving a port on a parent's lower edge
  and curving into ports on its children. It is drawn in markup and inline vector graphics, so it
  is on the page with scripts off, and it updates as the platform reports when scripts are on.
  The topology page's picture takes the same node and edge language.
- **How it is built.** The hand-written stylesheet gives way to a utility-first stylesheet whose
  tokens are custom properties, compiled into the one file the package ships, with components
  copied into the package from a headless, accessible component set and owned there, and a line
  icon set; so a host still restyles by overriding properties, and the specificity collisions a
  flat stylesheet invites are gone. Which overlays may use script is settled by a rule rather than
  a list: an overlay is an enhancement of a page that exists without it. A menu is a disclosure, a
  set of tabs is a set of links to routes, a choice is the browser's own select, and a confirmation
  is a page of its own; script may replace any of them in place, and nothing may depend on it.
- **The hosts.** The installation's console image mounts the shell and stays under its 500-line
  budget. The hosted product's website gets the tokens and the shell's chrome as the package's
  second host, with no change to the package to accommodate it; restyling the website's own pages
  on those tokens is a later feature.

What this feature is not: a change to any control plane route or to what any console page says or
does; a restyle of the local console a developer runs on their own machine; a custom illustrated
icon set in the inspiration's isometric style; charts; or the website's own pages, which are the
later feature above.

## Clarifications

### Session 2026-10-04

- Q: Are the 44 console terms proposed in `GLOSSARY.md` (`shape`, `shell`, `rail`, `bar`, `panel`,
  `inspector`, `operation`, `address`, `property`, `lifecycle`, `host`, `package`, `website`, …)
  the right words, with the synonyms they refuse? → A: Accept all as written; the *Proposed* marks
  are removed.
- Q: What do the rail's `members` and `deploy tokens` marks open for a member of several
  organizations, when the console has no current organization? → A: The rail marks the kind of
  page a member is on; `members` and `deploy tokens` open those of the organization the current
  page belongs to; on the front page, which lists organizations, they are shown but unavailable
  until an organization is opened. No current organization is remembered.
- Q: Does the front page, whose content is the organizations listing, have a panel? → A: No. The
  panel is never the page again: the front page takes the full width, and the panel appears from
  the first organization's page onward.
- Q: Which parts of the shell does the hosted product's website get around its landing, pricing
  and account pages? → A: The shell is composable. The backdrop and the bar are the least a host
  mounts; the rail, the panel and the inspector are parts a host adds where it has content for
  them. The installation's console mounts all of them; the website mounts the backdrop and the bar
  now.
- Q: The bdd checker reports 279 inline scenarios in specs 019 and 023–034, written before the
  hook; migrate them here, leave them and keep reporting them, or move `specs-from`? → A: Leave
  them. `specs-from` becomes `"035"`, the first spec written with the hook, with a comment naming
  the backlog; each older spec's scenarios move into living features when that spec is built.
- Q (found in implementation): "no operation outside the inspector" contradicts the shape's tab
  (FR-009) and would move per-row and create-page controls away from what they act on. → A: The
  inspector holds every operation on what the page shows; a row's operation stays in its row, a
  create page keeps its form, and any control elsewhere repeats one the inspector offers (FR-002).
- Q (found in implementation): browsers report "light" when nobody has stated a preference, so
  "dark leads, light by preference" shows most people the light theme. Which do they see? → A:
  Dark always, for now. The light theme stays in the package, tested and audited, for a host that
  chooses it (`<Shell theme="light">`); a switch a member uses is a later feature (FR-006).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A member finds their way through the shell (Priority: P1)

A member opens any console page and sees the same shell: the rail marks the area they are in, the
bar says where they are and offers the page's primary operation, the panel lists the project's
services with their lifecycle and marks the one they are reading, and the inspector holds every
operation the page offers. On a phone the shell collapses in reading order and nothing is hidden.

**Why this priority**: The shell is the redesign. Every other story is a property of it, and a
console whose pages each look different is worse than the one it replaces.

**Independent Test**: The browser suite opens every console page, scripts on and off, and asserts
the rail's current mark, the bar's breadcrumb and primary operation, the panel's listing and current
mark, and that the inspector holds the page's operations and the page body holds none; at a phone
width it asserts no page scrolls sideways and every landmark is still present.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/console/shell.feature`: a page is shown inside the shell, with every operation in its inspector
- added `features/console/shell.feature`: the rail opens the members and the deploy tokens of the organization the page is in
- added `features/console/shell.feature`: the front page has no panel, and the organization's areas wait until one is opened
- added `features/console/shell.feature`: the panel lists the project's services with their lifecycle and marks the one being read
- added `features/console/shell.feature`: the destructive operation waits behind a disclosure at the inspector's foot
- added `features/console/shell.feature`: on a narrow screen the shell collapses in reading order and hides nothing
- added `features/console/shell.feature`: a project with more services than the panel shows at once keeps the one being read in view
- added `features/console/shell.feature`: an organization's page lists its projects in the panel

---

### User Story 2 - Nothing stops working: scripts off, keyboard, and no violation (Priority: P1)

A member whose browser runs no scripts pauses, restarts, exposes and deletes a service, invites a
member and creates a deploy token through the new shell, exactly as before. A member using only a
keyboard reaches every operation with a visible focus. An automated audit of every page, in both
themes, finds no accessibility violation, including on text that sits over the glow's brightest
point.

**Why this priority**: These are the gates feature 017 set, and a redesign that loosened one would
be a regression dressed as an improvement. The rule that an overlay is an enhancement of a page
that exists without it is what lets a component set be adopted at all.

**Independent Test**: The existing browser suite, unchanged in what it asserts, passes in both
its scripts-on and scripts-off projects with route parity; the accessibility audit passes on every
page in both themes; a unit test composites the ink over each surface at the glow's peak in both
themes and asserts the ratio, and fails when a glow token is raised above the limit.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/console/scripts-off.feature`: an operation completes with scripts off
- added `features/console/scripts-off.feature`: a page's sections are reached as pages of their own with scripts off
- added `features/console/scripts-off.feature`: further operations open as a disclosure with scripts off
- added `features/console/scripts-off.feature`: a choice is made with scripts off
- added `features/console/scripts-off.feature`: with scripts on the same operation completes in place
- added `features/console/accessibility.feature`: every page has no accessibility violation in either theme
- added `features/console/accessibility.feature`: every operation is reached by keyboard with a visible focus
- added `features/console/accessibility.feature`: text keeps its contrast at the glow's brightest point
- added `features/console/accessibility.feature`: a glow brighter than the limit fails the build
- added `features/console/accessibility.feature`: a lifecycle is told by its shape as well as its colour
- added `features/console/accessibility.feature`: a member who asks for reduced motion sees the live indicator still
- added `features/console/accessibility.feature`: a browser that cannot blur what is behind a surface still shows readable text
- added `features/console/accessibility.feature`: in forced colours every surface keeps its edge

---

### User Story 3 - A member sees a service's shape (Priority: P2)

A member opens a service and sees, above its facts, what the platform runs for it: the route that
reaches it, the service itself with its generation and image, its instances with how many are
ready, and its database, joined in the direction traffic and data flow. The service's own node
carries its controls. With scripts off the figure is there on first paint; with scripts on its
ready count changes as the platform reports.

**Why this priority**: It is the one place the inspiration's canvas idiom lands on data the page
already has, and it turns a facts list into a picture a member reads in a glance. It depends on the
shell but the shell does not depend on it.

**Independent Test**: The browser suite opens a service that is exposed, has three instances and a
reported database, and asserts the four parts and their three connections, scripts off; then, with
scripts on, lets the fake control plane report a changed ready count and asserts the figure says
so. It opens an unexposed service and asserts no route part, and a service whose database is not
yet reported and asserts the part says so.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/console/shape.feature`: a service's shape shows its hostname, the service with its controls, its instances and its database, joined in order
- added `features/console/shape.feature`: a service that is not exposed has no hostname in its shape
- added `features/console/shape.feature`: a database the cluster has not reported yet is shown as waiting
- added `features/console/shape.feature`: a service that is not deployed yet still has a shape
- added `features/console/shape.feature`: a web-hosted service's shape shows each of its mounts, and no database
- added `features/console/shape.feature`: a mount with no service behind it is marked in the shape
- added `features/console/shape.feature`: the shape is on the page with scripts off
- added `features/console/shape.feature`: the shape changes as the platform reports
- added `features/console/shape.feature`: a value longer than its part is clipped, and whole below
- changed `features/topology/deployed-services.feature`: a member of a project reads the topology of a service deployed in it, and is given no credential

---

### User Story 4 - Dark and light, and nothing fetched from outside (Priority: P2)

A member reads the console over the slate mesh with the ember glow, whatever their browser
prefers; a host that chooses the light theme shows it in frosted white over a pale mesh. Either way
the face is the same, and every request the page makes goes to the console's own origin.

**Why this priority**: Two themes were already a property of the console and a console in a
cluster that reached outside it would fail an installation's own network policy. Both must be
true of the first release, and both are cheap to prove.

**Independent Test**: The browser suite opens a page with each colour-scheme preference and
asserts the theme in use; it records every request a page makes and asserts each goes to the
host's origin, in both hosts.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/console/theme.feature`: a member reads the console dark whatever the browser prefers
- added `features/console/theme.feature`: a host that chooses the light theme shows the console light
- added `features/console/theme.feature`: everything a page fetches comes from the console's own origin

---

### User Story 5 - A second host inherits the chrome (Priority: P3)

The hosted product's website builds against the new package, mounts the backdrop and the bar
around its own pages, overrides the properties it wants to, and its own suite passes without a
change to the package or to what its pages say and do. The console's documentation says how a host restyles it.

**Why this priority**: The package exists so that a second host does not copy it; the website is
that host, and its pages are restyled on these tokens in the next feature. This story proves the
seam before that feature leans on it.

**Independent Test**: The package's fixture host mounts the shell and overrides a property, and a
test asserts the override reaches the page; the website is built against the package and its
browser suite passes; the documentation check passes with the console's page describing the
tokens a host may override.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/console/hosts.feature`: a host mounts the parts of the shell it has content for, without a change to the package
- added `features/console/hosts.feature`: a host restyles the console by setting its custom properties and not its rules
- added `features/console/hosts.feature`: the hosted product's website keeps working on the new package
- added `features/console/hosts.feature`: the installation's console stays small
- added `features/documentation/console.feature`: the documentation of the console says how a host restyles it

---

### Edge Cases

- A project with more services than the panel's height: the panel scrolls, the services stay in
  name order, and the one being read is in view when the page opens. *(`features/console/shell.feature`:
  a project with more services than the panel shows at once keeps the one being read in view)*
- A service's image or address longer than its part of the shape: the part clips it and the facts
  below carry the whole value. *(the `Scenario Outline` in `features/console/shape.feature`: a value
  longer than its part is clipped, and whole below)*
- A service that is not deployed yet: the shape still draws, with the instances part saying none
  of the desired count is ready. *(`features/console/shape.feature`: a service that is not deployed
  yet still has a shape)*
- A browser that cannot blur what is behind a surface: the surface falls back to a tint opaque
  enough that the contrast rule still holds. *(`features/console/accessibility.feature`: a browser
  that cannot blur what is behind a surface still shows readable text)*
- A browser in forced-colours mode: every surface keeps a border and every control keeps an
  outline, so the shell is still a shell. *(`features/console/accessibility.feature`: in forced
  colours every surface keeps its edge)*
- A host that sets the glow properties brighter than the limit: the package's test does not run
  for the host, so the documentation names the limit as the property's contract.
  *(`features/documentation/console.feature`)*
- An organization page, which has no project to list services for: the panel lists the
  organization's projects with their service counts instead. *(`features/console/shell.feature`: an
  organization's page lists its projects in the panel)*
- The topology page a service already has: it becomes a section of the service, reached as a page
  of its own beside the overview, logs and history, and its picture takes the shape's part and
  joining language. *(the changed scenario in `features/topology/deployed-services.feature`)*

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Every console page MUST be shown inside the shell: the rail with the five areas and
  sign out, marking the area the page belongs to; the bar with the breadcrumb centred and the
  page's primary operation and the signed-in person at its end; the panel listing the project's
  services, or on an organization page its projects; the page; and the inspector. The members,
  deploy tokens and create pages list the organization's projects in the panel. The front page,
  whose content is the organizations listing, has no panel and takes the full width.
- **FR-001a**: The rail's `members` and `deploy tokens` areas MUST open those of the organization
  the current page belongs to, and on the front page MUST be shown but unavailable until an
  organization is opened; the console MUST NOT remember a current organization.
- **FR-002**: Every operation on what a page shows MUST be in its inspector, the destructive one
  behind a disclosure at its foot. Two kinds of control stay where they act: an operation on one
  row of a listing (changing a member's role, revoking a token) stays in its row, and a page whose
  whole purpose is a form (creating an organization or a project, applying a descriptor) keeps its
  form in the page. A control elsewhere on a page (the shape's tab) MUST be one the inspector also
  offers.
- **FR-003**: Every operation MUST complete with scripts off, as feature 017 required, and an
  overlay MUST be an enhancement of a page that exists without it: a menu is a disclosure, a set of
  tabs is a set of links to routes, a choice is the browser's own select, and a confirmation is a
  page of its own.
- **FR-004**: Every page MUST have no accessibility violation at WCAG 2.1 AA in either theme, and
  every operation MUST be reachable by keyboard with a visible focus.
- **FR-005**: The mesh's colours and the glow's peak MUST be tokens, and a test MUST composite the
  ink over each surface at the glow's peak in both themes and fail below 4.5:1.
- **FR-006**: The console MUST be dark whatever the browser prefers; the light theme MUST be kept
  in the package, chosen by a host, contrast-tested and audited like the dark one.
- **FR-007**: Every request a console page makes MUST go to the host's own origin; the face MUST be
  shipped in the package.
- **FR-008**: A service page MUST show the service's shape — its route when exposed, the service
  with its generation and image, its instances with how many are ready, and its database or that it
  is not reported yet — joined route to service, service to instances, service to database, drawn
  in the page's markup so it is present with scripts off and changing as the platform reports with
  scripts on.
- **FR-008a**: For a web-hosted service the shape MUST show, below the service, its instances and
  one part per mount (its path and the mounted service, linked when it exists, marked when the
  mount reports trouble), and no database.
- **FR-009**: The service's node MUST carry its controls, each the same operation the inspector
  offers.
- **FR-010**: A lifecycle MUST be told by a shape as well as a colour, as today, and the live
  indicator MUST remain visible when a member asks for reduced motion.
- **FR-011**: The package MUST ship one stylesheet in which every colour, face, radius and space
  is a custom property a host may override, and the documentation MUST list them with the
  contrast limit as the glow properties' contract.
- **FR-012**: The shell MUST be composable by a host: the backdrop and the bar are the least a host
  mounts, and the rail, the panel and the inspector are parts it adds where it has content for
  them. The package's fixture host MUST mount the shell's parts and prove a property override
  reaches the page, and the hosted product's website MUST build against the package, mounting the
  backdrop and the bar around its own pages, and pass its own browser suite with no change to the
  package.
- **FR-013**: The installation's console image MUST stay under feature 017's 500-line budget.
- **FR-014**: A service MUST have four sections, each a page of its own: overview, topology, logs
  and history. The topology page MUST be reached as one of them and its picture MUST use the
  shape's part and joining language; the history listing MUST move from the overview to its own
  section unchanged in columns and words.
- **FR-015**: No control plane route MAY change, and nothing a console page says MAY change: an
  operation keeps its name, a refusal its words, a listing its columns. What moves is where a
  thing is shown (the inspector, the bar, the history section), never what it is.

### Key Entities *(include if feature involves data; each a term in the project glossary)*

- **shell**: what every console page is shown inside: the rail, the bar, the panel and the
  inspector around the page, over the backdrop. A host mounts the backdrop and the bar at least,
  and the other parts where it has content for them.
- **shape**: what the platform runs for a deployed service — its route, the service, its instances
  and its database — and how they are joined.
- **host**: an application that mounts the console package: the installation's console, or the
  hosted product's website.
- **overlay**: something shown over a page, such as a menu, a dialog or a tooltip; in the console
  always an enhancement of a page that exists without it.
- **theme**: the console's dark or light rendering; dark, unless a host chooses light.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The accessibility audit reports zero violations on every console page, in both
  themes, scripts on and off.
- **SC-002**: The browser suites of both hosts pass in their scripts-off project with route parity,
  with no assertion weakened.
- **SC-003**: Every request a page makes in either host goes to that host's origin: zero requests
  leave it.
- **SC-004**: The computed contrast of ink over every surface at the glow's peak is at least 4.5:1
  in both themes, and raising a glow token past the limit fails the build.
- **SC-005**: The hosted product's website builds against the new package and its suite passes with
  changes confined to its own layout and stylesheet.
- **SC-006**: The installation's console image has fewer than 500 lines of its own source.
- **SC-007**: A service's shape is in the page's first response, so it is seen with scripts off,
  and reflects a changed ready count within five seconds with scripts on (two reads of the
  stream's two-second cadence and a render).
- **SC-008**: No console page scrolls sideways at a 400-pixel width, and every landmark of the shell
  is present there.

## Assumptions

- The "Lakeglass" artifact is the visual reference; where the spec and the mock disagree, the spec
  wins and the mock is updated.
- Inter is the face and amber the accent; both were chosen by the user and are not open.
- The panel lists services on project and service pages and projects on organization pages; the
  front page, which lists organizations, has none.
- The shape's parts come from what the service page already loads: the hostname, the lifecycle,
  the generation, the image, the instance counts and the reported database. No new control plane
  route is added for it.
- The topology page shipped by feature 019 stays a page; this feature changes how it is reached and
  how its picture is drawn, not what it shows.
- The local console a developer runs on their own machine is a separate surface and is not
  restyled here.
- Charts, a custom illustrated icon set and the website's own pages are out of scope.

## Dependencies

- **017-control-plane-console**: the package, the hosts and the suites this feature restyles and
  keeps green.
- **019-service-topology**: the topology page that becomes a section of the service and takes the
  shape's node language.
- The website's own pages, restyled on these tokens, are the next feature in `ankka-cloud` and
  depend on this one.

## Open Questions

None; both were settled in the clarification session above.
