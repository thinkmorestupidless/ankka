# Scala SDK

> A compact map of the Scala SDK — the artifacts, and for every component kind its base class, companion, handler declarations, effect builders and in-handler accessors.

Source: https://docs.ankka.cloud/reference/scala-sdk/
The Scala SDK is six libraries, published to Maven Central under `com.thinkmorestupidless` for Scala 3 and
JDK 21. This page lists what each component kind is made of. The guides under Build show each one in use.

## Artifacts

| Artifact | Package | Holds |
|---|---|---|
| `ankka-core` | `com.thinkmorestupidless.ankka.core` | Ids, `Done`, `Metadata`, `CommandError` and `ErrorCode`, codecs, and the effect types. No Pekko, no I/O. |
| `ankka-sdk` | `com.thinkmorestupidless.ankka.sdk` | The component base classes and companions, `ChangeSource`, and `ComponentClient`. |
| `ankka-runtime` | `com.thinkmorestupidless.ankka.runtime` | `Ankka.service`, `ProjectionRuntime`, `TimerRuntime`, `ViewClient` and SQL fragments. |
| `ankka-http` | `com.thinkmorestupidless.ankka.http` | `HttpEndpoint`, `HttpServer`, `Acl` and the request context. |
| `ankka-agent` | `com.thinkmorestupidless.ankka.agent` | `Agent`, `FunctionTool`, guardrails, session memory, `AgentRuntime` and model providers. |
| `ankka-testkit` | `com.thinkmorestupidless.ankka.testkit` | Unit testkits, `AnkkaTestKit`, `TestTransport`. `TestModelProvider` is in `ankka-agent`. |

A seventh library, `ankka-controlplane-api`, is published beside these for programs that call the
control plane rather than run as a service. It holds the control plane's request and response types and
the service descriptor's validation, and depends on `ankka-core` alone. See
[Control plane HTTP API](control-plane-api.md#from-scala).

```scala
val ankkaVersion = "0.5.0"

libraryDependencies ++= Seq(
  "com.thinkmorestupidless" %% "ankka-runtime" % ankkaVersion,
  "com.thinkmorestupidless" %% "ankka-http"    % ankkaVersion,
  "com.thinkmorestupidless" %% "ankka-agent"   % ankkaVersion,
  "com.thinkmorestupidless" %% "ankka-testkit" % ankkaVersion % Test
)
```

`ankka-runtime` brings `ankka-sdk` and `ankka-core` with it. A service created from the template already
has these lines.

## The shape every component shares

A component is a class and a companion object:

- The **class** extends the kind's base class and holds the handlers. It reads state through what the
  runtime gives it, such as `currentState`, and keeps none of its own.
- The **companion** extends the kind's `Companion`, names the component with a `ComponentId`, supplies
  serializers, constructs the class in `create`, and declares each handler with its wire name.

```scala
object ShoppingCartEntity
    extends EventSourcedEntity.Companion[ShoppingCartEntity, ShoppingCart, ShoppingCartEvent](
      componentId = ComponentId("shopping-cart"),
      stateSerializer = Codecs.serializer[ShoppingCart]("shopping-cart"),
      eventSerializer = Codecs.serializer[ShoppingCartEvent]("shopping-cart-event")
    ):
  def create(context: EventSourcedEntityContext) = new ShoppingCartEntity(context)

  val addItem = command("add-item")(_.addItem)
  val getCart = query("get-cart")(_.getCart)
```

`companion.descriptor` is what `Ankka.service.register` takes. A handler's argument and reply types each
need a `Serializer` in scope; `Codecs.serializer[A]("manifest")` derives a JSON one, and
`import com.thinkmorestupidless.ankka.core.Serializers.given` supplies them for primitives such as `String`, `Int`, `Long` and `Boolean`, and for `Done`.

## Event sourced entity

| Part | API |
|---|---|
| Base class | `EventSourcedEntity[S, E]` |
| Companion | `EventSourcedEntity.Companion[C, S, E](componentId, stateSerializer, eventSerializer)` |
| Must define | `emptyState: S`, `applyEvent(event: E): S`, `create(ctx: EventSourcedEntityContext)` |
| Handlers | `command(name)(_.method)`, `query(name)(_.method)`; with one argument or none |
| In a handler | `currentState`, `commandContext` (`entityId`, `metadata`, `sequenceNumber`) |
| Effects | `effects.persist(e, more*)`, `persistAll(events)`, `deleteEntity()`, then `.thenReply(s => r)`, `.thenReplyState`, `.thenNoReply`, `.deleteEntity()`, `.expireAfter(d)`; `effects.reply(r)`, `effects.error(msg, code)`, `effects.noReply` |
| Types | A command returns `Effect[R]`; a query returns `ReadOnlyEffect[R]` |
| Snapshots | `snapshotEvery: Option[Int]`, `Some(100)` by default |

See [Event sourced entities](../build/event-sourced-entities.md).

## Key value entity

| Part | API |
|---|---|
| Base class | `KeyValueEntity[S]` |
| Companion | `KeyValueEntity.Companion[C, S](componentId, stateSerializer)` |
| Must define | `emptyState: S`, `create(ctx: KeyValueEntityContext)` |
| Handlers | `command(name)(_.method)`, `query(name)(_.method)` |
| In a handler | `currentState`, `commandContext` |
| Effects | `effects.updateState(s)`, `deleteEntity()`, then `.thenReply(s => r)`, `.thenReplyState`, `.thenNoReply`, `.expireAfter(d)`; `effects.reply(r)`, `effects.error(msg, code)`, `effects.noReply` |

See [Key value entities](../build/key-value-entities.md).

## View

| Part | API |
|---|---|
| Base class | `View[Src, Row]` |
| Companion | `View.Companion[V, Src, Row](componentId, source, rowSerializer)` |
| Source | `ChangeSource.eventsOf(EntityCompanion)`, `ChangeSource.stateOf(KeyValueCompanion)`, `ChangeSource.fromTopic(name, serializer)` |
| Must define | `onChange(change: Src): Effect`, `create(ctx: ViewComponentContext)` |
| May override | `onDelete: Effect` (default: delete the row), `parallelism` (default 4) |
| In a handler | `rowState: Option[Row]`, `updateContext` (`subject`, `sequenceNumber`, `localOrigin`) |
| Effects | `effects.updateRow(row)`, `effects.deleteRow()`, `effects.ignore()` |
| Querying | `viewClient.forView(Companion)` then `get(key)`, `all()`, `where(sql)`, `ordered(sql, order)`, `count(sql)`, each with an `…Async` form |

Rows are keyed by the source's subject: the entity id, or a topic message's `ce-subject`. Conditions are SQL
fragments over the row's JSON, built with `jsonText("field") ++ sql" = $value"` from
`com.thinkmorestupidless.ankka.runtime.SqlSyntax`. See [Views](../build/views.md).

## Consumer

| Part | API |
|---|---|
| Base class | `Consumer[Src, Out]` |
| Companion | `Consumer.Companion[C, Src, Out](componentId, source)` |
| Must define | `onMessage(message: Src): Effect`, `create(ctx: ConsumerContext)` |
| May override | `onDelete: Effect` (default: ignore), `produceTo: Option[String]`, `outputSerializer: Option[Serializer[Out]]`, `parallelism` (default 4) |
| In a handler | `messageContext` (`subject`, `sequenceNumber`, `localOrigin`); the context's `componentClient` |
| Effects | `effects.produce(out)`, `effects.produce(out, metadata)`, `effects.produceAll(messages)`, `effects.done()`, `effects.ignore()` |
| One of several messages | `effects.message(out)`, then `.withKey(key)` to publish it under a record key other than its subject and `.withMetadata(metadata)` for its headers |

`produceAll` publishes its messages in order, and the change is handled when the broker has accepted all
of them; an empty list is handled at once. See [Consumers](../build/consumers.md) and
[Broker topics](../build/topics.md).

## Graph consumer

A consumer that publishes its source as graph deltas, in `com.thinkmorestupidless.ankka.sdk.graph`. It is
registered as a consumer and can publish nothing but deltas.

| Part | API |
|---|---|
| Base class | `GraphConsumer[Src]` |
| Companion | `GraphConsumer.Companion[C, Src](componentId, source, topic)` |
| Must define | `onMessage(message: Src): Effect`, `create(ctx: ConsumerContext)` |
| May override | `onDelete: Effect` (default: ignore), `parallelism` (default 4) |
| In a handler | `messageContext` (`subject`, `sequenceNumber`), `graph`, `effects` |
| Elements | `graph.node(id, labels, properties)`, `graph.edge(id, type, from, to, properties)`, `graph.tombstoneNode(id)`, `graph.tombstoneEdge(id, type, from, to)`; `.at(version)` states a version |
| Effects | `effects.publish(elements*)`, `effects.done()`, `effects.ignore()` |
| A refusal | `GraphElementRefused`, an `IllegalArgumentException` whose `why` names the rule broken |

A delta as it is published and read is a `GraphDelta`, in `com.thinkmorestupidless.ankka.core.graph`:

| Part | API |
|---|---|
| Fields | `kind` (`Node`, `Edge`, `Tombstone`), `element` (`Node`, `Edge`), `id`, `version`, `labels`, `edgeType`, `from`, `to`, `properties` |
| Derived | `key`, the record key `node:<id>` or `edge:<id>`; `isTombstone` |
| Reading | `GraphDelta.read(value)`, and `GraphDelta.read(key, value)`, which also checks the key; both answer `Either[String, GraphDelta]` |
| Serializer | `GraphDelta.serializer`, under the manifest `GraphDelta.SchemaName`, `ankka.graph-delta.v1` |
| Keys | `GraphDelta.nodeKey(id)`, `GraphDelta.edgeKey(id)` |

Property values are `String`, `Boolean`, `Int`, `Long`, `Double`, or a `Seq` of one of them. They are
normalised as the reader of the topic stores them: every integer is a `Long`, and a `Double` with a whole
value is that `Long`. See [Publish a graph](../build/graph.md).

## Workflow

| Part | API |
|---|---|
| Base class | `Workflow[S]` |
| Companion | `Workflow.Companion[W, S](componentId, stateSerializer)` |
| Must define | `emptyState: S`, `create(ctx: WorkflowContext)` |
| May override | `settings: WorkflowSettings` |
| Handlers | `command(name)(_.method)`, `query(name)(_.method)`, `step(name)(_.method)` with or without input |
| In a handler | `currentState`, `commandContext`; the context's `workflowId` and `componentClient` |
| Command effects | `effects.updateState(s)`, `effects.transitionTo(stepRef)`, `effects.delete()`, then `.transitionTo(stepRef)`, `.thenReply(r)`, `.thenReplyState`, `.thenNoReply`; `effects.reply(r)`, `effects.error(msg, code)` |
| Step effects | `stepEffects.updateState(s)` then, or directly: `.thenTransitionTo(stepRef)`, `.thenPause()`, `.thenPause(after, onTimeout)`, `.thenEnd`, `.thenFail(msg)` |
| Step references | `step.withInput(value)` for a step with input; `step.ref` for one without |
| Settings | `WorkflowSettings.builder.timeout(d).defaultStepTimeout(d).stepTimeout(step, d).defaultRecovery(r).stepRecovery(step, r).build` |
| Recovery | `RecoverStrategy.fail`, `RecoverStrategy.maxRetries(n)`, `.failoverTo(step)` |
| Engine state | `componentClient.forWorkflow(id).lifecycle(Companion).invoke()` |

See [Workflows](../build/workflows.md).

## Timed action and timers

| Part | API |
|---|---|
| Base class | `TimedAction` |
| Companion | `TimedAction.Companion[A](componentId)` |
| Must define | `create(ctx: TimedActionContext)` |
| Handlers | `handler(name)(_.method)`, with one argument or none |
| In a handler | `timerContext` (`timerName`, `previousAttempts`, `componentClient`) |
| Effects | `effects.done()`, `effects.error(msg)`, `effects.error(msg, code)` |
| Scheduling | `TimerRuntime().timerScheduler`: `createSingleTimer(name, delay, handle.deferred(input))`, `delete(name)`, `exists(name)` |

Scheduling twice under one name replaces the earlier timer. See [Timers](../build/timers.md).

## Agent

| Part | API |
|---|---|
| Base class | `Agent` |
| Companion | `Agent.Companion[A](componentId)` |
| Must define | `create(ctx: AgentContext)` |
| May override | `role` (default: the component id), `maxToolCallSteps` (default 100) |
| Handlers | `command(name)(_.method)`, `stream(name)(_.method)` |
| In a handler | `sessionId`, `componentClient`, `sessionContext` |
| Effects | `effects.systemMessage(t)`, `.userMessage(t)`, `.withContext(t)`, `.model(p)`, `.memory(m)`, `.tools(t*)`, `.guardrails(g*)`, then `.thenReply()`, `.thenReplyAs[T]`, `.thenStream()`; `effects.error(msg, code)` |
| Tools | `FunctionTool.named(n).describedAs(d).param[T](name, description)….handle { … }` |
| Guardrails | `Guardrail.maxInputLength(n)`, `Guardrail.forbidding(name, regex)`, or implement `checkInput` / `checkOutput` |
| Memory | `MemoryProvider.none`, `MemoryProvider.limitedWindow`, `.readLast(n)`, `.readOnly`, `.writeOnly`, `.filtered(MemoryFilter…)` |
| Runtime | `AgentRuntime.withDefaultModel(provider)`, `.withCompaction(CompactionSettings(…))`, `.descriptors` |
| Calling | `componentClient.forAgent(SessionId(id)).call(Companion.handler).invoke(input)`, `.stream(Companion.handler)(input)` |
| Models | `AnthropicProvider.fromEnv(model)`, `TestModelProvider()` |

See [Agents](../build/agents.md), [Streaming responses](../build/streaming.md) and
[Multi-agent orchestration](../build/multi-agent-orchestration.md).

## Judgments

| Part | API |
|---|---|
| Questions | `Question.choice[T](id, instructions)(value -> (key, description), …)`, `Question.choiceByKey(id, instructions)(key -> description, …)`, `Question.score(id, instructions)(levels*)`, `Question.yesNo(id, instructions)`, `.describing(yes, no)` |
| Answers | `judgment(question)` → `ChoiceAnswer[T](choice, probabilities, confidence)`, `ScoreAnswer(score, probabilities, confidence)` with `.level`, `YesNoAnswer(probability)`; `judgment.model`, `judgment.usage`, `judgment.contains(question)` |
| Effect | `effects.judgment.state(text)` or `.state(value)` (needs a `JsonValueCodec`), `.question(q, more*)`, `.provider(p)`, then `.thenReply()` or `.thenReply(judgment => value)` |
| Runtime | `AgentRuntime.withJudgments(provider, timeout)` (5 seconds by default), on either `AgentRuntime()` or `withDefaultModel(…)` |
| Providers | `JevProvider.fromEnv(model)` (reads `TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL`), `JevProvider.withApiKey(key, model, baseUrl)`; `DefaultModel` is `jev-1.13.0`; or implement `JudgmentProvider` |
| Guardrails | `Guardrail.judged(name).onInput(rules*).onOutput(rules*).provider(p)`; rules `Refuse.ifYes(q, atLeast)`, `Refuse.ifChosen(q, minConfidence)(options*)`, `Refuse.ifScore(q, atLeast)`, `Refuse.when(q)(answer => Boolean)` |
| Failures | `JudgmentFailed(provider, message, cause, timedOut)`; a judgment effect's is `Unavailable` or `Timeout`, a judged guardrail's could-not-check is `Unavailable` or `Timeout`, its refusal `Forbidden` |
| Testing | `TestJudgmentProvider().expect(answers*)`, `.always(answers*)`, `.failNext(message, timedOut)`, `.reporting(usage)`, `.requests`, `.lastRequest`, `.callCount`, `.reset()`; `Answers.choice`, `.choiceWith`, `.score`, `.scoreWith`, `.yesNo` |
| Usage | `forSessionMemory(id)` → `history` has `judgmentUsage` beside the text model's `usage` |

See [Judgments](../build/judgments.md).

## Autonomous agent

| Part | API |
|---|---|
| Task type | `Task.named(wireName).describedAs(d)` (a text result), `.resultConformsTo[R]` (needs a `JsonValueCodec[R]` and a `JsonSchema[R]`), `.rule(name)(r => TaskRule.Accepted \| TaskRule.Rejected(reason))` |
| Result schema | `given JsonSchema[R] = JsonSchema.derived` for a case class |
| Base class | `AutonomousAgent(context)`; override `tools: Seq[FunctionTool]` |
| Companion | `AutonomousAgent.Companion[A](componentId)`; define `create(context)` and `definition` |
| Definition | `define.describedAs(d).instructions(t).guardrails(g*).model(p).capability(TaskAcceptance.of(task).maxIterationsPerTask(n)).settings(AutonomousAgentSettings(…))` |
| In a tool | `context.componentClient`, `context.instanceId`, `AutonomousAgent.currentTask` |
| Tasks | `componentClient.tasks.create(task, instructions).withId(id).attach(…).attachReference(…).dependsOn(ids*).create()` |
| A task | `componentClient.forTask(id).get()`, `.get(task)`, `.await(task, timeout)`, `.cancel(reason)` |
| An instance | `componentClient.forAutonomousAgent(Companion)(instanceId).assign(ids*)`, `.suspend()`, `.resume()`, `.terminate()`, `.state()`, `.notifications()` |
| One task | `componentClient.forAutonomousAgent(Companion).runSingleTask(task, instructions)` |
| Runtime | the `AgentRuntime` hosts them; register `AgentRuntime.descriptors` and a `ProjectionRuntime()` |
| Testing | `TestModelProvider().expectCompleteTask(result)`, `.expectCompleteTaskJson(json)`, `.expectCompleteTaskText(t)`, `.expectFailTask(reason)`, `.whenToolResult(s)(r)`, `.whenUserAsks(s)(r)`; `AnkkaTestKit.awaitTask(id, task)` |

See [Autonomous agents](../build/autonomous-agents.md).

## HTTP endpoint

| Part | API |
|---|---|
| Base class | `HttpEndpoint(prefix)` |
| Must define | `acl: Acl` |
| Routes | `get`, `post`, `put`, `patch`, `delete` with path parameters only; `postBody`, `putBody`, `patchBody` with a body as the last argument; `sse` and `sseBody` for server-sent events |
| In a handler | `request` (`header`, `query`, `caller`), `query` (`required`, `optional`, `all`, `flag`), `principal`, `caller` (`Caller.Gateway`, `Caller.Service(project, name)`, `Caller.Local`) |
| ACLs | `Acl.DenyAll`, `Acl.AllowAll`, `Acl.AllowIf(ctx => …)`, `Acl.Authenticate(ctx => AuthDecision…)`, `Acl.allowCallers(Callers.internet, Callers.service("orders"), Callers.service(project, name), Callers.anyInProject, Callers.self)` |
| Other services | `clients.services(name)` or `clients.services(project, name)`: `get[R]`, `getText`, `post[B, R]`, `put[B, R]`, `delete`, `request` |
| Testing a caller | `testKit.asCaller(caller)` gives the header that makes a request arrive as that caller |
| Errors | throw `HttpProblem(status, message)` or `HttpProblem.badRequest`, `unauthorized`, `forbidden`, `notFound`, `conflict` |
| Serving | `HttpServer.of(clients => Endpoint(clients.componentClient))`, `HttpServer.at(interface, port)(…)` |

Handler parameters must be annotated with their type, `{ (cartId: String) => … }`, because the annotation
selects the route's arity and how each path segment is parsed. See [HTTP endpoints](../build/http-endpoints.md).

## Calling components

| Target | Call |
|---|---|
| Event sourced entity | `componentClient.forEventSourcedEntity(EntityId(id)).call(Companion.handler)` |
| Key value entity | `componentClient.forKeyValueEntity(EntityId(id)).call(Companion.handler)` |
| Workflow | `componentClient.forWorkflow(EntityId(id)).call(Companion.handler)` |
| Agent | `componentClient.forAgent(SessionId(id)).call(Companion.handler)` |

An invocation is `.invoke(input)` or `.invoke()`, which blocks, or `.invokeAsync(…)`, which returns a
`Future`. `.withMetadata(metadata)` attaches metadata. A refusal is thrown as `CommandError`. See
[Calling components](../build/component-client.md).

## Running a service

```scala
val service = Ankka.service
  .register(ShoppingCartEntity.descriptor)
  .register(CartRows.descriptor)
  .withExtension(ProjectionRuntime())
  .withExtension(TimerRuntime())
  .withExtension(HttpServer.of(clients => ShoppingCartEndpoint(clients.componentClient)))
  .start()
```

| Extension | Needed for |
|---|---|
| `ProjectionRuntime()` | Views and consumers over entities. `.withPublisher(p)`, `.withBroker(p, s)` or `.withKafka(servers)` for topics. |
| `TimerRuntime()` | Timed actions and `timerScheduler`. |
| `HttpServer.of(…)` | HTTP endpoints. |
| `AgentRuntime…` | Agents; also register `agents.descriptors`. |

`service.whenTerminated` completes when the service stops, and `service.terminate()` stops it.

## Testing

| Kit | Drives |
|---|---|
| `EventSourcedTestKit.of(Companion, id)` | One event sourced entity, no runtime. `call(handler)(input)` returns the events, reply or error. |
| `KeyValueEntityTestKit.of(Companion, id)` | One key value entity, no runtime. |
| `ConsumerTestKit.of(Companion, client)` | One consumer, no runtime. `onMessage(message, subject, sequenceNumber)` and `onDelete(subject, sequenceNumber)` return the effect and the `messages` it publishes: `payload`, the record `key` a broker is given, `metadata`, `text`. |
| `ConsumerTestKit.graph(Companion, client)` | One graph consumer, no runtime. The same two calls return the `GraphDelta`s published, read back from their bytes; `records` gives the same consumer as messages. |
| `TestTransport` | A `ComponentClient` whose calls are stubbed, for testing a component that calls others. |
| `AnkkaTestKit.start(descriptors…)` | The whole service against a throwaway Postgres. `restartService()` drops every entity from memory. |
| `TestModelProvider` | A scripted model that fails when its script runs out. |

See [Testing](../build/testing.md).
