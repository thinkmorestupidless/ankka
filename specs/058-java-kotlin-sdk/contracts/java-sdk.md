# Contract: the Java SDK

**Feature**: [spec.md](../spec.md) | **Research**: R1, R2, R3, R4, R6, R7, R9, R12 | **Artifacts**: `com.thinkmorestupidless:ankka-java`, `ankka-java-agent`, `ankka-java-testkit`

Everything below is in package `com.thinkmorestupidless.ankka.java` or a subpackage, is Java, and
names no type from `scala.*`, `org.apache.pekko.*`, `com.github.plokhotnyuk.*` or
`com.thinkmorestupidless.ankka.*` outside these packages (R12 checks this over the published jars).
The signatures are the contract; bodies are the implementation's.

## A service

```java
public final class Ankka {
  public static ServiceBuilder service(String name);
}
public interface ServiceBuilder {
  ServiceBuilder register(Class<?> componentClass);                 // reads the class once; problems collected
  ServiceBuilder register(Class<?> componentClass, String componentId);
  ServiceBuilder withEndpoint(Class<? extends HttpEndpoint> endpoint);
  ServiceBuilder withGrpcEndpoint(Class<? extends GrpcEndpoint> endpoint);
  ServiceBuilder withMcpEndpoint(Class<? extends McpEndpoint> endpoint);
  ServiceBuilder withSerializer(Serializer<?> serializer);          // a hand-written codec for a type the record codec refuses
  ServiceBuilder withModelProvider(ModelProvider provider);         // agent module
  AnkkaService start() throws ServiceProblems;                      // every declaration problem, or a running service
}
public interface AnkkaService extends AutoCloseable {
  ComponentClient componentClient();  ViewClient viewClient();  SecretStore secrets();  ServiceClients services();  ObjectStorage storage();
  URI httpAddress();  void awaitReady();  CompletionStage<Void> whenTerminated();  void terminate();  @Override void close();
}
public final class ServiceProblems extends RuntimeException { public List<DeclarationProblem> problems(); }
public record DeclarationProblem(String component, String member, String message) {}
```

A `main` is `Ankka.service("shop").register(ShoppingCart.class).withEndpoint(CartEndpoint.class).start().awaitReady();`.
Configuration is read from the same `ANKKA_*` variables and `service.json` as a Scala service's.

## Annotations (`...java.annotations`, all `RUNTIME`, all on methods unless said)

| Annotation | Target | Element | Meaning |
|---|---|---|---|
| `@ComponentId("cart")` | type | `value` | the component id; otherwise `register(cls, id)` supplies it |
| `@Manifest("Cart")` | type | `value` | the serializer manifest; default the simple name |
| `@Command("add-item")` | method | `value` | a command handler; the string is the wire name |
| `@Query("get-cart")` | method | `value` | a query handler; the method's return type must be `ReadOnlyEffect` (or its workflow/view form) |
| `@Step("charge")` | method | `value` | a workflow step |
| `@Action("remind")` | method | `value` | a timed action's handler |
| `@Stream("ask")` | method | `value` | an agent's streaming handler |
| `@Tool("get-weather")` | method | `value`, `description` | a tool on a `Tools` class |
| `@Rule("has-answer")` | method | `value` | an autonomous agent's result check |
| `@Source` | method | | a keyed view's per-source handler (the source type is the parameter's) |
| `@Get("/carts/{id}")`, `@Post`, `@Put`, `@Delete`, `@Patch` | method | `value` | an HTTP route; literals outrank parameters as the server does |
| `@Sse("/events")`, `@Socket("/live")`, `@Stream("/upload")` | method | `value` | SSE, WebSocket and streamed-body routes |
| `@Path("id")`, `@QueryParam("q")`, `@Header("x-id")`, `@Body` | parameter | `value` | where a route parameter comes from; `@Body` at most once |
| `@Describe("…")` | type, method, parameter | `value` | descriptions for tools and MCP |

Reflection runs once per registered class, at `register`; a problem in any annotation is a
`DeclarationProblem`, never an exception at a request. The reference page says `module-info.java`
needs `opens` to `com.thinkmorestupidless.ankka.java`.

## Components (`...java.component`)

```java
public abstract class EventSourcedEntity<S, E> {
  protected EventSourcedEntity();                                     // state and context are injected by the host
  protected final S currentState();   protected final EntityContext context();   protected final EventSourcedEffects<S, E> effects();
  protected S emptyState() { return null; }                          // override for a non-null start
  protected abstract S applyEvent(S state, E event);
}
public abstract class KeyValueEntity<S>   { protected final S currentState(); protected final KeyValueEffects<S> effects(); ... }
public abstract class View<Src, Row>      { protected abstract ViewEffect<Row> onChange(Src source); protected ViewEffect<Row> onDelete() { ... } }
public abstract class KeyedView<Row>      { /* @Source methods */  protected final KeyedViewEffects<Row> effects(); }
public abstract class Consumer<Src, Out>  { protected abstract ConsumerEffect<Out> onChange(Src source); protected ConsumerEffect<Out> onDelete(String id) {...} }
public abstract class GraphConsumer<Src>  { protected abstract GraphEffect onChange(Src source); }
public abstract class Workflow<S>         { protected final S currentState(); protected final WorkflowEffects<S> effects(); protected final StepEffects<S> stepEffects(); protected RecoverStrategy recovery() {...} }
public abstract class TimedAction         { protected final TimedActionEffects effects(); protected final TimerContext context(); }
```

Sources for views and consumers are declared with a static `ChangeSource`:
`View.of(ShoppingCart.class)`, `View.ofTopic("orders", OrderPlaced.class)`, `View.ofWorkflow(Checkout.class)`,
`Consumer.ofEntity(...)`, `.ofTopic(...)`, `.ofService("other", ...)`, bound by overriding `source()`.
Declared and recursive view queries: `public static ViewQuery<Row> byOwner(String owner)` annotated
`@Query("by-owner")` with `ViewQuery.sql("select ... where jsonText(row, 'owner') = ?", owner)`.

## Effects (`...java.effect`)

```java
public interface Effect<R> {}                                   // sealed in spirit: one bridge implementation
public interface ReadOnlyEffect<R> extends Effect<R> {}
public interface EventSourcedEffects<S, E> {
  PersistBuilder<S, E> persist(E event);  PersistBuilder<S, E> persist(E first, E... more);  PersistBuilder<S, E> persistAll(List<E> events);
  <R> ReadOnlyEffect<R> reply(R reply);   <R> ReadOnlyEffect<R> error(String message);   <R> ReadOnlyEffect<R> error(Problem problem);
}
public interface PersistBuilder<S, E> {
  <R> Effect<R> thenReply(Function<S, R> reply);  <R> Effect<R> thenReply(R reply);  Effect<Done> thenDone();
  PersistBuilder<S, E> thenSnapshot();  <R> Effect<R> thenDelete(Function<S, R> reply);
}
public interface KeyValueEffects<S>  { UpdateBuilder<S> updateState(S state); <R> ReadOnlyEffect<R> reply(R r); <R> ReadOnlyEffect<R> error(String m); <R> Effect<R> delete(R reply); }
public interface WorkflowEffects<S> {
  WorkflowBuilder<S> updateState(S state);  <R> ReadOnlyWorkflowEffect<R> reply(R r);  <R> ReadOnlyWorkflowEffect<R> error(String m);
  <R> WorkflowEffect<R> pause(R reply);     <R> WorkflowEffect<R> end(R reply);
}
public interface WorkflowBuilder<S> { <I, R> WorkflowEffect<R> transitionTo(StepRef<?, I> step, I input, R reply); <R> WorkflowEffect<R> transitionTo(NoInputStepRef<?> step, R reply); <R> WorkflowEffect<R> pause(R reply); <R> WorkflowEffect<R> end(R reply); }
public interface StepEffects<S> { StepBuilder<S> updateState(S s); StepEffect thenTransitionTo(StepRef<?, ?> step, Object input); StepEffect thenPause(); StepEffect thenEnd(); StepEffect thenTransitionTo(StepRef<?, ?> step, Object input, Duration timeout, StepRef<?, ?> onTimeout); }
public interface ViewEffect<Row> {}  public interface ViewEffects<Row> { ViewEffect<Row> updateRow(Row row); ViewEffect<Row> deleteRow(); ViewEffect<Row> ignore(); }
public interface ConsumerEffects<Out> { ConsumerEffect<Out> done(); ConsumerEffect<Out> publish(Out message); ConsumerEffect<Out> publish(Out message, String key); ConsumerEffect<Out> publishAll(List<Out> messages); ConsumerEffect<Out> ignore(); }
public interface TimedActionEffects { TimedActionEffect done(); TimedActionEffect error(String message); }
public final class Done { public static Done done(); }
```

`StepRef<W, I>` and `NoInputStepRef<W>` are serializable functional interfaces (`Checkout::charge`)
resolved as `CommandRef` is; `Workflow.step("charge")` is the string form.

## The serializer (`...java.codec`)

```java
public interface Serializer<A> {
  String manifest();  byte[] toBytes(A value);  A fromBytes(byte[] bytes);  ContentType contentType();  // JSON by default
  static <A> Serializer<A> of(Class<A> type);                            // the record codec's plan, or an EncodingException at call time
  static <A> Serializer<A> of(String manifest, Function<A, byte[]> write, Function<byte[], A> read);
}
public final class RecordCodec { public static <A> Serializer<A> forType(Type type); public static JsonSchema schemaOf(Type type); }
public final class EncodingException extends RuntimeException { public String path(); }   // "ShoppingCart.items[2].discount"
```

Supported types and rules: data-model.md, "The codec plan". A `null` in a record component is an
`EncodingException` naming the component (FR-009). A type the planner refuses is a
`DeclarationProblem` at start when a handler names it, and an `EncodingException` when asked for
by hand.

## Clients (`...java.client`)

```java
public interface ComponentClient {
  EntityRef forEventSourcedEntity(String id);   EntityRef forKeyValueEntity(String id);   WorkflowRef forWorkflow(String id);
  <Row> ViewClient<Row> forView(Class<? extends View<?, Row>> view);   <Row> ViewClient<Row> forKeyedView(Class<? extends KeyedView<Row>> view);
  TimedActionRef forTimedAction(Class<? extends TimedAction> action);
  AgentRef forAgent(String sessionId);   AutonomousRef forAutonomousAgent(String id);   TasksRef tasks();     // agent module adds these through AgentClients.of(client)
}
public interface EntityRef {
  <C, I, O> Invocation<I, O> call(CommandRef<C, I, O> handler);
  <C, O> Invocation<Void, O> call(NoArgRef<C, O> handler);
  <I, O> Invocation<I, O> call(String wireName, Class<I> input, Class<O> output);
}
public interface Invocation<I, O> { O invoke(I input); CompletionStage<O> invokeAsync(I input); Invocation<I, O> withTimeout(Duration timeout); }
@FunctionalInterface public interface CommandRef<C, I, O> extends Serializable { Effect<O> apply(C component, I input); }
@FunctionalInterface public interface NoArgRef<C, O>     extends Serializable { Effect<O> apply(C component); }
public interface WorkflowRef extends EntityRef { <C, I, O> Invocation<I, O> awaitEnd(CommandRef<C, I, O> start); }
public interface ViewClient<Row> { List<Row> query(String name, Object... args); Flow.Publisher<Row> stream(String name, Object... args); Optional<Row> byId(String id); }
public interface ServiceClients { ServiceClient of(String service); }
public interface ServiceClient { <I, O> O call(String path, I body, Class<O> reply); HttpResponse get(String path); ... }
public final class CallerError extends RuntimeException {}    // a method reference naming no registered handler, an unknown wire name
```

`invoke` blocks the calling virtual thread and is the ordinary form; `invokeAsync` returns the
`CompletionStage` the Kotlin SDK awaits. A `CommandRef` whose lambda is not a method reference
(`(c, i) -> c.addItem(i)`) is a `CallerError` naming the call site; the reference page says to write
`ShoppingCart::addItem`.

## Endpoints (`...java.http`)

```java
public abstract class HttpEndpoint {
  protected abstract Acl acl();
  protected final Request request();   protected final Caller caller();   protected final Optional<Principal> principal();
  protected final ComponentClient componentClient();   protected final ServiceClients services();
}
public interface Acl {
  static Acl denyAll(); static Acl allowAll(); static Acl allowIf(Predicate<Request> test);
  static Acl authenticate(Function<Request, AuthDecision> decide); static Acl allowCallers(String... services); static Acl allowLocal();
}
public final class Response { static Response of(Object body); static Response status(int status); Response header(String name, String value); static Response noContent(); static Response html(String html); static Response bytes(byte[] bytes, String contentType); static Response problem(Problem p); }
public record Problem(int status, String title, String detail) {}     // HttpProblem's shape
public interface Request { String path(); Optional<String> query(String name); Optional<String> header(String name); <A> A body(Class<A> type); Caller caller(); Optional<Principal> principal(); }
public record SseEvent(String data, Optional<String> event, Optional<String> id) {}
public interface Socket { void send(String text); void send(byte[] bytes); void close(int code, String reason); Flow.Publisher<Frame> inbound(); }
public abstract class GrpcEndpoint { protected abstract Acl acl(); protected abstract List<io.grpc.ServerServiceDefinition> services(); protected final GrpcClients clients(); }
public abstract class McpEndpoint { protected abstract Acl acl(); protected abstract List<McpTool> tools(); protected List<McpReference> references() {...} }
public final class ToolBuilder {
  public static ToolBuilder tool(String name);  ToolBuilder describedAs(String d);  <A> ToolBuilder arguments(Class<A> record);
  <A> McpTool handle(Function<A, Object> f);   <C, I, O> McpTool command(CommandRef<C, I, O> handler, Function<McpCall, String> entityId);
}
```

A route method returns a value (serialised by its codec, 200), a `Response`, `Done` (204), a
`Flow.Publisher<SseEvent>` for `@Sse`, or takes a `Socket` for `@Socket`; it may throw a
`ProblemException(Problem)` for an error status. Route parameters are bound by `@Path`,
`@QueryParam`, `@Header` and `@Body`; a parameter with none of them and a type the request can
supply (`Request`, `Caller`) is bound by type.

## Agents (`com.thinkmorestupidless.ankka.agent.java`, artifact `ankka-java-agent`)

```java
public abstract class Agent<S> { protected final AgentEffects<S> effects(); protected final AgentContext context(); protected S currentState(); }
public interface AgentEffects<S> {
  AgentBuilder<S> systemMessage(String s);   AgentBuilder<S> userMessage(String u);   <R> AgentEffect<R> reply(R reply);
}
public interface AgentBuilder<S> { AgentBuilder<S> tools(Class<?>... toolsClasses); AgentBuilder<S> tools(FunctionTool... tools); AgentBuilder<S> guardrail(Guardrail g); AgentBuilder<S> model(ModelSettings m); AgentBuilder<S> memory(Memory m); <R> AgentEffect<R> thenReply(Class<R> type); AgentEffect<String> thenReply(); AgentEffect<Void> thenStream(); }
public @interface Tool { String value(); String description() default ""; }    // on methods of any class with a public no-arg constructor
public final class FunctionTool { public static <A> FunctionTool named(String name, String description, Class<A> arguments, Function<A, String> run); FunctionTool requiresApproval(Duration within); }
public interface Guardrail { Optional<String> checkInput(String text); Optional<String> checkToolCall(ToolCall call); Optional<String> checkOutput(String text); }
public abstract class AutonomousAgent { protected abstract Task task(); /* @Rule methods: RuleResult check(Result r) */ }
public final class Task { public static Task named(String n); Task describedAs(String d); Task withTools(Class<?>... t); Task budget(Budget b); }
public final class Judgment { public static <E extends Enum<E>> Question<E> choice(String q, Class<E> choices); public static Question<Integer> score(String q, int from, int to); public static Question<Boolean> yesNo(String q); }
public final class Blueprint { ... the Scala builders with Java collections ... }
public final class ModelProviders { public static ModelProvider installation(); public static ModelProvider anthropic(); public static ModelProvider openAi(); public static ModelProvider preset(String name); public static ModelProvider local(URI url, String model); }
public final class AgentClients { public static AgentRef forAgent(ComponentClient c, String sessionId); ... }
```

The tool schema and decoder for a `@Tool` method's record parameter come from `RecordCodec`'s
plan: one schema and one decoder, as the Scala `FunctionTool` builder gives.

## Test kits (`...java.testkit`, artifact `ankka-java-testkit`)

```java
public final class EventSourcedTestKit<S, E> {
  public static <S, E> EventSourcedTestKit<S, E> of(Class<? extends EventSourcedEntity<S, E>> entity);   // and of(entity, id)
  public <C, I, O> Result<O> call(CommandRef<C, I, O> handler, I input);   public <C, O> Result<O> call(NoArgRef<C, O> handler);
  public S state();   public List<E> events();   public void restart();   public static <S, E> EventSourcedTestKit<S, E> withEvents(Class<...> entity, List<E> events);
}
public interface Result<O> { O reply(); boolean isError(); String error(); List<?> events(); Object state(); }
public final class KeyValueEntityTestKit<S> { ... call ...; S state(); }
public final class WorkflowTestKit<S> { call(...); StepResult step(); StepResult runUntilPause(); S state(); boolean ended(); }
public record StepResult(Optional<String> movedTo, boolean paused, boolean ended, Object state) {}
public final class ViewTestKit<Src, Row> { void onChange(Src s); void onDelete(String id); List<Row> rows(); List<Row> query(String name, Object... args); }
public final class KeyedViewTestKit<Row> { void onChange(Object source); List<Row> rows(); }
public final class ConsumerTestKit<Src, Out> { ConsumerResult<Out> onChange(Src s); }     // published(), calls()
public final class AgentTestKit<S> { static AgentTestKit<S> of(Class<? extends Agent<S>> agent, TestModelProvider model); AgentResult call(CommandRef, input); }
public final class TestModelProvider implements ModelProvider { static TestModelProvider scripted(ScriptedTurn... turns); }  // fails loudly when the script runs out
public final class AnkkaTestKit implements AutoCloseable {
  public static AnkkaTestKit start(ServiceBuilder service);   public static AnkkaTestKit start(ServiceBuilder service, Settings settings);
  public ComponentClient componentClient();  public ViewClient<?> viewClient();  public URI http();  public HttpClient httpClient();
  public void restartService();  public void eventually(Duration within, Runnable assertion);  public TimerProbe timers();  public SecretStore secrets();  public ObjectStorage storage();
  public ScriptedService service(String name);   // a stand-in for another service
}
```

The component kits need no runtime, cluster or database and round-trip inputs and replies through
the component's own serializers; `AnkkaTestKit` boots the whole service against a throwaway
database as the Scala one does. None depends on a test framework.

## What the surface check allows (R12)

Packages `java.*`, `javax.*` (none expected), `io.grpc.*` (gRPC endpoints only), `org.jspecify.*`,
and `com.thinkmorestupidless.ankka.java.*`, `com.thinkmorestupidless.ankka.agent.java.*`, excluding
`.bridge`. Anything else on a public signature fails the build.
