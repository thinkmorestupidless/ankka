# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## How this guidance is organised

This file holds what every session needs. Everything specific to one part of the tree lives in
`.claude/rules/<topic>.md`, each with a `paths:` frontmatter list, so Claude Code loads it only once
a file in that part of the tree is read. **Before planning a change to an area, read its rule file
yourself** — a plan made before any matching file is opened has not seen it yet.

| Rule file | Covers |
|---|---|
| `runtime.md` | Pekko and r2dbc traps, workflows, entity hosts, endpoints and `ToResponse`, shutdown order |
| `cluster-and-tls.md` | cluster formation overlays, zero trust / `RotatingTls`, bootstrap, database TLS |
| `observability.md` | call attribution and topology, tracing, the recorder, the local console |
| `messaging.md` | topic sources, consumer groups, view versions, Kafka, `publishAll`, graph deltas |
| `agents.md` | agents, judgments, autonomous agents, scripted model providers |
| `secrets.md` | service secrets, the secret key, project secrets |
| `control-plane.md` | control plane invariants, Keycloak identity, deploy tokens, registry credentials, `auth-oidc`, CLI |
| `kubernetes.md` | operator and `AnkkaService`, CNPG, cert-manager, Gateway API/Envoy, k3s suites, local and remote deploys, schema/DDL |
| `sidecar.md` | the polyglot sidecar, remote hosts, conformance, the Python SDK, Docker quirks |
| `wasm.md` | the WebAssembly module mode and the Rust crate |
| `sdk-typescript.md` | the TypeScript SDK under Node type stripping, Connect, packing |
| `grpc.md` | gRPC endpoints, balancing, status mapping, streaming tests |
| `web-hosting.md` | `"hosting": "web"`, the proxy, `proxy-core` |
| `console.md` | the `ankka-console` package and host, Playwright, the fake control plane |
| `build-and-release.md` | sbt build traps, templates (`ankka.g8`, `ankka init`), every publishing channel, compatibility |
| `docs.md` | the `docs/` tree, `tools/docs`, generated reference, skills, marketplace |
| `testing.md` | living features (speckit-bdd), `LogCapturing`, long suites |

When you learn a trap worth keeping, add it to the rule file for its area, not here. Only a rule that
bites anywhere in the tree belongs in this file.

## What this is

`ankka` reimplements [Akka's](https://doc.akka.io/) component model — a serverless
platform for agentic AI — in Scala 3 on Apache Pekko. Pekko is the Apache 2.0 fork of
Akka 2.6, chosen so the programming model carries no BSL constraint.

It was called `nakka` until September 2026; git history before the rename reads `nakka`, and a
`~/.nakka/` or a kind cluster named `nakka` on a machine is a leftover the code never reads.

`docs/` is the user-facing documentation — public, for developers building, deploying and operating
services on ankka — and `README.md` is a landing page into it. Start with
`docs/concepts/architecture.md`, `docs/concepts/designing-services.md` and
`docs/reference/limitations.md` (the honest "not implemented" list) before making design decisions.
Everything under `docs/` is public; internal design treatments live in the private
`ankka-deployments` repository (`design/`), not here.

## Commands

Docker is required — integration suites share a Postgres per test JVM, and one starts Kafka,
via testcontainers. No API key is needed.

```bash
sbt test                          # everything, including three k3s suites (CNPG inside; minutes, not
                                   # seconds) and the shopping cart image they deploy
sbt -Dankka.cluster.tests=off test  # skip the k3s suites AND the sample image build; about a minute
sbt agent/test                    # one module: core sdk runtime http grpc agent testkit telemetryOtlp
sbt grpc/test                     # gRPC endpoints: offline suites, and features/grpc/ through GherkinSuite
sbt telemetryOtlp/test            # telemetry export against a fake collector; TelemetryStoreSuite pulls
                                   # grafana/otel-lgtm (~850 MB) and takes minutes
sbt -Dankka.spikes=on 'grpc/testOnly *GrpcTlsSpike'                  # mutual TLS from RotatingTls's managers
caffeinate -i sbt -Dankka.spikes=on 'controlPlane/testOnly *GatewayGrpcSpike'   # gRPC through the gateway (k3s)
sbt operator/test                 # the Kubernetes operator (one k3s suite)
sbt controlPlane/test             # control plane: controlPlaneApi crd controlPlane cli operator
sbt docker:publishLocal           # build the images (operator, controlPlane, shoppingCart); others skip
sbt buildAll                      # format check, compile, test, every image; stops at the first failure
GRAALVM_HOME=... sbt cli/GraalVMNativeImage/packageBin   # the CLI as one executable, no JVM:
                                   # cli/target/graalvm-native-image/ankka; cli/native-smoke.sh checks it
sbt shoppingCart/test             # samples: shoppingCart multiAgentPlanner
sbt proxyCore/test proxy/test     # a web-hosted service's proxy: rules and engine (JDK only), then TLS
sbt -Dankka.template.tests=web 'cli/testOnly *WebTemplateSuite'   # `ankka init --language web`; needs node, npm
caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' 'controlPlane/testOnly *BrokerKeptFeatures'
                                   # features/broker on k3s, one suite per file (Broker<File>Features):
                                   # Strimzi, one Kafka, real carts, a probe holding a service's certificate
sbt sidecar/test                  # the polyglot sidecar: protocol, remote hosts, one k3s suite
sbt 'sidecar/testOnly *ConformanceSuite'                                       # the Scala reference, in-process
sbt 'sidecar/testOnly *ClientRequestSuite'                                     # a process's call to another service, as the sidecar makes it
sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.target=127.0.0.1:9010   # a process speaking the protocol
cd sdks/python && uv sync && uv run pytest -q && uv run mypy && uv run conformance   # the Python SDK, end to end
cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && npm run test:slow && npm run conformance   # the TypeScript SDK, end to end
cd sdks/rust && cargo test --workspace && cargo test -p shopping-cart --features slow && ./conformance.sh   # the Rust crate, end to end
sbt 'sidecar/testOnly *WasmHostSuite'   # the module mode's host; its end-to-end case needs cargo on PATH
sbt 'cli/testOnly *ActionSuite'   # the GitHub Action's install and configure steps, run as bash
sbt -Dankka.template.tests=python 'cli/testOnly *PythonTemplateSuite'   # `ankka init --language python`
                                   # (typescript, rust likewise); needs uv, node and npm, or cargo
sbt 'agent/testOnly com.thinkmorestupidless.ankka.agent.CompactionSuite -- *transcript*'   # one case (munit glob)
sbt compile                       # warning-free by construction: -Werror (compile only, not doc), -Wunused is on
just features                     # speckit-bdd check: the living features, the glossary and the specs that name them
just docs                         # check every page, build the site
just docs-sync                    # refresh included samples, generated tables and the rendered skill
just docs-reference               # rewrite the CLI and control plane route pages the JVM generates
just build-console                # the ankka-console package, then the host
just test-console                 # its type check, unit tests and Playwright suite against in-process fakes
just test-console-compose         # the Playwright suite against compose's Keycloak, a running control plane and `npm run dev`
cd console && npm run dev         # the console on :3000 against compose's Keycloak and `sbt controlPlane/run`
```

munit's `--` filter matches the full test name, suite included: a glob needs a leading wildcard
(`'*es.*'`), or it matches nothing and the suite reports green with zero tests.

Running the samples needs the bundled Postgres:

```bash
docker compose up -d
sbt shoppingCart/run              # HTTP on :9000
ANTHROPIC_API_KEY=sk-ant-... sbt multiAgentPlanner/run
ANKKA_AUTH_ISSUER=http://localhost:8081/realms/ankka sbt controlPlane/run   # compose runs Keycloak on 8081
ankka login                                                                   # dev / dev, in a browser
sbt 'cli/run services list --url http://localhost:9000 -p checkout'   # after `ankka login`
```

A two-node cluster on one machine (the default cluster port is random, so fix the first one's):

```bash
ANKKA_CLUSTER_PORT=17355 sbt shoppingCart/run
ANKKA_CLUSTER_SEED_NODES=pekko://ankka@127.0.0.1:17355 ANKKA_HTTP_PORT=9001 sbt shoppingCart/run
```

`AnthropicProviderSuite` skips unless `ANTHROPIC_API_KEY` is set, and `JevProviderLiveSuite` unless
`TYPESAFE_API_KEY` is. Everything else is deterministic and offline.

**A full `sbt test` is an hour of wall-clock, and a laptop sleeps.** A sleeping Mac pauses Docker
while the test JVM's deadlines keep counting. Run long suites under `caffeinate -i sbt test`, and read
a failure with an absurd duration (`MultiNodeClusterSuite … 20549s`) as the machine's — rerun it awake.

**Tests are serialized deliberately** (`Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)`
and `Test / parallelExecution := false` in `build.sbt`). Overlapping suites each start their own
container and contend: one suite measured 147s in parallel versus 6s alone. Do not "optimise" this back.

```bash
sbt scalafmtAll scalafmtSbt        # format; scalafmtCheckAll verifies
git config core.hooksPath .githooks   # once per clone (`just hooks`): refuse an unformatted commit
cs install scalafmt                   # the hook uses the scalafmt CLI when it is on PATH (~1s); without it, sbt (~15s)
```

The hook checks only staged `.scala` and `.sbt` files with the same `.scalafmt.conf` sbt reads.
`SortModifiers` is deliberately absent from `.scalafmt.conf` (it rewrites `private[ankka] final` to
`final private[ankka]` and churned 99 declarations).

The `Justfile` is deliberately thin: every recipe is one command or a call to `deploy-local.sh`, which
owns the logic and the guards. A recipe that reimplements a step becomes a second copy to keep in step.

**CI builds pull requests, and only the parts a pull request touched.** The `changes` job in
`.github/workflows/ci.yml` maps changed paths onto the jobs (`build`, `features`, `docs`, `sdk-python`,
`sdk-typescript`, `sdk-rust`, `console`, `template-scala`); a skipped job counts as a pass. Branch
protection requires the `template-scala` *summary* job, never the matrix's expanded names. `build` is
a matrix too, for speed — `build (testkit)`, `build (sidecar)`, `build (rest)` behind a required `build`
summary — and `rest` is defined by subtraction (`set testkit / Test / test := {}`, likewise `sidecar`),
so a new module's suites run there unlisted; a module given its own runner must be subtracted from it.
`.github/ci-coverage.py` holds the map to the tree both ways: every tracked file is claimed by some
filter (a file no job needs goes under `unchecked` with its reason) and every pattern matches some
file. So a job that starts reading a new part of the tree needs its filter extended, and a new
top-level directory fails CI until someone decides which job reads it. `main` merges only when every
job passed on a head up to date with `main`; pushes to `main` are not built. A full run on demand is
`gh workflow run ci`.

**The k3s suites run in `.github/workflows/cluster.yml`, nightly on `main` and on demand — never on a
pull request** — one runner per suite, so a run takes as long as the slowest suite (~20 min) rather
than the two hours they take in a row. The matrix is `.github/cluster-suites.py`'s: every concrete
class in a test file that reads `ankka.cluster.tests`, so a new suite runs unlisted; `changes` runs the
script on every pull request. Each runner is `sbt '<project>/testOnly <class>'`, which builds that
module's images. The nightly run passes `-Dankka.coldstarts=20` (SC-002; five by default). Check a
platform change before merging with `gh workflow run cluster --ref <branch>`, one suite with
`-f suite=<Name>`. Under `CI` the sidecar suite fails, rather than skips, when the Rust module does not
build.

## Architecture

### Effects are inert data; the runtime interprets them

The organising idea. Every handler returns a *description* of what should happen —
building one performs no I/O, reads no state and calls no model:

```scala
effects.persist(ItemAdded(item)).thenReply(_ => Done)
stepEffects.updateState(s).thenTransitionTo(deposit)
effects.systemMessage("...").tools(getWeather).thenReply()
```

This is why unit tests need no runtime, and why the runtime is free to decide *how*.
`modules/core` owns the algebra with no Pekko dependency at all. Where both the runtime and the
testkit reduce an effect they share one function (`EventSourcedEffect.materialise`,
`KeyValueEffect.materialise`) so they cannot disagree about semantics. The operator follows the same
idea: `Action` values are inert descriptions of cluster mutations and `Fabric8Executor` is the only
thing that performs them.

### Module dependency direction

```
core → sdk → runtime → {http, agent} → testkit → samples
runtime → telemetry-otlp → {sidecar, controlplane, samples}   (http, grpc, testkit are Test-only deps)
http → grpc → samples                                    (grpc-fixtures and testkit are Test-only deps)
http → auth-oidc → {controlplane, sidecar}               (the one token verifier; nimbus lives here only)
core → controlplane-api → cli
crd → operator                                           (no ankka dependencies at all)
controlplane-api + crd + sdk + runtime + http + telemetry-otlp → controlplane
                                  (cli, operator, testkit are Test-only deps)
protocol → nothing                                       (generated ScalaPB; -Wunused off, -source:3.3)
runtime + http + agent + protocol + telemetry-otlp → sidecar   (testkit and operator are Test-only deps)
proxy-core → nothing                                     (the JDK's HTTP server and client only)
proxy-core + runtime + http → proxy                      (test-pki and testkit are Test-only deps)
controlplane-api + proxy-core → cli
```

- `runtime` depends on `sdk`, not the reverse: the runtime interprets the SDK's descriptors.
  `ComponentClient` therefore lives in `sdk` over a `CallTransport`, with `runtime` supplying the
  sharding-backed implementation. Components receive it through their context — ankka has no
  container; a component is constructed by its own companion.
- `runtime/remote` holds the remote hosts and the `Conversation` trait in plain Scala values, so
  `runtime` never sees the generated protocol; `sidecar` translates over grpc-java.
- `grpc` sits above `http` because it uses `Acl`, `Caller`, `Principal` and `EndpointClients`
  unchanged; `runtime` never names it (it knows only `RuntimeExtension.grpcAddress`). Generated
  ScalaPB code lives in projects of its own (`grpc-fixtures`, `samples/shopping-cart-api`).
- `controlplane-api` depends on `core` only — no Pekko — so the CLI carries no actor system, database
  driver or Kubernetes client. It holds the wire types *and* the descriptor validation rules, so both
  ends apply the same checks.
- `controlplane` takes `cli % Test` and `operator % Test` so one suite can drive the real CLI against a
  real control plane, and `EndToEndClusterSuite` both halves against one k3s cluster — the only tests
  that catch either wire format disagreeing. Its tests also build the shopping cart image first
  (`sampleImageForClusterTests`): a build-level *task* dependency, not a classpath one, gated on
  `-Dankka.cluster.tests`.
- `crd` depends on **nothing**, not even `core`: both the control plane and the operator hold the
  `AnkkaService` resource without inheriting each other's world. It also holds `Hostnames`, the one
  derivation of an exposed service's hostname. `operator` depends only on `crd` and a Kubernetes
  client, so "the operator cannot reach into the control plane" is a build-level fact.
- **Which variables are the platform's is said once, in `core`'s `PlatformVariables`.** The operator
  **compiles the same source file** (`Compile / unmanagedSources` in `build.sbt`) so its only ankka
  dependency stays `crd`; the file therefore imports nothing outside the standard library.
  `PlatformDeclarationSuite` fails on a second copy or on a hand-kept list of `ANKKA_` literals.

### Component hosting

| Component | Hosted as |
|---|---|
| Event Sourced Entity | `EventSourcedBehavior` in cluster sharding |
| Key Value Entity | `DurableStateBehavior` in cluster sharding |
| View | Pekko Projection → Postgres row table, or a Kafka consumer group |
| Consumer | Pekko Projection, or a Kafka consumer group |
| Workflow | `EventSourcedBehavior` whose events *are* step transitions |
| Timer | Postgres table + cluster-singleton sweeper; a recurring one is the same row with `due_at = 'infinity'` (`.claude/rules/runtime.md`) |
| Agent | Sharded per **session id**, serialized per conversation via a stash |
| Autonomous agent | Sharded per **instance id**, `remember-entities`; its state an `ankka-agent-instance` entity, its tasks `ankka-task` entities |
| HTTP Endpoint | pekko-http route tree: request routes, SSE routes, and socket routes (a WebSocket per socket, its handler on one virtual thread for its life) |
| gRPC Endpoint | grpc-java on its own port, every kind of method through one binding (`grpc/Binding`) |

Entity and workflow hosts pre-serialize domain values into `JournalRecord` / `StateRecord` via Pekko
event and snapshot adapters, so the journal holds ankka's JSON under ankka's manifest.

A service can also be hosted outside the JVM: a process behind the **sidecar** (`sidecar.md`), a
**WebAssembly module** the sidecar image loads (`wasm.md`), or any HTTP program beside the platform's
**proxy** (`web-hosting.md`).

### Registration and handler identity

There is no classpath scanning. Components reach the runtime only by being handed over
explicitly, so an unregistered one fails at startup rather than at its first request.
Handlers are declared on typed companions:

```scala
val addItem = command("add-item")(_.addItem)   // "add-item" is the wire name
val getCart = query("get-cart")(_.getCart)     // query accepts only a ReadOnlyEffect
```

`query` taking only a `ReadOnlyEffect` makes "cannot persist" a compiler guarantee.
**The wire name is declared separately from the Scala method name on purpose.** It is a versioning
boundary: renaming a method must not change the protocol, or in-flight requests break during a
rolling deploy and persisted timers break permanently. A macro deriving it has been declined for this
reason. The same rule applies to any new wire name (`create-for-owner` is separate from `create` so an
in-flight create survives a rolling update).

### The RuntimeExtension seam

`ankka-runtime` must not depend on `ankka-http` or `ankka-agent`, so anything that needs the service
to exist before starting plugs in as a `RuntimeExtension`: `HttpServer`, `ProjectionRuntime`,
`TimerRuntime`, `AgentRuntime`. Extensions take factories rather than instances where a dependency
needs the `ActorSystem`.

`EntityProtocol.Command` is shared by every sharded host so the transport needs one sharding key
type. **`EntityProtocol.ModuleCommand` opens that hierarchy**, so the compiler no longer reports
non-exhaustive matches over `Command` — every host must handle unexpected commands explicitly, and
for `InvokeStream` that means *replying*, or a caller waiting on a token stream hangs forever.

### Virtual threads

Endpoints, workflow steps, consumers, timers and agent loops all run on `AnkkaExecutors.virtual`.
That makes the blocking `ComponentClient.invoke` free — an await parks the virtual thread — so tool
loops and workflow steps are ordinary sequential code. It is also what makes the HTTP
`RequestContext` sound as a `ThreadLocal`: one request per thread, cleared on the way out. **Work
handed to another thread cannot see a thread-local** — request context, trace and call origin alike.

### Where the rest is

- Cluster formation is an overlay per means of execution (`local`, `kubernetes`), selected by
  `ANKKA_CLUSTER_MODE`; in Kubernetes every port is mutual TLS from cert-manager and the caller is read
  from the client certificate's `ankka://<project>/<service>` URI. → `cluster-and-tls.md`
- Reconciliation is split: the control plane projects desired state into an `AnkkaService` resource,
  and an in-cluster operator (deliberately *not* an ankka application) owns everything below it. →
  `kubernetes.md`
- The control plane *is* an ankka application; Keycloak authenticates, the `Organization` entity
  authorizes; cross-entity checks live in endpoints, never handlers. → `control-plane.md`
- DDL has one canonical copy, `kustomization/components/postgres/ddl/`; a new file is named in seven
  lists. → `kubernetes.md` (Schema). Within a supported version range the schema is additive.

## Testing

Two levels, both real. `EventSourcedTestKit` / `KeyValueEntityTestKit` drive one component with no
actor system, cluster or database — milliseconds — while still round-tripping inputs and replies
through the component's own serializers. `AnkkaTestKit` boots the whole service against a throwaway
database — its own, copied from a schema template in one Postgres container the test JVM's kits share
(`SharedPostgres`, lingering 30s after its last kit); `restartService()` drops every entity from
memory, so a test can prove durability rather than caching. `TestModelProvider` answers from a script and **fails loudly** when it runs out.

**Ask of every check, before relying on it: could this pass while the thing it checks is false?**
It applies to a test, a CI step, a smoke test and a readiness wait alike, and most of the traps in
the rule files are a "yes" nobody asked: the deploy smoke test that accepted a 404, the conformance
filter that matched nothing and reported green, the overlay suite that found a string *somewhere*,
the `eventually` satisfied by a stale row. The cheapest proof a check can fail is to break the
behaviour once and watch it go red. When a spec-kit feature is implemented, each acceptance scenario
should end up as a test that fails without the feature (living features: `testing.md`).

## Traps that bite anywhere

- **Forked tests do not inherit sbt's `-D` properties.** `Test / fork := true`, so a switch passed as
  `sbt -Dfoo=bar test` is set in a JVM that runs no tests. Any new test switch must be forwarded in
  `Test / javaOptions` — `-Dankka.cluster.tests=off` and `ankka.conformance.shape` were both silent
  no-ops for two features. Read what a run says it ran, not what the script says it runs.
- **A test that binds a fixed port cannot run beside the documented workflow.** Every HTTP suite uses
  `HttpServer.at("127.0.0.1", 0)`, never `HttpServer.of` (port 9000), so it runs beside a developer's
  `sbt shoppingCart/run`.
- **An `eventually` must wait for the thing it asserts.** Retry on the value that changes, assert the
  identity that does not; an assertion outside the retry reads a stale projection row on a slow machine.
- **A fieldless Scala 3 enum encodes as `{"type":"Ready"}` under ankka's shared codec config.** Give a
  status-word enum an explicit string `JsonValueCodec` **in its companion object**
  (`ServiceLifecycle` does), so every derivation site sees the same one.
- **jsoniter reads a JSON `null` on an `Option` field as *absent*, and applies the default.** An
  `Option` whose default is not `None` cannot express "none". Say "none" positively (`http: false`)
  and keep the value a plain type. `DescriptorSuite` pins this.
- **A `lazy val` where order or a system matters.** A `val` listing functions defined below it lists
  nulls, and an extension looked up eagerly in a constructor breaks every suite that builds the class
  without an actor system.
- **`export` and `given` are keywords in Scala 3**: a parameter, helper, field or loop variable with
  either name is a syntax error reported far from the cause.
- **A script that fails an assertion has still done nothing — check what it left behind.** A
  half-applied edit once shipped both the old and new HTTP entry span, double-counting every metric.
  Re-grep for what a failed edit was supposed to remove.
- **Anything reading `~/.ankka/config.json` or `$HOME` must be overridable by a system property**
  (`-Dankka.config`; `ANKKA_CONFIG` from a shell — `HOME=$(mktemp -d)` does *not* isolate the JVM).
  Details in `control-plane.md`.
