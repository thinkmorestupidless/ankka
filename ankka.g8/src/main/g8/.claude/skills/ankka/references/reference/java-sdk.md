# Java SDK

> A compact map of the Java SDK — installing it, the codec, method references and wire names, modules, serializers, and for every component kind its base class, annotations, effects and test kit.

Source: https://docs.ankka.cloud/reference/java-sdk/
The Java SDK is the library `ankka-java`. A Java service is hosted by ankka's runtime in the same JVM, as
a Scala service is: there is no sidecar and no protocol between your classes and the platform. The runtime
owns the journal, sharding, projections, timers and HTTP, and your handlers decide what each command does.
This page lists what each component kind is made of. [Your first service in Java](../get-started/first-service-java.md)
builds one from the template.

## Installing

The SDK is on Maven Central as three artifacts, each published by every ankka release at the release's
version, with no Scala suffix:

| Artifact | Holds |
|---|---|
| `com.thinkmorestupidless:ankka-java` | the components, effects, clients, endpoints and codec |
| `com.thinkmorestupidless:ankka-java-testkit` | the component test kits and the whole-service kit, for tests |
| `com.thinkmorestupidless:ankka-java-agent` | agents, for a service that declares them |

Pin the version of the platform you deploy to, and say the same in the descriptor's `runtime`. The SDK needs
JDK 21 or later. Compile with `-parameters`: an endpoint binds a path parameter to the method parameter of
the same name, and without the flag the names are not in the class file. `ankka init cart --language java`
writes a Maven project with both settings.

The SDK's packages carry JSpecify's `@NullMarked`: every parameter and return is non-null unless marked
`@Nullable`, and a value that may be absent is an `Optional`.

## Codec and types

Every value that crosses a boundary — into the journal, to another component, onto a topic, over HTTP — is
encoded by the SDK's record codec, under the rules every ankka language shares, so one journal is readable
from all of them; [Serialization](../build/serialization.md) has the rules. The codec reads a type once, the
first time a handler names it, and a type it has no form for stops the service at start, naming the member.

| Java type | On the wire |
|---|---|
| a `record` | a JSON object, every component written under its name |
| a `sealed interface` (or sealed abstract class) whose cases are records | the case's object with `"type": "<Case>"` first |
| an `enum` | `{"type":"<CONSTANT>"}`; a bare string is read too |
| `String`, `int`/`Integer`, `long`/`Long`, `double`/`Double`, `boolean`/`Boolean` | at top level `text/plain`; inside a record, JSON |
| `BigDecimal` | a JSON number |
| `Instant`, `LocalDate`, `LocalDateTime`, `UUID`, `Duration` | ISO-8601 or canonical strings inside a record; a top-level `Duration` is its milliseconds as text |
| `byte[]` | raw bytes at top level; base64 inside a record |
| `Optional<T>` | `null` when empty inside a record; at top level, no bytes for empty |
| `List<T>`, `Set<T>`, arrays, `Map<String, T>` | a JSON array, a JSON object |
| `Done`, `Void` | the empty `done` and `unit` payloads, as a reply |

Refused, naming where: `float`, `short`, `byte` and `char` (use `int`, `long` or `double`, which every SDK
reads); a map whose key is not a `String`; a record component named `type`, where a sum writes its case; a
generic record, or a type variable the codec cannot resolve; and any other class, which needs a record, a
sealed interface, an enum or [a serializer of your own](#supplying-a-serializer). A `null` in a record is
refused when the value is written, naming the component (`Cart.items[2].name: is null`); an absent value is
`Optional.empty()`. Reading is lenient where the encoding is — fields in any order, an unknown field ignored,
an absent `Optional` empty — and strict where it is: a missing field fails, naming it.

A value is stored under its manifest: `@Manifest("shopping-cart")` on the type, or else the simple name in
kebab case (`ShoppingCartEvent` is `shopping-cart-event`), which changes if the class is renamed. Name it
once a journal holds the type.

## The shape every component shares

A component is a class that extends its kind's base class, annotated `@ComponentId`, with a constructor that
takes nothing (an entity's may take its `EntityContext`). Its handlers are methods annotated with their wire
names; each takes one parameter or none and returns an effect built by `effects()`. A key value entity:

```java
/** A key value entity holding a name. */
@ComponentId("profile")
public class Profile extends KeyValueEntity<Profile.ProfileState> {

  @Manifest("profile")
  public record ProfileState(String name) {}

  @Override
  protected ProfileState emptyState() {
    return new ProfileState("");
  }

  @Command("set")
  public Effect<String> set(String name) {
    if (name.isEmpty()) return effects().error("a name is needed");
    return effects().updateState(new ProfileState(name)).thenReply("done");
  }

  @Query("get")
  public ReadOnlyEffect<String> get() {
    return effects().reply(currentState().name().isEmpty() ? "none" : currentState().name());
  }

  @Command("delete")
  public Effect<String> delete() {
    return effects().deleteEntity().thenReply("done");
  }
}
```

`@Command` handlers return `Effect`; `@Query` handlers return `ReadOnlyEffect`, which `reply` and `error`
build and `updateState` and `persist` do not, so a query cannot persist. A handler with two parameters takes
a record instead. Components are registered by class: `Ankka.service("shop").register(Profile.class)`, or
`register(Profile.class, "profile")` for a class with no `@ComponentId`.

## Method references and wire names

The string in `@Command("add-item")` is the wire name: what the platform stores, routes by, and what an
in-flight call or a persisted timer addresses. The method's own name is yours, and renaming it changes
nothing on the wire. Anywhere a handler is named in code — a call, a step to transition to, a step's
settings, a timer's action, a test — it is named by a method reference:

```java
componentClient().forEventSourcedEntity(id).call(ShoppingCart::addItem).invoke(item)
stepEffects().updateState(s).thenTransitionTo(Checkout::charge)
timers().schedule("remind-" + id, Duration.ofMinutes(5), Reminder::remind, id)
```

The reference is resolved through the service's registry to the annotation's string, once per call site,
so the compiler checks the input and reply types and a renamed method still calls the same wire name. A
lambda is not a reference: `call(c -> c.addItem(item))` fails with a `CallerError` naming the calling class,
and so does a reference to a method with no `@Command` or `@Query`, or to a component the service does not
register. `call(componentId, wireName, input, output)` is the escape hatch for a component whose class is
not on the classpath; the compiler checks nothing there.

Every declaration is read when the service starts. A handler with no wire name, an empty one, one with the
platform's reserved `ankka:` prefix, two handlers with one wire name, a `@Query` that returns `Effect`, a
`static` handler, a method that returns an effect with no annotation, or a type the codec refuses: each is a
`DeclarationProblem`, and `start()` throws `ServiceProblems` listing every one, by class and member, before
anything listens.

## Module system

The SDK reads registered classes by reflection: it constructs components and endpoints, calls their handler
methods (which need not be public), and reads and builds records. A service on the class path, as the
template builds it, needs nothing for this. A service that declares a `module-info.java` must open every
package that holds a component, an endpoint or a type the codec reads, or the start fails on the first
inaccessible one. The SDK's jars declare no module name of their own, so open the packages to everyone:
`opens com.example.cart;`.

## Supplying a serializer

For a type the codec refuses, or one whose bytes you decide, hand the service a serializer of your own:

```java
Ankka.service("shop")
    .register(Ledger.class)
    .withSerializer(Money.class, Serializer.of("money", Money::toBytes, Money::fromBytes))
```

`Serializer.of(manifest, write, read)` is the whole of it; `contentType()` is JSON unless the serializer
overrides it. Every handler, row, event and message that names the type uses this serializer, in the
service and in its test kits. The manifest is what the journal records the bytes under, so keep it stable.
Another language reading the same journal must read the same bytes, which is then yours to keep.
`Serializer.of(Class)` answers the SDK's own serializer for a type, for a test or a hand-written one built
on it.

## Event sourced entity

| Part | API |
|---|---|
| Base class | `EventSourcedEntity<S, E>` |
| Annotations | `@ComponentId`; `@Command`, `@Query` on handlers; optionally `@SnapshotEvery(n)` (100 by default, `0` never) and `@Replicated` |
| Must define | `applyEvent(S state, E event)`, the one place state changes; it reads nothing but its arguments |
| May define | `emptyState()`, `null` unless overridden |
| In a handler | `currentState()`, `context()` (`entityId()`, `componentId()`, `componentClient()`, `metadata(key)`, `sequenceNumber()`, `attribution()`, `installation()`, `isHome()`, `forwardedFrom()`); in `applyEvent`, `eventContext()` |
| Effects | `effects().persist(e)`, `persist(e1, e2)`, `persist(e1, e2, e3)`, `persistAll(list)`, `deleteEntity()`, then `.thenReply(state -> r)`, `.thenReply(r)`, `.thenReplyState()`, `.thenDone()`, `.thenNoReply()`, `.thenDelete()`, `.expireAfter(d)`; `effects().reply(r)`, `error(message)`, `error(problem)`, `noReply()` |
| Replication | on a `@Replicated` entity, `handOverTo(installation)`, `replicateTo(installation)`, `stopReplicatingTo(installation)`, as effects or after `persist` |

All the events of one effect are persisted together, before the reply. `error(message)` refuses with `400`;
`error(Problem.conflict(...))`, `Problem.notFound(...)` and the rest refuse with the problem's status.

**A refusal is returned, never thrown.** A handler that throws fails its command as a fault: nothing is
persisted, the caller receives `500` with `internal error` rather than your message, and a trace shows a
fault rather than a refusal. Where a handler would throw because a rule is broken — the cart is checked out,
the quantity is not positive — return `effects().error(...)` instead, with the `Problem` whose status says
why. The same holds for a key value entity and a workflow's commands. See
[Event sourced entities](../build/event-sourced-entities.md) and [Error codes](error-codes.md).

The shopping cart's entity:

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

## Key value entity

| Part | API |
|---|---|
| Base class | `KeyValueEntity<S>` |
| May define | `emptyState()`, `null` unless overridden |
| Effects | `effects().updateState(s)`, `deleteEntity()`, then `.thenReply(...)`, `.thenReplyState()`, `.thenDone()`, `.thenNoReply()`, `.expireAfter(d)`; `effects().reply(r)`, `error(...)`, `noReply()` |

See [Key value entities](../build/key-value-entities.md).

## View

| Part | API |
|---|---|
| Base class | `View<Src, Row>` |
| Must define | `source()`, a `ChangeSource`; `onChange(Src change)` returning a `ViewEffect<Row>` |
| May define | `onDelete()`, which deletes the row by default; `version()`, whose raising rebuilds the view into a fresh table; `parallelism()`, four by default |
| Sources | `ChangeSource.of(Entity.class)`, an entity's events or state; `ofWorkflow(Workflow.class)`, a workflow's state; `ofTopic(topic, Type.class)` or `ofTopic(project, topic, Type.class)`, then `.fromEarliest()`, `.fromLatest()`, `.from(instant)`, `.withContract(contract)`, `.onBroker(name)`, `.parallel()` |
| In a handler | `rowState()`, the row held for the source id, as an `Optional`; `updateContext()` (`subject()`, `sequenceNumber()`, `localOrigin()`, `installation()`, `standing()`) |
| Effects | `effects().updateRow(row)`, `deleteRow()`, `ignore()` |
| Declared queries | `public static final ViewQuery` fields: `ViewQuery.declare(name, sql)`, `.watched()`; `ViewQuery.tableOf(View.class)` names the table |
| Querying | `viewClient().forView(View.class)`: `get(key)`, `all()`, `all(limit)`, `count()`, `ask(query, values)`, `askStream(...)`, `watch(query, values)`, `watchRow(key)`, and `…Async` forms |

A view over an entity, from the shopping cart:

```java
@ComponentId("cart-rows")
public class CartRows extends View<CartEvent, CartRows.CartRow> {

  @Manifest("cart-row")
  public record CartRow(
      String cartId,
      Map<String, Integer> quantities,
      boolean checkedOut,
      Map<String, String> names) {}

  @Override
  protected ChangeSource<CartEvent> source() {
    return ChangeSource.of(ShoppingCart.class);
  }

  @Override
  protected ViewEffect<CartRow> onChange(CartEvent event) {
    var current =
        rowState().orElse(new CartRow(updateContext().subject(), Map.of(), false, Map.of()));
    var quantities = new HashMap<>(current.quantities());
    var names = new HashMap<>(current.names());
    return switch (event) {
      case CartEvent.ItemAdded added -> {
        quantities.merge(added.item().productId(), added.item().quantity(), Integer::sum);
        names.put(added.item().productId(), added.item().name());
        yield effects()
            .updateRow(new CartRow(current.cartId(), quantities, current.checkedOut(), names));
      }
      case CartEvent.ItemRemoved removed -> {
        quantities.remove(removed.productId());
        names.remove(removed.productId());
        yield effects()
            .updateRow(new CartRow(current.cartId(), quantities, current.checkedOut(), names));
      }
      case CartEvent.CheckedOut ignored ->
          effects()
              .updateRow(
                  new CartRow(current.cartId(), current.quantities(), true, current.names()));
      // The deletion that follows removes the row: a discarded cart leaves the listing.
      case CartEvent.Discarded ignored -> effects().ignore();
    };
  }
}
```

A declared query is SQL over the view's table, each value written `:name`, checked when the service starts.
A recursive one, and one declared watchable, which selects each row's key beside the row:

```java
/** Every row under the row {@code row}, to any depth, by key: the same in every language. */
public static final ViewQuery UNDER =
    ViewQuery.declare(
        "under",
        "WITH RECURSIVE below AS (\n"
            + "  SELECT row_key, payload FROM "
            + TABLE
            + " WHERE payload::jsonb->>'under' = :row\n"
            + "  UNION\n"
            + "  SELECT n.row_key, n.payload FROM "
            + TABLE
            + " n JOIN below b ON n.payload::jsonb->>'under' = b.row_key\n"
            + ")\n"
            + "SELECT payload FROM below ORDER BY row_key");

/** Every row, by key, watchable: the same statement in every language. */
public static final ViewQuery ALL_ROWS =
    ViewQuery.declare("all-rows", "SELECT row_key, payload FROM " + TABLE + " ORDER BY row_key")
        .watched();
```

A view over a topic starts at the earliest message the broker holds unless its source says otherwise. See
[Views](../build/views.md).

## Keyed view

A view that reads several sources and names its rows by key. Each source is a method annotated
`@Source(Entity.class)`, or `@Source(topic = "name")`, taking the change, or the change and its
`KeyedChange<Row>`; `@Source(value = Entity.class, deleted = true)` handles that source's deletions.

| Part | API |
|---|---|
| Base class | `KeyedView<Row>` |
| In a handler | `change.subject()`, `change.sequenceNumber()`, `change.standing()`, `change.rows().get(key)`, `change.rows().ask(query, values)` |
| Effects | `effects().updateRow(key, row)`, `deleteRow(key)`, `updateRows(map)`, `deleteRows(keys)`, `ignore()`, then `.and(other)` |
| Querying | `viewClient().forKeyedView(View.class)`, as a view's |

```java
@ComponentId("joined-rows")
public class JoinedRows extends KeyedView<JoinedRows.JoinedRow> {

  /** A row the left writes under the key it names, holding a right entity's id. */
  @Manifest("joined-row")
  public record JoinedRow(String key, String holding, List<String> notes) {
    JoinedRow noting(String who) {
      var more = new ArrayList<>(notes);
      more.add(who);
      return new JoinedRow(key, holding, List.copyOf(more));
    }
  }

  /** The rows holding one right entity, by key: the same statement in every language. */
  public static final ViewQuery OF_RIGHT =
      ViewQuery.declare(
          "of-right",
          "SELECT payload FROM "
              + ViewQuery.tableOf(JoinedRows.class)
              + " WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key");

  /** The left names a row {@code key|holding}, and writes it from what it held, noting itself. */
  @Source(JoinedLeft.class)
  public KeyedViewEffect<JoinedRow> onLeft(Noted event, KeyedChange<JoinedRow> change) {
    var parts = event.text().split("\\|", 2);
    var key = parts[0];
    var holding = parts[1];
    var held = change.rows().get(key).map(JoinedRow::notes).orElse(List.of());
    return effects().updateRow(key, new JoinedRow(key, holding, held).noting("left"));
  }

  /** The right finds every row holding it by asking the view's own query, and notes itself. */
  @Source(JoinedRight.class)
  public KeyedViewEffect<JoinedRow> onRight(Noted event, KeyedChange<JoinedRow> change) {
    var theirs = change.rows().ask(OF_RIGHT, Map.of("holding", change.subject()));
    var rows = new LinkedHashMap<String, JoinedRow>();
    for (var row : theirs) rows.put(row.key(), row.noting("right"));
    return effects().updateRows(rows);
  }
}
```

## Consumer

| Part | API |
|---|---|
| Base class | `Consumer<Src, Out>`; `Out` is `Void` for one that publishes nothing |
| Must define | `source()`, a `ChangeSource`; `onChange(Src change)` returning a `ConsumerEffect<Out>` |
| May define | `onDelete()`, which ignores by default; `publishes()`, an `Optional<Publication>` (`Publication.to(topic)`, `to(project, topic)`, then `.withContract(contract)`, `.onBroker(name)`); `mirroredChanges()`; `version()`; `parallelism()` |
| In a handler | `messageContext()` (`subject()`, `sequenceNumber()`, `localOrigin()`, `installation()`, `standing()`), `componentClient()`, `services()`, `secrets()`, `storage()` |
| Effects | `effects().publish(message)`, `publish(message, key)`, `publishAll(messages)`, `publishMessages(outgoings)`, `done()`, `ignore()` |
| One of several messages | `Outgoing.of(payload)`, then `.withKey(key)` and `.withMetadata(name, value)` |

```java
@ComponentId("checkout-fanout")
public class CheckoutFanout extends Consumer<CartEvent, Fanned> {

  @Override
  protected ChangeSource<CartEvent> source() {
    return ChangeSource.of(ShoppingCartEntity.class);
  }

  @Override
  protected Optional<Publication> publishes() {
    return Optional.of(Publication.to("conformance-fanout"));
  }

  @Override
  protected ConsumerEffect<Fanned> onChange(CartEvent event) {
    return switch (event) {
      case CartEvent.ItemAdded ignored -> effects().publishAll(List.of());
      case CartEvent.ItemRemoved ignored -> effects().publish(new Fanned(0));
      case CartEvent.CheckedOut ignored ->
          effects()
              .publishMessages(
                  List.of(
                      Outgoing.of(new Fanned(1)),
                      Outgoing.of(new Fanned(2)).withKey("second:" + messageContext().subject()),
                      Outgoing.of(new Fanned(3)).withMetadata("x-n", "3")));
      case CartEvent.Discarded ignored -> effects().ignore();
    };
  }
}
```

Delivery is at least once, and a handler that throws has the change delivered again, so what it does must be
safe to do twice. A consumer over a topic must say where it starts. A consumer that publishes needs a
broker. See [Consumers](../build/consumers.md).

## Graph consumer

A consumer that publishes its source as graph deltas, keyed for a compacted topic. It is registered as any
component is, and can publish nothing but deltas.

| Part | API |
|---|---|
| Base class | `GraphConsumer<Src>` |
| Must define | `source()`, `topic()`, `onChange(Src change)` returning a `GraphEffect` |
| May define | `onDelete()`, which ignores by default; `version()`; `parallelism()` |
| Elements | `graph().node(id, labels, properties)`, `edge(id, type, from, to, properties)`, `tombstoneNode(id)`, `tombstoneEdge(id, type, from, to)`; then `.at(version)` to state one at a version other than the change's sequence number |
| Effects | `effects().publish(elements...)`, `publish(list)`, `done()`, `ignore()` |
| Reading deltas | `GraphDelta.read(value)`, `GraphDelta.read(key, value)`, `GraphDelta.nodeKey(id)`, `edgeKey(id)`, `SCHEMA_NAME` |

A property's value is a `String`, `Boolean`, `Integer`, `Long` or `Double`, or a non-empty `List` of one of
those; anything else, or an element the graph rules refuse, is an `IllegalArgumentException` where it is
built.

```java
@ComponentId("cart-graph")
public class CartGraph extends GraphConsumer<CartEvent> {

  @Override
  protected ChangeSource<CartEvent> source() {
    return ChangeSource.of(ShoppingCartEntity.class);
  }

  @Override
  protected String topic() {
    return "conformance-graph";
  }

  private GraphElement cart(String id, boolean checkedOut) {
    return graph()
        .node("cart:" + id, List.of("Cart"), Map.of("cartId", id, "checkedOut", checkedOut));
  }

  @Override
  protected GraphEffect onChange(CartEvent event) {
    var id = messageContext().subject();
    return switch (event) {
      case CartEvent.ItemAdded ignored -> effects().publish(cart(id, false));
      case CartEvent.ItemRemoved ignored -> effects().publish(cart(id, false));
      case CartEvent.CheckedOut ignored ->
          effects()
              .publish(
                  cart(id, true),
                  graph().node("checkout:" + id, List.of("Checkout"), Map.of("cartId", id)),
                  graph()
                      .edge("checked-out:" + id, "CHECKED_OUT", "cart:" + id, "checkout:" + id,
                          Map.of()));
      case CartEvent.Discarded ignored -> effects().ignore();
    };
  }

  @Override
  protected GraphEffect onDelete() {
    return effects().publish(graph().tombstoneNode("cart:" + messageContext().subject()));
  }
}
```

See [Publish a graph](../build/graph.md).

## Workflow

| Part | API |
|---|---|
| Base class | `Workflow<S>` |
| Annotations | `@Command`, `@Query` on commands; `@Step("name")` on steps, each taking one input or none and returning a `StepEffect` |
| May define | `emptyState()`; `settings()`, a `WorkflowSettings` |
| Command effects | `effects().updateState(s)`, `transitionTo(Step::ref)`, `transitionTo(Step::ref, input)`, `delete()`, then `.transitionTo(...)`, `.thenReply(r)`, `.thenReplyState()`, `.thenDone()`, `.thenNoReply()`; `effects().reply(r)`, `error(...)` |
| Step effects | `stepEffects().updateState(s)`, then `.thenTransitionTo(Step::ref)`, `.thenTransitionTo(Step::ref, input)`, `.thenPause()`, `.thenPause(duration, Step::onTimeout)`, `.thenEnd()`, `.thenFail(message)`; or the same directly on `stepEffects()` |
| Settings | `WorkflowSettings.builder().timeout(d).defaultStepTimeout(d).stepTimeout(Step::ref, d).defaultRecovery(r).stepRecovery(Step::ref, r).build()` |
| Recovery | `RecoverStrategy.fail()`, `maxRetries(n)`, then `.failoverTo(Step::ref)` |
| In a step | `currentState()`, `context().componentClient()`, `services()`, `secrets()`, `storage()` |

```java
@ComponentId("checkout")
public class Checkout extends Workflow<Checkout.CheckoutState> {

  @Manifest("checkout")
  public record CheckoutState(String id, String mode, String status) {
    CheckoutState withStatus(String next) {
      return new CheckoutState(id, mode, next);
    }
  }

  private static final Set<String> DECLINED = Set.of("fail", "abort", "drop", "doomed");

  @Override
  protected CheckoutState emptyState() {
    return new CheckoutState(context().entityId(), "", "new");
  }

  @Override
  protected WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .defaultStepTimeout(Duration.ofSeconds(10))
        .stepRecovery(Checkout::charge, RecoverStrategy.maxRetries(1).failoverTo(Checkout::compensate))
        .build();
  }

  @Command("start")
  public Effect<String> start(String mode) {
    if (!currentState().status().equals("new"))
      return effects().error(Problem.conflict("already started"));
    return effects()
        .updateState(new CheckoutState(currentState().id(), mode, "reserving"))
        .transitionTo(Checkout::reserve)
        .thenReply("started");
  }

  @Step("reserve")
  public StepEffect reserve() {
    // A client call from a step: what the cart holds.
    context()
        .componentClient()
        .forEventSourcedEntity(currentState().id())
        .call(ShoppingCartEntity::totalQuantity)
        .invoke();
    var reserved = stepEffects().updateState(currentState().withStatus("reserved"));
    return currentState().mode().equals("pause")
        ? reserved.thenTransitionTo(Checkout::waitStep)
        : reserved.thenTransitionTo(Checkout::charge);
  }

  @Step("wait")
  public StepEffect waitStep() {
    return stepEffects()
        .updateState(currentState().withStatus("waiting"))
        .thenPause(Duration.ofMillis(1500), Checkout::charge);
  }

  @Step("charge")
  public StepEffect charge() {
    if (DECLINED.contains(currentState().mode())) throw new RuntimeException("payment declined");
    return stepEffects().updateState(currentState().withStatus("charged")).thenEnd();
  }

  @Step("compensate")
  public StepEffect compensate() {
    return switch (currentState().mode()) {
      case "abort" ->
          stepEffects()
              .updateState(currentState().withStatus("aborted"))
              .thenFail("payment declined");
      case "drop" -> stepEffects().thenFail("payment declined");
      case "doomed" -> throw new RuntimeException("compensation failed too");
      default -> stepEffects().updateState(currentState().withStatus("compensated")).thenEnd();
    };
  }

  @Query("status")
  public ReadOnlyEffect<String> status() {
    return effects().reply(currentState().status());
  }
}
```

A step that throws, or runs out of time, is retried as its recovery says, then failed over or failed; a step
that returns `thenFail(...)` ends the workflow failed. Every transition is journaled, so a workflow resumes
where it was after a restart. A step names the step that follows by method reference, so renaming the method
leaves the journal's step name, the `@Step` value, alone. See [Workflows](../build/workflows.md).

## Timed action and timers

| Part | API |
|---|---|
| Base class | `TimedAction` |
| Annotations | `@Action("name")` on each handler, taking one input or none and returning a `TimedActionEffect` |
| In a handler | `context()` (`timerName()`, `previousAttempts()`, `dueTime()`, `clock()`, `timers()`, `componentClient()`, `services()`, `secrets()`, `storage()`) |
| Effects | `effects().done()`, `error(message)`, `error(problem)` |
| Scheduling | `timers().schedule(name, delay, Action::ref, input)`, `schedule(name, delay, Action::ref)`, `scheduleRecurring(name, delay, period, Action::ref[, input])`, `cancel(name)`, `exists(name)` |

```java
/** A timed action that records on the conformance entity it is given. */
@ComponentId("reminder")
public class Reminder extends TimedAction {

  @Action("remind")
  public TimedActionEffect remind(String id) {
    record(id, "reminded");
    return effects().done();
  }

  /** Records the due time it was run for, as the runtime told it. */
  @Action("tick")
  public TimedActionEffect tick(String id) {
    record(id, "due:" + context().dueTime().toEpochMilli());
    return effects().done();
  }

  private void record(String id, String what) {
    context()
        .componentClient()
        .forEventSourcedEntity(id)
        .call(ConformanceEntity::record)
        .invoke(what);
  }
}
```

`timers()` is on an endpoint, a timed action's context and the running service, and is refused in a service
that registers no timed action. Timers are kept in the database, so they survive a restart; a name is one
timer, and scheduling it again replaces what it would call and when. A timer is delivered at least once,
and `error(...)` or an exception has it retried; `dueTime()` is the same for every attempt at one due time.
See [Timers](../build/timers.md).

## Agent

Agents, autonomous agents, judgments and blueprints are in `ankka-java-agent`, package
`com.thinkmorestupidless.ankka.agent.javasdk`. A service that declares any of them gives the service the
agents module, `Agents.module()`; one that registers an agent without it refuses to start, naming the
module.

| Part | API |
|---|---|
| Base class | `Agent` |
| Annotations | `@ComponentId`; `@Command` on a handler answering `AgentEffect<R>`, `@Stream` on one answering `StreamEffect` |
| May override | `settings()`: `AgentSettings.defaults().role(r).maxToolCallSteps(n).mcpServer(s).resultGuardrail(g)` |
| In a handler | `sessionId()`, `componentClient()`, `services()`, `secrets()`, `storage()` |
| Effects | `effects().systemMessage(t)`, `.userMessage(t)`, `.withContext(t)`, `.model(p)`, `.memory(m)`, `.tools(t...)`, `.guardrails(g...)`, `.maxToolCallSteps(n)`, then `.thenReply()`, `.thenReply(Type.class)`, `.thenStream()`; `effects().error(message)`, `error(problem)`, `effects().judgment()` |
| Tools | an object, or a class with a constructor taking nothing, whose methods carry `@Tool(value, description)`, optionally `requiresApproval = true` and `approvalWithin = "PT10M"`; each parameter is an argument, described by `@Describe`, and a lone record parameter's components are the arguments; `FunctionTool.of(name, description, run)` for a tool built in code |
| MCP servers | `McpServer.named(n)`, `McpServer.at(n, url)`, `McpServer.service(n, service, path)`, then `.header(name, "ANKKA_MCP_…")`, `.requiresApproval()`, `.requiresApproval(within)` |
| Guardrails | `Guardrail.maxInputLength(n)`, `Guardrail.forbidding(name, regex)`, `Guardrail.judged(name)`, or implement `checkInput`, `checkOutput` or `checkResult`, each answering the reason for a refusal or nothing |
| Memory | `Memory.session()` (the default), `Memory.none()`, then `.readLast(n)`, `.readOnly()`, `.writeOnly()` |
| Module | `Agents.module().withModelProvider(p[, timeout])`, `.withCompaction(maxHistoryBytes, keepRecentMessages[, summariser])`, given to `withModule` |
| Calling | `AgentClients.forAgent(componentClient, sessionId).call(Agent::handler).invoke(input)`; `.stream(Agent::handler, input)`, a `Flow.Publisher<String>`; `.ask(Agent::handler).invoke(input)` answers `AgentOutcome.Answered(value)` or `AgentOutcome.AwaitingApproval(requests)`; `.decide(Agent::handler, Decision.approved(id, by))` or `Decision.refused(id, by, note)`; `.decideStream(...)` for a turn that began as a stream; `.approvals()`; `call` throws `ApprovalAwaited` when the turn waits for a person |

The cart's helper reads a cart through a tool and refunds one only once a person approves:

```java
/** An agent whose tool reads a cart, one that refunds only once approved, and a guarded handler. */
@ComponentId("cart-helper")
public class CartHelper extends Agent {

  /** What the refund tool refunded. */
  public static final List<String> REFUNDED = new CopyOnWriteArrayList<>();

  public record CartQuery(@Describe("the cart to read") String cartId) {}

  public record Summary(String cartId, int items) {}

  public static final class Tools {
    private final ComponentClient client;

    Tools(ComponentClient client) {
      this.client = client;
    }

    @Tool(value = "cart_items", description = "How many items a cart holds")
    public int items(CartQuery query) {
      return client
          .forEventSourcedEntity(query.cartId())
          .call(ShoppingCart::getCart)
          .invoke()
          .items()
          .size();
    }

    @Tool(value = "refund", description = "Refunds a cart", requiresApproval = true)
    public String refund(CartQuery query) {
      REFUNDED.add(query.cartId());
      return "refunded " + query.cartId();
    }
  }

  @Command("ask")
  public AgentEffect<String> ask(String question) {
    return effects()
        .systemMessage("You help with carts.")
        .userMessage(question)
        .tools(new Tools(componentClient()))
        .thenReply();
  }

  @Command("summarise")
  public AgentEffect<Summary> summarise(String cartId) {
    return effects().userMessage("Summarise cart " + cartId).thenReply(Summary.class);
  }

  @Command("short")
  public AgentEffect<String> shortQuestion(String question) {
    return effects()
        .userMessage(question)
        .guardrails(Guardrail.maxInputLength(10))
        .thenReply();
  }
}
```

A tool runs after the handler has returned, so a tool that calls other components is given the client
when it is built, as `Tools` is here. The service gives the module its default model; an effect that
names a model with `.model(p)` is answered by that one instead:

```java
/** The cart, the agent that helps with it and the one that answers questions about it. */
public static ServiceBuilder service(ModelProvider model) {
  return Ankka.service("shop")
      .withModule(Agents.module().withModelProvider(model))
      .register(ShoppingCart.class)
      .register(CartHelper.class)
      .register(CartAnswerer.class);
}
```

A turn is called by the agent's session id and the handler's method reference:

```java
/** One turn of the session {@code session}: the model's answer, its tools run on the way. */
public static String ask(ComponentClient client, String session, String question) {
  return AgentClients.forAgent(client, session).call(CartHelper::ask).invoke(question);
}
```

`AgentTestKit` runs one turn with no runtime against a scripted model, the tool's calls answered by a
`TestClient`:

```java
var model =
    new TestModelProvider()
        .expectToolCall("cart_items", "{\"cartId\":\"c-1\"}")
        .expectText("Cart c-1 holds 2 lines");
var cart =
    TestClient.of(ShoppingCart.class)
        .stub(
            ShoppingCart::getCart,
            () ->
                new CartState(
                    "c-1", List.of(new LineItem("p", "Pen", 2), new LineItem("q", "Pad", 1)), false));
var kit = AgentTestKit.of(CartHelper.class, model, "s-1", cart);

var result = kit.call(CartHelper::ask, "How full is c-1?");
// result.reply() is "Cart c-1 holds 2 lines"; model.lastRequest() holds what the tool answered
```

`TestModelProvider` scripts the model: `expectText(t)`, `expectToolCall(tool, argumentsJson[, callId])`,
`expectRefusal(reason)`, `whenUserAsks(substring, text)`, and for an autonomous agent
`expectCompleteTask(resultJson)`, `expectCompleteTaskText(t)` and `expectFailTask(reason)`; it records
`requests()`, `lastRequest()` and `callCount()`, and a script that runs out fails the turn, naming the
script. An `AgentResult` has `reply()`, `isError()`, `error()`, `problem()`, `isAwaiting()`, `awaiting()`
and `messages()`, the session's length after the turn. See [Agents](../build/agents.md).

## Autonomous agent

| Part | API |
|---|---|
| Task type | `Task.named(wireName).describedAs(d)`, a text result; `.resultConformsTo(Record.class)`; `.rule(name, result -> RuleResult.accepted())` or `RuleResult.rejected(reason)` |
| Base class | `AutonomousAgent`, with `@ComponentId`; define `definition()`, override `tools()` (a list of tool objects) |
| Definition | `AutonomousDefinition.describedAs(d).instructions(t).guardrails(g...).model(p).mcpServers(s...).resultGuardrails(g...).accepts(type[, maxIterationsPerTask])` |
| In a tool | `componentClient()`, `instanceId()`, `services()`, `secrets()`, `storage()`, `AutonomousAgent.currentTask()` |
| Tasks | `AgentClients.tasks(client).create(type, instructions).withId(id).attach(name, contentType, content).attachReference(name, contentType, uri).dependsOn(ids...).create()` |
| A task | `AgentClients.forTask(client, id).get()`, `.get(type)`, `.await(type, timeout)`, `.cancel(reason)`; a `TaskSnapshot` has `ended()`, `result()`, `reason()`, `instanceId()` and `json()`, the whole record |
| An instance | `AgentClients.forAutonomousAgent(client, Agent.class, instanceId).assign(ids...)`, answering an `AssignResult`; `.suspend()`, `.resume()`, `.terminate()`, `.state()`, `.notifications()`, `.decide(decision)` |
| One task | `AgentClients.runSingleTask(client, Agent.class, type, instructions)`, answering the task's id |

A task type's result is a record, whose components are the schema the model is shown:

```java
/** The kinds of work the cart answerer accepts. */
public final class CartTasks {

  private CartTasks() {}

  /** An answer, and what the agent looked at to reach it. */
  public record Answer(String answer, List<String> sources) {}

  /**
   * {@code "answer"} is the wire name, written into every task of this type: renaming the field
   * changes nothing stored.
   */
  public static final TaskType<Answer> ANSWER =
      Task.named("answer")
          .describedAs("Answer a question about a shopping cart, citing what you looked at")
          .resultConformsTo(Answer.class)
          .rule(
              "cites-sources",
              answer ->
                  answer.sources().isEmpty()
                      ? RuleResult.rejected("say which tools you used in sources")
                      : RuleResult.accepted());
}
```

```java
/**
 * Answers questions about carts, on its own: it is handed a task and works it, iteration by
 * iteration, until it completes it with an {@code Answer} that cites its sources.
 *
 * <p>Its tools read the cart entity through the component client. A tool may run more than once
 * for one request of the model — after a crash the last recorded request's tools run again — and
 * these only read, so that costs nothing.
 */
@ComponentId("cart-answerer")
public class CartAnswerer extends AutonomousAgent {

  public static final class CartTools {
    private final ComponentClient client;

    CartTools(ComponentClient client) {
      this.client = client;
    }

    private CartState cart(String cartId) {
      return client.forEventSourcedEntity(cartId).call(ShoppingCart::getCart).invoke();
    }

    @Tool(value = "cart_contents", description = "Lists what is in a cart.")
    public String contents(@Describe("The id of the cart.") String cartId) {
      var items = cart(cartId).items();
      if (items.isEmpty()) return "cart " + cartId + " is empty";
      return items.stream()
          .map(i -> i.quantity() + " x " + i.name())
          .collect(Collectors.joining(", "));
    }

    @Tool(value = "cart_total", description = "Counts the items in a cart.")
    public String total(@Describe("The id of the cart.") String cartId) {
      int total = cart(cartId).items().stream().mapToInt(LineItem::quantity).sum();
      return "cart " + cartId + " holds " + total + " items";
    }
  }

  @Override
  protected AutonomousDefinition definition() {
    return AutonomousDefinition.describedAs("Answers questions about shopping carts")
        .instructions("Look carts up rather than guessing. Be brief.")
        .guardrails(Guardrail.maxInputLength(2000))
        .accepts(CartTasks.ANSWER, 5);
  }

  @Override
  protected List<Object> tools() {
    return List.of(new CartTools(componentClient()));
  }
}
```

The agents module brings the platform's own components an autonomous agent needs, so registering the
agent is all a service does. Running a task answers its id at once; anyone holding the id reads its record:

```java
/** Starts the work and answers at once, with the task's id. */
public static String startAnswer(ComponentClient client, String question) {
  return AgentClients.runSingleTask(client, CartAnswerer.class, CartTasks.ANSWER, question);
}

/** The task's record once it has ended, its result read as an {@code Answer}. */
public static TaskSnapshot<CartTasks.Answer> answer(ComponentClient client, String taskId) {
  return AgentClients.forTask(client, taskId).await(CartTasks.ANSWER, Duration.ofMinutes(1));
}
```

In the whole-service kit the scripted model is the module's default, and a task is worked to its end:

```java
var model =
    new TestModelProvider()
        .expectToolCall("cart_total", "{\"cartId\":\"c-1\"}")
        .expectCompleteTask(
            "{\"answer\":\"Cart c-1 holds 3 items.\",\"sources\":[\"cart_total\"]}");
try (var kit = AnkkaTestKit.start(service(model))) {
  var client = kit.componentClient();
  client.forEventSourcedEntity("c-1").call(ShoppingCart::addItem).invoke(new LineItem("p", "Pen", 3));

  var taskId = startAnswer(client, "How many items are in cart c-1?");
  return answer(client, taskId).result().orElseThrow();
}
```

See [Autonomous agents](../build/autonomous-agents.md).

## Judgments

| Part | API |
|---|---|
| Questions | `Question.choice(id, instructions, ChoiceOption.of(value, key, description)...)`, `Question.score(id, instructions, levels...)`, `Question.yesNo(id, instructions).describing(yes, no)` |
| Answers | `judgment.answer(question)`: a `ChoiceAnswer(choice, probabilities, confidence)`, a `ScoreAnswer(score, probabilities, confidence)` with `level()`, or a `YesNoAnswer(probability)`; `judgment.model()` |
| Effect | `effects().judgment().state(text)` or `.state(value)`, `.question(q...)`, `.provider(p)`, then `.thenReply()` or `.thenReply(judgment -> value)` |
| Module | `Agents.module().withJudgments(provider[, timeout])`, five seconds by default |
| Providers | `JudgmentProviders.jev()`, `JudgmentProviders.jev(model)`, reading `TYPESAFE_API_KEY` and `TYPESAFE_BASE_URL` |
| Guardrails | `Guardrail.judged(name).onInput(rules...).onOutput(rules...).onResult(rules...).provider(p)`; rules `Refusal.ifYes(q, atLeast)`, `Refusal.ifChosen(q, minConfidence, options...)`, `Refusal.ifScore(q, atLeast)`, `Refusal.when(q, answer -> refuse)` |

Questions are values with wire ids, declared once and asked from any handler:

```java
public static final ChoiceQuestion<Tone> TONE =
    Question.choice(
        "tone",
        "The tone of the text",
        ChoiceOption.of(Tone.POLITE, "polite", "Courteous"),
        ChoiceOption.of(Tone.RUDE, "rude", "Discourteous"));

public static final ScoreQuestion QUALITY =
    Question.score("quality", "How well written the text is", "poor", "fair", "good");

public static final YesNoQuestion ABUSIVE =
    Question.yesNo("abusive", "Is the text abusive?").describing("it is", "it is not");
```

```java
@Command("verdict")
public AgentEffect<Verdict> verdict(String text) {
  return effects()
      .judgment()
      .state(text)
      .question(TONE, QUALITY, ABUSIVE)
      .thenReply(
          j ->
              new Verdict(
                  j.answer(TONE).choice().name(),
                  j.answer(QUALITY).level(),
                  j.answer(ABUSIVE).probability() >= 0.5,
                  j.model()));
}

@Command("guarded")
public AgentEffect<String> guarded(String text) {
  return effects()
      .userMessage(text)
      .guardrails(Guardrail.judged("abuse").onInput(Refusal.ifYes(ABUSIVE, 0.5)))
      .thenReply();
}
```

See [Judgments](../build/judgments.md).

## Blueprints

| Part | API |
|---|---|
| Blueprint | `Blueprint.named(n).input(shape).worker(w).step(s).schedule(schedule).runBudget(modelCalls).timeLimit(d)`; `Blueprint.fromJson(json)`, `Blueprint.fromResource(path)`; `.json()`, `.digest()` |
| Shapes | `Shape.string()`, `number()`, `integer()`, `bool()`, `any()`, `arr(items)`, `obj(fields)`, `enumOf(values...)`, `fromJson(schema)` |
| Workers | `Worker.named(n).instructions(t).model(name).tools(names...).guardrails(names...).budget(modelCalls)` |
| Steps | `Step.named(n)`, then one of `.ask(worker)`, `.work(worker)`, `.judge(questions...)`, `.call(handler)`, `.forEach(worker, over)`, `.gather(workers...)`, `.gather(worker, times)`, `.critique(drafter, verdict, rounds)`; with `.reads(names...)`, `.result(shape)`, `.each(read[, limit, keepGoing])`, `.overWorkers(workers...)`, `.times(n)`, `.until(verdict, rounds)` |
| Schedules | `Schedule.weekly(day, time, zone)`, `Schedule.everyHours(n, zone)`, `Schedule.everyDays(n, zone)`, then `.perMissedPeriod()` |
| Registry | `BlueprintRegistry.empty().tools(...).mcpServers(...).model(name, p).guardrail(name, g).questions(q...).handlers(BlueprintHandler.of(name, (run, inputJson) -> resultJson)...).carrying(blueprints...)`, given as `Agents.module().withBlueprints(context -> registry)` |
| Registering | `AgentClients.blueprints(client).register(blueprint)` or `.register(json)`, `.versions(name)`, `.current(name)` |
| Runs | `AgentClients.runs(client).start(blueprint, inputJson, runId)`, `.get(runId)`, `.await(runId, timeout)`, `.cancel(runId)`; a `RunSnapshot` has `ended()` and `stepNamed(name)` |

A blueprint and the registry that carries it, a worker listing titles and a handler keeping them:

```java
public static final Blueprint KEEPER =
    Blueprint.named("keeper")
        .input(Shape.obj(Map.of("topic", Shape.string())))
        .worker(Worker.named("lister").instructions("List titles on the topic.").budget(2))
        .step(Step.named("titles").ask("lister").reads("input").result(Shape.arr(Shape.string())))
        .step(Step.named("keep").call("keep").reads("titles").result(Shape.integer()));

public static BlueprintRegistry registry(BlueprintContext context) {
  return BlueprintRegistry.empty()
      .handlers(
          BlueprintHandler.of(
              "keep",
              (run, input) -> {
                KEPT.add(run.blueprint() + "/" + run.step() + ":" + input);
                return "2";
              }))
      .carrying(KEEPER);
}
```

The registry is built once when the service starts, from a `BlueprintContext` holding the clients a
handler needs; `Agents.module().withBlueprints(Keeper::registry)` gives it to the service. A run is started
by the blueprint's name with its input as JSON:

```java
public static RunsRef.RunSnapshot keep(ComponentClient client, String runId, String topic) {
  var runs = AgentClients.runs(client);
  runs.start("keeper", "{\"topic\":\"" + topic + "\"}", runId);
  return runs.await(runId, Duration.ofMinutes(1));
}
```

See [Blueprints](../build/blueprints.md).

## Model providers

| Built with | What it is |
|---|---|
| `ModelProviders.anthropic(model)` | Anthropic's API, with `ANTHROPIC_API_KEY`; named `anthropic` |
| `ModelProviders.openAi(model)` | OpenAI's API, with `OPENAI_API_KEY`; named `openai` |
| `ModelProviders.preset(name, preset, model)` | a preset that needs no key, such as `ollama` or `lmstudio` |
| `ModelProviders.preset(name, preset, model, key)` | any preset, with its key given |
| `ModelProviders.at(name, protocol, url, model, key)` | `anthropic` or `openai` protocol at any address, the key an `Optional` |
| `ModelProviders.installation(model)` | a model the installation's model server serves, reached with the service's certificate |

A provider built from the environment whose key is not set still exists, under its name, and every call
to it fails naming the variable. Each refuses what it was given with an `IllegalArgumentException` naming
the problem, under the same rules a Scala service and the sidecar apply. See
[Model providers](model-providers.md).

## HTTP endpoint

| Part | API |
|---|---|
| Base class | `HttpEndpoint`, registered with `withEndpoint(Endpoint.class)` |
| Must define | `acl()` |
| Routes | `@Get(template)`, `@Post`, `@Put`, `@Delete`, `@Patch`; `@Sse(template)` returning a `Flow.Publisher<SseEvent>` or a `Stream<SseEvent>`; `@Socket(template)` taking a `WebSocket`; `@StreamBody(template)`, whose body is read as a stream |
| Parameters | a parameter named as a template segment, or `@Path(name)`; `@QueryParam(name)`, `@Header(name)`, `@Body` (at most one) |
| Answers | a value, `200` with it; `Done`, `204`; a `Response` (`ok`, `created`, `noContent`, `status`, `html`, `bytes`, `redirect`, `problem`, then `.withStatus(n)`, `.header(name, value)`) |
| Errors | `throw new ProblemException(Problem.notFound(...))`; a `CommandException` from a call answers its problem's status |
| ACL | `Acl.allowAll()`, `denyAll()`, `allowIf(request -> …)`, `authenticate(request -> AuthDecision.allow(principal))`, `allowCallers("orders")`, `allowCallers(Callers.internet(), Callers.service("orders"), …)`, `anyOf(...)`, `clientKeys()`, `oidc()` |
| In a handler | `request()` (`method()`, `path()`, `query(name)`, `queryAll(name)`, `requiredQuery(name)`, `flag(name)`, `header(name)`, `headers()`), `caller()`, `principal()`, `componentClient()`, `viewClient()`, `services()`, `secrets()`, `storage()`, `timers()`, `clientKeys()` |

`acl()` is abstract, so an endpoint without one does not compile. One instance serves every request; the
request is readable on the handler's own thread only, so read what you need before handing work to another.
A refusal from an entity passes through the endpoint to its caller with its status, with no handling of its
own. See [HTTP endpoints](../build/http-endpoints.md).

An MCP endpoint, `McpEndpoint`, declares tools in its constructor — a tool that is an entity's handler named
by reference, or one with a handler of its own — and is registered with `withMcpEndpoint`:

```java
/**
 * The cart as an MCP server at {@code /mcp}: a customer's assistant reads a cart, adds to it and
 * checks it out through these tools rather than the cart's routes. Two tools are the entity's own
 * handlers, declared by reference; one has a handler of its own, because its arguments are not the
 * command's input.
 */
public class CartMcpEndpoint extends McpEndpoint {

  public record AddItemArgs(
      @Describe("The cart's id") String cartId,
      @Describe("The product's id") String productId,
      @Describe("How many; greater than zero") int quantity) {}

  public CartMcpEndpoint() {
    tool("get_cart")
        .describedAs("Reads one cart: its lines, and whether it is checked out")
        .query(ShoppingCart::getCart, "cartId");

    tool("add_item")
        .describedAs("Adds a quantity of a product to a cart")
        .arguments(AddItemArgs.class)
        .handle(
            args ->
                componentClient()
                    .forEventSourcedEntity(args.cartId())
                    .call(ShoppingCart::addItem)
                    .invoke(new LineItem(args.productId(), args.productId(), args.quantity())));

    tool("checkout")
        .describedAs("Checks a cart out, answering what it held")
        .command(ShoppingCart::checkout, "cartId");
  }

  // A request carrying one of the cart's client keys, as the key's subject (CartKeysEndpoint).
  @Override
  protected Acl acl() {
    return Acl.clientKeys();
  }
}
```

A gRPC endpoint, `GrpcEndpoint`, declares its methods in its constructor against the descriptors grpc-java's
`protoc` plugin generates, and is registered with `withGrpcEndpoint`. See
[MCP endpoints](../build/mcp-endpoints.md) and [gRPC endpoints](../build/grpc-endpoints.md).

## Calling components

| Call | Does |
|---|---|
| `componentClient().forEventSourcedEntity(id)`, `forKeyValueEntity(id)`, `forWorkflow(id)` | one instance to call |
| `.call(Component::handler).invoke(input)`, `.invoke()` | calls a handler and waits for its reply |
| `.invokeAsync(input)` | the same, returning a `CompletionStage` at once |
| `.withTimeout(d)`, `.withMetadata(key, value)` | the same call, waiting at most `d`, or carrying a metadata entry |
| `forWorkflow(id).awaitEnd(Workflow.class, timeout)`, `awaitEndAsync`, `awaitEndEvents`, `callThenAwaitEnd(Workflow::ref, timeout)`, `lifecycle(Workflow.class)` | waits for a workflow's end and answers its final state; a failed or deleted workflow is a `CommandException` with `500` and a `workflowFailure()`, one still running when the time is up `504` |
| `viewClient().forView(View.class)` | a view's rows, as in [View](#view) |

`invoke` parks the calling virtual thread, so a blocking call is the ordinary form: endpoints, steps,
consumers and timed actions all run on virtual threads. A refusal is a `CommandException` whose `problem()`
is the refusal's. An entity's handler has a component client in `context()`, but no service client, secret
store or storage: a blocking call on an entity's single-writer path holds every other command to it. See
[Calling components](../build/component-client.md).

## Configuration

A Java service reads the same `ANKKA_*` variables a Scala service reads ([Runtime configuration](configuration.md)),
and a variable its descriptor's `env` sets reaches the JVM's environment, read with `System.getenv`.
`withHttp(host, port)` and `withGrpc(host, port)` on the builder replace where the configuration says to
serve.

## Secret store

The service's secret store, for a value it must keep and never record ([Secrets a service keeps](../build/secrets.md)):
`secrets().put(name, value)`, `get(name)` (an `Optional<String>`) and `delete(name)`, on an endpoint, a
consumer, a workflow, a timed action's context and the running service. An entity has none. Where the
service has no store, every call is a `CommandException` with `503`.

## Calling other services

A handler calls a route of another service by that service's name, and the runtime makes the call as this
service, so the other service's access rules can admit it by name
([Calling other services](../build/calling-services.md)). `services().of(name)` is a service of the same
project and `of(project, name)` one of another that has granted this one; an endpoint, a workflow, a
consumer and a timed action have `services()`, an entity does not.

```java
var client = services().of(service);
try {
  if (request().query("mode").filter("typed"::equals).isPresent())
    return new ServiceCallRecord("response", 200, "", client.getText(path, headers), "", "");
  var answer =
      client.request(
          method,
          path,
          body.isEmpty() ? null : body.getBytes(StandardCharsets.UTF_8),
          body.isEmpty() ? null : request().header("Content-Type").orElse(null),
          headers);
  var named =
      answer.headers().stream()
          .filter(h -> h.getKey().equalsIgnoreCase("x-answer"))
          .map(Map.Entry::getValue)
          .findFirst()
          .orElse("");
  return new ServiceCallRecord(
      "response", answer.status(), answer.contentType(), answer.text(), named, "");
} catch (ServiceCallFailed e) {
  return new ServiceCallRecord("failed", e.status(), "", e.body(), "", "");
} catch (ServiceUnresolvable e) {
  return ServiceCallRecord.raised("unresolvable", e.getMessage());
} catch (ServiceIdentityMismatch e) {
  return ServiceCallRecord.raised("mismatch", e.getMessage());
} catch (ServiceUnanswered e) {
  return ServiceCallRecord.raised("unanswered", e.getMessage());
} catch (CommandException e) {
  return ServiceCallRecord.raised("refused", e.problem().detail());
}
```

A `ServiceClient` has `get(path, Reply.class)`, `post` and `put`, which write and read bodies with the record
codec, `getText(path)`, `delete(path)`, and the raw `request(method, path, body, contentType)`, whose body
and content type may be `null` for none and which answers a `ServiceResponse` — status, content type, body
and headers — for every status. A call fails with:

| Exception | When | Was anything sent |
|---|---|---|
| `ServiceUnresolvable` | no address is known for the name | no |
| `ServiceIdentityMismatch` | what answered is not the service asked for | no |
| `ServiceUnanswered` | the connection failed, or no answer came in time | perhaps |
| `ServiceCallFailed` | the service answered outside 2xx, to a typed call | yes, and it answered |
| `CommandException` | the runtime refused the request | no |

The first three extend `ServiceUnreachable`. In a test, `Settings.withScriptedService(name)` plays another
service.

## Running a service

```java
public static ServiceBuilder service() {
  return Ankka.service("shopping-cart")
      .register(ShoppingCart.class)
      .register(CartRows.class)
      .register(CheckoutNotifier.class)
      .register(CheckoutsSeen.class)
      .withEndpoint(CartEndpoint.class)
      .withEndpoint(CheckoutsSeenEndpoint.class)
      .withEndpoint(CartKeysEndpoint.class)
      .withMcpEndpoint(CartMcpEndpoint.class);
}
```

`Ankka.service(name)` begins a definition; `register`, `withEndpoint`, `withMcpEndpoint`,
`withGrpcEndpoint`, `withGrpcClients` and `withSerializer` add to it; nothing is read or started until
`start()`, which reads every class, throws `ServiceProblems` for any declaration problem, and answers an
`AnkkaService`. That has `awaitReady()`, `whenTerminated()`, `terminate()`, `close()`, `httpAddress()` and
the clients — `componentClient()`, `viewClient()`, `timers()`, `secrets()`, `storage()`. A `main` that
starts it and joins `whenTerminated()` is the whole of a deployed service's entry point; the template builds
its image with Jib.

## Testing

| Kit | Drives |
|---|---|
| `EventSourcedTestKit.of(Entity.class[, id])` | One entity, no runtime: `call(Entity::handler[, input])` answers a `Result` with `reply()`, `isError()`, `error()`, `problem()`, `events()`, `state()`; `state()`, `events()`, `deleted()`, `restart()`; `withEvents(...)`, `home(...)` and `follower(...)` for a recovered or replicated copy |
| `KeyValueEntityTestKit.of(Entity.class[, id])` | One key value entity |
| `WorkflowTestKit.of(Workflow.class[, id[, client]])` | One workflow: each command runs its steps until the workflow pauses, ends or fails; `timeOut()` fires a pause's timeout; `StepResult` has `steps()`, `lifecycle()`, `pausedFor()`, `onTimeout()` |
| `ViewTestKit.of(View.class)` | A view: `change(subject, value)`, `deleted(subject)`, `row(subject)`, `rows()`; declared queries are checked as at start |
| `KeyedViewTestKit.of(View.class)` | A keyed view: `change(Source.class, subject, value)`, `change(topic, subject, value)`, `deleted(...)`, and `answering(query, …)` for each declared query a handler asks, since nothing runs SQL |
| `ConsumerTestKit.of(Consumer.class[, client])` | A consumer: `onMessage(...)`, `onDelete(subject)`, each answering what it would publish (`payloads()`, `messages()`, `recordKeys()`); `ConsumerTestKit.graph(...)` for a graph consumer, answering `GraphDelta`s |
| `AgentTestKit.of(Agent.class, model[, sessionId, client])` | One turn of an agent, no runtime, against a `TestModelProvider`: `call(Agent::handler, input)` answers an `AgentResult`; the next turn reads what the last recorded |
| `TestClient.of(Component.class...)` | The component client a kit hands a component: `.stub(Entity::handler, input -> reply)`, `.refuse(Entity::handler, problem)`; a call nobody stubbed fails, naming it |
| `AnkkaTestKit.start(Main.service()[, settings])` | The whole service against a throwaway Postgres in Docker: `http()`, `httpClient()`, `componentClient()`, `viewClient()`, `timers()`, `restartService()`, `published(topic)`, `timerRuns()`, `eventually(within, assertion)`, `socket(path)`, `asCaller(caller)`; `installations(names...)` for a replicated group |

Every unit kit crosses the component's own serializers and reduces effects with the function the runtime
uses, so a kit and a running service cannot disagree about what an effect means. The whole-service kit
holds a broker in memory, so a consumer that publishes runs without one. See [Testing](../build/testing.md).

## Developing the SDK

From the root of the ankka repository:

```bash
sbt sdkJava/test testkitJava/test                     # the SDK and its kits
sbt shoppingCartJava/test                             # the Scala cart's features against the Java cart
sbt -Dankka.conformance.reference=java 'sidecar/testOnly *.ConformanceSuite'   # the conformance suite, against the Java reference
```

The Java reference service the conformance suite drives is in `modules/sdk-java/src/test/java`, package
`…javasdk.reference`. See [Adding a language SDK](../contributing/language-sdks.md).
