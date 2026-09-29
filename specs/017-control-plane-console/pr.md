## A console for a deployed installation (feature 017)

An installation now has a web console at `https://console.<base domain>`. A person signs in through the
installation's Keycloak and manages organizations, projects, members, deploy tokens and services as
themselves, through the same control plane API the CLI uses. The limitations page no longer says the CLI
is the only client for a deployed installation.

### The shape

- **A package and a thin host.** `console/package` is `ankka-console` on npm: the control plane client,
  sign-in and sessions, and the pages as React Router 8 routes a host mounts under any prefix, with panels,
  actions and hidden operations as extension points. `console/host` is the image, 73 lines. A fixture host
  under another prefix, layout and session store proves a second host (ankka-cloud) needs no package change.
- **No token in the browser.** The session is a sealed cookie carrying only the refresh token (under 2 KB);
  access tokens stay in each instance's memory; sign-out ends the Keycloak session server to server. There
  is no session store, so the two instances are interchangeable.
- **Server-rendered, and every operation a plain form.** Pages render before any script runs; with scripts,
  navigation does not reload and service pages and logs update live over server-sent events fed by polling.
  Everything works with scripts disabled.
- **A platform workload.** Its own namespace, no Kubernetes grant, TLS to the gateway requiring the
  gateway's certificate, mutual TLS to the control plane as `ankka://platform/console`, a network policy
  admitting only the gateway. One new `ankka-platform` key, `consoleAuthority`, held to its derivation by
  `RemoteOverlaySuite`.
- **Released with everything else.** The `images` job publishes `ankka-console`; a new `console-package` job
  publishes the npm package.

### Proof

| What | Result |
|---|---|
| Unit tests (`npm test`) | 100 pass |
| Playwright, fake target, scripts on and off | 77 pass; every acceptance scenario has a test; all 37 control plane routes exercised |
| Playwright, compose Keycloak and a real control plane | all pass |
| Accessibility | zero WCAG 2.1 AA violations on every page; every operation completed by keyboard alone |
| Render median / client navigation median (compose) | 42 ms / 97 ms, against 500 / 300 |
| Status change / new log line shown (fake target) | within 5 s |
| `ControlPlaneFixturesSuite` + package decode | 24 wire types, full and minimal, all decode |
| `RemoteOverlaySuite` | 19 pass |
| `ControlPlaneClusterSuite` (k3s) | K3S_RESULT |
| `just docs` | 71 pages, no problems |

The compose run found three real disagreements the fake had hidden, all fixed: creates answer 204 with no
body, an omitted token lifetime is the 90-day default, and a new token's secret was hidden until the
listing's projection caught up.

### Before merging, and after the first release

- **The first npm publish of `ankka-console` is by hand**, after that tag's `publish` job, then attach the
  trusted publisher; `CLAUDE.md`, Publishing, has the commands.
- **The `ankka-console` package on ghcr.io is created private**; make it public once, as for the other images.
- **An installation whose realm predates the console** needs its client added with `kcadm`, then its Secret
  created; `docs/platform/console.md` gives both commands. The local deploy script does this itself.
- **A cloud installation** creates `ankka-console-secrets` out of band once Keycloak has generated the client
  secret; until then the console does not start and nothing waits on it.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
