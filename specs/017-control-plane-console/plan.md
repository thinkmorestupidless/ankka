# Implementation Plan: A Console for a Deployed Installation

**Branch**: `017-control-plane-console` | **Date**: 2026-09-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/017-control-plane-console/spec.md`

## Summary

A web console for a deployed installation at `https://console.<base domain>`: a server-rendered
React Router 8 application on Node 24 that signs a person in through the installation's realm with
the authorization code flow and PKCE, holds their tokens on its server behind a sealed cookie
carrying only the refresh token, and drives the control plane's HTTP API as that person for
organizations, projects, services, members and deploy tokens. Service pages and log pages stay
current over server-sent events the console feeds by polling the control plane. It is built as a
package (`ankka-console`: the typed client, the session and sign-in machinery behind store and token
interfaces, the pages as mountable route modules with extension points) and a thin host (the image),
so `ankka-cloud` can mount the same pages. In a cluster it is a platform workload under the
zero-trust rules — TLS to the gateway, mutual TLS to the control plane as `ankka://platform/console`,
no Kubernetes grant, no database — deployed as a kustomize component every overlay applies. Proven
by unit tests, a Playwright suite (scripts on and off, axe, keyboard) against fakes in CI and the
compose stack locally, wire fixtures the platform emits, and one case in the k3s end-to-end suite.

## Technical Context

**Language/Version**: TypeScript 5.x on Node 24 (ESM); Scala 3 for the fixtures suite and the
cluster-suite case; bash for the deploy script.

**Primary Dependencies**: `react-router` 8.4 + `@react-router/dev` + `@react-router/node` (Vite 7,
React 19); `openid-client` 6; `zod` 4; `jose` (fake issuer only); `@playwright/test` +
`@axe-core/playwright`. No Express, no UI library, no session library (Web Crypto from `node:crypto`).

**Storage**: none. Sessions are a sealed cookie plus the identity provider's session; an instance
caches access tokens in memory only.

**Testing**: `node --test` for units and the fixture host; Playwright for the browser suite;
munit (`ControlPlaneFixturesSuite` in `controlplane-api`, a case in `EndToEndClusterSuite`, assertions
in `RemoteOverlaySuite`).

**Target Platform**: a Linux container (`node:24-bookworm-slim`, non-root) on the platform's
Kubernetes behind Envoy Gateway; a developer's machine over plain HTTP.

**Project Type**: web application: one npm workspace `console/` with a published package, a private
host and a private end-to-end suite; a kustomize component; Scala test additions.

**Performance Goals**: server render under 500 ms median and client navigation under 300 ms median on
kind (SC-004); an update visible within 5 s of the control plane's report at one read per open page
per 2 s (SC-012); a rolling replacement refusing no request (SC-006).

**Constraints**: no token in the browser (FR-002); every operation works with scripts off (FR-026);
WCAG 2.1 AA with zero axe violations (FR-027a); the host under 500 lines of its own source (SC-009);
no control plane route added (Assumptions); the cookie under 2 KB (SC-003).

**Scale/Scope**: 15 routes, 27 operations, ~35 wire types; two instances per installation; tens of
concurrent people, each holding at most a few streams.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gates are the rules `CLAUDE.md`
states for this repository. Evaluated before research and again after design:

| Rule | Status |
|---|---|
| Effects are inert data; the runtime interprets them | Not applicable: the console has no components. It follows the corresponding rule for a web app — a loader reads, an action mutates, a page renders what was read back (FR-023). |
| Module dependency direction; no platform-side jar reaches a repository by accident | Pass: `controlplane-api` gains a test suite only; nothing in the Scala build depends on `console/`. The package is published from npm like the SDK, with `publish / skip` semantics by being outside sbt. |
| Zero trust is an overlay property | Pass: TLS and the client certificate are the cluster overlay's (`ANKKA_CONSOLE_TLS_DIR`); locally there is no TLS and no caller. The console requires `ankka://gateway` on its serving port and presents `ankka://platform/console` to the control plane (R3, R14). |
| No secret in a journal; write the cluster first | Pass by construction: the console has no journal. The registry password is posted once and never rendered (FR-014). |
| Tests are real, at two levels, and fail loudly | Pass: fakes are scriptable and refuse an unscripted answer? — the fake control plane answers from state, not a script, so this rule is met by the fixture suite refusing drift and the scenario map refusing an untested scenario (SC-010, SC-011). |
| Anything reading config must be overridable in-process; a test binds loopback on an ephemeral port | Pass: the host is configured by environment only; the Playwright fake target starts the host, fakes and control plane on ephemeral ports. |
| A CLI's/host's `main` is a one-line wrapper | Pass: `console/host/server.ts` calls `createConsoleServer(env)`; the server is the package's. |
| Documentation: a page stands alone; samples come from tested code; a new page is in nav and a skill | Pass: R18; the reference page's samples are `docs:start` regions in the fixture host. |
| The realm import is one-shot | Acknowledged: the client is added to the file for new installations and documented as a `kcadm` step for existing ones (FR-035, R14). |
| Nothing in the build writes to a tracked file during publish; only a tag publishes | Pass: the package's version is `0.0.0` in the tree and written by the release job, as the SDK's is (R16). |
| The generation must not be on the pod template; never render an HPA; `IfNotPresent`; `preStop` sleep; `management` port name | Pass where applicable: the console's Deployment is hand-written with `IfNotPresent`, `preStop.sleep: 5s`, ports named `http` and `probe`; it is not an ankka cluster, so no `management` port and no formation label. |

**Post-design re-check**: no violation introduced. One deliberate deviation is recorded in Complexity
Tracking: a third value in `ankka-platform` that is derivable from the other two.

## Project Structure

### Documentation (this feature)

```text
specs/017-control-plane-console/
├── plan.md              # This file
├── research.md          # Phase 0: R1–R18
├── data-model.md        # Phase 1: session, context, streams, wire mirrors
├── quickstart.md        # Phase 1: how to run and prove it
├── contracts/
│   ├── package-api.md           # what a host imports, supplies and adds
│   ├── control-plane-routes.md  # the parity table and status handling
│   ├── stream.md                # server-sent events
│   ├── configuration.md         # env, manifests, overlays, the realm client
│   └── fixtures.md              # wire-type fixture format
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
console/                                   # one npm workspace, one lock file
├── package.json                           # workspaces: package, host, e2e; scripts: typecheck, test, e2e, build
├── package-lock.json
├── Dockerfile                             # multi-stage, node:24-bookworm-slim, uid 1000, the host's build/
├── package/                               # published as `ankka-console`, version 0.0.0
│   ├── package.json                       # exports: ".", "./client", "./server", "./testing", "./styles.css"
│   ├── src/
│   │   ├── index.ts                       # consoleRoutes, consoleMiddleware, interfaces, useConsole
│   │   ├── routes.ts                      # relative() over dist/routes, the route table of package-api.md
│   │   ├── routes/                        # one module per page and resource route (loader/action/default)
│   │   │   ├── front.tsx  organization.tsx  members.tsx  tokens.tsx  organization-new.tsx  project-new.tsx
│   │   │   ├── project.tsx  service.tsx  service-apply.tsx  logs.tsx
│   │   │   ├── auth.sign-in.ts  auth.callback.ts  auth.sign-out.ts
│   │   │   └── stream.service.ts  stream.project.ts
│   │   ├── middleware.ts                  # session read, refresh, origin check, ConsoleContext
│   │   ├── auth/                          # oidc.ts (openid-client config, two addresses), pkce, callback checks
│   │   ├── session/                       # seal.ts (AES-GCM/HKDF), cookie-store.ts, token-cache.ts, token-source.ts
│   │   ├── client/                        # control-plane.ts, schemas.ts (zod mirrors), errors.ts
│   │   ├── stream/                        # sse.ts, service-stream.ts, log-follow.ts (R10)
│   │   ├── server/                        # create-console-server.ts (https, rotation, probe, log, shutdown)
│   │   ├── extensions/                    # types, ErrorBoundary for panels, action rendering
│   │   ├── ui/                            # shared components: forms, status words, error display, href()
│   │   ├── testing/                       # fake-control-plane.ts, fake-issuer.ts, scenarios.ts
│   │   └── styles.css
│   ├── fixtures/control-plane/*.json      # written by ControlPlaneFixturesSuite
│   └── test/                              # node --test: seal, cache, follow, origin, schemas, fixture-host/
│       └── fixture-host/                  # a second host: other prefix, other layout, a store, a panel, an action
├── host/                                  # private: the image
│   ├── package.json
│   ├── react-router.config.ts  vite.config.ts  tsconfig.json
│   ├── app/root.tsx  app/routes.ts  app/layout.tsx  app/entry.server.tsx
│   └── server.ts                          # one call: createConsoleServer(process.env)
└── e2e/                                   # private: Playwright
    ├── playwright.config.ts               # projects scripts-on, scripts-off; target fake|compose
    ├── scenarios.ts                       # spec scenario → test title map (SC-011)
    └── tests/*.spec.ts                    # sign-in, organizations, projects, services, members, tokens, streams, a11y

kustomization/components/console/          # namespace, serviceaccount, secrets, deployment, service, httproute, zero-trust
kustomization/overlays/local/              # + component, consoleAuthority key, replacements
kustomization/overlays/cloud/              # + component, key, replacements, secret delete, realm client patch, image
kustomization/components/keycloak/realm-import.json   # + the ankka-console client
kustomization/deploy-local.sh              # build + kind load + printed address
controlplane-api/src/test/scala/.../ControlPlaneFixturesSuite.scala
controlplane/src/test/scala/.../RemoteOverlaySuite.scala   # + consoleAuthority, secret deletion, image
controlplane/src/test/scala/.../EndToEndClusterSuite.scala # + the console case (curl through the gateway)
.github/workflows/ci.yml                   # + console job and filters
.github/workflows/release.yml              # + image, console-package job
Justfile                                   # + build-console, test-console (one command each)
docs/operate/console.md  docs/platform/console.md  docs/reference/console-package.md   # new
docs/reference/limitations.md  docs/platform/{identity,install-local,install-cloud}.md  docs/concepts/observability.md   # edited
mkdocs.yml  tools/docs/skill/{ankka-platform,ankka-deploy}/SKILL.md
```

**Structure Decision**: One workspace under `console/` keeps the package, the host and the browser
suite on one lock file and one `npm ci`, which is what FR-037 asks and what CI caches. The package
owns everything a second host needs, including the server, so this repository's host is a
configuration and a layout (SC-009). Kubernetes, Scala and docs changes go where their kind already
lives.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| `ankka-platform.consoleAuthority`, a third value derivable from `baseDomain` and `httpsPort` | The realm client's redirect URI and the console's own origin need host and port composed into one string, and a kustomize replacement substitutes one delimited segment; it cannot compose two sources. | Two replacements on one field clobber each other; a patch per overlay duplicates the value in more places than one key does; leaving the port off breaks kind, where HTTPS is on 8443. `RemoteOverlaySuite` asserts the key equals its derivation, so the redundancy cannot drift. |

## Phase 0 and Phase 1 outputs

- `research.md`: R1 framework, R2 routes from a package, R3 server and TLS, R4 sign-in, R5 session,
  R6 CSRF, R7 package boundary, R8 client and fixtures, R9 streaming, R10 log following, R11 test
  doubles, R12 Playwright, R13 end-to-end case, R14 deployment, R15 image and release, R16 package
  publication, R17 shutdown and logging, R18 documentation.
- `data-model.md`, `contracts/*.md`, `quickstart.md` as listed above.

## Risks the tasks must retire first

1. **Routes from a package (R2).** The spike in the fixture host is the first task; its fallback is
   defined and cheap.
2. **Client-certificate inspection in Node (R3).** Prove `subjectaltname` carries `URI:ankka://gateway`
   against a cert-manager-issued certificate in the k3s case before building on it.
3. **Keycloak's discovery through the backchannel (R4).** Confirm the in-cluster address answers
   discovery with the external issuer, as the control plane's key fetch assumes, in the same case.
4. **SSE through Envoy Gateway (R9).** Confirm no response buffering on the kind gateway with a
   two-second event cadence before the stream design is relied on.
