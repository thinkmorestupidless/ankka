# Quickstart: proving the console treatment

## Prerequisites

Node 24, npm, Docker (the compose target only), a checkout of `ankka-cloud` beside this one for
the last step.

## Build and the unit tests

```bash
cd console && npm ci
npm run build -w package              # tokens → theme.generated.css → tailwind → dist/styles.css, dist/fonts/
CONSOLE_PACK=1 npm test -w package    # contrast, docs block, fixture host (backdrop + bar alone), pack (fonts in the tarball), host size
```

Expected: every test passes; `contrast.test.ts` prints the lowest ratio per theme; `host-size`
prints the host's line count (< 500).

Show the contrast test can fail: in `src/ui/tokens.ts` raise `dark.mesh.glowAlpha` by 0.2 and run
`npm test -w package` — the test fails naming `--ac-color-glow` and the pair. Revert.

## The browser suite against fakes

```bash
npm run e2e                           # both projects: scripts-on and scripts-off, route parity at the end
```

Expected, among the existing cases:
- `shell.spec.ts`: every page inside the shell; the front page without a panel; nothing scrolls
  sideways at 400 px.
- `treatment.spec.ts`: axe clean in dark and in light (light added as a host would, by class); no request leaves the target's origin and
  one of them is the face; the live indicator visible under reduced motion; borders under forced
  colours.
- `shape.spec.ts`: four parts and three joins for an exposed service; no address when unexposed;
  "Not reported yet" for a database; the ready count changes with scripts on.
- `scripts-off.spec.ts`: overview, topology, logs and history each a page; pause, a disclosure and a
  select with scripts off; pause in place with scripts on.

Show the own-origin case can fail: add a Google Fonts `<link>` to `console/host/app/root.tsx`,
rebuild, and run the case — it fails naming the foreign origin. Revert.

## The console against the compose stack

```bash
docker compose up -d                                       # repository root: Postgres, Keycloak
ANKKA_AUTH_ISSUER=http://localhost:8081/realms/ankka sbt controlPlane/run
just test-console-compose                                  # the same suite against the real control plane
```

## The documentation

```bash
just docs                               # fails if the properties block is stale or a page is not in nav
CONSOLE_DOCS_UPDATE=1 npm test -w package -- --test-name-pattern=docs   # rewrites the block from tokens.ts
```

## The second host (`ankka-cloud`)

```bash
cd ../ankka-cloud && just vendor-console   # repacks the package from this checkout
just test-web                              # type check, node --test, Playwright (scripts on/off, axe)
```

Expected: the website's layout mounts `Backdrop` and `Bar`; the suite passes with changes confined
to `web/host/app/layout.tsx` and `site.css`. If a package change is needed to make it pass, stop:
the seam is wrong, and the fix belongs in this feature.

## Looking at it

```bash
cd console && npm run dev                  # :3000 against compose's Keycloak and the control plane
```

Compare with the direction mock (the "Lakeglass" artifact): the shell, the shape, the listing,
and 400 px in the browser's device mode. The console is dark; to see light, set `theme="light"` on
the `Shell` in `console/host/app/layout.tsx`.
