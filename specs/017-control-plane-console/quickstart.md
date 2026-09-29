# Quickstart: A Console for a Deployed Installation

How to run and prove the feature. Details of what each step exercises are in `contracts/`.

## Prerequisites

- Node 24 and npm; Docker; `sbt` and a JDK 21 for the control plane and the Scala suites; `kind`
  and `kubectl` for the local platform.
- Playwright's Chromium: `cd console && npx playwright install --with-deps chromium` (once).

## 1. Run it against a local control plane (stories 1–4, 7)

```bash
docker compose up -d
ANKKA_AUTH_ISSUER=http://localhost:8081/realms/ankka sbt controlPlane/run
cd console && npm ci && npm run dev
```

Open `http://localhost:3000`, sign in as `dev` / `dev`, create an organization and a project, apply
`samples/shopping-cart/service.json` to a service (it stays `UpdateInProgress` — a control plane run
this way reaches no cluster), pause and resume it, invite an email, create a deploy token and use it
with `ankka services list`. Check with the CLI after each step:

```bash
ankka config set url http://localhost:9000 && ankka login
ankka organizations list && ankka projects list && ankka services list -p <project>
```

## 2. The package and host tests (every change)

```bash
cd console
npm run typecheck          # both workspaces
npm test                   # node --test: sealing, cache, dedupe, origin check, schemas vs fixtures, fixture host
npm run e2e                # Playwright against the fake control plane and fake issuer: scripts on and off, axe, keyboard
```

Expected: every scenario in `e2e/scenarios.ts` has a test; zero axe violations; the report under
`console/e2e/report/` with traces on failure.

## 3. The browser suite against the real stack (SC-003, SC-004, SC-012)

With step 1's compose stack and control plane running:

```bash
cd console && CONSOLE_E2E_TARGET=compose npm run e2e
```

Expected: the same tests pass; the token inspection finds nothing; the timing checks report medians
under 500 ms (render) and 300 ms (navigation); a status change and a log line each appear within
5 seconds.

## 4. The fixtures hold both sides (SC-010)

```bash
sbt 'controlPlaneApi/testOnly *ControlPlaneFixturesSuite'          # fails on drift, names the type
sbt -Dankka.docs.update=true 'controlPlaneApi/testOnly *ControlPlaneFixturesSuite'   # regenerate
cd console && npm test -- --test-name-pattern fixtures
```

## 5. Deployed on kind (story 5)

```bash
kind create cluster --name ankka --config kustomization/kind.yaml
./kustomization/deploy-local.sh
```

The script ends by printing `https://console.127.0.0.1.sslip.io:8443`. Open it in a browser that
trusts `~/.ankka/local-ca.crt`; sign in as `dev`. Then:

```bash
kubectl -n ankka-console get pods                          # 2/2 Ready
kubectl -n ankka-console delete pod -l app.kubernetes.io/name=ankka-console --wait=false   # session survives
kubectl -n ankka-controlplane run probe --rm -it --image=curlimages/curl -- \
  curl -sk --max-time 3 https://ankka-console.ankka-console.svc:9000/   # refused at the network
```

## 6. The cluster suites (FR-039, R14's overlay assertions)

```bash
sbt 'controlPlane/testOnly *RemoteOverlaySuite'           # both overlays render; consoleAuthority agrees; secrets deleted in cloud
sbt 'controlPlane/testOnly *EndToEndClusterSuite'         # k3s: sign in through the gateway with curl, create an organization, list services
```

## 7. Docs

```bash
just docs
```

Expected: the limitations page no longer says there is no console for a deployed installation; the
three new pages are in the nav and in the `ankka-platform` and `ankka-deploy` skills; every
`service.json` block validates.
