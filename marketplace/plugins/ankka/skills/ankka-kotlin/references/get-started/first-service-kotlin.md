# Your first service in Kotlin

> Create a Kotlin service from ankka's template, read its event sourced entity, view and HTTP endpoint, run it against a local Postgres, test it with and without a database, and watch it in the local console.

Source: https://docs.ankka.cloud/get-started/first-service-kotlin/
This tutorial creates a shopping cart in Kotlin from ankka's template, walks through each file, runs it on
your machine, tests it, and looks at it in the local console. You need a JDK 21 or later, Docker, and the
`ankka` CLI from [Install the tools](install.md). Gradle is not needed: the project carries a wrapper,
`./gradlew`, which fetches the version it names, and a JDK 21 to compile with if the machine has none.

A Kotlin service is hosted by ankka's runtime in the same JVM, as a Scala or Java service is. There is no
sidecar and no protocol between your code and the platform. The runtime owns everything stateful and
distributed — the journal, sharding, projections, timers and HTTP — and your classes own the decisions:
given this command and this state, what should happen. The Kotlin SDK is a library over the Java SDK, so
every Java type is usable as it is; it adds data classes in the codec, suspending handlers and calls, and
builders for effects and routes.

## Create the project

```bash
ankka init cart --language kotlin
cd cart
```

The name becomes the project's directory, the Gradle project, the image name and the descriptor's name.
The package is `com.example.cart` unless `--package` names another. The template writes:

- `build.gradle.kts`, which depends on `ankka-kotlin` and, for tests, `ankka-kotlin-testkit`, at the ankka
  version `gradle.properties` names: the version the CLI belongs to. It compiles with `javaParameters`,
  because an endpoint binds a path parameter by name, and with `-Xjspecify-annotations=strict`, so a `null`
  given where the SDK takes a value does not compile.
- `ShoppingCart.kt` (the domain and the entity), `CartView.kt` (a view of every cart), `CartEndpoint.kt`
  and `Main.kt`, which registers them.
- `ShoppingCartTest`, `CartViewTest` and `ServiceTest`, the two levels of test.
- `docker-compose.yml`, which starts Postgres; a Dockerfile; `service.json`, the descriptor; and GitHub
  workflows for CI and deployment. The project's README says how to run each.

## The domain

The cart's state and events are data classes and a sealed interface, at the top of `ShoppingCart.kt`:

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

/** What happened to a cart: the journal holds these, as JSON. */
@Manifest("shopping-cart-event")
sealed interface CartEvent {
    data class ItemAdded(val item: LineItem) : CartEvent

    data class ItemRemoved(val productId: String) : CartEvent

    data object CheckedOut : CartEvent

    data object Discarded : CartEvent
}
```

**Constructor parameters are the stored format.** The codec reads a data class by its primary constructor
and writes `productId` because the parameter is named so; a Scala, Java, Python or TypeScript service
reading the same journal expects the same names, so keep them once data exists. A sealed interface is
written as its case's object with `"type"` naming the case, as every ankka language stores a sum type; a
`data object` case is written with no other field. `@Manifest` names the type in the journal, so the class
can be renamed later without losing what was stored under it.

## An entity

An event sourced entity keeps its state as the fold of the events it persisted:

```kotlin
/** A customer's cart, one per cart id. Each handler's wire name is declared apart from its name. */
@ComponentId("shopping-cart")
class ShoppingCart : EventSourcedEntity<Cart, CartEvent>() {
    override fun emptyState() = Cart.empty(context().entityId())

    @Command("add-item")
    fun addItem(item: LineItem): Effect<Done> = effects {
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

Five things in this class are true of every ankka component in Kotlin:

- **A component is a class that extends its kind's base class.** `EventSourcedEntity<Cart, CartEvent>`
  names the state and the event types; `@ComponentId` names the component.
- **A handler returns an effect.** `effects { persist(...).thenDone() }` builds the description of what
  should happen. The handler performs no I/O, which is why it can be tested with nothing running.
- **The wire name is the annotation's string.** `@Command("add-item")` is what the platform stores and
  routes by. Renaming the function changes nothing; renaming `"add-item"` is a protocol change.
- **A query cannot persist.** A `@Query` function returns `ReadOnlyEffect`; one that returns `Effect` stops
  the service at start, naming the function.
- **A refusal is a value.** `error(...)` persists nothing and answers the caller with its status: `400` for
  a message alone, or the status of the `Problem` it is given, such as `409` for `Problem.conflict(...)`.

## A view

A view keeps a queryable row per cart, written from the cart's events:

```kotlin
/** Every cart as a row, kept from the cart's events: how many of each product, and whether it is checked out. */
@ComponentId("cart-rows")
class CartRows : View<CartEvent, CartRows.CartRow>() {
    @Manifest("cart-row")
    data class CartRow(val cartId: String, val quantities: Map<String, Int>, val checkedOut: Boolean)

    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCart::class.java)

    override fun onChange(event: CartEvent): ViewEffect<CartRow> = effects {
        val row = rowState().orNull() ?: CartRow(updateContext().subject(), emptyMap(), false)
        when (event) {
            is CartEvent.ItemAdded -> {
                val productId = event.item.productId
                updateRow(row.copy(quantities = row.quantities + (productId to (row.quantities[productId] ?: 0) + event.item.quantity)))
            }
            is CartEvent.ItemRemoved -> updateRow(row.copy(quantities = row.quantities - event.productId))
            CartEvent.CheckedOut -> updateRow(row.copy(checkedOut = true))
            // The deletion that follows removes the row: a discarded cart leaves the view.
            CartEvent.Discarded -> ignore()
        }
    }
}
```

`source()` names the entity whose events the view reads, and `onChange` returns what to do with the row
for each one: `updateRow`, `deleteRow` or `ignore`. `rowState()` is the row the view holds for the cart, if
any, and `.orNull()` turns its `Optional` into a Kotlin nullable. When the cart is deleted, its row is
deleted too. A view is eventually consistent with its source: a row is written shortly after the event, not
in the same transaction.

## An endpoint

An endpoint is the service's HTTP edge. The template declares its routes in code:

```kotlin
/** The cart over HTTP. Each handler suspends while it waits for the cart, holding no thread. */
class CartEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        get("/carts/{cartId}") { cart(path("cartId")).call(ShoppingCart::getCart).invokeSuspending() }
        get("/carts/{cartId}/total") { cart(path("cartId")).call(ShoppingCart::totalQuantity).invokeSuspending() }
        get("/carts/{cartId}/row") {
            val cartId = path("cartId")
            viewClient().forView(CartRows::class.java).getOrNull(cartId)
                ?: throw ProblemException(Problem.notFound("no row for '$cartId' yet"))
        }
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

Each route is a suspending block run on the request's own virtual thread. `path("cartId")` reads a path
parameter, and `post<LineItem>` reads the body as a `LineItem`. A block that answers a value answers
`200` with it; one that answers `Done` answers `204`. A call names the entity's handler by a function
reference, `ShoppingCart::addItem`, so the compiler checks the input and the reply types, and
`invokeSuspending` waits for the answer without holding a thread. A refusal from the entity reaches the
HTTP caller as its status with no handling in the endpoint. Routes can also be declared as annotated
functions, as in Java; [Kotlin SDK](../reference/kotlin-sdk.md) shows both.

`acl()` is required: an endpoint without one does not compile, because an access rule nobody chose is not
a default worth having. `Acl.allowAll()` is fine on your machine; decide it deliberately before the service
is exposed, as [HTTP endpoints](../build/http-endpoints.md) shows.

## Register it

Registration is explicit: a component that is not registered does not exist. `Main.kt` lists them:

```kotlin
/** The service: every component and endpoint registered by name, nothing found by scanning. */
fun service(): ServiceBuilder =
    Ankka.service("{{name}}")
        .register(ShoppingCart::class.java)
        .register(CartRows::class.java)
        .withEndpoint(CartEndpoint::class.java)
```

`start()` reads every registered class once. A declaration it cannot use — a handler with no wire name,
two handlers with one wire name, a type the codec has no form for — stops the start with every problem
listed, each naming the class and the member, before anything listens.

## Run it

The service keeps its journal and the view's rows in Postgres. The database schema comes out of the
`ankka-runtime` library, so extract it first, then start Postgres with it and run the service:

```bash
./gradlew schema          # the schema, from the ankka runtime, into build/ddl
docker compose up -d      # Postgres on 5432, initialised from build/ddl
./gradlew run             # the service, HTTP on :9000
```

In another terminal:

```bash
curl -X POST localhost:9000/carts/c1/items -H 'content-type: application/json' \
     -d '{"productId":"p1","name":"Pen","quantity":2}'
# (HTTP 204, no body)

curl localhost:9000/carts/c1
# {"cartId":"c1","items":[{"productId":"p1","name":"Pen","quantity":2}],"checkedOut":false}

curl localhost:9000/carts/c1/row
# {"cartId":"c1","quantities":{"p1":2},"checkedOut":false}
```

The row is written a moment after the event; read too soon, the route answers `404` until it is. Stop the
service with ctrl-c and start it again, then read the cart: it is still there. Its state was never
stored, only its events, and the entity rebuilt itself by replaying them. Postgres applies its init
directory only to an empty volume; after an ankka upgrade, recreate it with `docker compose down -v`,
`./gradlew schema` and `docker compose up -d`.

## Test it without the runtime

The component test kits drive one component with no runtime, cluster or database. In
`ShoppingCartTest`:

```kotlin
@Test
fun `an item added is in the cart`() {
    val cart = eventSourcedTestKit(ShoppingCart::class, "c1")
    val result = cart.call(ShoppingCart::addItem, LineItem("p1", "Pen", 2))
    assertEquals(Done.DONE, result.reply())
    assertEquals(listOf<CartEvent>(CartEvent.ItemAdded(LineItem("p1", "Pen", 2))), result.events())
    assertEquals(2, cart.state().totalQuantity)
}
```

And the view, in `CartViewTest`, handed the cart's events one at a time:

```kotlin
@Test
fun `a cart's row counts each product and says when it is checked out`() {
    val view = viewTestKit(CartRows::class)
    view.change("c1", CartEvent.ItemAdded(LineItem("p1", "Pen", 2)))
    view.change("c1", CartEvent.ItemAdded(LineItem("p1", "Pen", 1)))
    view.change("c1", CartEvent.CheckedOut)
    assertEquals(CartRows.CartRow("c1", mapOf("p1" to 3), true), view.row("c1").orNull())
}
```

```bash
./gradlew test --tests '*ShoppingCartTest' --tests '*CartViewTest'
```

These take milliseconds. Inputs, events, state, rows and replies still cross the components' own
serializers, so a type the encoding cannot express fails here rather than on first deployment. A refused
command answers a result whose `isError` is true and whose `problem()` carries the status.

## Test it through the real runtime

`ankkaTestKit` starts the whole service against a throwaway Postgres of its own in Docker, with its
endpoints on a free port, runs the block, and stops the service. In `ServiceTest`:

```kotlin
@Test
fun `an item added over HTTP is read back after a restart, and reaches the view`() =
    ankkaTestKit(service()) {
        val added =
            send(
                HttpRequest.newBuilder(http().resolve("/carts/c1/items"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"productId":"p1","name":"Pen","quantity":2}"""))
            )
        assertEquals(204, added.statusCode(), added.body())

        restartService()

        val cart = send(HttpRequest.newBuilder(http().resolve("/carts/c1")).GET())
        assertEquals(200, cart.statusCode(), cart.body())
        assertTrue(cart.body().contains("\"quantity\":2"), cart.body())

        runBlocking {
            eventually {
                val row = send(HttpRequest.newBuilder(http().resolve("/carts/c1/row")).GET())
                assertEquals(200, row.statusCode(), row.body())
                assertTrue(row.body().contains("\"p1\":2"), row.body())
            }
        }
    }
```

```bash
./gradlew test
```

`restartService()` stops the service and starts it again on the same database, so what the test reads
back was recorded rather than remembered. The view is read inside `eventually`, which retries until the row
has been written, because a view follows its source shortly after rather than at once. The first run
downloads the Postgres image, so it is slower than the ones after it.

## Watch it in the local console

```bash
ankka local console       # http://localhost:9889
```

The console finds every ankka service running on your machine by itself. For this one it shows the entity
and the view, a form for each HTTP route, and a trace of every request the service has served. The trace
of `POST /carts/{cartId}/items` shows the endpoint and the `shopping-cart#add-item` call beneath it. The
console can read a cart through the entity's query `get-cart`, and refuses to run `add-item`, because a
command can persist and the console only runs queries. [The local console](../operate/local-console.md)
covers the rest.

## Next

[Deploy to a local platform](deploy-locally.md). The template's Dockerfile copies what
`./gradlew installDist` lays out:

```bash
./gradlew installDist && docker build -t cart:local .
```

`service.json` is its descriptor; `runtime` is the ankka version the image was built against. A tag `v*`
runs `.github/workflows/deploy.yml`, which pushes the image to the repository's GitHub Container Registry
and deploys it. [Kotlin SDK](../reference/kotlin-sdk.md) lists what every other component kind is made
of.
