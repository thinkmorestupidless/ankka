---
paths:
  - "console/**"
---

# The console

## The console is a package and a thin host

`console/` is an npm workspace (feature 017): `package/` is `ankka-console` — the control plane client
with zod mirrors of `controlplane-api`'s wire types, sign-in through the realm, sessions, and the pages
as React Router 8 route modules — and `host/` is the image, 73 lines that mount the package at `/`.
`ankka-cloud` is meant to be a second host, which is why everything a host would otherwise copy is in
the package and `package/test/fixture-host/` proves a second host works with no package change.

- **No token reaches the browser.** The session is a sealed (AES-GCM) cookie carrying only the refresh
  token; each instance caches access tokens in memory and refreshes on a miss, so any instance serves
  any session and there is no store. Sign-out ends the Keycloak session server to server (a confidential
  client posting the refresh token to the logout endpoint), so no identity token is kept either.
- **The control plane is the only authority.** The console calls its API as the person and shows its
  refusals verbatim; it reads the issuer from `GET /auth` so the two cannot disagree. Pages call the
  API; no route was added to the control plane for the console.
- **Live updates are polling, streamed.** Service pages and listings open a server-sent event stream the
  console feeds by reading the control plane every two seconds as the person; logs follow by
  overlapping re-reads (the logs route has no cursor).
- **`ankka-console` is browser-safe; `ankka-console/server` is not.** A host's layout imports the
  former, its root and `routes.ts` the latter, and the browser bundle must resolve no Node module.
- **The look is properties, generated, layered and tested.** `console/package/src/ui/tokens.ts` is the
  one source of every colour, face, radius and size; the build writes it into the Tailwind theme
  (`prefix(ac)`, so `--ac-color-*`), compiles `dist/styles.css` (no host needs Tailwind), and a node test
  owns the properties table in `docs/reference/console-package.md`. Every package rule is in a cascade
  layer, so a host's unlayered declaration wins whatever its specificity. The surfaces are translucent,
  so `test/contrast.test.ts` composites every ink over every surface at the backdrop's brightest point
  in both themes; a colour change that fails it is a design change, not a test to loosen. The console
  is dark: browsers report "light" when nobody has chosen, so it does not follow the preference, and
  `<Shell theme="light">` is a host's choice. Every page is inside the shell (`Shell`, `Backdrop`, `Bar`,
  `Rail`, `Listing`, and each page's `Page` with its inspector), and an overlay only ever enhances a
  page that exists without it: menus are `<details>`, sections are routes, choices are `<select>`.
- **The fake control plane is a second implementation of the API's observable rules**, so it drifts:
  `ControlPlaneFixturesSuite` holds the client's schemas to the codecs, and the Playwright suite runs
  against the compose stack too (`just test-console-compose`), which is what found the drift so far.

## Traps

- **React Router's route-config loader ignores the host's Vite `resolve.conditions`.** `routes.ts` is
  evaluated by the framework's own loader, so `import "ankka-console/server"` there fails to resolve under a
  source-only condition that works everywhere else in the app (`Failed to resolve entry for package`). The
  console's host therefore consumes the package through its built `dist/`, exactly as an npm consumer does,
  and the workspace's `build`, `dev` and `e2e` scripts build the package first.
- **`react-router build` with no `app/entry.server.tsx` installs `isbot` into the nearest `package.json`.**
  Run in `console/package/test/fixture-host/`, that was the package's own manifest, and the reinstall that
  followed pruned `@react-router/dev` out of `node_modules`. The fixture host has its own server entry for
  this reason; check `git status` after a build that printed anything about installing.
- **A Playwright test that reloads straight after a click cancels the click's submission.** With scripts on,
  a form posts through `fetch`; `page.reload()` or `page.goto()` right after the click aborts it, and the
  write never happens — the test then fails on a state that is correct. Wait for the page the action lands
  on, or for the `POST`'s response, before navigating. It cost four "failures" of correct behaviour.
- **The control plane's listings are projections; the fake's are not unless asked.** Organizations,
  projects, services and tokens are listed from views that trail a write by up to a second or two, and the
  delete checks ("still has N projects") count from them too. A test that reads a listing after a write
  must reload until it shows (`afterProjection` in the e2e fixtures); a page must go to what it created by
  id rather than to a list. The token page hid a new token's secret until the listing caught up, a bug only
  the compose run could see.
- **The control plane answers `Done` as 204 with no body**, including for creating an organization or a
  project, and an omitted deploy-token lifetime is the 90-day default, not "never" (`0` is never). The fake
  had both wrong until the compose run found them; `ControlPlaneFixturesSuite` covers bodies, not statuses.
- **Keycloak's issuer depends on who asks, so the console's backchannel pretends to be the gateway.**
  Configured with a host name only, Keycloak takes its issuer's scheme and port from each request's
  forwarded headers, else from how it was reached. The control plane only reads keys over the in-cluster
  address, which carry no issuer; the console also runs discovery and the token grants there, and
  unadorned they answered `https://auth.<base>:8443` — refused at discovery (a 500 at sign-in, found only
  by the k3s suite) and wrong in every token. `Issuer` sends `X-Forwarded-Proto/Host/Port` for the public
  issuer on every backchannel call; `fakeIssuer({ hostOnly })` reproduces Keycloak's behaviour for the test.
- **A React effect depending on a function from a hook re-runs on every render.** The stream hook depended
  on `useConsole().href`, a fresh closure each render, so every event it delivered re-rendered the page and
  reopened the stream, which never left "connecting". Depend on the stable value (the mount path) instead.
- **Never `@theme inline` in the console's theme.** Under `@theme` a utility reads `var(--ac-color-…)`, so a
  host's declaration of the property reaches it; `inline` writes the value into the utility instead, and a
  host's override silently stops applying to anything drawn with utilities.
- **A licence comment is not part of an `@layer`'s head.** The built stylesheet opens with `/*! tailwindcss
  … */` immediately before `@layer theme{`; a check that reads layers must strip comments first, or it
  reports the package's dark properties as unlayered when they are not.
- **A pinned item in a scrolling list needs an opaque ground.** The listing keeps the item being read in
  view with `position: sticky`; on a translucent tint the rows scrolling beneath it show through its text.
  It is its tint over `--ac-color-mesh-base`, the same ground the no-blur fallback uses.
- **A name on the page twice breaks an unscoped locator.** The shell lists a project's services beside
  every service page and repeats a service's controls on its shape, so `getByRole("link", { name })`
  can match two. The browser suite reaches with `body(page)`, `ops(page)` and `sections(page)` from
  `e2e/fixtures.ts`; scope a new locator the same way rather than renaming a control to dodge it.
