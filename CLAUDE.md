# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`nakka` reimplements [Akka's](https://doc.akka.io/) component model — a serverless
platform for agentic AI — in Scala 3 on Apache Pekko. Pekko is the Apache 2.0 fork of
Akka 2.6, chosen so the programming model carries no BSL constraint.

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
sbt -Dnakka.cluster.tests=off test  # skip the three k3s suites AND the sample image build they
                                   # need; everything else still runs, in about a minute
sbt agent/test                    # one module: core sdk runtime http agent testkit
sbt operator/test                 # the Kubernetes operator (one k3s suite)
sbt controlPlane/test             # control plane: controlPlaneApi crd controlPlane cli operator
sbt docker:publishLocal           # build all three images — aggregates to operator,
                                   # controlPlane and the shoppingCart sample; every other
                                   # project is silently skipped, same as compile and test
sbt shoppingCart/Docker/publishLocal  # just the sample's image — what `testOnly` on
                                   # SampleDeploymentClusterSuite needs and does not build
sbt buildAll                      # everything: format check, compile, test, every image —
                                   # one command, stops at the first failing stage
sbt shoppingCart/test             # samples: shoppingCart multiAgentPlanner
sbt 'testkit/testOnly nakka.testkit.WorkflowSuite'
sbt 'agent/testOnly nakka.agent.CompactionSuite -- *transcript*'   # one case (munit glob)
sbt compile                       # should be warning-free; -Wunused is on
```

Running the samples needs the bundled Postgres:

```bash
docker compose up -d
sbt shoppingCart/run              # HTTP on :9000
ANTHROPIC_API_KEY=sk-ant-... sbt multiAgentPlanner/run
NAKKA_CONTROLPLANE_TOKEN=dev sbt controlPlane/run
sbt 'cli/run services list --url http://localhost:9000 --token dev -p checkout'
```

`AnthropicProviderSuite` exercises the live API and **skips** unless `ANTHROPIC_API_KEY`
is set. Everything else is deterministic and offline.

**Tests are serialized deliberately** (`Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)`
and `Test / parallelExecution := false` in `build.sbt`). Overlapping suites each start
their own container and contend: one suite measured 147s in parallel versus 6s alone.
Do not "optimise" this back.

```bash
sbt scalafmtAll scalafmtSbt        # format; scalafmtCheckAll verifies
```

`SortModifiers` is deliberately absent from `.scalafmt.conf`: it rewrites
`private[nakka] final` to `final private[nakka]`, which is scalafmt's canonical order but
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
crd → operator                                           (no nakka dependencies at all)
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
It is gated on `-Dnakka.cluster.tests`, so switching the suites off skips the build as well.

`crd` depends on **nothing** — not even `core`. It holds the `NakkaService` resource, and
both the control plane and the operator have to hold it without inheriting the other's
world. `operator` depends only on `crd` and a Kubernetes client, which makes "the operator
cannot reach into the control plane" a build-level fact rather than a convention.

`runtime` depends on `sdk`, not the reverse: the runtime interprets the SDK's
descriptors, so it must see them. This was inverted in the original plan and had to be
corrected.

`ComponentClient` therefore lives in `sdk` over a `CallTransport` interface, with
`runtime` supplying the sharding-backed implementation. Components receive it through
their context, because nakka has no container — a component is constructed by its own
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
`StateRecord` via Pekko event and snapshot adapters, so the journal holds nakka's JSON
under nakka's manifest rather than a reflected form of the domain type.

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

`nakka-runtime` must not depend on `nakka-http` or `nakka-agent`, so anything that needs
the service to exist before starting plugs in as a `RuntimeExtension`:
`HttpServer`, `ProjectionRuntime`, `TimerRuntime`, `AgentRuntime`. Extensions take
factories rather than instances where a dependency needs the `ActorSystem`.

`EntityProtocol.Command` is shared by every sharded host so the transport needs one
sharding key type. **`EntityProtocol.ModuleCommand` opens that hierarchy**, so the
compiler no longer reports non-exhaustive matches over `Command` — every host must handle
unexpected commands explicitly, and for `InvokeStream` that means *replying*, since a
caller waiting on a token stream would otherwise hang forever. An earlier comment
claiming exhaustivity was preserved was wrong; that mistake let exactly that hang in.

### Virtual threads

Endpoints, workflow steps, consumers, timers and agent loops all run on
`NakkaExecutors.virtual`. That is what makes the blocking `ComponentClient.invoke` free —
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

The control plane projects a service's desired state into a `NakkaService` custom resource
and folds the status back; an in-cluster **operator** watches those resources and owns
everything below — namespace, Deployment, reported status. The resource is the only thing
either side knows about the other.

The split is what buys cascade deletion (owner references, so there is no orphan sweep
anywhere in this design), sub-second change notification (watches, not polling), and a
control plane that holds no credential able to create a workload. Doing it in one process
was the original plan and was rejected on review — it reimplemented all three.

The operator is deliberately **not** a nakka application. It has no entities, no journal
and no sharding, so hosting it on nakka would give it a cluster to form and a database not
to use — and a process whose entire job is to keep working while other things are broken
should depend on as little as possible.

`Action` values are inert descriptions of cluster mutations and `Fabric8Executor` is the
only thing that performs them, which is the same organising idea as the component effects.

### The control plane is a nakka application

`ControlPlane.components` and `ControlPlane.endpoints` are the whole inventory: three
event sourced entities (organization, project, service), three views for listing, three
endpoints. `ControlPlane.builder` also registers `ProjectionRuntime()` — without it every
listing stays permanently empty while every write succeeds.

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
- **A fieldless Scala 3 enum encodes as `{"type":"Ready"}` under nakka's shared codec
  config.** The discriminator is right for events and wrong for a status word a CLI
  prints. Give such an enum an explicit string `JsonValueCodec` **in its companion
  object**, so it is in implicit scope everywhere the enum appears — putting it at each
  derivation site invites missing one and shipping two wire formats.
  (`ServiceLifecycle` does this.)
- **Anything reading `~/.nakka/config.json` or `$HOME` must be overridable by a system
  property.** Environment variables cannot be set in-process, so `NAKKA_CONFIG` alone
  makes `config set` untestable without writing to the developer's own home directory.
  `Settings.path` checks `-Dnakka.config` first for exactly this reason.
- **Read piped input through `Console.in`, not `System.in`.** Only the former is
  redirectable by `Console.withIn`, which is what lets a test drive `apply -f -` without
  spawning a subprocess.
- **A Deployment's `spec.selector` is immutable.** It must never contain nakka's
  generation, or the second apply is rejected permanently and the service is bricked at
  generation 2. The generation goes on the *pod template's* annotations instead, which is
  also what makes a restart roll the pods — so restart and apply are one mechanism.
- **`strategy: Recreate`, always — a rolling update is a second replica by another name.**
  Kubernetes' default is `RollingUpdate` with `maxSurge: 25%`, which at one replica rounds up to a
  whole extra pod: old and new run side by side on every deploy and restart, each its own
  single-node cluster, both writing one journal, with the Service routing to both. It breaks
  "never more than one replica" without touching the replica count. The platform's own manifests
  always said `Recreate`; rendered workloads did not, unnoticed for two features because `pause`
  has no journal and, with no readiness probe, the overlap lasted a second. The price is honest:
  at one replica a deploy is a brief outage.
- **Changing a field Kubernetes defaulted can wedge every existing object.** Moving a live
  Deployment to `Recreate` is rejected — `invalid: spec.strategy` — while its defaulted
  `rollingUpdate` block is still there, and server-side apply cannot remove a field no manager
  owns. Every reconcile then fails forever. A JSON merge patch can (`"rollingUpdate": null`);
  `Fabric8Executor` does exactly that, once, on that exact 422. **Tests that start from an empty
  cluster cannot see this class of bug** — it took a real cluster with a real leftover object.
- **Never render a HorizontalPodAutoscaler, and never more than one replica.** Each pod
  joins itself as a single-node cluster, so a second replica is a second writer to the
  same journal. An autoscaler reaches that state on its own.
- **Two things are called "generation".** nakka's lives in the resource's `spec` and is
  what `Service.onObserved` compares; Kubernetes' is `metadata.generation` and is only
  meaningful against `status.observedGeneration`. Conflating them reports the right answer
  about the wrong generation.
- **Two nakka services must never share a Postgres database.** `nakka_timers` has no
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
  admin credentials directly rather than exercising the shipped RBAC — one exception mints
  a real token for the operator's own ServiceAccount to prove a withheld verb is refused by
  the API server itself, not just unused (`OperatorClusterSuite`, feature 002 US5).
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
  as `sbt -Dfoo=bar test` is set in a JVM that runs no tests. `-Dnakka.cluster.tests=off` was a
  documented no-op for two features — the "skipped" suites quietly took seven minutes — until
  `Test / javaOptions` started forwarding it. Any new test switch needs the same forwarding.
- **A service's default descriptor now asserts something.** Saying nothing means "serves HTTP on
  9000" and the pod is not `Ready` until that port opens. Right for a nakka service; an image that
  listens on nothing (`pause`) needs `"http": false` or it is `Failed` when the rollout deadline
  passes. Every `pause` descriptor in the test suites carries it.
- **Kustomize's load restrictor is checked per component directory, not against the
  top-level build root.** A component cannot reference a file outside its own directory —
  not via a relative path, not via a symlink resolving there — even when a common ancestor
  contains both. There is no override for `kubectl apply -k`. The CRD, the operator's
  install manifest and the control plane's RBAC are therefore canonical *inside*
  `kustomization/components/`, with `operator/src/main/resources/nakka/{crd,install}/` and
  `controlplane/src/main/resources/nakka/install/` holding symlinks *into* them — the
  reverse of the direction that seems obvious, and the only direction that works, since sbt
  and the JVM follow symlinks transparently but kustomize does not. Never `ln -sf` onto a
  path that might already hold the real content; copy it out first. This one cost real file
  content, recovered only because the compiled classpath still had it.
- **A CLI's `main` should be a one-line wrapper.** `Main.run(args, out, err): Int`
  returns the exit code and `main` calls `sys.exit` on it; `sys.exit` inside the command
  logic would kill the test JVM.

## Deploying locally

```bash
kind create cluster --name nakka
./kustomization/deploy-local.sh
```

Installs [CloudNativePG](https://cloudnative-pg.io/) (server-side apply — its CRDs are too large
for client-side apply's own annotation-size limit), builds all three images — the operator, the
control plane and the shopping cart sample — loads them into the cluster with `kind load
docker-image`, and applies the CRD, the operator, the control plane's own
CNPG-managed database and the control plane itself via `kubectl apply -k`. No registry —
`DOCKER_REPOSITORY` in `build.sbt`'s `dockerSettings` is the one setting that changes once one
exists. The script checks `kubectl config current-context` and refuses to run against anything
other than the `kind-*` cluster it targets.

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
`modules/runtime/src/main/resources/nakka/ddl`, not by a kustomize generator, for the same
load-restrictor reason, plus one generated `99-grants.sql` key alongside the DDL — see the trap
above about CNPG running `postInitApplicationSQLRefs` as its own superuser, not as the role that
owns the database.

Every service's own database is provisioned separately, by the operator, per `NakkaServiceSpec` —
see `README.md`'s "Databases are provisioned automatically" for the model, and
`kustomization/components/cnpg/` for the install component itself.

## Schema

DDL lives in `modules/runtime/src/main/resources/nakka/ddl/` and is the single copy:
`docker-compose.yml` mounts that directory, and `NakkaTestKit` copies the same files into
its container. A test can never pass against a schema local development does not have.
The journal and projection scripts are taken verbatim from the Pekko projects.

## Testing

Two levels, both real.

`EventSourcedTestKit` / `KeyValueEntityTestKit` drive one component with no actor system,
cluster or database — effects are inert values, so this is milliseconds. Inputs and
replies still round-trip through the component's own serializers, so a missing codec
fails there rather than on first deployment.

`NakkaTestKit` boots the whole service against a throwaway Postgres. `restartService()`
drops every entity from memory, so a test can prove durability rather than caching.

`TestModelProvider` answers from a script and **fails loudly** when the script runs out —
a test whose model quietly returned a default is no longer testing what it says.

## Other agent configs

A `~/.codex/config.toml` is present. Reply `/import` to scan and list what is importable
(MCP servers, slash commands, subagents, skills, instructions), then
`/import --yes=<digest>` with the digest that scan prints to apply the user-level items.
If `/import` is unavailable here, run `claude import` from a terminal.
