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
sbt test                          # all 349 tests, ~4min
sbt agent/test                    # one module: core sdk runtime http agent testkit
sbt controlPlane/test             # control plane: controlPlaneApi controlPlane cli
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
controlplane-api + sdk + runtime + http → controlplane   (cli, testkit are Test-only deps)
```

`controlplane-api` depends on `core` only — not on Pekko — so the CLI carries no actor
system, no database driver and no Kubernetes client. It holds the wire types *and* the
descriptor validation rules, so both ends apply the same checks; it depends on `core`
rather than redefining a codec because jsoniter needs its config inlined at the call
site, and `Codecs.make` already does that.

`controlplane` takes `cli % Test` so one suite can drive the real `Main.run` against a
real control plane. That is the only test that can catch the two ends disagreeing about
the wire format.

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
- **A CLI's `main` should be a one-line wrapper.** `Main.run(args, out, err): Int`
  returns the exit code and `main` calls `sys.exit` on it; `sys.exit` inside the command
  logic would kill the test JVM.

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
