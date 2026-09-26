# Quickstart: TypeScript SDK

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Contracts**: [contracts/](./contracts/)

How to prove the feature works, from the cheapest check to the most expensive. Each tier stands
alone; run them in order when reviewing. Node 24 and Docker are needed from tier 1; a JVM and sbt only
from tier 4; kind only for tier 6.

## Tier 1 — the codec against the fixtures (seconds, no Docker)

```bash
cd sdks/typescript && npm ci && npm run proto
npm run typecheck                                   # tsc --noEmit over src, test, examples
node --test test/encoding-fixtures.test.ts          # every file in proto/fixtures, decoded and re-encoded
```

Expected: every fixture passes both ways; the test named for a fixture with no codec fails rather
than skips (`test_no_fixture_is_missing`'s analogue). Then break one thing on purpose and see it
named: change the double renderer to emit `1` for `1.0` and `numbers.json` fails on bytes.

## Tier 2 — the component model, no sidecar (seconds)

```bash
npm test                                            # node --test 'test/**/*.test.ts' 'examples/**/*.test.ts', slow ones skipped
```

Expected: unit tests of every kind through the unit testkits; `registration.test.ts` shows a
persisting query, an endpoint without `acl` and a class missing a codec each refused — at compile
time via `// @ts-expect-error` lines that fail typecheck if the refusal ever stops, and at runtime for
the plain-JavaScript path; `server-stream.test.ts` drives the servicers through an in-process Connect
client: init, replay, command, snapshot on request, failure leaves state unchanged, strict ordering,
loopback-only binding; `stream-close.test.ts` shows a clean close and an abort both release state.

## Tier 3 — the shopping cart through the real sidecar (a few minutes, Docker)

```bash
sbt -Dankka.cluster.tests=off -Dankka.template.tests=off sidecar/docker:publishLocal shoppingCart/docker:publishLocal
cd sdks/typescript && npm run test:slow             # the Docker-backed tests
```

Expected: `examples/shopping-cart/cart.test.ts`'s integration cases pass — every route, every kind,
a scripted-model agent turn, and state surviving `kit.restart()`; `journal-portable.test.ts` starts
the Scala cart image beside the TypeScript cart on one Postgres and each reads the other's journal
with identical state (SC-003).

Then the documented developer loop, by hand:

```bash
docker compose --profile polyglot up -d             # in the repository root: Postgres and the sidecar
cd sdks/typescript && npm run example               # node examples/shopping-cart/main.ts
curl -s -X POST localhost:9000/carts/c1/items -H 'content-type: application/json' -d '{"productId":"p1","name":"Pen","quantity":2}'
curl -s localhost:9000/carts/c1
ankka console                                       # the service is listed with its components (V7)
```

## Tier 4 — the conformance suite against the TypeScript reference (a few minutes, sbt)

```bash
cd sdks/typescript && npm run conformance           # serves the reference on 127.0.0.1:9010, runs the sbt suite
ANKKA_CONFORMANCE_ONLY='es.*' npm run conformance   # one family
```

Expected: 49 behaviours pass (SC-002). Then break one on purpose — make `refuse` persist an event —
and `es.refusal-persists-nothing` is the one case that fails. The sidecar's own reference must still
pass too:

```bash
sbt 'sidecar/testOnly *ConformanceSuite'            # unchanged, in-process Scala reference
```

## Tier 5 — the package as npm would receive it, on both Node lines (a minute)

```bash
cd sdks/typescript && npm run build && mkdir -p /tmp/ankka-pack && npm pack --pack-destination /tmp/ankka-pack
mkdir -p /tmp/ankka-smoke && cd /tmp/ankka-smoke && npm init -y >/dev/null
npm install /tmp/ankka-pack/ankka-0.0.0.tgz --no-save --ignore-scripts
node --input-type=module -e "import { Ankka, s } from 'ankka'; import { EventSourcedTestKit } from 'ankka/testkit'; console.log('ok')"
```

Expected: the tarball contains `dist/` including `dist/_proto/`, and the import succeeds from an
empty project with nothing but the tarball and its declared dependencies. Repeat with Node 22.22 via
`nvm use 22` or the CI matrix; on Node 20 the import throws a message naming the floor (FR-018).

## Tier 6 — the local installation (fifteen minutes, kind)

```bash
kind create cluster --name ankka --config kustomization/kind.yaml && ./kustomization/deploy-local.sh
cd sdks/typescript && docker build -t sample-shopping-cart-typescript:latest -f examples/shopping-cart/Dockerfile .
kind load docker-image sample-shopping-cart-typescript:latest --name ankka
ankka login && ankka projects create shop
ankka services apply -p shop -f sdks/typescript/examples/shopping-cart/service.json
ankka services get -p shop cart                     # Ready; the sidecar's discovery names ankka-typescript
curl --cacert ~/.ankka/local-ca.crt -X POST https://cart-shop.127.0.0.1.sslip.io:8443/carts/c1/items -d '{"productId":"p1","name":"Pen","quantity":2}' -H 'content-type: application/json'
ankka services scale -p shop cart 3 && ankka services restart -p shop cart
```

Expected: the service reaches `Ready`, scales and restarts with the same commands and words as any
service (SC-006), and `git diff --stat main -- sidecar modules operator controlplane controlplane-api crd protocol`
is empty (FR-033).

## Documentation

```bash
just docs-sync && just docs                         # includes refreshed; every page checked; site built
```

Expected: `docs check` reports no problems with the two new pages, the `ankka-typescript` skill and
the TypeScript sections in the shared skills; every code block on the TypeScript pages is an include
(SC-008). Then the timed run of SC-004: a reader with Node 24 and Docker, no JVM, follows
`get-started/first-service-typescript.md` from an empty directory to a commanded entity; record the
minutes.

## Measurements to record before calling it done

- Wall-clock for the getting-started page, from `mkdir` to the first `curl` answering (SC-004: under fifteen minutes).
- The full `npm test` and `npm run test:slow` durations on a laptop, for the README.
- One command through the TypeScript cart versus the Python cart through the same sidecar, both
  measured the way feature 009 measured SC-003, so the codec's cost is visible and not assumed.

## Reviewer's checklist

- `npm run typecheck`, `npm test`, `npm run test:slow`, `npm run conformance` all green on Node 24;
  `npm run typecheck` and `npm test` green on Node 22.22.
- Every fixture in `proto/fixtures` is named in the fixture test's output; none skipped.
- `diff -r protocol/src/main/protobuf sdks/typescript/proto/src/main/protobuf` and the fixtures diff are empty.
- `git diff --stat main -- sidecar modules operator controlplane controlplane-api crd protocol` is empty.
- No `enum`, decorator, parameter property or `reflect-metadata` anywhere under `sdks/typescript`
  (`grep -rnE '^\s*(export )?enum |@[A-Za-z]+\(|constructor\((private|public|readonly)' src examples test` finds nothing).
- Every `import` of a local `.ts` file names the extension; `node examples/shopping-cart/main.ts` runs with no flags.
- `package.json`: `version` is `0.0.0`, `files` is `["dist"]`, `exports` has `types` first, `repository.directory` is `sdks/typescript`, `engines.node` is `>=22.22.0`.
- The CI job runs on the sidecar image the same job built, never a literal tag from elsewhere.
- Each new docs page is in `mkdocs.yml`'s `nav` and in the `ankka-typescript` skill's `pages:`; each shared skill with a "Python differences" section has a "TypeScript differences" section.
- `CLAUDE.md` names the commands, the module, and the one manual publish and why.
