# Kotlin SDK

> A compact map of the Kotlin SDK — installing it, data classes in the codec, nullability, suspending handlers, effect blocks, and for every component kind its base class, effects and test kit.

Source: https://docs.ankka.cloud/reference/kotlin-sdk/
The Kotlin SDK is the library `ankka-kotlin`, a layer over the [Java SDK](java-sdk.md). A Kotlin service is
hosted by ankka's runtime in the same JVM: there is no sidecar. Every Java type — the base classes, the
annotations, the clients, the effects — is used from Kotlin as it is, and the Kotlin library adds what
Kotlin's own form needs: data classes in the codec, `OrNull` forms of every `Optional`, suspending handlers
and calls, and blocks for effects, routes and tools. The Java SDK's page is the full list of its API; this
page says what is different in Kotlin. [Your first service in Kotlin](../get-started/first-service-kotlin.md)
builds one from the template.

## Installing

The SDK is on Maven Central, published by every ankka release at the release's version:

| Artifact | Holds |
|---|---|
| `com.thinkmorestupidless:ankka-kotlin` | the Kotlin layer, bringing `ankka-java` with it |
| `com.thinkmorestupidless:ankka-kotlin-testkit` | the test kits' Kotlin forms, bringing `ankka-java-testkit` |
| `com.thinkmorestupidless:ankka-kotlin-agent` | agents, for a service that declares them |

The template's Gradle build, from `ankka init cart --language kotlin`, sets the two compiler options the SDK
needs, on JDK 21:

```kotlin
kotlin {
    jvmToolchain(21)
    compilerOptions {
        javaParameters.set(true)
        freeCompilerArgs.add("-Xjspecify-annotations=strict")
    }
}
```

`javaParameters` keeps each parameter's name in the class file, which binds an endpoint's path parameters.
`-Xjspecify-annotations=strict` reads the Java SDK's nullness annotations as Kotlin types, so a `null`
passed where the SDK takes a value does not compile. Pin `ankkaVersion` in `gradle.properties` to the
platform you deploy to, and say the same in the descriptor's `runtime`.

## Data classes

The codec reads a Kotlin `data class` by its primary constructor, as it reads a Java record, under the
rules every ankka language shares ([Serialization](../build/serialization.md)). A sealed interface whose
cases are data classes or `object`s is a sum, its case written as `"type"` first; an `object` case has no
fields. An enum is `{"type":"<CONSTANT>"}`.

```kotlin
/** A quantity of one product in a cart. */
@Manifest("line-item")
data class LineItem(val productId: String, val name: String, val quantity: Int)

/** A cart: its lines, ordered by product, and whether it has been checked out. */
@Manifest("shopping-cart")
data class Cart(val cartId: String, val items: List<LineItem>, val checkedOut: Boolean) {
    /** Adds [item], merging it into the line for the same product. */
    fun addItem(item: LineItem): Cart {
        val merged = items.find { it.productId == item.productId }?.let { item.copy(quantity = it.quantity + item.quantity) } ?: item
        return copy(items = (without(item.productId) + merged).sortedBy { it.productId })
    }

    fun removeItem(productId: String) = copy(items = without(productId))

    fun contains(productId: String) = items.any { it.productId == productId }

    val totalQuantity: Int
        get() = items.sumOf { it.quantity }

    private fun without(productId: String) = items.filterNot { it.productId == productId }

    companion object {
        fun empty(cartId: String) = Cart(cartId, emptyList(), false)
    }
}

/** What happened to a cart: the journal holds these, as the Scala and Java carts' journals do. */
@Manifest("shopping-cart-event")
sealed interface CartEvent {
    data class ItemAdded(val item: LineItem) : CartEvent

    data class ItemRemoved(val productId: String) : CartEvent

    data object CheckedOut : CartEvent

    data object Discarded : CartEvent
}
```

Reading follows the constructor: a field that is absent takes the parameter's default when it has one,
`null` when its type is nullable, and otherwise fails, naming it (`is missing`). Every constructor parameter
must be a property, `val`, so the codec can write it back, or the service does not start, naming the
parameter (`declare it val`). The refusals are the Java SDK's: `Float`, `Short`, `Byte` and `Char`, a map
whose key is not a `String`, a field named `type`, a generic data class, and any other class. `@Manifest` on
the type names its manifest; without it, the simple name in kebab case. `kotlinx.serialization` is not used;
for a type the codec refuses, hand the service a serializer with `withSerializer`, as in Java.

## Nullability

A nullable property in a data class is how a Kotlin value says "none": it is written `null` and read from
`null` or from an absent field, where Java uses `Optional`. Where the Java SDK answers an `Optional`, the
Kotlin SDK has a form that answers a nullable: `Request.principalOrNull`, `queryOrNull(name)`,
`headerOrNull(name)`, `Response.bodyOrNull`, `EntityContext.attributionOrNull`, `installationOrNull`,
`metadataOrNull(key)`, `ViewQueries.getOrNull(key)`, `ViewRows.getOrNull(key)`,
`CommandException.workflowFailureOrNull`, `SecretStore.getOrNull(name)`, `Bucket.getOrNull(name)` and
the others in `com.thinkmorestupidless.ankka.kotlin`. A protected method of a base class —
`principal()` in an endpoint, `rowState()` in a view — keeps its `Optional`; `.orNull()` turns any
`Optional` into a nullable.

Where the Java SDK takes a value that may be absent, Kotlin passes `null`: `ServiceClient.request` takes a
nullable body and a nullable content type, for a request with none.

## Suspension

A handler may be a `suspend fun`: an entity's or a workflow's `@Command` and `@Query`, a workflow's `@Step`,
an endpoint's annotated route, a timed action's `@Action`, a route in a `routes { }` block and an MCP tool's
handler. It runs on the handler's own virtual thread, so a suspension waiting for a reply holds no
platform thread, and to the platform it is the same handler as a plain one: the same wire name, the same
effects, the same failure. A view's or a consumer's `onChange`, and a keyed view's `@Source` handlers, are
plain functions.

The suspending forms of the calls:

| Call | Answers |
|---|---|
| `invocation.invokeSuspending(input)`, `invokeSuspending()` | a component's reply |
| `viewQueries.getSuspending(key)`, `allSuspending(limit)`, `countSuspending()`, `askSuspending(query, values)` | a row or `null`, rows, a count |
| `viewQueries.askFlow(query, values)` | a cold `Flow` of rows, run once per collection |
| `serviceClient.getSuspending<R>(path)`, `postSuspending(path, body)`, `putSuspending(path, body)`, `deleteSuspending(path)` | another service's answer |
| `flow.asPublisher()` | a `Flow` as the `Flow.Publisher` an SSE route returns |

The request context, the trace and the call's origin are thread-locals of the handler's thread. Stay on it:
a component called from inside `withContext(Dispatchers.IO)` is called with none of them. Return to the
handler's thread with `withContext(AnkkaDispatcher)` after work done elsewhere.

## Effect blocks

Every component's `effects { }` takes the component's Java effects as its receiver, so a block builds
exactly the effect the chained form builds:

```kotlin
@Command("add-item")
suspend fun addItem(item: LineItem): Effect<Done> = effects { persist(CartEvent.ItemAdded(item)).thenDone() }
```

A block that ends in `reply(...)` or `error(...)` is a `ReadOnlyEffect`, which a `@Query` returns. A
workflow's steps build with `stepEffects { }`. `thenReply { state -> ... }` is infix on a persist and an
update, and `transitionTo` and `thenTransitionTo` take a Kotlin function reference, suspending or not:
`updateState(s).transitionTo(Checkout::charge)`.

## Method references and wire names

The wire name is the annotation's string, as in Java, and a handler is named in code by a function
reference: `call(ShoppingCart::addItem)`, `thenTransitionTo(Checkout::charge)`,
`timers().schedule(name, delay, Reminder::remind, id)`. Renaming the function changes nothing on the wire.
A reference to a `suspend fun` is a `KSuspendFunction`, which the Java SDK's method-reference types do not
accept; `EntityRef.call` has a Kotlin form for one, and `commandRef(...)` and `stepRef(...)` make a
reference of either kind wherever one is taken. The declaration checks at start are the Java SDK's.

## Event sourced entity

| Part | API |
|---|---|
| Base class | `EventSourcedEntity<S, E>`, annotated `@ComponentId` |
| Must define | `override fun applyEvent(state: S, event: E): S` |
| May define | `override fun emptyState(): S` |
| Handlers | `@Command`, `@Query` on functions, plain or `suspend`, each taking one parameter or none |
| Effects | `effects { persist(e).thenReply { state -> … } }`, `persist(...).thenDone()`, `deleteEntity()`, `reply(r)`, `error(message)`, `error(problem)`; the rest as in [Java](java-sdk.md#event-sourced-entity) |

```kotlin
/** A customer's cart, one per cart id. Its handlers' wire names are the Scala cart's. */
@ComponentId("shopping-cart")
class ShoppingCart : EventSourcedEntity<Cart, CartEvent>() {
    override fun emptyState() = Cart.empty(context().entityId())

    // A suspend fun, as any handler may be: it waits without holding a thread.
    @Command("add-item")
    suspend fun addItem(item: LineItem): Effect<Done> = effects {
        when {
            currentState().checkedOut -> error(checkedOut)
            item.quantity <= 0 -> error("quantity must be greater than zero, was ${item.quantity}")
            else -> persist(CartEvent.ItemAdded(item)).thenDone()
        }
    }

    @Command("remove-item")
    fun removeItem(productId: String): Effect<Done> = effects {
        when {
            currentState().checkedOut -> error(checkedOut)
            !currentState().contains(productId) -> error(Problem.notFound("cart does not contain '$productId'"))
            else -> persist(CartEvent.ItemRemoved(productId)).thenDone()
        }
    }

    @Command("checkout")
    fun checkout(): Effect<Cart> = effects {
        when {
            currentState().checkedOut -> error(checkedOut)
            currentState().items.isEmpty() -> error("cannot check out an empty cart")
            else -> persist(CartEvent.CheckedOut).thenReplyState()
        }
    }

    @Command("discard")
    fun discard(): Effect<Done> = effects {
        if (currentState().checkedOut) error(checkedOut) else persist(CartEvent.Discarded).thenDelete().thenDone()
    }

    @Query("get-cart")
    fun getCart(): ReadOnlyEffect<Cart> = effects { reply(currentState()) }

    @Query("total-quantity")
    fun totalQuantity(): ReadOnlyEffect<Int> = effects { reply(currentState().totalQuantity) }

    override fun applyEvent(state: Cart, event: CartEvent): Cart =
        when (event) {
            is CartEvent.ItemAdded -> state.addItem(event.item)
            is CartEvent.ItemRemoved -> state.removeItem(event.productId)
            CartEvent.CheckedOut -> state.copy(checkedOut = true)
            CartEvent.Discarded -> state
        }

    private val checkedOut: Problem
        get() = Problem.conflict("cart is already checked out")
}
```

**A refusal is returned, never thrown.** A handler that throws fails its command as a fault: nothing is
persisted, the caller receives `500` with `internal error` rather than your message, and a trace shows a
fault. Where a rule is broken, return `error(...)` from the block, with the `Problem` whose status says why.
See [Error codes](error-codes.md).

## Key value entity

| Part | API |
|---|---|
| Base class | `KeyValueEntity<S>` |
| Effects | `effects { updateState(s).thenReply(r) }`, `updateState(s).thenReply { state -> … }`, `deleteEntity()`, `reply(r)`, `error(...)` |

```kotlin
/** A key value entity holding a name. */
@ComponentId("profile")
class Profile : KeyValueEntity<Profile.ProfileState>() {

    @Manifest("profile")
    data class ProfileState(val name: String)

    override fun emptyState(): ProfileState = ProfileState("")

    @Command("set")
    fun set(name: String): Effect<String> = effects {
        if (name.isEmpty()) error("a name is needed") else updateState(ProfileState(name)).thenReply("done")
    }

    @Query("get")
    fun get(): ReadOnlyEffect<String> = effects { reply(currentState().name.ifEmpty { "none" }) }

    @Command("delete")
    fun delete(): Effect<String> = effects { deleteEntity().thenReply("done") }
}
```

## View

| Part | API |
|---|---|
| Base class | `View<Src, Row>` |
| Must define | `source()`; `onChange(change)` returning `effects { updateRow(row) }`, `deleteRow()` or `ignore()` |
| Declared queries | `val` properties of type `ViewQuery` in the companion object |
| Querying | `viewClient().forView(View::class.java)`: `getOrNull(key)`, `all()`, `ask(query, values)`, and the suspending forms |

```kotlin
/**
 * Every cart as a row, to answer what the entity cannot: which carts hold a product, which are
 * checked out. The row's JSON is the Scala cart's `cart-row`.
 */
@ComponentId("cart-rows")
class CartRows : View<CartEvent, CartRows.CartRow>() {
    @Manifest("cart-row")
    data class CartRow(
        val cartId: String,
        val quantities: Map<String, Int>,
        val checkedOut: Boolean,
        val names: Map<String, String>,
    )

    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCart::class.java)

    override fun onChange(event: CartEvent): ViewEffect<CartRow> = effects {
        val row = rowState().orNull() ?: CartRow(updateContext().subject(), emptyMap(), false, emptyMap())
        when (event) {
            is CartEvent.ItemAdded ->
                updateRow(
                    row.copy(
                        quantities = row.quantities + (event.item.productId to (row.quantities[event.item.productId] ?: 0) + event.item.quantity),
                        names = row.names + (event.item.productId to event.item.name),
                    )
                )
            is CartEvent.ItemRemoved ->
                updateRow(row.copy(quantities = row.quantities - event.productId, names = row.names - event.productId))
            CartEvent.CheckedOut -> updateRow(row.copy(checkedOut = true))
            // The deletion that follows removes the row: a discarded cart leaves the listing.
            CartEvent.Discarded -> ignore()
        }
    }
}
```

A view with a recursive declared query, and a watchable one, in its companion object:

```kotlin
@ComponentId("tree-rows")
class TreeRows : View<TreeNode.TreeEvent, TreeRows.TreeRow>() {

    @Manifest("tree-row")
    data class TreeRow(val key: String, val under: String?)

    override fun source(): ChangeSource<TreeNode.TreeEvent> = ChangeSource.of(TreeNode::class.java)

    override fun onChange(event: TreeNode.TreeEvent): ViewEffect<TreeRow> = effects {
        when (event) {
            is TreeNode.TreeEvent.Placed -> updateRow(TreeRow(updateContext().subject(), event.under))
        }
    }

    companion object {
        private val TABLE = ViewQuery.tableOf(TreeRows::class.java)

        /** Every row under the row `row`, to any depth, by key: the same in every language. */
        val UNDER: ViewQuery =
            ViewQuery.declare(
                "under",
                """
                WITH RECURSIVE below AS (
                  SELECT row_key, payload FROM $TABLE WHERE payload::jsonb->>'under' = :row
                  UNION
                  SELECT n.row_key, n.payload FROM $TABLE n JOIN below b ON n.payload::jsonb->>'under' = b.row_key
                )
                SELECT payload FROM below ORDER BY row_key
                """.trimIndent(),
            )

        /** Every row, by key, watchable: the same statement in every language. */
        val ALL_ROWS: ViewQuery = ViewQuery.declare("all-rows", "SELECT row_key, payload FROM $TABLE ORDER BY row_key").watched()
    }
}
```

See [Views](../build/views.md).

## Keyed view

`KeyedView<Row>`, with a function annotated `@Source(...)` for each source, taking the change and its
`KeyedChange<Row>`; `effects { updateRow(key, row) }` and the rest of the Java keyed view's effects.

```kotlin
@ComponentId("joined-rows")
class JoinedRows : KeyedView<JoinedRows.JoinedRow>() {

    /** A row the left writes under the key it names, holding a right entity's id. */
    @Manifest("joined-row")
    data class JoinedRow(val key: String, val holding: String, val notes: List<String>) {
        fun noting(who: String): JoinedRow = copy(notes = notes + who)
    }

    /** The left names a row `key|holding`, and writes it from what it held, noting itself. */
    @Source(JoinedLeft::class)
    fun onLeft(event: Noted, change: KeyedChange<JoinedRow>): KeyedViewEffect<JoinedRow> {
        val (key, holding) = event.text.split("|", limit = 2)
        val held = change.rows().getOrNull(key)?.notes ?: emptyList()
        return effects { updateRow(key, JoinedRow(key, holding, held).noting("left")) }
    }

    /** The right finds every row holding it by asking the view's own query, and notes itself. */
    @Source(JoinedRight::class)
    fun onRight(event: Noted, change: KeyedChange<JoinedRow>): KeyedViewEffect<JoinedRow> {
        val theirs = change.rows().ask(OF_RIGHT, mapOf("holding" to change.subject()))
        return effects { updateRows(theirs.associate { it.key to it.noting("right") }) }
    }

    companion object {
        /** The rows holding one right entity, by key: the same statement in every language. */
        val OF_RIGHT: ViewQuery =
            ViewQuery.declare(
                "of-right",
                "SELECT payload FROM ${ViewQuery.tableOf(JoinedRows::class.java)} WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key",
            )
    }
}
```

## Consumer

`Consumer<Src, Out>`, `Out` being `Void` for one that publishes nothing. `publishes()` keeps the Java
signature, an `Optional<Publication>`. Effects: `effects { publish(message) }`, `publish(message, key)`,
`publishAll(messages)`, `publishMessages(outgoings)`, `done()`, `ignore()`.

```kotlin
@ComponentId("checkout-fanout")
class CheckoutFanout : Consumer<CartEvent, Fanned>() {

    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCartEntity::class.java)

    override fun publishes(): Optional<Publication> = Optional.of(Publication.to("conformance-fanout"))

    override fun onChange(event: CartEvent): ConsumerEffect<Fanned> = effects {
        when (event) {
            is CartEvent.ItemAdded -> publishAll(emptyList())
            is CartEvent.ItemRemoved -> publish(Fanned(0))
            CartEvent.CheckedOut ->
                publishMessages(
                    listOf(
                        Outgoing.of(Fanned(1)),
                        Outgoing.of(Fanned(2)).withKey("second:${messageContext().subject()}"),
                        Outgoing.of(Fanned(3)).withMetadata("x-n", "3"),
                    )
                )
            CartEvent.Discarded -> ignore()
        }
    }
}
```

Delivery is at least once, so what a handler does must be safe to do twice. See
[Consumers](../build/consumers.md).

## Graph consumer

`GraphConsumer<Src>`, with `source()`, `topic()`, and `onChange` building its deltas in an `effects { }`
block from `graph()`'s nodes, edges and tombstones.

```kotlin
@ComponentId("cart-graph")
class CartGraph : GraphConsumer<CartEvent>() {

    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCartEntity::class.java)

    override fun topic(): String = "conformance-graph"

    private fun cart(id: String, checkedOut: Boolean): GraphElement =
        graph().node("cart:$id", listOf("Cart"), mapOf("cartId" to id, "checkedOut" to checkedOut))

    override fun onChange(event: CartEvent): GraphEffect = effects {
        val id = messageContext().subject()
        when (event) {
            is CartEvent.ItemAdded, is CartEvent.ItemRemoved -> publish(cart(id, false))
            CartEvent.CheckedOut ->
                publish(
                    cart(id, true),
                    graph().node("checkout:$id", listOf("Checkout"), mapOf("cartId" to id)),
                    graph().edge("checked-out:$id", "CHECKED_OUT", "cart:$id", "checkout:$id", emptyMap()),
                )
            CartEvent.Discarded -> ignore()
        }
    }

    override fun onDelete(): GraphEffect = effects { publish(graph().tombstoneNode("cart:${messageContext().subject()}")) }
}
```

See [Publish a graph](../build/graph.md).

## Workflow

`Workflow<S>`, its commands building with `effects { }` and its steps, each annotated `@Step` and plain or
`suspend`, with `stepEffects { }`. A suspending step that waits for a component holds no thread while it
waits. Settings and recovery are the Java SDK's `WorkflowSettings` and `RecoverStrategy`, naming a step
with `stepRef(Workflow::step)`.

```kotlin
/** A checkout carried out as a workflow: what it is checking out, and what the cart held once it was. */
@Manifest("checkout")
data class Checkout(val cartId: String, val ordered: Cart?)

/**
 * Checks a cart out in a step. The step is a suspend fun: while it waits for the cart's answer the
 * workflow holds no thread.
 */
@ComponentId("checkout")
class CheckoutWorkflow : Workflow<Checkout>() {
    @Command("start")
    fun start(cartId: String): Effect<Done> = effects {
        updateState(Checkout(cartId, null)).transitionTo(CheckoutWorkflow::checkOut).thenDone()
    }

    @Query("get")
    fun get(): ReadOnlyEffect<Checkout> = effects { reply(currentState()) }

    @Step("check-out")
    suspend fun checkOut(): StepEffect {
        val ordered =
            context().componentClient().forEventSourcedEntity(currentState().cartId).call(ShoppingCart::checkout).invokeSuspending()
        return stepEffects { updateState(currentState().copy(ordered = ordered)).thenEnd() }
    }
}
```

See [Workflows](../build/workflows.md).

## Timed action and timers

`TimedAction`, with a function annotated `@Action` for each handler, plain or `suspend`, returning
`effects { done() }`, or `error(...)`, which has the timer retried.

```kotlin
@ComponentId("reminder")
class Reminder : TimedAction() {

    @Action("remind")
    fun remind(id: String): TimedActionEffect {
        record(id, "reminded")
        return effects { done() }
    }

    /** Records the due time it was run for, as the runtime told it. */
    @Action("tick")
    fun tick(id: String): TimedActionEffect {
        record(id, "due:${context().dueTime().toEpochMilli()}")
        return effects { done() }
    }

    private fun record(id: String, what: String) {
        context().componentClient().forEventSourcedEntity(id).call(ConformanceEntity::record).invoke(what)
    }
}
```

Scheduling is `timers().schedule(name, delay, Reminder::remind, input)`, as in Java. See
[Timers](../build/timers.md).

## Agent

Agents are the Java SDK's (`Agent`, `AutonomousAgent`, the agents module, every builder and client in
[the Java SDK](java-sdk.md#agent)), with `ankka-kotlin-agent`'s extensions in
`com.thinkmorestupidless.ankka.kotlin.agent` so that they read as Kotlin:

| Part | Kotlin form |
|---|---|
| Handlers | `@Command` and `@Stream` on a `suspend fun` or a plain one, answering `AgentEffect<R>` or `StreamEffect` |
| Effects | `effects { systemMessage(t).userMessage(t)…thenReply() }`; `thenReply<R>()` reads the answer as `R`, a data class or any type the SDK encodes; `tools<T>()` offers the `@Tool` methods of `T`, built with its constructor that takes nothing |
| Tools | a `@Tool` method may be a `suspend fun`; a lone data class parameter's properties are the arguments, each described by `@Describe` on its constructor parameter |
| Calling | `componentClient().forAgent(sessionId).call(Agent::handler).invokeSuspending(input)`; `.ask(Agent::handler)` for the outcome; `.streamFlow(streamRef(Agent::handler), input)`, a `Flow<String>`; `agentRef(Agent::handler)` names a handler wherever the Java API takes a reference, a plain `fun` handler's included |
| Autonomous agents | a task type's result is a data class; `AutonomousRef.notificationsFlow()`, a `Flow<Notification>` |

An agent written with suspend handlers and a suspend tool taking a data class:

```kotlin
data class Place(@Describe("the city to forecast") val city: String, val days: Int = 1)

/** A tool class whose tool is a suspend fun taking a data class, built by its own constructor. */
class WeatherTools {
    @Tool("forecast", description = "The forecast for a city")
    suspend fun forecast(place: Place): String {
        delay(1)
        ASKED.add("${place.city}/${place.days}")
        return "${place.city}: 21C"
    }

    companion object {
        val ASKED: MutableList<String> = CopyOnWriteArrayList()
    }
}

/** An agent written as a Kotlin developer writes one: suspend handlers and the effects DSL. */
@ComponentId("forecaster")
class Forecaster : Agent() {
    @Command("ask")
    suspend fun ask(question: String): AgentEffect<String> {
        delay(1)
        return effects {
            systemMessage("You answer questions about the weather.")
                .userMessage(question)
                .tools<WeatherTools>()
                .thenReply()
        }
    }

    @Command("structured")
    fun structured(question: String): AgentEffect<Forecast> =
        effects { userMessage(question).thenReply<Forecast>() }
}
```

The service gives the module its default model, as a Java service does:

```kotlin
fun guideService(model: ModelProvider): ServiceBuilder =
    Ankka.service("guide")
        .withModule(Agents.module().withModelProvider(model))
        .register(Guide::class.java)
        .register(Forecaster::class.java)
```

```kotlin
val reply = runBlocking {
    kit.componentClient().forAgent("s-1").call(Forecaster::ask).invokeSuspending("How warm is Oslo?")
}
```

The Java SDK's `AgentTestKit` and `TestModelProvider` drive one turn with no runtime; `agentRef` names a
suspend handler to it:

```kotlin
val model =
    TestModelProvider()
        .expectToolCall("forecast", """{"city":"Oslo","days":2}""")
        .expectText("Oslo will be 21C")
val kit = AgentTestKit.of(Forecaster::class.java, model)

val result = kit.call(agentRef(Forecaster::ask), "How warm is Oslo?")

assertEquals("Oslo will be 21C", result.reply())
assertEquals(listOf("Oslo/2"), WeatherTools.ASKED)
assertContains(model.lastRequest(), "Oslo: 21C")
```

See [Agents](../build/agents.md).

## Autonomous agent

A task type's result is a data class, whose properties are the schema the model is shown:

```kotlin
/** An answer, and the pages the agent read to reach it. */
data class Answer(val answer: String, val sources: List<String>)

/** `"answer"` is the wire name, written into every task of this type. */
val ANSWER: TaskType<Answer> =
    Task.named("answer")
        .describedAs("Answer a question from the guide, citing the pages you read")
        .resultConformsTo(Answer::class.java)
        .rule("cites-sources") {
            if (it.sources.isEmpty()) RuleResult.rejected("say which pages you read in sources") else RuleResult.accepted()
        }
```

```kotlin
/** The guide's pages, read by a suspending tool. */
class GuideTools {
    @Tool("read_page", description = "Reads one page of the guide.")
    suspend fun readPage(@Describe("The page's name.") page: String): String = "the $page page says: be brief"
}

/** Answers questions from the guide, on its own, until it completes the task with an [Answer]. */
@ComponentId("guide")
class Guide : AutonomousAgent() {
    override fun definition(): AutonomousDefinition =
        AutonomousDefinition.describedAs("Answers questions from the guide")
            .instructions("Read the guide rather than guessing. Be brief.")
            .guardrails(Guardrail.maxInputLength(2000))
            .accepts(ANSWER, 5)

    override fun tools(): List<Any> = listOf(GuideTools())
}
```

Tasks and instances are reached through the Java SDK's `AgentClients`: `runSingleTask`, `tasks`,
`forTask` and `forAutonomousAgent`, as [the Java SDK](java-sdk.md#autonomous-agent) lists them. In the
whole-service kit the scripted model is the module's default, and a task is worked to its end:

```kotlin
val model =
    TestModelProvider()
        .expectToolCall("read_page", """{"page":"style"}""")
        .expectCompleteTask("""{"answer":"Be brief.","sources":["style"]}""")
AnkkaTestKit.start(guideService(model)).use { kit ->
    val client = kit.componentClient()
    val taskId = AgentClients.runSingleTask(client, Guide::class.java, ANSWER, "How should I write?")
    val done = AgentClients.forTask(client, taskId).await(ANSWER, Duration.ofMinutes(1))
    assertEquals(Answer("Be brief.", listOf("style")), done.result().orElseThrow())
}
```

See [Autonomous agents](../build/autonomous-agents.md).

## Judgments, blueprints and model providers

Judgments, blueprints and model providers are the Java SDK's, unchanged: `Question`, `effects { judgment()… }`,
`Blueprint`, `BlueprintRegistry`, `ModelProviders` and `JudgmentProviders`, as
[the Java SDK](java-sdk.md#judgments) lists them. A blueprint's handler is a Kotlin lambda,
`BlueprintHandler.of("keep") { run, input -> … }`. See [Judgments](../build/judgments.md),
[Blueprints](../build/blueprints.md) and [Model providers](model-providers.md).

## HTTP endpoint

An endpoint is the Java SDK's `HttpEndpoint`, its routes declared either way. Annotated functions, plain or
`suspend`:

```kotlin
class CheckoutsSeenEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    @Get("/checkouts-seen/{cartId}")
    fun get(cartId: String): CheckoutsSeen.CheckoutSeen =
        viewClient().forView(CheckoutsSeen::class.java).getOrNull(cartId)
            ?: throw ProblemException(Problem.notFound("no checkout of '$cartId' has been read"))
}
```

Or in code, by implementing `RouteRegistrar` and answering `routes { }`: `get`, `delete` and
`post(template) { … }`, and `post<B>`, `put<B>` and `patch<B>(template) { body -> … }` for a body read as
`B`. Each block is suspending, runs on the request's thread, and reads `path(name)` and `request`:

```kotlin
/** The cart over HTTP, route for route as the Scala cart serves it, declared in code. */
class CartEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        get("/carts/{cartId}") { cart(path("cartId")).call(ShoppingCart::getCart).invokeSuspending() }
        get("/carts/{cartId}/total") { cart(path("cartId")).call(ShoppingCart::totalQuantity).invokeSuspending() }
        post<LineItem>("/carts/{cartId}/items") { item ->
            cart(path("cartId")).call(ShoppingCart::addItem).invokeSuspending(item)
        }
        delete("/carts/{cartId}/items/{productId}") {
            cart(path("cartId")).call(ShoppingCart::removeItem).invokeSuspending(path("productId"))
        }
        post("/carts/{cartId}/checkout") { cart(path("cartId")).call(ShoppingCart::checkout).invokeSuspending() }
        delete("/carts/{cartId}") { cart(path("cartId")).call(ShoppingCart::discard).invokeSuspending() }
    }

    private fun cart(cartId: String): EntityRef = componentClient().forEventSourcedEntity(cartId)
}
```

An MCP endpoint's tools take `handling<A> { args -> … }`, and `command` and `query` take Kotlin function
references:

```kotlin
/**
 * The cart as an MCP server at `/mcp`: a customer's assistant reads a cart, adds to it and checks
 * it out through these tools rather than the cart's routes.
 */
class CartMcpEndpoint : McpEndpoint() {
    data class AddItemArgs(
        @Describe("The cart's id") val cartId: String,
        @Describe("The product's id") val productId: String,
        @Describe("How many; greater than zero") val quantity: Int,
    )

    init {
        tool("get_cart").describedAs("Reads one cart: its lines, and whether it is checked out").query(ShoppingCart::getCart, "cartId")
        tool("add_item").describedAs("Adds a quantity of a product to a cart").handling<AddItemArgs> { args ->
            componentClient()
                .forEventSourcedEntity(args.cartId)
                .call(ShoppingCart::addItem)
                .invokeSuspending(LineItem(args.productId, args.productId, args.quantity))
        }
        tool("checkout").describedAs("Checks a cart out, answering what it held").command(ShoppingCart::checkout, "cartId")
    }

    // A request carrying one of the cart's client keys, as the key's subject (CartKeysEndpoint).
    override fun acl(): Acl = Acl.clientKeys()
}
```

gRPC endpoints are the Java SDK's `GrpcEndpoint`, over grpc-java's descriptors. See
[HTTP endpoints](../build/http-endpoints.md) and [MCP endpoints](../build/mcp-endpoints.md).

## Calling components

As in Java, with the suspending forms:
`componentClient().forEventSourcedEntity(id).call(ShoppingCart::addItem).invokeSuspending(item)`. A refusal
is a `CommandException`; `workflowFailureOrNull` names a failed workflow's step. See
[Calling components](../build/component-client.md).

## Configuration

The same `ANKKA_*` variables as every ankka service ([Runtime configuration](configuration.md)); a variable
the descriptor's `env` sets is read with `System.getenv`.

## Secret store

`secrets().put(name, value)`, `getOrNull(name)` and `delete(name)`, wherever the Java SDK offers a secret
store: an endpoint, a consumer, a workflow, a timed action and the running service, never an entity. See
[Secrets a service keeps](../build/secrets.md).

## Calling other services

`services().of(name)` or `of(project, name)` answers the Java SDK's `ServiceClient`; its raw `request` takes
a nullable body and content type.

```kotlin
    val client = services().of(service)
    return try {
        if (request().queryOrNull("mode") == "typed") {
            ServiceCallRecord("response", 200, body = client.getText(path, headers))
        } else {
            val bytes = if (body.isEmpty()) null else body.toByteArray()
            val contentType = if (body.isEmpty()) null else request().headerOrNull("Content-Type")
            val answer = client.request(method, path, bytes, contentType, headers)
            val named = answer.headers.firstOrNull { it.key.equals("x-answer", ignoreCase = true) }?.value ?: ""
            ServiceCallRecord("response", answer.status, answer.contentType, answer.text(), named)
        }
    } catch (e: ServiceCallFailed) {
        ServiceCallRecord("failed", e.status(), body = e.body())
    } catch (e: ServiceUnresolvable) {
        ServiceCallRecord("unresolvable", message = e.message.orEmpty())
    } catch (e: ServiceIdentityMismatch) {
        ServiceCallRecord("mismatch", message = e.message.orEmpty())
    } catch (e: ServiceUnanswered) {
        ServiceCallRecord("unanswered", message = e.message.orEmpty())
    } catch (e: CommandException) {
        ServiceCallRecord("refused", message = e.problem().detail)
    }
}
```

The exceptions are the Java SDK's: `ServiceUnresolvable`, `ServiceIdentityMismatch` and `ServiceUnanswered`,
each a `ServiceUnreachable`; `ServiceCallFailed` for an answer outside 2xx to a typed call; `CommandException`
for a refusal by the runtime. See [Calling other services](../build/calling-services.md).

## Running a service

```kotlin
fun service(): ServiceBuilder =
    Ankka.service("shopping-cart")
        .register(ShoppingCart::class.java)
        .register(CartRows::class.java)
        .register(CheckoutNotifier::class.java)
        .register(CheckoutsSeen::class.java)
        .register(CheckoutWorkflow::class.java)
        .withEndpoint(CartEndpoint::class.java)
        .withEndpoint(CheckoutsSeenEndpoint::class.java)
        .withEndpoint(CartKeysEndpoint::class.java)
        .withMcpEndpoint(CartMcpEndpoint::class.java)

fun main() {
    service().start().whenTerminated().toCompletableFuture().join()
}
```

`Ankka.service(name)`, `register(...)` and `start()` are the Java SDK's; `start()` throws `ServiceProblems`
listing every declaration problem before anything listens.

## Testing

The Java SDK's kits, with Kotlin entry points in `com.thinkmorestupidless.ankka.kotlin.testkit`:

| Kit | Drives |
|---|---|
| `eventSourcedTestKit(Entity::class[, id])`, `keyValueEntityTestKit(...)`, `workflowTestKit(...)`, `viewTestKit(...)`, `consumerTestKit(...)` | one component, no runtime |
| `ankkaTestKit(service(), settings) { … }` | the whole service against a throwaway Postgres, closed when the block ends |
| `eventually(within = 10.seconds) { … }` | a suspending retry of an assertion, inside the whole-service kit |

A whole-service test of a suspending workflow step:

```kotlin
@Test
fun `a checkout's suspending step checks the cart out and keeps what it held`() =
    ankkaTestKit(service()) {
        val widget = LineItem("widget", "Widget", 2)
        runBlocking {
            componentClient().forEventSourcedEntity("c1").call(ShoppingCart::addItem).invokeSuspending(widget)
            componentClient().forWorkflow("checkout-c1").call(CheckoutWorkflow::start).invoke("c1")
            eventually {
                val checkout = componentClient().forWorkflow("checkout-c1").call(CheckoutWorkflow::get).invoke()
                assertEquals(Cart("c1", listOf(widget), true), checkout.ordered)
            }
        }
```

See [Testing](../build/testing.md).

## Developing the SDK

The SDK is a Gradle build in `sdks/kotlin` of the ankka repository, built against the Java SDK of the
same commit:

```bash
just kotlin               # the Kotlin SDK's checks
just kotlin-conformance   # the conformance suite, against the Kotlin reference service
just template-kotlin      # the Kotlin template, generated and tested
```

The reference service the conformance suite drives is `sdks/kotlin/samples/reference`. See
[Adding a language SDK](../contributing/language-sdks.md).
