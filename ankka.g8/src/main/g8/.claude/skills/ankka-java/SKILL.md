---
name: ankka-java
description: Write, run, test or deploy an ankka service in Java, hosted by the ankka runtime in the service's own JVM — components as classes extending a kind's base class, handlers as annotated methods whose annotation string is the wire name (@Command, @Query, @Step, @Action, @Get, @Post, @Sse), calls named by method references (ShoppingCart::addItem), records and sealed interfaces in the shared encoding, Optional for absent values, the Java test kits, the Maven template, and the descriptor of a JVM-hosted service. Use when the task involves Java, Maven, the ankka-java artifact, JUnit in an ankka project, a record or sealed interface stored by ankka, or porting Java code from another platform to ankka.
---

# ankka in Java

A Java service runs in one JVM with the ankka runtime: the SDK (`com.thinkmorestupidless:ankka-java`) is a
facade over the runtime the Scala SDK uses, so there is no sidecar and no protocol between the code and
the platform. The runtime owns everything durable and distributed (sharding, the journal, projections,
timers, the workflow engine, HTTP binding and ACLs, cluster membership); the classes own the decisions.
Every component guide in the other ankka skills applies; this skill holds what differs.

## Rules

1. **Every component is a class extending its kind's base class, with a no-argument constructor.**
   `@ComponentId("shopping-cart") public class ShoppingCart extends EventSourcedEntity<Cart, CartEvent>`
   with `emptyState()` and `applyEvent(state, event)`; the others are `KeyValueEntity<S>`, `Workflow<S>`,
   `View<Src, Row>`, `KeyedView<Row>`, `Consumer<Src, Out>`, `GraphConsumer<Src>`, `TimedAction`,
   `HttpEndpoint`, `McpEndpoint` and `GrpcEndpoint`. The host constructs every instance; an entity or
   workflow may instead take an `EntityContext`. Nothing is injected through a constructor: reach the
   state with `currentState()`, clients with `context().componentClient()` or `componentClient()`.
2. **The annotation's string is the wire name.** `@Command("add-item") public Effect<Done> addItem(LineItem
   item)`, `@Query("get-cart") public ReadOnlyEffect<Cart> getCart()`; `@Step("charge")` on a workflow's
   `StepEffect` method, `@Action("remind")` on a timed action's. The method's name is yours; the string is
   the platform's. A handler takes one parameter or none: several values are one record.
3. **Calls name handlers with method references, never lambdas.**
   `componentClient().forEventSourcedEntity(id).call(ShoppingCart::addItem).invoke(item)`; steps and
   timers likewise (`.transitionTo(Checkout::reserve)`, `timers().schedule(name, delay, Reminder::remind,
   id)`). The SDK reads the reference's `SerializedLambda` and resolves it to the registered handler's wire
   name once; a lambda names no method and is refused at the call.
4. **Register by class, once.** `Ankka.service("cart").register(ShoppingCart.class)
   .withEndpoint(CartEndpoint.class).withMcpEndpoint(CartMcp.class)`, then `.start()`. Every annotation is
   read at `register`; a problem is a `DeclarationProblem` and `start()` throws `ServiceProblems` listing
   them all, so a bad handler fails the service at startup, never at a request.
5. **Stored types are records and sealed interfaces.** `@Manifest("shopping-cart") public record
   Cart(String cartId, List<LineItem> items, boolean checkedOut)`; an event type is a `sealed interface`
   whose cases are records, written with `"type"` naming the case. A field may not be `null`: an absent
   value is `Optional<T>`, never a null `Optional`. Map keys are `String`. `float`, `short` and `char` have
   no form in the encoding and are refused; a field or case named `type` is refused. Field names are the
   JSON names the journal holds.
6. **Effects are values.** `effects().persist(e).thenDone()`, `.thenReply(state -> r)`, `.thenReplyState()`,
   `.thenDelete()`, `.expireAfter(d)`, `effects().reply(r)`, `effects().error(Problem.conflict(msg))`;
   key value `effects().updateState(s).thenDone()`; workflow `effects().updateState(s)
   .transitionTo(Checkout::reserve).thenReply(r)`, `stepEffects().updateState(s).thenTransitionTo(..)`,
   `.thenPause(d, Checkout::charge)`, `.thenEnd()`, `.thenFail(msg)`; view `updateRow`, `deleteRow`,
   `ignore`; consumer `publish`, `publishAll`, `publishMessages`, `done`, `ignore`.
7. **A query returns `ReadOnlyEffect`.** `effects().reply(..)` and `effects().error(..)` build one; a
   `@Query` method declared to return `Effect` is refused at `register`, because a query cannot persist.
8. **Endpoints declare `acl()` and annotated routes.** `@Get("/carts/{cartId}") public Cart get(String
   cartId)`; path parameters bind by parameter name (compile with `-parameters`, as the template does) or
   by `@Path("name")`; `@Body`, `@QueryParam`, `@Header` read the rest; `@Sse` returns a
   `Flow.Publisher<SseEvent>` or a `Stream<SseEvent>`. Answer a status with `throw new
   ProblemException(Problem.notFound(msg))`; a `CommandException` from a call answers with the handler's
   problem. A `Done` reply answers 204.
9. **Blocking is free.** Handlers, steps and routes run on virtual threads, so `invoke()` is ordinary
   sequential code; `invokeAsync()` answers a `CompletionStage` to fan out.
10. **Test at two levels.** `EventSourcedTestKit.of(ShoppingCart.class, "c1").call(ShoppingCart::addItem,
    item)` answers a result with `reply()`, `events()`, `isError()` and `problem()`; `KeyValueEntityTestKit`,
    `WorkflowTestKit` (`call`, `timeOut()`, `lifecycle()`), `ViewTestKit` (`change`, `deleted`, `row`),
    `ConsumerTestKit` and `ConsumerTestKit.graph(..)` run one component with no runtime.
    `AnkkaTestKit.start(Main.service())` starts the whole service against a throwaway Postgres (Docker);
    `restartService()` proves durability. The kits name no test framework; the template uses JUnit 5.
11. **Deploy as a JVM-hosted service.** The descriptor names the image and `runtime`, as a Scala
    service's does; `./mvnw package jib:dockerBuild` builds the image. A topic source or output needs
    `ANKKA_KAFKA_BOOTSTRAP_SERVERS` in the descriptor's `env`.

## Development loop

`ankka init cart --language java` writes a Maven project with the wrapper: `./mvnw test` runs the entity's
kit and the whole-service test; `./mvnw generate-resources` copies the runtime's schema into
`target/ddl`, `docker compose up -d` starts Postgres, `./mvnw exec:java` serves HTTP on `:9000`. The
template compiles with `--release 21` and `-parameters`. In the ankka repository: `sbt sdkJava/test
testkitJava/test shoppingCartJava/test`, and the conformance suite against the Java reference with
`sbt -Dankka.conformance.reference=java 'sidecar/testOnly *.ConformanceSuite'`. See
`references/get-started/first-service-java.md` for the first service and `references/reference/java-sdk.md`
for every base class, annotation, effect and kit.

## Mistakes to check for

- A lambda where a method reference is needed: `.call(c -> c.addItem(item))` names no handler and is
  refused at the call ("names its handler with a lambda"). Write `.call(ShoppingCart::addItem)`.
- A method reference to a method with no `@Command` or `@Query`, or on a class the service does not
  register: refused at the call as "not a registered handler".
- `null` in a record: writing it fails ("a value the encoding writes may not be null"). Use `Optional<T>`
  and `Optional.empty()`.
- A `@Query` returning `Effect`: refused at startup. It returns `ReadOnlyEffect`.
- A method returning an effect with no annotation, or carrying both `@Command` and `@Query`: refused.
- A handler or step with two parameters: refused. Take one record.
- A `module-info.java` without `opens`: the SDK reads components, records and handlers by reflection, so
  every package holding them must be opened to it, or `register` fails. The template has no module
  descriptor and needs none.
- Compiling without `-parameters`: a route's path parameters cannot bind by name. Keep the compiler flag,
  or annotate each with `@Path`.
- A wire name changed on a deployed service, mistaken for a rename: renaming the method is free, changing
  the annotation's string is a breaking change.
- A `float`, `short` or `char` field, a non-`String` map key, or a sealed case that is a class rather than
  a record: the codec refuses them at `register`.
- A constructor with parameters on an endpoint, view or consumer: the host constructs it with none.
- Another SDK's habits: `@Component(id = ...)`, unannotated public methods as handlers, `.method(...)` on the
  component client, `@Acl` on a class. ankka uses `@ComponentId`, annotated handlers, `.call(...)` and an
  `acl()` method; `references/reference/java-sdk.md` lists the rest.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Get started

- `references/get-started/first-service-java.md` — Create a Java service from ankka's template, read its event sourced entity and HTTP endpoint, run it against a local Postgres, test it with and without a database, and watch it in the local console.

### Concepts

- `references/concepts/polyglot.md` — How ankka hosts a service in Java, Kotlin, Python, TypeScript, Go or Rust — in its own JVM, beside a process as a sidecar, or as a WebAssembly module — with the runtime owning everything durable.

### Build

- `references/build/serialization.md` — How ankka encodes state, events, arguments and messages as JSON under a named manifest, what the JSON looks like in every language, and how to change a stored type without breaking a journal.
- `references/build/testing.md` — Test ankka components at two levels in every language, with unit test kits that run one component and nothing else, integration test kits that run the whole service against a real database, and scripted models.

### Run and deploy

- `references/deploy/images.md` — Package a Scala, Java, Kotlin, Python, TypeScript, Go or Rust ankka service as a container image, tag it, and get it onto a cluster by pushing to a registry or loading it into a local kind node.
- `references/deploy/deploy-a-service.md` — Write a service descriptor, apply it with the ankka CLI, and follow the service from UpdateInProgress to Ready, including environment variables, secrets, version declarations and Python services.

### Reference

- `references/reference/java-sdk.md` — A compact map of the Java SDK — installing it, the codec, method references and wire names, modules, serializers, and for every component kind its base class, annotations, effects and test kit.
