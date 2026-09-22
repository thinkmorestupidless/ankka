# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`ankka` reimplements [Akka's](https://doc.akka.io/) component model — a serverless
platform for agentic AI — in Scala 3 on Apache Pekko. Pekko is the Apache 2.0 fork of
Akka 2.6, chosen so the programming model carries no BSL constraint.

It was called `nakka` until September 2026, before anything was released. Every reference was
renamed — packages `nakka.*` became `com.thinkmorestupidless.ankka.*`, and every artifact, image,
namespace, label domain, config key, environment variable, CRD (`AnkkaService`, short name `asvc`)
and file path followed — so git history before that commit reads `nakka` throughout, and a
`~/.nakka/` or a kind cluster named `nakka` on a machine is a leftover, not something the code reads.

`README.md` is the user-facing reference (component model, agents, streaming, topics,
the control plane and CLI, divergences from Akka, and an honest "Not implemented" list).
`docs/orchestration.md` covers multi-agent patterns. Read both before making design
decisions.

## Commands

Docker is required — integration suites start their own Postgres, and one starts Kafka,
via testcontainers. No API key is needed.

```bash
sbt test                          # everything, including three suites that start a k3s cluster
                                   # and install CloudNativePG into it — CNPG's own controller
                                   # takes ~25s to become ready, and a project's first database
                                   # another 20-60s on top, so they run minutes, not seconds.
                                   # One of them deploys the real shopping cart, so this also
                                   # builds its image and imports ~650MB into the k3s node
sbt -Dankka.cluster.tests=off test  # skip the three k3s suites AND the sample image build they
                                   # need; everything else still runs, in about a minute
sbt agent/test                    # one module: core sdk runtime http agent testkit
sbt operator/test                 # the Kubernetes operator (one k3s suite)
sbt controlPlane/test             # control plane: controlPlaneApi crd controlPlane cli operator
sbt docker:publishLocal           # build all three images — aggregates to operator,
                                   # controlPlane and the shoppingCart sample; every other
                                   # project is silently skipped, same as compile and test
sbt buildAll                      # everything: format check, compile, test, every image —
                                   # one command, stops at the first failing stage
sbt shoppingCart/test             # samples: shoppingCart multiAgentPlanner
sbt 'testkit/testOnly com.thinkmorestupidless.ankka.testkit.WorkflowSuite'
sbt 'agent/testOnly com.thinkmorestupidless.ankka.agent.CompactionSuite -- *transcript*'   # one case (munit glob)
sbt compile                       # should be warning-free; -Wunused is on
```

Running the samples needs the bundled Postgres:

```bash
docker compose up -d
sbt shoppingCart/run              # HTTP on :9000
ANTHROPIC_API_KEY=sk-ant-... sbt multiAgentPlanner/run
ANKKA_AUTH_ISSUER=http://localhost:8081/realms/ankka sbt controlPlane/run   # compose runs Keycloak on 8081
ankka login                                                                   # dev / dev, in a browser
sbt 'cli/run services list --url http://localhost:9000 -p checkout'   # after `ankka login`
```

A two-node cluster on one machine, to see sharding and handoff without Kubernetes — fix the
first node's port so the second can name it (the default is a random port, so several services
and test suites can share a laptop):

```bash
ANKKA_CLUSTER_PORT=17355 sbt shoppingCart/run
ANKKA_CLUSTER_SEED_NODES=pekko://ankka@127.0.0.1:17355 ANKKA_HTTP_PORT=9001 sbt shoppingCart/run
```

`AnthropicProviderSuite` exercises the live API and **skips** unless `ANTHROPIC_API_KEY`
is set. Everything else is deterministic and offline.

**A full `sbt test` is an hour of wall-clock on a laptop, and a laptop sleeps.** A sleeping Mac
pauses Docker and every container in it while the test JVM's deadlines keep counting; an
overnight run came back after 9h50m with two k3s cases failed on timeouts across a 5-hour gap in
the log's timestamps, and every other suite green. Run long suites under `caffeinate -i sbt test`,
and read a failure whose duration is absurd (`MultiNodeClusterSuite … 20549s`) as the machine's,
not the platform's — then rerun it awake.

**Tests are serialized deliberately** (`Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)`
and `Test / parallelExecution := false` in `build.sbt`). Overlapping suites each start
their own container and contend: one suite measured 147s in parallel versus 6s alone.
Do not "optimise" this back.

```bash
sbt scalafmtAll scalafmtSbt        # format; scalafmtCheckAll verifies
```

A `Justfile` wraps the multi-step ones — `just up` (create the kind cluster and deploy
everything), `just down`, `just deploy`, `just test`, `just console`. It is deliberately thin:
every recipe is one command or a call to `deploy-local.sh`, which owns the logic and the guards.
Keep it that way — a recipe that reimplements a step becomes a second copy to keep in step with
this file, and everything here still works without `just` installed.

`SortModifiers` is deliberately absent from `.scalafmt.conf`: it rewrites
`private[ankka] final` to `final private[ankka]`, which is scalafmt's canonical order but
reads worse, and it churned 99 declarations for no benefit.

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
`modules/core` owns the algebra with no Pekko dependency at all.

Where both the runtime and the testkit need to reduce an effect, they share one function
(`EventSourcedEffect.materialise`, `KeyValueEffect.materialise`) so they cannot disagree
about semantics. A bug where they *did* disagree — the runtime folding a deletion marker
before computing the reply — is the reason that shape exists.

### Module dependency direction

```
core → sdk → runtime → {http, agent} → testkit → samples
core → controlplane-api → cli
crd → operator                                           (no ankka dependencies at all)
controlplane-api + crd + sdk + runtime + http → controlplane
                                  (cli, operator, testkit are Test-only deps)
```

`controlplane-api` depends on `core` only — not on Pekko — so the CLI carries no actor
system, no database driver and no Kubernetes client. It holds the wire types *and* the
descriptor validation rules, so both ends apply the same checks; it depends on `core`
rather than redefining a codec because jsoniter needs its config inlined at the call
site, and `Codecs.make` already does that.

`controlplane` takes `cli % Test` so one suite can drive the real `Main.run` against a
real control plane. That is the only test that can catch the two ends disagreeing about
the wire format. It takes `operator % Test` for exactly the same reason applied to the
second wire format: `EndToEndClusterSuite` runs both halves against one k3s cluster, and
nothing else can catch them disagreeing about the custom resource.

`controlplane`'s *tests* also build the shopping cart's Docker image first
(`sampleImageForClusterTests` in `build.sbt`), because `SampleDeploymentClusterSuite` deploys the
real sample into k3s. That is a build-level **task** dependency, not a classpath one: `controlplane`
gains no dependency on `shoppingCart` in any compilation scope, and the graph above is unchanged.
It is gated on `-Dankka.cluster.tests`, so switching the suites off skips the build as well.

`crd` depends on **nothing** — not even `core`. It holds the `AnkkaService` resource, and
both the control plane and the operator have to hold it without inheriting the other's
world. It also holds `Hostnames`, the one derivation of an exposed service's hostname, for the
same reason: the control plane shows and refuses it, the operator renders it, and the resource
itself deliberately carries only `exposed: Boolean` — so no writer of the resource can point a
route at a name the service does not own. `operator` depends only on `crd` and a Kubernetes client, which makes "the operator
cannot reach into the control plane" a build-level fact rather than a convention.

`runtime` depends on `sdk`, not the reverse: the runtime interprets the SDK's
descriptors, so it must see them. This was inverted in the original plan and had to be
corrected.

`ComponentClient` therefore lives in `sdk` over a `CallTransport` interface, with
`runtime` supplying the sharding-backed implementation. Components receive it through
their context, because ankka has no container — a component is constructed by its own
companion and anything it needs arrives that way.

### Component hosting

| Component | Hosted as |
|---|---|
| Event Sourced Entity | `EventSourcedBehavior` in cluster sharding |
| Key Value Entity | `DurableStateBehavior` in cluster sharding |
| View | Pekko Projection → Postgres row table, or a Kafka consumer group |
| Consumer | Pekko Projection, or a Kafka consumer group |
| Workflow | `EventSourcedBehavior` whose events *are* step transitions |
| Timer | Postgres table + cluster-singleton sweeper |
| Agent | Sharded per **session id**, serialized per conversation via a stash |
| HTTP Endpoint | pekko-http route tree |

Entity and workflow hosts pre-serialize domain values into `JournalRecord` /
`StateRecord` via Pekko event and snapshot adapters, so the journal holds ankka's JSON
under ankka's manifest rather than a reflected form of the domain type.

### Registration and handler identity

There is no classpath scanning. Components reach the runtime only by being handed over
explicitly, so an unregistered one fails at startup rather than at its first request.

Handlers are declared on typed companions, which replaces Akka's `Entity::method`
lambda-bytecode inspection:

```scala
val addItem = command("add-item")(_.addItem)   // "add-item" is the wire name
val getCart = query("get-cart")(_.getCart)     // query accepts only a ReadOnlyEffect
```

`query` taking only a `ReadOnlyEffect` is what makes "cannot persist" a compiler
guarantee rather than a convention.

**The wire name is declared separately from the Scala method name on purpose.** It is a
versioning boundary: renaming a method must not change the protocol, or in-flight
requests break during a rolling deploy and persisted timers break permanently. A macro
deriving the wire name from the method name has been considered and declined for this
reason — see the discussion in the git history.

### The RuntimeExtension seam

`ankka-runtime` must not depend on `ankka-http` or `ankka-agent`, so anything that needs
the service to exist before starting plugs in as a `RuntimeExtension`:
`HttpServer`, `ProjectionRuntime`, `TimerRuntime`, `AgentRuntime`. Extensions take
factories rather than instances where a dependency needs the `ActorSystem`.

`EntityProtocol.Command` is shared by every sharded host so the transport needs one
sharding key type. **`EntityProtocol.ModuleCommand` opens that hierarchy**, so the
compiler no longer reports non-exhaustive matches over `Command` — every host must handle
unexpected commands explicitly, and for `InvokeStream` that means *replying*, since a
caller waiting on a token stream would otherwise hang forever. An earlier comment
claiming exhaustivity was preserved was wrong; that mistake let exactly that hang in.

### Cluster formation is an overlay, chosen by where the process runs

`reference.conf` says nothing about how a node finds its peers — no hostname, no port, no seed
nodes, no join-self. That lives in one overlay per means of execution,
`modules/runtime/src/main/resources/ankka-cluster-<mode>.conf`, selected by `ANKKA_CLUSTER_MODE`:

| mode | set by | formation |
|---|---|---|
| `local` (default) | nobody | join `ANKKA_CLUSTER_SEED_NODES` if given, else join self; loopback, random port |
| `kubernetes` | the operator, never a descriptor | Cluster Bootstrap over the Kubernetes API, `${POD_IP}`, fixed ports 17355/7626 |

`ClusterConfig.load` stacks them — system properties > the service's `application.conf` > the
overlay > `reference.conf` — so a service's own file can override any choice the platform made,
and a *new* means of execution is a new overlay file plus one entry in `ClusterConfig.Modes`.
`ClusterFormation.form` then reads `ankka.cluster.formation` and either joins programmatically
or starts Pekko Management and Cluster Bootstrap; the startup code is identical in every mode.

The Kubernetes overlay's substitutions are `${X}`, not `${?X}`: a missing `POD_IP` must be a
startup failure naming it, not a node that binds loopback and quietly joins nothing. The operator
supplies all five (`ANKKA_CLUSTER_MODE`, `POD_IP`, `ANKKA_CLUSTER_SERVICE`,
`ANKKA_CLUSTER_POD_SELECTOR`, `ANKKA_CLUSTER_CONTACT_POINTS`), and `ServiceSpec.problems`
refuses a descriptor that sets any of them — the same rule as `ANKKA_HTTP_PORT`.

Readiness in that mode is `/ready` on the management port: cluster membership (Bootstrap's
own check) AND every `RuntimeExtension` with an opinion — `HttpServer` says no until it has
bound. An image whose runtime predates this feature has no management endpoint at all, so it
is held un-ready rather than joining itself beside its peers; that is the safety net for old
images, not an accident.

The dependencies this adds to `runtime` are `pekko-management`, `pekko-management-cluster-bootstrap`,
`pekko-management-cluster-http` and `pekko-discovery-kubernetes-api` (`project/Dependencies.scala`,
`pekkoMgmt`); they are what forced the `pekkoHttpFamily` override described under traps.

The reference for the shape is the substrate project's three-file layout; its
`-Dconfig.resource` selector was rejected because it *replaces* `application.conf`, which
belongs to the service, not the platform.

### Virtual threads

Endpoints, workflow steps, consumers, timers and agent loops all run on
`AnkkaExecutors.virtual`. That is what makes the blocking `ComponentClient.invoke` free —
an await parks the virtual thread and releases its carrier — so tool loops and workflow
steps can be written as ordinary sequential code.

It is also what makes the HTTP `RequestContext` (query parameters, headers) sound as a
`ThreadLocal`: one request per thread, cleared on the way out. The consequence is that
work handed to *another* thread cannot see it.

### Agents

The agent loop, tool dispatch, session memory, guardrails and token accounting all sit
above `ModelProvider`, which has two methods. Adding a provider means writing one
adapter, not re-implementing agent behaviour. `AnthropicProvider` uses the official Java
SDK as transport only.

Session memory is an event-sourced entity, which is what makes multi-agent collaboration,
compaction hooks and durability fall out rather than being features.

### Reconciliation is split across two processes

The control plane projects a service's desired state into an `AnkkaService` custom resource
and folds the status back; an in-cluster **operator** watches those resources and owns
everything below — namespace, Deployment, reported status. The resource is the only thing
either side knows about the other.

The split is what buys cascade deletion (owner references, so there is no orphan sweep
anywhere in this design), sub-second change notification (watches, not polling), and a
control plane that holds no credential able to create a workload. Doing it in one process
was the original plan and was rejected on review — it reimplemented all three.

The operator is deliberately **not** an ankka application. It has no entities, no journal
and no sharding, so hosting it on ankka would give it a cluster to form and a database not
to use — and a process whose entire job is to keep working while other things are broken
should depend on as little as possible.

`Action` values are inert descriptions of cluster mutations and `Fabric8Executor` is the
only thing that performs them, which is the same organising idea as the component effects.

### Identity: Keycloak authenticates, the control plane authorizes

Every control plane route but `GET /auth` and the health probe is behind `Acl.Authenticate`
(`ControlPlaneAcl.oidc`): a token is verified offline against the realm's cached JWKS
(`controlplane/auth/TokenVerifier`, nimbus — the one library added, in `controlplane` only), and
the caller becomes a `Principal` on the request. Only the token's `sub` is ever a key; email and
name are display. Keycloak decides who is a user; the `Organization` entity decides what they may
touch; the control plane holds no Keycloak admin credential and the design (invitations claimed on
first verified login, research R9) exists so it never needs one. Every command carries an
`Attribution` as *metadata* (`Attribution.from(commandContext.metadata)`), and every
command-produced event has `actor: Option[Actor]` and `at: Option[Instant]` with `None` defaults so
pre-feature journals replay (`EventCompatibilitySuite` pins the old JSON).

The issuer inside a cluster is **derived** from `ANKKA_BASE_DOMAIN` and `ANKKA_HTTPS_PORT`
(`AuthConfig.derivedIssuer`: `https://auth.<base>[:port]/realms/ankka`) and keys are read over
the plain in-cluster service address (`ANKKA_AUTH_JWKS_URL`); locally, `ANKKA_AUTH_ISSUER` names
the compose Keycloak. The realm is one file, `kustomization/components/keycloak/realm.json`,
imported by a `KeycloakRealmImport` that `deploy-local.sh` renders (never checked in) and mounted
by compose; it carries no users — the deploy script and compose's init create `dev`, so a remote
installation cannot inherit one.

### The control plane is an ankka application

`ControlPlane.components` and `ControlPlane.endpoints` are the whole inventory: three
event sourced entities (organization, project, service), three views for listing, and the
endpoints — organizations, projects, services, `whoami`, and the one open discovery route.
`componentsWith(projector)` adds the two consumers that react to desired-state changes
(`ProjectionTrigger`, `SuspensionTrigger`). `ControlPlane.builder` also registers
`ProjectionRuntime()` — without it every listing stays permanently empty while every write
succeeds.

Two invariants carry most of the weight:

- **`generation`** increments on every apply and restart; an observation states the
  generation it describes, and one describing a superseded generation is dropped. The
  guard is in `Service.onObserved` — the *fold* — so replay reproduces it exactly. An
  observation identical to the recorded state is also refused, or the timer-driven
  reconciler would grow the journal forever.
- **`exists` vs `known`.** Deleting an organization or project is a tombstone: `exists`
  goes false but `known` stays true, so the id cannot be recreated. A service is
  deliberately the opposite — a name is a deployment target, not a tenancy boundary.

Cross-entity checks live in the endpoint, never a handler. An entity cannot see another
entity's state, and calling out to fetch it would be a check that does not hold anyway.

### Endpoints receive `EndpointClients`, not a `ComponentClient`

`HttpServer.of` / `.at` take `EndpointClients => HttpEndpoint`, bundling `componentClient`
and `viewClient` — an endpoint that lists things needs the read side too. Registration is
an explicit lambda:

```scala
HttpServer.of(clients => ShoppingCartEndpoint(clients.componentClient))
```

The lambda cannot be shortened to `ShoppingCartEndpoint(_.componentClient)`: the
placeholder binds to the *inner* application, so that parses as passing a function where
a `ComponentClient` is expected. A bundle was chosen over an overload because two
factory shapes would break lambda parameter inference at every call site.

## Traps that have already cost debugging time

- **`Sink.last`, not `Sink.head`, on r2dbc connection publishers.** `head` cancels
  upstream on the first element; cancelling mid-handover means the pool never gets the
  connection back, and a few queries drain it.
- **Do not tune `pekko.persistence.r2dbc.behind-current-time` down.** It guards against
  reading events whose commit timestamp is still in flight. Setting it to zero made a
  suite 15× *slower*, not faster.
- **Guard global timeouts on `startedAtMillis > 0`.** A workflow that has not started has
  no start time, and treating `0` as one makes `elapsed` the whole Unix epoch — firing
  the timeout instantly and failing the workflow before its first step runs.
- **Never touch `ActorContext` (including `ctx.log`, `ctx.system`) from a `Future`
  callback.** It is not thread-safe. Doing so in the timer sweeper made every reschedule
  throw, silently turning "retry with backoff" into "retry immediately, forever, with the
  attempt counter stuck at zero". Capture what async work needs while inside the actor.
- **SSE payloads must be JSON-encoded.** Raw text in a `data:` field loses a leading
  space to the protocol's own rules, and a newline inside a token splits the frame — both
  silent corruptions that only appear on text a model happened to generate.
- **Literal route segments outrank parameters.** Without explicit specificity ordering,
  `/chat/{session}` swallows `/chat/awkward` purely by declaration order.
- **One `TestModelProvider` cannot serve both an agent and an async consumer.** The
  compactor runs asynchronously, so whether the agent or the summariser reaches the queue
  first is a race, and a response queued for one gets consumed by the other. Give each a
  provider. (A compaction test passed for the wrong reason until this was separated.)
- **A workflow left mid-flight keeps consuming a shared scripted model**, starving the
  next test. Drain it before the test ends.
- **A fieldless Scala 3 enum encodes as `{"type":"Ready"}` under ankka's shared codec
  config.** The discriminator is right for events and wrong for a status word a CLI
  prints. Give such an enum an explicit string `JsonValueCodec` **in its companion
  object**, so it is in implicit scope everywhere the enum appears — putting it at each
  derivation site invites missing one and shipping two wire formats.
  (`ServiceLifecycle` does this.)
- **Anything reading `~/.ankka/config.json` or `$HOME` must be overridable by a system
  property.** Environment variables cannot be set in-process, so `ANKKA_CONFIG` alone
  makes `config set` untestable without writing to the developer's own home directory.
  `Settings.path` checks `-Dankka.config` first for exactly this reason. The mirror of that trap
  bites from the *shell*: `HOME=$(mktemp -d) ankka …` does **not** isolate the CLI, because `~`
  resolves through the JVM's `user.home`, which the launcher fixes at startup — the process goes
  on reading the developer's real `~/.ankka/config.json` while the command looks isolated. Two
  attempts at feature 007's "no cluster credentials" proof were spent on a TLS error that was
  really a config never consulted. From a shell the override is `ANKKA_CONFIG`; in-process,
  `-Dankka.config`.
- **Read piped input through `Console.in`, not `System.in`.** Only the former is
  redirectable by `Console.withIn`, which is what lets a test drive `apply -f -` without
  spawning a subprocess.
- **A Deployment's `spec.selector` is immutable.** It must never contain ankka's
  generation, or the second apply is rejected permanently and the service is bricked at
  generation 2. The generation lives on the Deployment's own annotations.
- **The generation must not be on the pod template either.** Feature 001 put it there so a
  restart would roll the pods — but the generation increments on *every* apply, so every apply
  rolled every pod, including one that only changed the instance count. Found by the test
  that scales 3→4 and asserts the three existing pods survive. What rolls the pods is a
  separate `restarts` counter on the pod template, incremented only by `services restart`;
  an apply that changes nothing Kubernetes cares about changes nothing Kubernetes sees.
- **`RollingUpdate` with `maxSurge: 1, maxUnavailable: 0` — feature 003's `Recreate` was
  reversed, on purpose.** `Recreate` was correct while every pod joined itself: a rolling update
  put two single-node clusters on one journal. Once nodes find each other (feature 004) the
  overlap is the *point* — the new pod joins the existing cluster, takes its shards by handoff
  and only then is an old one stopped — and `Recreate` would be the outage. This holds at one
  instance too, measured: the surge pod bootstraps into the old pod's cluster, so a single-instance
  service deploys with no downtime either. Do not put `Recreate` back for "safety"; the guard
  against the split it once prevented is now `join-self-if-no-seed-nodes = off` in the Kubernetes
  overlay, where joining self *is* the split.
- **Changing a field Kubernetes defaulted can wedge every existing object.** Moving a live
  Deployment from `RollingUpdate` to `Recreate` was rejected — `invalid: spec.strategy` — while
  its defaulted `rollingUpdate` block was still there, and server-side apply cannot remove a
  field no manager owns; every reconcile then failed forever, until a one-off JSON merge patch
  (`"rollingUpdate": null`). The reverse migration needs no such thing (an apply that *sets*
  `rollingUpdate` owns it), so that code is gone — but the class of bug stays: **tests that
  start from an empty cluster cannot see it**. It took a real cluster with a real leftover
  object, and the rolling-update migration test exists to keep it in view.
- **Never render a HorizontalPodAutoscaler.** `minInstances` is honoured as a fixed count, and
  that is the whole story until ankka has a reason to scale on load. An autoscaler that
  scaled to zero would also be a cold start nobody asked for.
- **`pekko.coordinated-shutdown.exit-jvm = on` belongs in the Kubernetes overlay only, never
  `reference.conf`.** In a pod a process is a node and exiting after a split-brain down is the
  point — without it the pod stays `Running`, never ready, never restarted. Anywhere else it
  turns the first stopped `ActorSystem` into a dead process: put in the base, it killed the
  forked test JVM (`Forked test harness failed: EOFException`) the moment a suite called
  `AnkkaTestKit.stop()`.
- **`pekko.cluster.seed-nodes = ${?ENV}` is a type error at load.** It is a list, and an
  environment variable is a string. The local overlay's `ankka.cluster.seed-nodes` is a
  comma-separated *string* under ankka's own key, and `ClusterFormation` splits it and joins
  programmatically — the same call it makes to join itself.
- **`ClusterConfig.layered` puts the overlay *above* a config a caller built for itself.**
  The obvious precedence (overlay beneath the application) is what `load` does, and it is wrong
  for a config that came from `ConfigFactory.load()` — the test kit's — because that config
  already carries Pekko's reference defaults for every key the overlay sets, a fixed remoting
  port among them. Two test systems on one machine then bind the same port. A config that
  already has `ankka.cluster.formation` came from the loader and passes through untouched.
- **pekko-management pulls `pekko-http` 1.1.0, and eviction lifts only part of the family.**
  `pekko-http` goes to 1.4.0 but `pekko-http-spray-json` stays, and Pekko HTTP checks family
  versions at startup — every HTTP suite died in `beforeAll`. `dependencyOverrides ++=
  pekkoHttpFamily` in `commonSettings` pins all of them; add any new pekko-http artifact to that
  list, not only to `libraryDependencies`.
- **Scaling a Deployment directly is undone within one resync.** The operator's reconcile
  loop restores the replica count from the resource, so `kubectl scale --replicas=0` is not how
  a test takes a service down: it is back before the assertion runs. `ankka services pause` /
  `resume` is — the count is rendered from the spec, and pause is the spec saying zero.
- **`dependencyOverrides` never reaches a POM.** Feature 004 pinned the Pekko HTTP family with an
  override in `commonSettings`; the first build *outside* this repository (feature 006) got
  `pekko-http-spray-json 1.1.0` from pekko-management beside `pekko-http 1.4.0` and Pekko HTTP
  refused to start. Anything a consumer must see is a direct `libraryDependencies` entry in the
  published module — `ankka-runtime` now declares the family.
- **A Docker tag may not contain `+`, and a dynver snapshot version does.** `docker:publishLocal`
  failed on every image the moment `ThisBuild / version` went: `invalid tag
  "ankka-operator:0.0.0+12-…"`. `dockerSettings` sets `Docker / version` with `+` → `-`; a
  release version has no `+` and tags exactly as itself. The template's build does the same.
- **`JavaAppPackaging` enables `DockerPlugin`**, so `sbt cli/stage` for the CLI also made root's
  `docker:publishLocal` build a CLI image. The CLI's `Docker / publishLocal` and `Docker / publish`
  are no-ops; the CLI is a local binary, never an image.
- **A task dependency on `root / publishLocal` publishes nothing.** Aggregation is how the
  *command line* fans a task out to the aggregated projects; in the task graph, `(root /
  publishLocal).value` runs the root's own — skipped — publish and returns in 0s. `templateArtifacts`
  names the six modules. The same is true of `root / test` and `root / compile`.
- **`testOnly` does not go through `test`.** A dependency hung on `Test / test` is bypassed by
  `sbt module/testOnly X`, which is exactly how one suite is run; hook both.
- **A test must never name an image by a literal tag.** `EndToEndClusterSuite` said
  `sample-shopping-cart:0.1.0-SNAPSHOT` — the fixed version feature 006 deleted — and kept passing
  for two features on a stale image of that name in the Docker daemon, until the rename made that
  image one that reads environment variables the operator no longer sets: it started, was never
  `Ready`, and only the cases where it was the *only* pod timed out. The second tag is
  `BuildInfo.version` with `+` → `-` (what `Docker / version` produces), and `testOnly` builds the
  images too, so the tag a suite asks for is the one this sbt session made.
- **A top-level `require(...)` is not an sbt DSL entry** (`required: sbt.internal.DslEntry`); a
  check in a `build.sbt` is a `val` whose body calls `sys.error`.
- **Giter8 reads `default.properties` from `src/main/g8/`, not the template root** — at the root
  it is silently ignored ("Ignoring unrecognized parameter: name"). Spaces in `--name` must be
  quoted inside the sbt command string; an empty directory needs `sbt --allow-empty`.
- **A wildcard is one label deep — for X.509 certificates and for Gateway API listeners alike.**
  `*.example.test` covers `cart-checkout.example.test` and not `cart.checkout.example.test`; two
  implementations that got the listener rule wrong filed it as a bug. With TLS on the
  installation's single Gateway, that is *why* an exposed service's hostname is
  `<service>-<project>.<base>` (one label; `com.thinkmorestupidless.ankka.crd.Hostnames`) and not the two-level form
  that reads better. Two costs, both refused at expose time: a label over 63 characters, and a
  collision between hyphenated names (`a-b` in `c`, `a` in `b-c`).
- **A cert-manager `ClusterIssuer` looks up its `ca.secretName` in cert-manager's own namespace,
  not the Certificate's.** `secrets "ankka-root-ca" not found` with the secret sitting right there
  in `ankka-gateway`. A namespaced `Issuer` beside the secret is the honest shape for a local CA.
- **The ClusterIssuer trap runs both ways, and the remote overlay needs the other direction.**
  A cluster-scoped issuer resolves its secrets in *cert-manager's* namespace rather than the
  Certificate's, which is why a `ClusterIssuer` was wrong for the local CA (above) — its secret
  sits beside the Certificate. `overlays/arrakis` is the mirror: DNSimple is not one of
  cert-manager's built-in DNS-01 solvers, so it needs the out-of-tree webhook, and that webhook
  reads its API token with *its own* ServiceAccount in the namespace of the challenge. The chart
  grants that with a Role in its release namespace, pinned by `resourceNames` to its own secret.
  A namespaced `Issuer` in `ankka-gateway` therefore sends the webhook after a secret it cannot
  read, and issuance fails `forbidden` on the *token* — which reads nothing like the wildcard
  certificate being the problem. Same property, opposite answer, and `RemoteOverlaySuite` pins
  both directions so neither gets "tidied" into the other.
- **A Gateway API `RequestRedirect` without `port` keeps the *request's* port in the Location.**
  `http://…:8080/x` → `https://…:8080/x`, which goes nowhere on kind, where HTTPS is on 8443. The
  redirect route names its port (443 in the component, the kind host port in the overlay).
- **A route can be `Accepted` and still not serve.** A backend in another namespace is
  `ResolvedRefs: False / RefNotPermitted` and Envoy answers 500 for it; an unlabelled namespace is
  `Accepted: False / NotAllowedByListeners` and gets a 404. The resource's `status.route` folds
  both conditions, and `services get` shows it as `route rejected: <reason>`.
- **Every reconcile reads an `HTTPRoute`, so a cluster without the Gateway API must read as "no
  route", not fail.** The read-first removal and the status read both run for every service on
  every pass; `Fabric8Executor` treats a 404 on the *type* as absent. Only an exposed service's
  `EnsureHttpRoute` is allowed to fail on a missing CRD, loudly.
- **Envoy Gateway's CRDs need Kubernetes ≥ 1.32.** Its experimental `xbackends` CRD carries a
  CEL rule using `format.dns1123Label()`, which a 1.31 API server rejects
  (`CustomResourceDefinition … is invalid: … x-kubernetes-validations[0].rule`) — with `kubectl`
  as much as with fabric8, so it looked like a client bug first. The k3s test image moved from
  v1.31.2 to v1.35.1 for this; and `kubectl apply` of a multi-document manifest applies everything
  *else* and exits 1, so a `grep -c applied` after it hides exactly this — check the exit code.
- **`curl -o /dev/null` without checking the status accepts a 404.** `deploy-local.sh` ended by
  curling `$API_URL/health` and testing only curl's exit code — and there is no `/health` endpoint
  on the control plane, which serves `/organizations`, `/projects` and `/services`. A 404 is a
  *successful* HTTP exchange, so curl exited 0 and the smoke test passed on every deploy it ever
  ran, including ones where every route was broken. It now asks for the organizations listing with
  the token read from the cluster and requires a 200, which exercises DNS, TLS, the gateway route,
  a control plane pod, the ACL and a database query. Note also that `curl -w '%{http_code}'`
  prints `000` of its own accord when it never got a response, so `|| echo 000` yields `000000`
  and the "could not connect" branch never matches — `|| true` is the guard `set -e` needs.
- **A `waitFor` that swallows exceptions turns a broken check into "it never happened".** Two
  runs were spent on a certificate that was `Ready` in 20s by hand, because the fabric8
  generic-resource status parsing in the check threw and the loop reported a timeout. For
  objects from CRDs the suites do not model (cert-manager, Gateway API status), the checks now
  ask `kubectl … -o jsonpath` on the node — the same tool `deploy-local.sh` waits with — and the
  k3s suites apply those manifests with the node's `kubectl` too.
- **`-Djdk.net.hosts.file` steers `java.net.http` — for the whole JVM.** Names not in the file
  stop resolving, so it can never be set on the forked test JVM. `ControlPlaneClusterSuite` runs
  the real CLI as a subprocess with it, on the same classpath; that is also the honest way to
  exercise `Main.main` and its `sys.exit`.
- **LibreSSL's `openssl req -newkey ec` writes explicit EC parameters, which the JDK refuses**
  (`Only named ECParameters supported`). Test certificate fixtures are RSA.
- **The k3s node's `kubectl exec` works; its `wget` is BusyBox** (no PUT, no `--cacert`). Drive
  the gateway from the *host* with `curl --cacert --resolve` against the mapped NodePort — which is
  also the only proof that matches what a developer's machine does.
- **A pod that cannot answer a bootstrap probe must never be a contact point.** Upgrading the
  kind cluster from a feature-003 image deadlocked: the old pods were `Ready` by their tcp probe,
  so the rolling update kept them; the new pods discovered them by the identity labels, got no
  answer on a management port that did not exist, and Pekko's join decider refused to form a
  cluster while any contact point was silent — `Exceeded stable margins but missing seed node
  information from some contact points`, forever. The discovery selector therefore includes
  `ankka.thinkmorestupidless.com/formation=bootstrap`, a label only pod templates rendered since
  feature 004 carry (`Labels.FormationKey`); it is *not* in the Deployment's immutable
  `spec.selector` nor the Service's. Empty-cluster suites cannot see this class of bug — the
  same lesson as the `Recreate` migration, from the other direction.
- **A rolling replacement still refuses requests without a `preStop` sleep.** A pod leaves its
  Service's endpoints the moment its deletion starts, but kube-proxy on each node learns that up
  to a second later — and the runtime unbinds its HTTP port the instant SIGTERM arrives. In that
  second a request routed to the old pod is refused: 2 of 33 control-plane commands during one
  replacement, measured. `lifecycle.preStop.sleep: 5s` (Kubernetes' own sleep action, so a
  workload image owes the platform no shell) runs *before* SIGTERM and the pod serves through it.
  Rendered on every workload and in the control plane's manifest; do not remove it as "unused".
- **A k3s test node running five sample JVMs answers in seconds, not milliseconds.** A suite that
  deploys several real ankka services into one k3s container starves it: the control plane's GET
  latency went to a median of 5.2s and a throughput assertion failed for the wrong reason. Deploy
  the real image only for the service a case actually needs to be `Ready`; the rest can be
  `pause` with `"http": false`.
- **The container port's *name* `management` is load-bearing.** The readiness probe is
  `httpGet` on the port by name, not number, so renaming the port in `Rendering` (or in the
  control plane's own manifest) leaves a probe that resolves to nothing and a pod that is never
  ready, with no error anywhere.
- **"Marking node as UNREACHABLE" for a node that just exited is expected, not a bug.** A
  graceful leave still has the failure detector fire on the survivors between the socket
  closing and the `Removed` propagating; a test asserting *zero* unreachable events during a
  clean leave is asserting something Pekko does not promise. Assert on membership converging.
- **`kubectl delete pod --force` is a graceful leave, not a crash.** It still sends SIGTERM
  and the node runs coordinated shutdown, so it proves nothing about failure detection or the
  split-brain resolver. A crash test is `kill -9` of the JVM from the node (`crictl` inside the
  k3s container), and a partition test is `iptables` — `MultiNodeClusterSuite` does both.
- **Two things are called "generation".** ankka's lives in the resource's `spec` and is
  what `Service.onObserved` compares; Kubernetes' is `metadata.generation` and is only
  meaningful against `status.observedGeneration`. Conflating them reports the right answer
  about the wrong generation.
- **Two ankka services must never share a Postgres database.** `ankka_timers` has no
  service column, and `TimerSweeper` *deletes* rows whose component id it does not
  recognise — so they silently delete each other's timers. View row tables, named from the
  component id alone, collide the same way.
- **Server-side apply rejects an object carrying `metadata.managedFields`.** Always build
  a fresh object to apply; never re-apply one read back from the server. This only shows
  up against a real API server, which is what the k3s suites are for.
- **A project's namespace is the control plane's to create, not the operator's.** Owner
  references are namespace-scoped, so the resource must live beside the workload it owns —
  which makes the namespace a precondition of writing the resource, and the operator only
  learns a project exists by seeing that resource.
- **Server-side apply on an object that does not exist yet is still a PATCH, not a POST.**
  RBAC granting only `create` on a resource 403s the first time `ensureNamespace` runs,
  which is every time a project's namespace is new — the one case that rule exists for. Any
  resource written via `serverSideApply()` needs `patch` in its ClusterRole. Caught only by
  deploying against a real cluster: the k3s test suites mostly use kind's/testcontainers'
  admin credentials directly rather than exercising the shipped RBAC — the exceptions mint a
  real token for the operator's own ServiceAccount, and for a deployed service's, to prove a
  withheld verb is refused by the API server itself, not just unused (`OperatorClusterSuite`,
  features 002 US5 and 004 US1).
- **A `Database` or `DatabaseRole` referencing a `Cluster` in another namespace gets no
  status and no events at all**, not an error — the CNPG reconciler simply never touches
  it. This is why per-project Postgres capacity lives in the project's own namespace rather
  than a shared one: a cross-namespace reference cannot be detected from the referencing
  object's own status, only inferred from it never changing.
- **CNPG does not generate passwords.** Given a `DatabaseRole` with no `passwordSecret`, it
  creates a role nobody can log in as, silently. The platform must generate one itself —
  and only when the credential secret is absent, since regenerating on every reconcile
  rotates the password under a running service on a timer.
- **A `Database` can lose the race against its own `DatabaseRole`.** Both are applied in one
  pass and reconciled independently; a `Database` whose owner role does not exist yet fails
  with `role "x" does not exist` and self-heals once the role lands. Report this as *in
  progress*, never `Failed` — a transient ordering artifact that looks exactly like a
  permanent one.
- **A CNPG `forbidden` error on a role's password secret is transient, not an RBAC bug.**
  CNPG maintains a per-`Cluster` secrets allowlist and adds a newly-referenced
  `passwordSecret` to it only on its own next reconcile — 20-40 seconds after the role and
  secret are created together, not immediately. A status check during that window looks
  identical to a real permission failure; only the message's specific shape
  (`secrets "x" is forbidden`, naming the *secret*, not the role or database) and the fact
  that it clears on its own distinguish the two.
- **`bootstrap.initdb.postInitApplicationSQLRefs` runs as the `postgres` superuser, not as
  `bootstrap.initdb.owner`.** Every table the DDL creates ends up owned by `postgres`, so
  the role the application actually connects as has no privileges on any of them —
  discovered by deploying the control plane's own CNPG-managed database for real: it
  started cleanly, then every write timed out with `permission denied for table
  projection_management`. Fixed with a trailing `GRANT ALL ... TO <owner>` SQL fragment
  applied after the DDL, in the same `configMapRefs` list. This is the one case in this
  codebase that uses `bootstrap.initdb` at all — every per-service database goes through the
  operator's `Database`/`DatabaseRole`, whose owner is the role connecting to it from the
  start, so this trap cannot recur there.
- **Kubernetes defaults `imagePullPolicy` to `Always` for a `:latest` tag.** An image loaded
  straight onto a node (`kind load`, `ctr import`) is then ignored and the pod fails
  `ErrImagePull` with the image sitting right there. The operator renders `IfNotPresent` on every
  workload for this reason, as the platform's own manifests always have for themselves. It went
  unnoticed for two features because every test deployed `registry.k8s.io/pause:3.9` — pullable,
  *and* not `:latest`, which removes both halves of the trap at once.
- **containerd namespaces are hard isolation, and kubelet reads only `k8s.io`.** An image imported
  with plain `ctr images import` lands in the default namespace: the import succeeds, `ctr images
  ls` shows it, and kubelet still cannot see it. In a k3s container it is
  `ctr -a /run/k3s/containerd/containerd.sock -n k8s.io images import` — and `ctr`, not `k3s ctr`,
  which that image answers with "No help topic".
- **jsoniter reads a JSON `null` on an `Option` field as *absent*, and applies the default.** So an
  `Option` whose default is not `None` cannot express "none": `port: Option[Int] = Some(9000)`
  decoded `{"port": null}` as 9000, silently, on a descriptor that crosses the codec twice. Say
  "none" positively (`http: false`), and keep the value a plain type — a `null` on an `Int` is a
  loud decode error instead. `DescriptorSuite` pins this.
- **A port-forward does not go through the Service.** It is API server → pod, so a test using one
  passes with a broken selector or the wrong `targetPort` — the pod is `Ready`, the address is
  dead, and nothing notices. To test that a Service routes, make the request from the k3s *node*
  to its `clusterIP` (`k3s.execInContainer("wget", …)`). By IP: the node does not resolve cluster
  DNS names, only pods do.
- **Forked tests do not inherit sbt's `-D` properties.** `Test / fork := true`, so a switch passed
  as `sbt -Dfoo=bar test` is set in a JVM that runs no tests. `-Dankka.cluster.tests=off` was a
  documented no-op for two features — the "skipped" suites quietly took seven minutes — until
  `Test / javaOptions` started forwarding it. Any new test switch needs the same forwarding.
- **A service's default descriptor now asserts something.** Saying nothing means "serves HTTP on
  9000" and the pod is not `Ready` until that port opens. Right for an ankka service; an image that
  listens on nothing (`pause`) needs `"http": false` or it is `Failed` when the rollout deadline
  passes. Every `pause` descriptor in the test suites carries it.
- **Kustomize's load restrictor is checked per component directory, not against the
  top-level build root.** A component cannot reference a file outside its own directory —
  not via a relative path, not via a symlink resolving there — even when a common ancestor
  contains both. There is no override for `kubectl apply -k`. The CRD, the operator's
  install manifest and the control plane's RBAC are therefore canonical *inside*
  `kustomization/components/`, with `operator/src/main/resources/ankka/{crd,install}/` and
  `controlplane/src/main/resources/ankka/install/` holding symlinks *into* them — the
  reverse of the direction that seems obvious, and the only direction that works, since sbt
  and the JVM follow symlinks transparently but kustomize does not. Never `ln -sf` onto a
  path that might already hold the real content; copy it out first. This one cost real file
  content, recovered only because the compiled classpath still had it.
- **`actions/checkout` hijacks pushes to any other GitHub repository.** It persists the workflow's
  token as `http.https://github.com/.extraheader`, which matches *every* github.com URL and beats
  the `x-access-token:<token>@host` credentials written into a push URL. The release's template
  job pushed to `ankka.g8` as `github-actions[bot]` and got "Permission to … denied", a 403 that
  reads exactly like a repository that does not exist — it did exist, and the token was never
  tried. `persist-credentials: false` on that checkout is the fix.

- **Local mode runs no management server, so observability needs its own local exposure.**
  `ClusterFormation` starts Pekko Management only in the `bootstrap` path, and says why: it binds a
  fixed port, which two services on one laptop would fight over. `VersionRoute` is therefore the
  obvious model for an observability endpoint and the wrong one — it does not exist where the local
  console needs it. Observability is exposed twice, chosen by where the process runs, exactly as
  formation is: a loopback endpoint on an ephemeral port locally (`ObservabilityEndpoint`, on the
  JDK's own HTTP server so `runtime` gains no dependency), and a `ManagementRouteProvider`
  (`ObservabilityRoute`) under Kubernetes. One recorder, two exposures.
- **A trace set on the caller's thread is invisible to the thread doing the work.** `dispatch`
  returns a `Future`; the handler runs later on its own virtual thread. Wrapping the *call to*
  `dispatch` in `Trace.within` compiles, runs, and produces an endpoint span and an entity span in
  two unrelated traces — a list, not a tree. The trace belongs where `RequestScope` already puts
  the request context: inside the `Future`, on the handler's thread. The general rule is the one
  `RequestContext` already states — work handed to another thread cannot see a thread-local — and
  tracing inherits it exactly. Where it genuinely cannot follow, the time shows as *unattributed*
  and an orphan span stays at the root marked unknown. **Never re-parent an orphan to the nearest
  plausible candidate**: a tree that reads correctly and describes something that did not happen is
  worse than a visible hole.
- **A refusal is not a failure, and the recorder has to be told which it was.** `effects.error(...)`
  returns a *value*, so a refused command reaches the caller looking exactly like a success — a
  `try`/`finally` around the handler records `Ok` for a working ACL. `interpret` returns the span
  outcome rather than leaving the caller to infer it from an exception that never comes. A console
  that paints a refusal red, or a fault green, teaches its reader to ignore the column.
- **Never intern anything unbounded into the recorder's name table.** Component and handler names
  are interned once and become `Int`s, which is what keeps a span allocation-free. That table is
  bounded *only* because registration is explicit and handler names are declared on companions.
  Entity ids, session ids and request paths with parameters filled in are not bounded, and interning
  one would grow the table for the life of the process.
- **A benchmark needs a denominator that is the thing the criterion names.** SC-003 asked for
  instrumentation within 5% of "a service's throughput". Measured against an empty loop recording
  cost 58%; against a jsoniter round-trip, 28%; against a testkit entity call, 50% *while measuring
  faster than the serializer alone*, which is the JIT folding a monomorphic loop. All three numbers
  were arithmetically true and answered a question nobody asked. Against a real service — HTTP in,
  entity, journal, reply — one invocation is 640µs and recording is 22ns, or 0.003%. A ratio that
  moves with the shape of the harness is measuring the harness.
- **A script that fails an assertion has still done nothing — check what it left behind.** An edit
  meant to remove the first attempt at the HTTP entry span asserted on two call sites, found one,
  and threw before writing; the follow-up added the replacement without removing the original. Both
  shipped, every request recorded two spans, every metric double-counted, and no test could see it.
  It took reading metrics off a real deployment and noticing one request wearing two component
  names. Re-grep for what a failed edit was supposed to remove.

- **An `eventually` must wait for the thing it asserts.** `ControlPlaneHttpSuite` waited for the
  cart's row to appear and then asserted, outside the retry, that the row carried the image the
  *previous* test had applied. The generation-1 row satisfies "a row exists", so on a machine where
  the projection lags the test read a stale row and failed — green on a laptop, red on CI. Every
  other `eventually` in that suite has the right shape: retry on the value that changes
  (`"services":0`, the hostname, the row disappearing), assert the identity that does not.

- **A `var` that is assigned and never read is a lifecycle that never runs.** The local console
  endpoint was held in a `@volatile var` on the `Ankka` builder object; nothing read it, so
  `stop()` — and with it `ServiceRegistration.withdraw` — was unreachable, and every locally-run
  service left its entry in `~/.ankka/running` forever. Unit tests of `announce` and `withdraw`
  passed throughout: both were correct, and the defect was that one was never called. The console
  sweeps entries nothing answers for, so the only symptom was a directory quietly filling up. It
  belongs to `AnkkaService`, which is the thing that gets terminated — a singleton builder would
  keep only the most recently started endpoint anyway. `ServiceRegistrationSuite` drives the whole
  lifecycle for this reason; nothing narrower can catch a call that is never made.

- **A test that binds a fixed port cannot run beside the documented workflow.** `HttpServer.of`
  takes the default 9000, so a suite registering one fails with `Address already in use` on any
  machine already serving that port — including a developer running `sbt shoppingCart/run` next to
  their tests, which is how this repository says to work. Every HTTP suite uses
  `HttpServer.at("127.0.0.1", 0)`: loopback, ephemeral, the same reason the cluster's remoting port
  defaults to random. CI passes either way, so this only ever fails on the machine of the person
  doing the thing the README recommends.

- **A CLI's `main` should be a one-line wrapper.** `Main.run(args, out, err): Int`
  returns the exit code and `main` calls `sys.exit` on it; `sys.exit` inside the command
  logic would kill the test JVM.

- **`KeycloakRealmImport` is one-shot.** It creates a realm that does not exist and never updates
  or deletes one; a re-apply is a no-op and deleting the resource leaves the realm. So the realm
  JSON is a file the deploy script renders into the resource (and compose mounts), and a change to
  it on an existing installation is a console job. Never check the rendered resource in.
- **Keycloak writes a lone `aud` as a string and several as an array.** A test (or a verifier)
  that reads `aud` as an array sees nothing on a service-account token. nimbus handles both;
  `KeycloakAdmin.audiences` does for tests.
- **A token has no `sub` unless a scope maps it.** The built-in `basic` scope was not attached to
  a client created through the admin API with an explicit scope list, and the token verified but
  carried no subject. The `ankka-controlplane` scope carries its own subject mapper so a client
  needs nothing else.
- **A Keycloak user with no first and last name cannot log in with a password grant** — "Account
  is not fully set up", a pending profile action. The deploy script, compose and the test helper
  all set both on the users they create.
- **A kustomize Component's `namespace:` transformer runs over everything the overlay accumulated
  before it.** Setting it on the Keycloak operator component renamed CNPG's namespace and the render
  failed with an ID conflict. The operator's manifests sit in a nested plain Kustomization
  (`components/keycloak-operator/manifests`) whose transformer sees only them — and the
  ClusterRoleBinding's subject, which no namespace transformer reaches, is patched by hand there
  and in `KeycloakStack`.
- **The Keycloak operator needs all four of its CRDs, not the two ankka uses.** With only
  `keycloaks` and `keycloakrealmimports` applied it crash-loops on
  `keycloakoidcclients … Not Found` and never reconciles anything. The component's nested
  kustomization and `KeycloakStack` install the OIDC and SAML client CRDs too.
- **The control plane's issuer must equal what Keycloak writes into `iss`, port included — and
  Keycloak learns the port only from `X-Forwarded-Port`.** With a bare `hostname` it takes scheme
  and port from the proxy headers; Envoy forwards the proto and not the port, so through kind's
  8443 every token and every discovery URL named `https://auth.<base>/…` — unreachable on kind and
  a 401 on every request. The identity provider's `HTTPRoute` sets `X-Forwarded-Port` to the HTTPS
  port (the overlay replaces it from `ankka-platform.httpsPort`, the deploy script's sed too, and
  `KeycloakStack` templates the mapped port), the control plane derives the same string from
  `ANKKA_BASE_DOMAIN` and `ANKKA_HTTPS_PORT`, and `EndToEndClusterSuite` asserts the advertised
  issuer equals the derived one. `X-Forwarded-Host` with a port works as well; `Host` with a port
  does not — measured against the image, not read from the docs.
- **An operator *reports* `Paused`, so a listing row cannot infer "the members paused it" from
  its own lifecycle word.** `ServiceRows` kept `Paused` on any observation while the row said
  `Paused` — and a stale operator report of the pause, landing just after a resume, pinned the
  listing at `Paused 1/1` while `services get` said `Ready` (the k3s end-to-end suite caught it;
  the fast harness could not until it replayed that exact report). The row now carries the
  members' `paused` flag and the organization's `suspended` flag and applies the same rule as the
  entity's fold: desired state wins over a report. `SuspensionSuite` pins the sequence.
- **A suite that fills a manifest placeholder with a plain `replace` also rewrites variable
  *names* that contain it.** `ControlPlaneClusterSuite` turned `ANKKA_BASE_DOMAIN` into
  `ANKKA_test.local`, so the deployed control plane had no base domain — harmless for two
  features, and a crash-loop at startup once the issuer was derived from it. Replace the
  placeholder with a lookbehind (`(?<!ANKKA_)BASE_DOMAIN`), and read a deployed pod's `env` before
  blaming its image.
- **A CLI test that deletes the credentials file after removing the config override deletes the
  developer's own.** `Credentials.path` follows `Settings.path`; clean up *before* the property
  goes, in a directory the test owns.

## Publishing

Six modules are libraries an application depends on — `core`, `sdk`, `runtime`, `http`, `agent`,
`testkit` — and are published as `com.thinkmorestupidless:ankka-<module>_3`. Everything else
(`controlplane-api`, `crd`, `operator`, `controlplane`, `cli`, the samples, root) carries
`publish / skip := true`: a platform-side jar cannot reach a repository by accident, and "these
are not libraries" is a build fact rather than a note.

```bash
sbt publishLocal                     # the development loop: ~/.ivy2/local, exactly six artifacts
sbt 'show version'                   # sbt-dynver: 0.2.0 at tag v0.2.0; 0.2.0+3-sha-SNAPSHOT past it; dirty tree → -SNAPSHOT
sbt -Dankka.release.local=/tmp/repo publishSigned   # the release path against a directory, with a throwaway key
git tag v0.2.0 && git push --tags    # the only thing that publishes; the workflow stages it for approval
```

**Only a tag publishes anything.** The workflow no longer runs on pushes to `main`: the portal's
snapshot repository answers 403 for this namespace (claimed through legacy OSSRH in 2023, and
snapshot publishing there is a separate entitlement from releases), so every commit was a red
build — which is how a real failure goes unnoticed. Snapshots are `sbt publishLocal` now, which is
what the samples and `TemplateSuite` resolve anyway.

**Nothing in the build may write to a tracked file during `publish`.** A `templateVersion` task
used to rewrite `ankka.g8`'s `default.properties` from `version.value`, hung off `core`'s
`publish` and `publishLocal`. It only writes for a non-SNAPSHOT version, so it never fired locally
and always fired on a tag: it dirtied the tree, dynver appended a timestamp and `-SNAPSHOT`,
`ci-release` (which reloads the build before publishing) re-derived the version from the dirtied
tree, and the release went to the snapshot repository — 403, on a namespace with no snapshot
entitlement. Three tagged attempts failed that way. The write was useless besides: the `template`
job checks the repository out afresh, so the publish job's workspace never reached the template
that is pushed. The version is now written by that job, immediately before `git subtree split`.

**A tag publishes.** `ci-release` runs sbt's own `sonaRelease`, which uploads the bundle to the
Central Portal and publishes it; nothing on Central can ever be unpublished, only superseded.
`sonaUpload` is the same upload that stops short and waits for the Publish button — `0.1.0` went
out that way, because that path had never completed and a first attempt whose failure mode is
"permanently published" is the wrong first attempt. Set `CI_SONATYPE_RELEASE: sonaUpload` in the
workflow to rehearse a release again. Both are sbt commands, not a plugin's: sbt-ci-release 1.12.1
depends on sbt-dynver and sbt-pgp only, so sbt-sonatype's `sonatypeCentral*` names do not exist
here, whatever a stale copy of that plugin in the coursier cache suggests.

**`0.1.0` is the first release**, and what it cost is in the git history: a tag published a
snapshot three times before reaching the portal. The snapshot repository 403s for this namespace
(claimed through legacy OSSRH in 2023; snapshot publishing there is a separate entitlement), which
looked like a credentials problem for a long time and never was — the releases endpoint accepted
the first bundle that actually reached it.

**There is no `ThisBuild / version`, and there must never be one.** The version comes from the
git tag through `sbt-dynver`; a version set in the build silently overrides the tag, which is the
one thing a release must not do. A *dirty tree* does the same thing quietly: dynver appends a
timestamp and `-SNAPSHOT`, and `ci-release` then takes the snapshot path, so a tag publishes a
snapshot and no release. That is what the first `v0.1.0` did — and the tree was not really dirty,
`git describe --dirty` was reporting stale index stat info after the runner's forced checkout.
The workflow runs `git update-index --refresh` and refuses to build from a tree that is still
dirty, rather than shipping a snapshot named like a release. `com.thinkmorestupidless.ankka.core.BuildInfo.version` carries the same value into code
— the CLI prints it, the control plane compares an application's declared runtime against it.

**The template** is `ankka.g8/` — a Giter8 template, tested by `cli`'s `TemplateSuite`, which
publishes locally, expands it into a temp directory through the real `ankka init`, and runs the
expansion's own `sbt test` and image build as subprocesses (`-Dankka.template.tests=off` skips
it; it needs `sbt` on `PATH` and Docker). `ankka init` shells out to `sbt new` and carries no
template of its own; it passes its `BuildInfo.version` as `--ankka_version`. The directory is
named `ankka.g8` because sbt's Giter8 resolver only accepts `owner/repo.g8` and
`file://…/x.g8` — a template in a subdirectory of another repository cannot be reached by `sbt
new` at all, which is why the release workflow subtree-pushes it to `thinkmorestupidless/ankka.g8`.

**Compatibility** (`com.thinkmorestupidless.ankka.controlplane.api.Compatibility`): a descriptor's declared `runtime` is
checked against `BuildInfo.version` when the control plane *projects* the service — same major,
minor equal or one below — and an unsupported one takes the existing "cannot project" path as
`ClusterView.Refused` → `Unavailable` with both versions in the detail, before any resource is
written. Undeclared is unchecked. The rule lives in `controlplane-api` so the CLI can one day
print it without the control plane. **A consequence for DDL changes**: within a supported range
the schema is additive — a running application must never lose a table or column it needs.

A release to Maven Central waits on one action outside this repository: claiming the
`com.thinkmorestupidless` namespace on the Sonatype Central Portal and setting the four secrets
the workflow names. Every other step is proven locally.

## Deploying locally

```bash
kind create cluster --name ankka --config kustomization/kind.yaml
./kustomization/deploy-local.sh
```

Installs [CloudNativePG](https://cloudnative-pg.io/), [cert-manager](https://cert-manager.io/)
and [Envoy Gateway](https://gateway.envoyproxy.io/) (all server-side apply — CNPG's CRDs are too
large for client-side apply's own annotation-size limit), builds all three images — the operator,
the control plane and the shopping cart sample — loads them into the cluster with `kind load
docker-image`, and applies the CRD, the operator, the platform's Gateway and local CA, the
control plane's own CNPG-managed database and the control plane itself via `kubectl apply -k`. No
registry — `DOCKER_REPOSITORY` in `build.sbt`'s `dockerSettings` is the one setting that changes
once one exists. The script checks `kubectl config current-context` and refuses to run against
anything other than the `kind-*` cluster it targets, and refuses a cluster whose node does not
publish the gateway's NodePorts (30080/30443 → the host's 8080/8443, from `kind.yaml`) — kind
decides that at creation and it cannot be added later.

It ends by exporting the local CA's root to `~/.ankka/local-ca.crt` and printing the control
plane's address, `https://api.127.0.0.1.sslip.io:8443`, with the `ankka config set url` /
`config set ca` lines to use it. No port-forward anywhere. The base domain and the HTTPS host port
are written once, in `kustomization/overlays/local/platform-configmap.yaml`, and kustomize
`replacements` copy them into the wildcard `Certificate`, the `Gateway` listener, both
Deployments' `ANKKA_BASE_DOMAIN` and the control plane's own `HTTPRoute`; `ANKKA_BASE_DOMAIN` on the
script overrides the domain for a machine whose resolver blocks sslip.io.

It ends by `rollout restart`ing the operator and control plane. Without that a *re*-run changes
nothing that is running: the manifests are unchanged and the tag is still `:latest`, so `kubectl
apply` sees no difference, nothing rolls, and the pods keep executing the image they started with
while every line of output reports success.

`kustomization/components/{crd,operator,controlplane}/` hold the canonical CRD, install manifest
and RBAC — see the load-restrictor trap below for why the direction is `kustomization/` → `src/main
/resources/` symlink, not the other way round. `kustomization/components/postgres/` is a CNPG
`Cluster` for the control plane's own database, the one case in this codebase using
`bootstrap.initdb` rather than the operator's per-service `Database`/`DatabaseRole` machinery; its
schema ConfigMap is generated by the deploy script directly from
`modules/runtime/src/main/resources/ankka/ddl`, not by a kustomize generator, for the same
load-restrictor reason, plus one generated `99-grants.sql` key alongside the DDL — see the trap
above about CNPG running `postInitApplicationSQLRefs` as its own superuser, not as the role that
owns the database.

## Deploying anywhere else

`kustomization/overlays/arrakis/` (the first production cluster; production clusters are named after
planets from Dune, and `caladan` would be a copy with its own base domain) is the same components
with only what must differ: a
`LoadBalancer` instead of the kind node ports, an ACME issuer over **DNS-01** instead of a
self-signed root (a wildcard certificate cannot be had from HTTP-01), a real base domain on 443,
and Keycloak's development admin secret **deleted** rather than overridden — `admin`/`admin` is
public in this repository, so the identity provider is made to refuse to start until a real Secret
exists out of band. (The shared control plane token this once applied to no longer exists.) Images are the remaining gap: every Deployment names an unqualified image with
`imagePullPolicy: IfNotPresent`, which is right for `kind load` and useless for a cluster that
must pull, so a registry needs `DOCKER_REPOSITORY` and an `images:` block (left commented in the
overlay — a wrong registry fails minutes later as `ImagePullBackOff`, an absent one immediately).

`deploy-local.sh` will not apply it: that script refuses any context that is not the local kind
cluster, on purpose, and that guard is worth more than the convenience. Apply it by hand, after
the three CRD-bearing controllers. `RemoteOverlaySuite` renders both overlays and asserts they
differ in exactly the intended ways — it skips when `kubectl` is not on the host's PATH, which is
the only thing that can render kustomize.

Every service's own database is provisioned separately, by the operator, per `AnkkaServiceSpec` —
see `README.md`'s "Databases are provisioned automatically" for the model, and
`kustomization/components/cnpg/` for the install component itself.

## Schema

DDL lives in `modules/runtime/src/main/resources/ankka/ddl/` and is the single copy:
`docker-compose.yml` mounts that directory, and `AnkkaTestKit` copies the same files into
its container. A test can never pass against a schema local development does not have.
The journal and projection scripts are taken verbatim from the Pekko projects.

## Testing

Two levels, both real.

`EventSourcedTestKit` / `KeyValueEntityTestKit` drive one component with no actor system,
cluster or database — effects are inert values, so this is milliseconds. Inputs and
replies still round-trip through the component's own serializers, so a missing codec
fails there rather than on first deployment.

`AnkkaTestKit` boots the whole service against a throwaway Postgres. `restartService()`
drops every entity from memory, so a test can prove durability rather than caching.

`TestModelProvider` answers from a script and **fails loudly** when the script runs out —
a test whose model quietly returned a default is no longer testing what it says.

## Other agent configs

A `~/.codex/config.toml` is present. Reply `/import` to scan and list what is importable
(MCP servers, slash commands, subagents, skills, instructions), then
`/import --yes=<digest>` with the digest that scan prints to apply the user-level items.
If `/import` is unavailable here, run `claude import` from a terminal.
