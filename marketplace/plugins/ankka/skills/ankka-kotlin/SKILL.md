---
name: ankka-kotlin
description: Write, run, test or deploy an ankka service in Kotlin, a library over the Java SDK hosted by the ankka runtime in the service's own JVM — components as classes extending the Java base classes, handlers as annotated functions whose annotation string is the wire name, calls named by function references (ShoppingCart::addItem), effects { } blocks, the routes { } DSL, suspend fun handlers and steps, invokeSuspending, data classes and sealed interfaces in the shared encoding, OrNull forms of Optional, JSpecify nullability read strictly, the Kotlin test kits, the Gradle template, and the descriptor of a JVM-hosted service. Use when the task involves Kotlin, Gradle, the ankka-kotlin artifact, coroutines or suspend functions in an ankka service, or a data class stored by ankka.
---

# ankka in Kotlin

A Kotlin service runs in one JVM with the ankka runtime. `com.thinkmorestupidless:ankka-kotlin` is a
library over the Java SDK (`ankka-java`): every Java type is used from Kotlin as it is, and the Kotlin
library adds what Kotlin's own form needs — data classes in the codec, suspending handlers and calls,
`OrNull` forms of `Optional`, and DSL blocks for effects, routes and tools. There is no sidecar. The
`ankka-java` skill's rules hold unless this one says otherwise; every component guide in the other ankka
skills applies.

## Rules

1. **Components extend the Java base classes; handlers are annotated functions.**
   `@ComponentId("shopping-cart") class ShoppingCart : EventSourcedEntity<Cart, CartEvent>()` with
   `override fun emptyState()` and `override fun applyEvent(state, event)`. `@Command("add-item")`,
   `@Query("get-cart")`, `@Step("charge")` and `@Action("remind")` name each handler on the wire; the
   function's name is yours. A handler takes one parameter or none.
2. **Build effects in a block.** `fun addItem(item: LineItem): Effect<Done> = effects {
   persist(CartEvent.ItemAdded(item)).thenDone() }`; the block's receiver is the component's own Java
   effects, so it builds exactly the effect the chained form does. A block ending in `reply(..)` is a
   `ReadOnlyEffect`, which a `@Query` must return. Workflow steps use `stepEffects { }`; the infix forms
   `persist(e) thenReply { s -> r }` and `updateState(s) thenTransitionTo Checkout::ship` read as one line.
3. **Calls name handlers with function references.** `forEventSourcedEntity(id).call(ShoppingCart::addItem)
   .invokeSuspending(item)`. A reference to an ordinary function passes to the Java SDK as a functional
   interface; Kotlin compiles it to an adapter method of the calling class, and the SDK reads through the
   adapter to the handler it calls. A reference to a `suspend fun` handler or step uses the Kotlin
   overloads (`import com.thinkmorestupidless.ankka.kotlin.call`, `stepRef(Checkout::charge)`, or the
   DSL's `transitionTo`/`thenTransitionTo`), which hand the SDK the same class, method and descriptor.
4. **Handlers, steps and routes may suspend.** A `suspend fun` handler runs inside `runBlocking` on the
   handler's own virtual thread; while it waits, the thread parks and holds no carrier.
   `invokeSuspending()`, `getSuspending(key)`, `askSuspending(query)`, `askFlow(query)` and the service
   client's `getSuspending`/`postSuspending` suspend rather than block. The request context, the trace
   and the call's origin are thread-locals of that thread: stay on it. After work elsewhere, return with
   `withContext(AnkkaDispatcher)` before calling the platform.
5. **Routes may be declared in code.** `class CartEndpoint : HttpEndpoint(), RouteRegistrar` with
   `override fun routes() = routes { get("/carts/{cartId}") { ... path("cartId") ... };
   post<LineItem>("/carts/{cartId}/items") { item -> ... } }`; each block suspends, and its answer is
   encoded as an annotated route's is. The annotated routes of the Java SDK work as well. An MCP tool's
   handler is `handling<Args> { args -> ... }`.
6. **Stored types are data classes and sealed interfaces.** `@Manifest("line-item") data class
   LineItem(val productId: String, val name: String, val quantity: Int)`; an event type is a `sealed
   interface` whose cases are data classes and `data object`s. The codec reads a data class by its primary
   constructor: every parameter is a `val`, a nullable parameter is the encoding's absent value, and a
   parameter's default fills a field the JSON does not have.
7. **Nullability is checked by the compiler.** The Java SDK is `@NullMarked` (JSpecify); build with
   `-Xjspecify-annotations=strict` (the template does), so a `null` given where the SDK takes a value does
   not compile. Every public `Optional` answer has an `OrNull` form (`request.principalOrNull`,
   `viewQueries.getOrNull(key)`, `secrets.getOrNull(name)`); a protected one, such as a view's
   `rowState()`, is read with `.orNull()`.
8. **Register by class.** `Ankka.service("cart").register(ShoppingCart::class.java)
   .withEndpoint(CartEndpoint::class.java)`; a problem in any declaration fails `start()`.
9. **Test with the Kotlin kits.** `eventSourcedTestKit(ShoppingCart::class, "c1").call(ShoppingCart::addItem,
   item)`, `keyValueEntityTestKit`, `workflowTestKit`, `viewTestKit`, `consumerTestKit`; `ankkaTestKit(service())
   { ... }` starts the whole service against a throwaway Postgres (Docker), closes it after the block, and
   offers `restartService()` and a suspending `eventually { }`.
10. **Deploy as a JVM-hosted service.** The descriptor names the image and `runtime`;
    `./gradlew installDist` then `docker build` makes the image. JVM only: no Kotlin multiplatform or
    Kotlin/Native, and the codec is the SDK's, not `kotlinx.serialization`.

## Development loop

`ankka init cart --language kotlin` writes a Gradle project with the wrapper (it provisions a JDK 21
toolchain when the machine has none): `./gradlew test` runs the entity's and the view's kits and the
whole-service test; `./gradlew schema` copies the runtime's schema into `build/ddl`, `docker compose up -d`
starts Postgres, `./gradlew run` serves HTTP on `:9000`. The build sets `javaParameters` and
`-Xjspecify-annotations=strict`. In the ankka repository, `just kotlin` (`sdks/kotlin/gradlew-this-commit.sh
check`) publishes this commit's Java SDK to `~/.m2` and runs the Gradle build; `just kotlin-conformance`
runs the conformance suite against the Kotlin reference. See `references/get-started/first-service-kotlin.md`
for the first service and `references/reference/kotlin-sdk.md` for the DSL, suspension and the kits.

## Mistakes to check for

- Switching dispatcher inside a suspending handler: `withContext(Dispatchers.IO) { client.call(..) }` or a
  coroutine launched on another dispatcher calls the platform without the request's context, trace and
  origin. Do the other work there, then return with `withContext(AnkkaDispatcher)`.
- A lambda where a function reference is needed: name the handler, `ShoppingCart::addItem`.
- An `internal` handler: Kotlin gives its JVM method a name with `$`, which the SDK reads as
  compiler-made, so it is no handler. Handlers are public.
- A data class constructor parameter that is not a `val`: refused ("declare it val"). A plain `class` as a
  stored type: use a data class or a `data object`.
- `null` passed to the SDK, or the build's `-Xjspecify-annotations=strict` removed so it compiles: it then
  fails at run time instead.
- A `@Query` whose block ends in `persist(..)`, or a `suspend fun` `@Query` not returning
  `ReadOnlyEffect`: refused at startup.
- A blocking `invoke()` inside a handler that launched other coroutines: they cannot run until it
  returns. Use `invokeSuspending()`.
- `-PankkaVersion=` copied from an earlier run while working on the SDK itself: Gradle builds against
  whatever `~/.m2` holds at that version. Run `gradlew-this-commit.sh`, which publishes first.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Get started

- `references/get-started/first-service-kotlin.md` — Create a Kotlin service from ankka's template, read its event sourced entity, view and HTTP endpoint, run it against a local Postgres, test it with and without a database, and watch it in the local console.

### Concepts

- `references/concepts/polyglot.md` — How ankka hosts a service in Java, Kotlin, Python, TypeScript, Go or Rust — in its own JVM, beside a process as a sidecar, or as a WebAssembly module — with the runtime owning everything durable.

### Build

- `references/build/serialization.md` — How ankka encodes state, events, arguments and messages as JSON under a named manifest, what the JSON looks like in every language, and how to change a stored type without breaking a journal.
- `references/build/testing.md` — Test ankka components at two levels in every language, with unit test kits that run one component and nothing else, integration test kits that run the whole service against a real database, and scripted models.

### Run and deploy

- `references/deploy/images.md` — Package a Scala, Java, Kotlin, Python, TypeScript, Go or Rust ankka service as a container image, tag it, and get it onto a cluster by pushing to a registry or loading it into a local kind node.
- `references/deploy/deploy-a-service.md` — Write a service descriptor, apply it with the ankka CLI, and follow the service from UpdateInProgress to Ready, including environment variables, secrets, version declarations and Python services.

### Reference

- `references/reference/kotlin-sdk.md` — A compact map of the Kotlin SDK — installing it, data classes in the codec, nullability, suspending handlers, effect blocks, and for every component kind its base class, effects and test kit.
