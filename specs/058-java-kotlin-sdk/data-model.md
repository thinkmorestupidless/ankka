# Data Model: Java and Kotlin SDKs

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

The journal, the state tables, the encoding, the descriptor and the conformance cases are unchanged
(FR-026); their models are in `protocol/ENCODING.md`, `specs/009-polyglot-runtimes/data-model.md`
and the control plane's descriptor types. This document is the SDKs' own values: what a developer
declares, what the bridge builds from it once at start, what it holds while the service runs, and
what the test kits expose. Names are the contracts' ([contracts/](./contracts/)).

## Declarations (what a Java developer writes)

A **component class** extends one of the Java base classes and carries handler methods annotated
with a wire name. The base class fixes the kind; the annotations fix the handlers.

| Kind | Base class | Handler annotations | Handler shape |
|---|---|---|---|
| Event sourced entity | `EventSourcedEntity<S, E>` | `@Command("name")`, `@Query("name")` | `Effect<R> m(I in)` or `Effect<R> m()`; a query returns `ReadOnlyEffect<R>`; `S applyEvent(E e)` is abstract, not annotated |
| Key value entity | `KeyValueEntity<S>` | `@Command`, `@Query` | as above, `UpdateBuilder` instead of `PersistBuilder` |
| View | `View<Src, Row>` | none: `onChange(Src)`, `onDelete()` abstract; `@Query("name")` on declared queries | `ViewEffect<Row> onChange(Src)`; a declared query is a static method returning `ViewQuery<Row>` |
| Keyed view | `KeyedView<Row>` | `@Source` on `onChange` methods, one per source type; `@Query` | `KeyedViewEffect<Row> onCart(ShoppingCart c)` |
| Consumer | `Consumer<Src, Out>` | none: `onChange(Src)` abstract | `ConsumerEffect<Out>` |
| Graph consumer | `GraphConsumer<Src>` | none | `GraphEffect` |
| Workflow | `Workflow<S>` | `@Command`, `@Query`, `@Step("name")` | a step is `StepEffect m(I in)` or `StepEffect m()`; commands return `WorkflowEffect<R>` |
| Timed action | `TimedAction` | `@Action("name")` | `TimedActionEffect m(I in)` |
| Agent | `Agent<S>` | `@Command`, `@Stream` | `AgentEffect<R>`; tools are `@Tool("name")` methods on a `Tools` class |
| Autonomous agent | `AutonomousAgent` | `@Rule("name")` result checks | `RuleResult m(R result)` |
| HTTP endpoint | `HttpEndpoint` | `@Get`, `@Post`, `@Put`, `@Delete`, `@Patch`, `@Sse`, `@Socket`, `@StreamBody` on methods; `@Path`, `@Body`, `@QueryParam`, `@Header` on parameters | abstract `Acl acl()` |
| gRPC endpoint | `GrpcEndpoint` | none: `services()` returns bound io.grpc service definitions | |
| MCP endpoint | `McpEndpoint` | none: `tools()` built with `ToolBuilder` | |

**Validation rules** (every one a `DeclarationProblem` collected at start, FR-003): a handler
annotation with an empty name; two handlers of one component with one wire name; a method shaped
like a handler (public, returning an effect type) with no annotation; `@Query` on a method whose
declared return type is not `ReadOnlyEffect` (or `ReadOnlyWorkflowEffect`, `ReadOnlyViewQuery`);
`@Command` or `@Step` with more than one parameter; a parameter or return type with no serializer
(no record plan derivable, no `Serializer` registered); a `@Step` on a non-workflow; a route whose
template does not parse or whose `@Path` parameters do not match its template's; two routes with
the same verb and template; an endpoint class with no public no-argument constructor; a component
class that is abstract, generic at its top level, or not public; an `applyEvent` missing from an
entity (compile time, since abstract); a `Tools` class whose `@Tool` method's parameter is not a
record. A component the service calls but did not register is reported by the registry as today
(`ComponentRegistry.from`), named.

## The declaration plan (what the bridge builds once at start)

```
ComponentPlan
  kind            : EventSourced | KeyValue | View | KeyedView | Consumer | GraphConsumer | Workflow | TimedAction | Agent | Autonomous
  componentId     : String                  # from the class's @ComponentId("cart") or the required constructor argument
  javaClass       : Class<?>
  constructor     : MethodHandle            # (Context) -> component, or () -> component
  handlers        : Map[WireName, HandlerPlan]
  steps           : Map[StepName, StepPlan]  # workflows only
  stateSerializer : Serializer<S>;  eventSerializer : Serializer<E>  # from the class's type arguments, through the codec registry
  problems        : List[DeclarationProblem]

HandlerPlan
  wireName : String;  method : Method;  handle : MethodHandle
  input    : Optional<Serializer<I>>;  output : Serializer<R>
  readOnly : Boolean                       # true for @Query; checked against the return type
  javaKey  : (declaringClass, methodName, descriptor)   # what a CommandRef's SerializedLambda resolves to
```

A `ComponentPlan` becomes exactly one Scala descriptor (`EventSourcedEntityDescriptor` and the
rest) whose `create(ctx)` constructs a Scala adapter around a fresh Java instance, and whose
handler bindings are `CommandHandle`/`NoArgHandle` values with `run` functions that call the
`MethodHandle`. The plan is also what the client's `CommandRef` resolution reads
(`javaKey → wireName`), and what the MCP tool builder reads for `.command(ShoppingCart::addItem)`.
Plans are immutable after `register`; `ServiceBuilder.start()` refuses to start with any problem.

## The codec plan (`RecordCodec`)

For every type the declarations name, built once at start and cached by `Class` (and for generics
by the full `java.lang.reflect.Type`):

| Java type | Plan node | JSON (ENCODING.md) | Rules |
|---|---|---|---|
| a `record` | `RecordNode(components in declaration order)` | object, every component written | a `null` component refused on write, naming record and component (FR-009); an absent field on read refused unless `Optional` |
| a `sealed interface` or abstract sealed class | `SumNode(cases by simple name)` | the case's object with `"type":"<SimpleName>"` **first** | permitted subclasses from `getPermittedSubclasses`; a case named `type`, or a component named `type`, refused at start; unknown `type` on read refused |
| an `enum` | `EnumNode` | `{"type":"A"}` as a field; the bare `"A"` accepted on read | matches a fieldless Scala enum with its string codec |
| `Optional<T>` | `OptionNode(T)` | `null` when empty | `Optional.empty()` is the encoding's absent form; a `null` `Optional` reference is refused |
| `List<T>`, `Set<T>`, arrays | `SeqNode(T)` | array, `[]` when empty | `Set` reads into a `LinkedHashSet` to keep order |
| `Map<String, V>` | `MapNode(V)` | object | any other key type refused at start |
| `String`, `boolean`/`Boolean`, `int`/`Integer`, `long`/`Long`, `double`/`Double` | leaves | string, boolean, integer, integer (exact past 2⁵³), shortest round-trip double | `float`, `short`, `byte`, `char` refused at start (no Scala-side fixture shape); `BigDecimal` as a number string per the fixtures |
| `Instant`, `Duration`, `LocalDate`, `LocalDateTime`, `UUID` | leaves | ISO strings as `ENCODING.md` | `Instant` through `Instant.toString`, which is the rule |
| `byte[]` | leaf | base64 string inside JSON; top level: the binary `bytes` payload | |
| `Done`, `Void`/`void` | leaves | the binary `done` and `unit` payloads | |
| any other class | **refused at start** | | the developer implements `Serializer<A>` or adapts a library (FR-010) |

Text payloads (`string`, `int`, `long`, `double`, `boolean`, `duration-millis`) and
`option[<manifest>]` follow `ENCODING.md`'s text and binary tables; manifests default to the
record's simple name as Scala's default to the case class's. A `Manifest` can be set with
`@Manifest("Cart")` on the type, matching Scala's `Codecs.serializer[A]("Cart")`.

The same planner serves the MCP tool and agent tool JSON Schema (R6, R7): a `RecordNode` becomes an
object schema with `required` for non-`Optional` components, a `SumNode` a `oneOf` with a `const`
`type`, leaves their JSON Schema types.

## Effects (values a handler returns)

Java interfaces, one package-private implementation each, holding the Scala effect value (R4):

| Java type | Holds | Produced by |
|---|---|---|
| `Effect<R>` | `EventSourcedEffect[S, E, R]` or `KeyValueEffect[S, R]` | `effects().persist(e...)`, `.updateState(s)`, `.reply(r)`, `.error(msg)`, `.delete()` |
| `ReadOnlyEffect<R> extends Effect<R>` | the same, reply or error only | `effects().reply(r)`, `effects().error(...)` |
| `PersistBuilder<S, E>` | the Scala `PersistBuilder` | `.thenReply(Function<S, R>)`, `.thenReply(R)`, `.thenDelete()`, `.thenSnapshot()` |
| `WorkflowEffect<R>`, `ReadOnlyWorkflowEffect<R>` | `WorkflowEffect` | `effects().updateState(s).transitionTo(Workflow::step, input).thenReply(r)`, `.pause()`, `.end()`, `.reply(r)` |
| `StepEffect` | `WorkflowStepEffect` | `stepEffects().updateState(s).thenTransitionTo(...)`, `.thenPause()`, `.thenEnd()` |
| `ViewEffect<Row>`, `KeyedViewEffect<Row>` | the view effects | `effects().updateRow(row)`, `.deleteRow()`, `.ignore()`; keyed: `.updateRow(key, row)` |
| `ConsumerEffect<Out>` | `ConsumerEffect` | `effects().done()`, `.publish(out)`, `.publishAll(List<Out>)`, `.ignore()` |
| `GraphEffect` | the graph consumer's | `effects().node(...)`, `.edge(...)`, `.remove(...)` |
| `TimedActionEffect` | `TimedActionEffect` | `effects().done()`, `.error(msg)` |
| `AgentEffect<R>` | the agent's `AgentEffect` | `effects().systemMessage(s).userMessage(u).tools(Tools.class).guardrail(g).thenReply()`, `.reply(r)`, `.memory(...)` |

Steps are named at a transition by a method reference (`transitionTo(Checkout::charge, input)`)
resolved like a `CommandRef`, or by the string name as the escape hatch.

## Contexts (what a handler reads)

`EntityContext` (`entityId()`, `commandName()`, `caller()`, `timers()`, `componentClient()`,
`services()`, `secrets()`, `storage()`, `now()`), `WorkflowContext` (adds `workflowId()`,
`stepName()`), `ViewContext`, `ConsumerContext` (`sourceId()`, `origin()`), `TimerContext`,
`AgentContext` (`sessionId()`, `turn()`), `Request` for endpoints (`path()`, `query(name)`,
`header(name)`, `caller()`, `principal()`, `body(Class)`); all Java interfaces over the Scala
contexts, read on the handler's thread.

## Clients

```
ComponentClient
  forEventSourcedEntity(id) : EntityRef        forKeyValueEntity(id) : EntityRef
  forWorkflow(id) : WorkflowRef                forView(View.class) : ViewClient      forKeyedView(...)
  forTimedAction(TimedAction.class) : TimedActionRef
  forAgent(sessionId) : AgentRef               forAutonomousAgent(id) : AutonomousRef     tasks() : TasksRef
EntityRef
  call(CommandRef<C, I, O> ref) : Invocation<I, O>          # typed, through the registry (R2)
  call(String wireName, Class<I>, Class<O>) : Invocation<I, O>   # untyped escape hatch
Invocation<I, O>
  invoke(I in) : O                     # blocks; free on a virtual thread
  invokeAsync(I in) : CompletionStage<O>
  withTimeout(Duration)
WorkflowRef adds awaitEnd(CommandRef) ; AgentRef adds stream(...) : Flow.Publisher<Token>
ServiceClient  (services().of("other")) : the service client over the Scala EndpointClients/ServiceClients
```

`CommandRef<C, I, O>` is `Serializable` with one abstract method `Effect<O> apply(C component, I in)`
(and `NoArgRef<C, O>`); the bridge reads its `SerializedLambda`, caches `(implClass, implMethod,
implSignature) → wireName` after one lookup in the plans, and raises a `CallerError` naming the
method when it names no registered handler.

## Problems (what start reports)

`ServiceProblems` is the list `ServiceBuilder.start()` throws: each `DeclarationProblem(component,
member, message)` printed one per line in the order the classes were registered, before any
port is bound. The Scala registry's own problems (unregistered component called, duplicate
component id) are appended in their existing form.

## The test kits' values

| Kit | Exposes |
|---|---|
| `EventSourcedTestKit<S, E>` | `Result<R> call(CommandRef, in)`: `reply()`, `events()`, `state()`, `isError()`, `error()`; `state()`; `events()` cumulative; `restart()` replays |
| `KeyValueEntityTestKit<S>` | the same with `state()` after update |
| `WorkflowTestKit<S>` (new in Scala too) | `call(ref, in)`; `step()` runs the next step and returns `StepResult` (`movedTo`, `paused`, `ended`, `state`); `runUntilPause()`; `state()` |
| `ViewTestKit<Src, Row>`, `KeyedViewTestKit<Row>` | `onChange(src)`, `onDelete(id)`, `rows()`, `query(name, args)` |
| `ConsumerTestKit<Src, Out>` | `onChange(src)` → `published()`, `calls()` (recorded component calls) |
| `AgentTestKit<S>` | `TestModelProvider` script, `call(ref, in)` → `reply()`, `toolCalls()`, `transcript()` |
| `AnkkaTestKit` | `start(ServiceBuilder)`; `componentClient()`, `http()` (base URI), `restartService()`, `eventually(Duration, Runnable)`, `timers()` (the `TimerProbe`), `secrets()`, `storage()`, `close()` |

## Kotlin forms (what the Kotlin SDK adds over the Java model)

| Java | Kotlin |
|---|---|
| a `record` as state or event | a `data class`: the codec reads its primary constructor through `kotlin.reflect`; a parameter's **default** is taken for an absent field; a parameter with no default and no value fails recovery naming the parameter (FR-021) |
| `Optional<T>` | `T?`: `null` is the absent form; a non-null parameter given `null` is refused by the Kotlin compiler (FR-020); the Java API is annotated with JSpecify so Kotlin sees non-null parameters |
| `effects().persist(e).thenReply(s -> Done.done())` | `effects { persist(e) thenReply { Done } }` and the chained form, both producing the Java effect |
| `Effect<R> addItem(LineItem item)` | `fun addItem(item: LineItem): Effect<Done>` **or** `suspend fun addItem(item: LineItem): Effect<Done>`; a suspending handler is registered as a Java handler whose body runs `runBlocking(AnkkaDispatcher)` on the handler's virtual thread (R5) |
| `ref.call(Cart::addItem).invoke(item)` | `ref.call(Cart::addItem).invoke(item)` (blocking) and `suspend fun invokeSuspending(item)`, awaiting the `CompletionStage` |
| `@Get("/carts/{id}") Response get(@Path("id") String id)` | the same annotations; `routes { get("/carts/{id}") { id: String -> ... } }` as the DSL form |
| `Flow.Publisher<SseEvent>` | `Flow<SseEvent>` (kotlinx) converted at the boundary |
| `AnkkaTestKit.start(...)` | `ankkaTestKit { ... }` with `runTest`-friendly suspending `eventually` |

Nothing Kotlin crosses to the wire: a data class is written by the same `RecordCodec` plan nodes
(a `RecordNode` built from the primary constructor), so a Kotlin event and a Java record of the same
shape are byte-identical, which the cross-language entity rows prove.

## State the bridge holds while running

Per service: the plans (immutable), the codec cache (immutable after start), the `CommandRef`
resolution cache (append-only, bounded by the number of distinct lambda classes), and nothing
else; component instances are the hosts' as for Scala, and per-request state is thread-local on
the handler's virtual thread as today.
