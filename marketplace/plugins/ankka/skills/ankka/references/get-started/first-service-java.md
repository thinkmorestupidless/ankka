# Your first service in Java

> Create a Java service from ankka's template, read its event sourced entity and HTTP endpoint, run it against a local Postgres, test it with and without a database, and watch it in the local console.

Source: https://docs.ankka.cloud/get-started/first-service-java/
This tutorial creates a shopping cart in Java from ankka's template, walks through each file, runs it on
your machine, tests it, and looks at it in the local console. You need a JDK 21 or later, Docker, and the
`ankka` CLI from [Install the tools](install.md). Maven is not needed: the project carries a wrapper,
`./mvnw`, which fetches the version it names.

A Java service is hosted by ankka's runtime in the same JVM, as a Scala service is. There is no sidecar
and no protocol between your code and the platform. The runtime owns everything stateful and distributed
— the journal, sharding, projections, timers and HTTP — and your classes own the decisions: given this
command and this state, what should happen.

## Create the project

```bash
ankka init cart --language java
cd cart
```

The name becomes the project's directory, the Maven artifact, the image name and the descriptor's name.
The Java package is `com.example.cart` unless `--package` names another. The template writes:

- `pom.xml`, which depends on `ankka-java` and, for tests, `ankka-java-testkit`, at the ankka version the
  CLI belongs to. It compiles with `-parameters`, because an endpoint binds a path parameter to the method
  parameter of the same name.
- The domain (`LineItem`, `Cart`, `CartEvent`), the entity (`ShoppingCart`), the endpoint
  (`CartEndpoint`) and `Main`, which registers them.
- `ShoppingCartTest` and `ServiceTest`, the two levels of test.
- `docker-compose.yml`, which starts Postgres; a Dockerfile; `service.json`, the descriptor; and GitHub
  workflows for CI and deployment. The project's README says how to run each.

## The domain

The cart's state and events are records and a sealed interface. A line of the cart:

```java
/** A quantity of one product in a cart. */
@Manifest("line-item")
public record LineItem(String productId, String name, int quantity) {}
```

The cart itself, with the methods its events are folded by:

```java
/** A cart: its lines, ordered by product, and whether it has been checked out. */
@Manifest("shopping-cart")
public record Cart(String cartId, List<LineItem> items, boolean checkedOut) {

  public static Cart empty(String cartId) {
    return new Cart(cartId, List.of(), false);
  }

  /** Adds {@code item}, merging it into the line for the same product. */
  public Cart addItem(LineItem item) {
    var merged =
        items.stream()
            .filter(i -> i.productId().equals(item.productId()))
            .findFirst()
            .map(e -> new LineItem(item.productId(), item.name(), e.quantity() + item.quantity()))
            .orElse(item);
    var next = new ArrayList<>(without(item.productId()));
    next.add(merged);
    next.sort(Comparator.comparing(LineItem::productId));
    return new Cart(cartId, List.copyOf(next), checkedOut);
  }

  public Cart removeItem(String productId) {
    return new Cart(cartId, without(productId), checkedOut);
  }

  public Cart onCheckedOut() {
    return new Cart(cartId, items, true);
  }

  public boolean contains(String productId) {
    return items.stream().anyMatch(i -> i.productId().equals(productId));
  }

  public int totalQuantity() {
    return items.stream().mapToInt(LineItem::quantity).sum();
  }

  public boolean isEmpty() {
    return items.isEmpty();
  }

  private List<LineItem> without(String productId) {
    return items.stream().filter(i -> !i.productId().equals(productId)).toList();
  }
}
```

And what can happen to it:

```java
/** What happened to a cart: the journal holds these, as the Scala cart's journal does. */
@Manifest("shopping-cart-event")
public sealed interface CartEvent {
  record ItemAdded(LineItem item) implements CartEvent {}

  record ItemRemoved(String productId) implements CartEvent {}

  record CheckedOut() implements CartEvent {}

  record Discarded() implements CartEvent {}
}
```

**Record components are the stored format.** The codec writes `productId` because the component is
named so; a service in any other language reading the same journal expects the same names, so keep
them once data exists. A sealed interface is written as its case's object with `"type"` naming the case,
as every ankka language stores a sum type. `@Manifest` names the type in the journal, so the class can be
renamed later without losing what was stored under it. A `null` in a record is refused when the value is
written, naming the component; a value that may be absent is an `Optional`.

## An entity

An event sourced entity keeps its state as the fold of the events it persisted:

```java
/** A customer's cart, one per cart id. Its handlers' wire names are the Scala cart's. */
@ComponentId("shopping-cart")
public class ShoppingCart extends EventSourcedEntity<Cart, CartEvent> {

  @Override
  protected Cart emptyState() {
    return Cart.empty(context().entityId());
  }

  @Command("add-item")
  public Effect<Done> addItem(LineItem item) {
    if (currentState().checkedOut()) return effects().error(checkedOut());
    if (item.quantity() <= 0)
      return effects().error("quantity must be greater than zero, was " + item.quantity());
    return effects().persist(new CartEvent.ItemAdded(item)).thenDone();
  }

  @Command("remove-item")
  public Effect<Done> removeItem(String productId) {
    if (currentState().checkedOut()) return effects().error(checkedOut());
    if (!currentState().contains(productId))
      return effects().error(Problem.notFound("cart does not contain '" + productId + "'"));
    return effects().persist(new CartEvent.ItemRemoved(productId)).thenDone();
  }

  @Command("checkout")
  public Effect<Cart> checkout() {
    if (currentState().checkedOut()) return effects().error(checkedOut());
    if (currentState().isEmpty()) return effects().error("cannot check out an empty cart");
    return effects().persist(new CartEvent.CheckedOut()).thenReplyState();
  }

  @Command("discard")
  public Effect<Done> discard() {
    if (currentState().checkedOut()) return effects().error(checkedOut());
    return effects().persist(new CartEvent.Discarded()).thenDelete().thenDone();
  }

  @Query("get-cart")
  public ReadOnlyEffect<Cart> getCart() {
    return effects().reply(currentState());
  }

  @Query("total-quantity")
  public ReadOnlyEffect<Integer> totalQuantity() {
    return effects().reply(currentState().totalQuantity());
  }

  @Override
  protected Cart applyEvent(Cart state, CartEvent event) {
    return switch (event) {
      case CartEvent.ItemAdded added -> state.addItem(added.item());
      case CartEvent.ItemRemoved removed -> state.removeItem(removed.productId());
      case CartEvent.CheckedOut ignored -> state.onCheckedOut();
      case CartEvent.Discarded ignored -> state;
    };
  }

  private static Problem checkedOut() {
    return Problem.conflict("cart is already checked out");
  }
}
```

Five things in this file are true of every ankka component in Java:

- **A component is a class that extends its kind's base class.** `EventSourcedEntity<Cart, CartEvent>`
  names the state and the event types; `@ComponentId` names the component.
- **A handler returns an effect.** `effects().persist(...).thenDone()` is a description of what should
  happen. The handler performs no I/O, which is why it can be tested with nothing running.
- **The wire name is the annotation's string.** `@Command("add-item")` is what the platform stores and
  routes by. Renaming the method changes nothing; renaming `"add-item"` is a protocol change.
- **A query cannot persist.** A `@Query` method returns `ReadOnlyEffect`; one that returns `Effect` stops
  the service at start, naming the method.
- **A refusal is a value.** `effects().error(...)` persists nothing and answers the caller with its
  status: `400` for a message alone, or the status of the `Problem` it is given, such as `409` for
  `Problem.conflict(...)`.

## An endpoint

An endpoint is the service's HTTP edge:

```java
/** The cart over HTTP, route for route as the Scala cart serves it. */
public class CartEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Get("/carts/{cartId}")
  public Cart get(String cartId) {
    return cart(cartId).call(ShoppingCart::getCart).invoke();
  }

  @Get("/carts/{cartId}/total")
  public Integer total(String cartId) {
    return cart(cartId).call(ShoppingCart::totalQuantity).invoke();
  }

  @Post("/carts/{cartId}/items")
  public Done addItem(String cartId, @Body LineItem item) {
    return cart(cartId).call(ShoppingCart::addItem).invoke(item);
  }

  @Delete("/carts/{cartId}/items/{productId}")
  public Done removeItem(String cartId, String productId) {
    return cart(cartId).call(ShoppingCart::removeItem).invoke(productId);
  }

  @Post("/carts/{cartId}/checkout")
  public Cart checkout(String cartId) {
    return cart(cartId).call(ShoppingCart::checkout).invoke();
  }

  @Delete("/carts/{cartId}")
  public Done discard(String cartId) {
    return cart(cartId).call(ShoppingCart::discard).invoke();
  }

  private EntityRef cart(String cartId) {
    return componentClient().forEventSourcedEntity(cartId);
  }
}
```

Each route is a method annotated with its verb and path template. A parameter named as a template segment
is bound to it, and `@Body` reads the request body as the parameter's type. A method that returns a value
answers `200` with it; one that returns `Done` answers `204`. A call names the entity's handler by a method
reference, `ShoppingCart::addItem`, so the compiler checks the input and the reply types, and the SDK
resolves the reference to the handler's wire name. A refusal from the entity reaches the HTTP caller as its
status with no handling in the endpoint.

`acl()` is required: an endpoint without one does not compile, because an access rule nobody chose is not
a default worth having. `Acl.allowAll()` is fine on your machine; decide it deliberately before the service
is exposed, as [HTTP endpoints](../build/http-endpoints.md) shows.

## Register it

Registration is explicit: a component that is not registered does not exist. `Main` lists them:

```java
public static ServiceBuilder service() {
  return Ankka.service("{{name}}").register(ShoppingCart.class).withEndpoint(CartEndpoint.class);
}
```

`start()` reads every registered class once. A declaration it cannot use — a handler with no wire name,
two handlers with one wire name, a type the codec has no form for — stops the start with every problem
listed, each naming the class and the member, before anything listens.

## Run it

The service keeps its journal in Postgres. The database schema comes out of the `ankka-runtime` library,
so extract it first, then start Postgres with it and run the service:

```bash
./mvnw generate-resources   # the schema, from the ankka runtime, into target/ddl
docker compose up -d        # Postgres on 5432, initialised from target/ddl
./mvnw exec:java            # the service, HTTP on :9000
```

In another terminal:

```bash
curl -X POST localhost:9000/carts/c1/items -H 'content-type: application/json' \
     -d '{"productId":"p1","name":"Pen","quantity":2}'
# (HTTP 204, no body)

curl localhost:9000/carts/c1
# {"cartId":"c1","items":[{"productId":"p1","name":"Pen","quantity":2}],"checkedOut":false}
```

Stop the service with ctrl-c and start it again, then read the cart: it is still there. Its state was never
stored, only its events, and the entity rebuilt itself by replaying them. Postgres applies its init
directory only to an empty volume; after an ankka upgrade, recreate it with `docker compose down -v`,
`./mvnw generate-resources` and `docker compose up -d`.

## Test it without the runtime

`EventSourcedTestKit` drives one entity with no runtime, cluster or database. In `ShoppingCartTest`:

```java
@Test
void anItemAddedIsInTheCart() {
  var cart = EventSourcedTestKit.of(ShoppingCart.class, "c1");
  var result = cart.call(ShoppingCart::addItem, new LineItem("p1", "Pen", 2));
  assertEquals(Done.DONE, result.reply());
  assertEquals(List.of(new CartEvent.ItemAdded(new LineItem("p1", "Pen", 2))), result.events());
  assertEquals(2, cart.state().totalQuantity());
}
```

```bash
./mvnw test -Dtest=ShoppingCartTest
```

This takes milliseconds. A call names the handler by method reference, as the endpoint does. Inputs,
events, state and replies still cross the entity's own serializers, so a type the encoding cannot express
fails here rather than on first deployment. A refused command answers a result whose `isError()` is true
and whose `problem()` carries the status.

## Test it through the real runtime

`AnkkaTestKit` starts the whole service against a throwaway Postgres of its own in Docker, with its
endpoints on a free port. In `ServiceTest`:

```java
@Test
void anItemAddedOverHttpIsReadBackAfterARestart() throws Exception {
  var added =
      send(
          HttpRequest.newBuilder(kit.http().resolve("/carts/c1/items"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(
                  "{\"productId\":\"p1\",\"name\":\"Pen\",\"quantity\":2}")));
  assertEquals(204, added.statusCode(), added.body());

  kit.restartService();

  var cart = send(HttpRequest.newBuilder(kit.http().resolve("/carts/c1")).GET());
  assertEquals(200, cart.statusCode(), cart.body());
  assertTrue(cart.body().contains("\"quantity\":2"), cart.body());
}
```

```bash
./mvnw test
```

`restartService()` stops the service and starts it again on the same database, so what the test reads
back was recorded rather than remembered. The first run downloads the Postgres image, so it is slower than
the ones after it.

## Watch it in the local console

```bash
ankka local console       # http://localhost:9889
```

The console finds every ankka service running on your machine by itself. For this one it shows the
registered entity, a form for each HTTP route, and a trace of every request the service has served. The
trace of `POST /carts/{cartId}/items` shows the endpoint and the `shopping-cart#add-item` call beneath it.
The console can read a cart through the entity's query `get-cart`, and refuses to run `add-item`, because
a command can persist and the console only runs queries. [The local console](../operate/local-console.md)
covers the rest.

## Next

[Deploy to a local platform](deploy-locally.md). The template builds its image with Jib, with no
Dockerfile needed:

```bash
./mvnw package jib:dockerBuild    # the image cart:local, into Docker
```

`service.json` is its descriptor; `runtime` is the ankka version the image was built against. A tag
`v*` runs `.github/workflows/deploy.yml`, which pushes the image to the repository's GitHub Container
Registry and deploys it. [Java SDK](../reference/java-sdk.md) lists what every other component kind is made
of.
