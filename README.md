# nakka

A serverless application platform for agentic AI, built on the actor model — a
reimplementation of [Akka's](https://doc.akka.io/) component model in Scala 3 on
[Apache Pekko](https://pekko.apache.org/).

You write components; nakka supplies the runtime. Sharding, persistence, replay,
projections, durable orchestration and the agent loop are the platform's problem, not
yours.

```scala
final class ShoppingCartEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[ShoppingCart, ShoppingCartEvent]:

  def emptyState: ShoppingCart = ShoppingCart.empty(context.entityId)

  def applyEvent(event: ShoppingCartEvent): ShoppingCart = event match
    case ItemAdded(item)        => currentState.addItem(item)
    case ItemRemoved(productId) => currentState.removeItem(productId)
    case CheckedOut             => currentState.onCheckedOut

  def addItem(item: LineItem): Effect[Done] =
    if currentState.checkedOut then effects.error("cart is already checked out", Conflict)
    else effects.persist(ItemAdded(item)).thenReply(_ => Done)
```

## Why Pekko

Akka moved to the Business Source Licence. Pekko is the Apache 2.0 fork of Akka 2.6 with
the same APIs — typed actors, cluster sharding, persistence, projections, streams, HTTP —
so nakka can offer Akka's programming model with no licence constraint on who runs it.

## Getting started

```bash
docker compose up -d                 # Postgres: journal, views, timers, offsets
sbt "shoppingCart/run"               # HTTP on :9000
```

```bash
curl -X POST localhost:9000/carts/c1/items \
  -H 'Content-Type: application/json' \
  -d '{"productId":"p1","name":"Widget","quantity":2}'

curl localhost:9000/carts/c1
curl -X POST localhost:9000/carts/c1/checkout
```

The agentic sample needs a key:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
sbt "multiAgentPlanner/run"
```

## The component model

| Component | What it is | Hosted as |
|---|---|---|
| **Event Sourced Entity** | State derived by replaying persisted events | `EventSourcedBehavior` in cluster sharding |
| **Key Value Entity** | Latest value only, no history | `DurableStateBehavior` in cluster sharding |
| **View** | Queryable projection of a source's changes | Pekko Projection → Postgres row table |
| **Consumer** | Reacts to changes, optionally publishes onward | Pekko Projection, at-least-once |
| **Workflow** | Durable multi-step process | `EventSourcedBehavior` whose events *are* step transitions |
| **Timer** | A call the runtime makes later | Postgres table + cluster-singleton sweeper |
| **Agent** | Carries out a task by talking to a model | Sharded per **session id**, serialized per conversation |
| **HTTP Endpoint** | The outside world | pekko-http route tree |

### Effects are data

Every handler returns a *description* of what should happen. Building one performs no
I/O, reads no state and calls no model — which is why a component's decision-making can
be unit-tested with nothing running, and why the runtime is free to decide *how*.

```scala
effects.persist(ItemAdded(item)).thenReply(_ => Done)   // event sourced entity
effects.updateState(profile).thenReplyState             // key value entity
stepEffects.updateState(s).thenTransitionTo(deposit)    // workflow step
effects.systemMessage("...").tools(getWeather).thenReply()  // agent
```

### Registration is explicit

There is no classpath scanning. A service's components are a value you can inspect,
diff and test, and a component that was never registered fails at startup rather than
at its first request.

```scala
val service = Nakka.service
  .register(ShoppingCartEntity.descriptor)
  .register(CartRows.descriptor)
  .withExtension(ProjectionRuntime.withPublisher(publisher))
  .withExtension(HttpServer.of(ShoppingCartEndpoint(_)))
  .start()
```

Handlers are registered on the companion, which is what replaces Akka's
`Entity::method` lambda-bytecode inspection:

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

`command` and `query` differ by more than name: `query` accepts only a `ReadOnlyEffect`,
so "this handler cannot persist" is enforced by the compiler rather than by convention.

Call sites are then fully typed, with no reflection anywhere:

```scala
componentClient.forEventSourcedEntity(cartId).call(ShoppingCartEntity.addItem).invoke(item)
```

### Blocking is free

`invoke` blocks; `invokeAsync` returns a `Future`. Endpoints, workflow steps, consumers,
timers and agent loops all run on virtual threads, so an await parks the virtual thread
and releases its carrier. Sequential code stays readable *and* cheap.

## Agents

```scala
final class WeatherAgent extends Agent:
  def ask(question: String): Effect[String] =
    effects
      .systemMessage("You are a concise weather assistant.")
      .userMessage(question)
      .tools(WeatherAgent.getWeather)
      .thenReply()
```

**Tools are declared, not annotated.** The same `SchemaType` instance emits the JSON
Schema *and* decodes the arguments, so a parameter cannot be described one way and read
another — and arity or type mistakes are compile errors:

```scala
val getWeather = FunctionTool
  .named("get_weather")
  .describedAs("Returns the weather forecast for a given city.")
  .param[String]("location", "A location or city name.")
  .param[Option[String]]("date", "Forecast date, yyyy-MM-dd")
  .handle { (location, date) => forecast(location, date) }
```

**Session memory is an event-sourced entity.** That is what makes collaboration fall
out: several agents share a conversation by addressing the same session id, memory
events are subscribable for compaction, and history survives a restart because it is a
journal like any other.

**Agents are sharded by session**, and requests to one session are handled strictly one
at a time. Without that, two overlapping requests read the same history, both append to
it, and neither turn acknowledges the other.

A failing tool comes back to the model as an *error tool result*, not an exception —
which is what lets it recover, usually by fixing its arguments.

## Testing

Two levels, both real.

**Unit — no actor system, no cluster, no database.** Effects are inert values, so this
is milliseconds. Inputs and replies still round-trip through the component's own
serializers, so a missing codec fails here rather than on first deployment.

```scala
val kit    = EventSourcedTestKit.of(ShoppingCartEntity, "cart-1")
val result = kit.call(ShoppingCartEntity.addItem)(LineItem("p1", "Widget", 2))

assertEquals(result.replyValue, Done)
assertEquals(result.events, Vector(ItemAdded(LineItem("p1", "Widget", 2))))
```

**Integration — the whole service against a throwaway Postgres**, using the same DDL
docker-compose applies. `restartService()` drops every entity from memory so a test can
prove durability rather than caching.

Agents are tested against `TestModelProvider`, which answers from a script and *fails
loudly* when the script runs out — a test whose model quietly returned a default is no
longer testing what it says.

```bash
sbt test          # 177 tests, ~70s, no API key needed
```

## Deliberate divergences from Akka

| Akka | nakka | Why |
|---|---|---|
| `@Component`, classpath scanning | explicit `register(...)` | A missing component fails at startup, not at first request |
| `Entity::method` lambda inspection | typed companion handles | No reflection; call sites checked by the compiler |
| `@FunctionTool` + reflection | `FunctionTool` builder | Schema and decoder come from one instance and cannot disagree |
| Bespoke SQL-like view query language | real SQL over a JSON row column | No parser to build, strictly more expressive, indexes are explicit |
| ACL by absent annotation | abstract `def acl` | An unstated ACL is a decision nobody made |
| `budget_tokens` / `temperature` | `effort`, adaptive thinking | Current Claude models reject both outright |

## Layout

```
modules/core      effects, ids, codecs, component descriptors — no Pekko, no I/O
modules/sdk       the component API: entities, workflows, views, consumers, timers, client
modules/runtime   interprets effects: sharding, persistence, projections, timers
modules/http      endpoint DSL and server
modules/agent     model providers, session memory, function tools, the agent loop
modules/testkit   unit and integration test support
samples/shopping-cart          entities, views, consumers, HTTP
samples/multi-agent-planner    dynamic + parallel multi-agent orchestration
```

Dependencies run strictly `core → sdk → runtime → {http, agent} → testkit → samples`.

## Not implemented

Honest gaps, not oversights:

- **Streaming responses.** `ModelProvider.stream` has a working default that emits a
  completed response; no real SSE token streaming, and no `StreamEffect` on agents.
- **Broker topics.** Consumers publish through a `MessagePublisher` SPI with a tested
  in-memory implementation. No Kafka publisher ships, and topic-*sourced* components are
  rejected at startup rather than silently never delivering.
- **HTTP query parameters and headers in handlers.** ACL predicates see them; handlers
  do not.
- **Multi-region.** Single-region only. No replication filters, no `origin` routing.
- **Control plane.** No CLI, console, or deployment descriptors — this is the SDK and
  runtime, not Akka's hosted platform.
- **Multi-table views**, view rebuild-on-deploy, and Akka's `@SnapshotHandler`
  projection optimisation.

## Licence

Apache 2.0, matching Pekko.
