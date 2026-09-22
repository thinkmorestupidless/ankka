# Quickstart: proving the feature works

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Contracts**: [contracts/](./contracts/)

Six tiers, cheapest first. Each is a gate for the next. Tiers 1–4 need only Docker; tier 5 needs
Node 22; tier 6 needs the kind cluster.

## Tier 1 — the protocol, both ends in one JVM (seconds, no Docker)

```bash
sbt protocol/compile                                  # generated code, warning-free
sbt 'sidecar/testOnly *ProtocolSuite'
```

Expected: every conversation in `contracts/protocol.md` round-trips between `GrpcConversation`
and `ProcessDouble`; a reply with the wrong `command_id`, events from a `read_only` handler, and a
snapshot nobody asked for are each a `ProtocolViolation`; a bind on a non-loopback interface is
refused; discovery with protocol `99.0` is refused with both versions in the message and a
`ReportError` at the double.

## Tier 2 — remote hosts against the double (about a minute, Docker for Postgres)

```bash
sbt 'sidecar/testOnly *RemoteEntitySuite *RemoteWorkflowSuite *RemoteProjectionSuite *RemoteAgentSuite'
```

Expected, from the spec's stories and edge cases:

- S1.1–S1.4: persist, refuse, recover after `restartService()`, snapshot at the interval.
- Edge cases: the double restarted mid-stream → the instance re-inits on its next command; the
  sidecar restarted → the double receives fresh `Init`s; a handler that never replies → the
  command times out, nothing is persisted, the stream is closed; a late reply → dropped; ten
  commands to one id → ten sequences in order; passivation → the double sees the stream close and
  releases the state.
- S3.1–S3.6 for key value, workflow, view, consumer, timed action, and the client callback with
  a child span.
- S4.1–S4.4 for the agent with a scripted provider in the sidecar: the tool runs at the double.

## Tier 3 — the conformance suite against the Scala reference (a minute)

```bash
sbt 'sidecar/testOnly *ConformanceSuite'
```

Expected: every behaviour in `contracts/conformance.md` passes in-process. This is the baseline
that defines what tier 5 must match.

## Tier 4 — the platform's own suites still pass, unchanged

```bash
sbt -Dankka.cluster.tests=off test
sbt 'operator/testOnly *RenderingSuite'                  # two containers for hosting=process
sbt 'controlPlane/testOnly *DescriptorSuite *ProjectorSuite'   # hosting, protocol, refusals
```

Expected: no existing test modified (FR-026, SC-005); `Rendering` puts the credential `envFrom`
on `runtime` only, the model variables on `runtime`, everything else on `app`, the readiness probe
by name on `management`; a descriptor with `hosting: process` and no `protocol`, or with
`ANKKA_PROCESS_PORT` in its env, is refused at the CLI *and* the control plane; `protocol: 2.0`
against a `1.x` platform is `Unavailable` with both versions.

## Tier 5 — the TypeScript SDK (a few minutes, Node 22 and Docker)

```bash
cd sdks/typescript
npm ci && npm run proto && npm test                     # unit testkit, no sidecar
npm run conformance                                     # starts examples/shopping-cart, runs tier 3 against it
```

Expected: `npm test` proves the effect builders and the unit testkit (a query cannot persist is a
type error, exercised with `// @ts-expect-error`); `npm run conformance` passes every behaviour,
including `es.journal-portable` (SC-002) and `agent.session-survives-process-restart`. Time from
an empty directory to the first entity commanded, following `docs/polyglot.md`, is under fifteen
minutes with no JVM installed (SC-007) — measured by a person, once, before the feature is called
done.

## Tier 6 — the local installation (fifteen minutes, kind)

```bash
kind create cluster --name ankka --config kustomization/kind.yaml
./kustomization/deploy-local.sh                          # now also builds and loads ankka-sidecar
sbt 'sidecar/testOnly *SidecarClusterSuite'              # k3s, from an empty cluster
ankka services apply -f sdks/typescript/examples/shopping-cart/service.json -p checkout
ankka services get cart -p checkout                      # hosting: process, protocol: 1.0, Ready
ankka services expose cart -p checkout
curl --cacert ~/.ankka/local-ca.crt https://cart-checkout.127.0.0.1.sslip.io:8443/carts/c1/items -d '{"productId":"p1","quantity":1}'
ankka services apply -f ... --instances 3 && kubectl get pods -n checkout -w   # the first pod survives
ankka services restart cart -p checkout                  # one at a time; a request loop sees 0 refusals
```

Expected: S2.1–S2.6. The k3s suite asserts, against a real API server: two containers, readiness
false with the app container killed (`crictl` inside the node) and the sidecar still a member,
scale 1→3 with the original pod untouched, a restart with zero refused requests from a continuous
loop, a protocol connection attempted from another pod in the namespace refused (SC-008), and the
credential secret absent from the app container's environment (`kubectl exec … env`).

## Measurements to record before calling it done

| what | how | criterion |
|---|---|---|
| one command, process-hosted vs in-process | the feature 007 harness (HTTP in, entity, journal, reply), both modes, same machine | ≤ 2× (SC-003); the difference visible as unattributed time in the console |
| restart refusals | a request loop during `services restart` on the kind cluster | 0 (SC-004) |
| time to first entity, TypeScript | a person, from an empty directory, with `docs/polyglot.md` | < 15 min (SC-007) |

## Reviewer's checklist

- [ ] `runtime` has no dependency on `protocol`, gRPC, `http` or `agent` (`sbt 'runtime/dependencyTree'`).
- [ ] Every published module's `libraryDependencies` is unchanged.
- [ ] No existing test file is modified; `git diff --stat main -- '*Suite.scala'` lists only new files.
- [ ] `EventSourcedEffect.materialise` and `RemoteEffect.materialise` agree on `Fail`, `NoReply`, `Reply` — the conformance suite's `es.refusal-persists-nothing` and `es.no-reply` pass in both modes.
- [ ] The sidecar's gRPC servers bind `127.0.0.1` and nothing else; the operator's rendering sets no other address.
- [ ] `ANKKA_PROCESS_*` / `ANKKA_SIDECAR_*` are refused in `ServiceSpec.problems` with the same message shape as `ANKKA_HTTP_PORT`.
- [ ] The k3s suite names the sidecar image by `BuildInfo.version`, never a literal tag.
- [ ] `-Dankka.conformance.target` is forwarded in `Test / javaOptions`.
- [ ] The `.proto` files under `protocol/` and `sdks/typescript/proto/` are identical (`diff -r`), checked by CI.
- [ ] `README.md` states plainly that the TypeScript SDK is the second language and the conformance suite is how a third arrives; `CLAUDE.md` gains the new traps found on the way.
