# ankka console

The installation's web console. `package/` is the `ankka-console` npm package: the control plane
client, the sign-in and session machinery, and the pages as routes a host mounts. `host/` is the thin
application built into the `ankka-console` image. `e2e/` is the Playwright suite.

See `docs/reference/console-package.md` for mounting the package in a host of your own and
`docs/platform/console.md` for installing the console.

```bash
npm ci && npm run typecheck && npm test && npm run e2e
npm run dev          # against docker compose's Keycloak and `sbt controlPlane/run`
```
