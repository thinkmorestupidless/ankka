# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`nakka` reimplements [Akka's](https://doc.akka.io/) component model — a serverless
platform for agentic AI — in Scala 3 on Apache Pekko. Pekko is the Apache 2.0 fork of
Akka 2.6, chosen so the programming model carries no BSL constraint.

`README.md` is the user-facing reference (component model, agents, streaming, topics,
divergences from Akka, and an honest "Not implemented" list). `docs/orchestration.md`
covers multi-agent patterns. Read both before making design decisions.

## Commands

Docker is required — integration suites start their own Postgres, and one starts Kafka,
via testcontainers. No API key is needed.

```bash
sbt test                          # all 245 tests, ~2min
sbt agent/test                    # one module: core sdk runtime http agent testkit
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
```

`AnthropicProviderSuite` exercises the live API and **skips** unless `ANTHROPIC_API_KEY`
is set. Everything else is deterministic and offline.

**Tests are serialized deliberately** (`Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)`
and `Test / parallelExecution := false` in `build.sbt`). Overlapping suites each start
their own container and contend: one suite measured 147s in parallel versus 6s alone.
Do not "optimise" this back.

**scalafmt is configured but the tree is not formatted to it** — `sbt scalafmtCheckAll`
currently fails on ~113 files. Running `scalafmtAll` would rewrite essentially the whole
codebase. Decide deliberately before doing that; don't run it as incidental cleanup.

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
```

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
