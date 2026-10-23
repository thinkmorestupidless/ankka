---
title: Java SDK
description: A compact map of the Java SDK — installing it, the codec, method references and wire names, modules, serializers, and for every component kind its base class, annotations, effects and test kit.
kind: reference
languages: [java]
components: [event-sourced-entity, key-value-entity, view, consumer, workflow, timed-action, http-endpoint]
related: [get-started/first-service-java.md, reference/kotlin-sdk.md, reference/scala-sdk.md, build/serialization.md, reference/limitations.md]
---

# Java SDK

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

<!-- include: modules/sdk-java/src/test/java/com/thinkmorestupidless/ankka/javasdk/reference/Profile.java#key-value-entity -->
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

<!-- include: samples/shopping-cart-java/src/main/java/shoppingcart/javasdk/ShoppingCart.java#entity -->
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

<!-- include: samples/shopping-cart-java/src/main/java/shoppingcart/javasdk/CartRows.java#view -->
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

<!-- include: modules/sdk-java/src/test/java/com/thinkmorestupidless/ankka/javasdk/reference/TreeRows.java#declared-queries -->
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

<!-- include: modules/sdk-java/src/test/java/com/thinkmorestupidless/ankka/javasdk/reference/JoinedRows.java#keyed-view -->
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

<!-- include: modules/sdk-java/src/test/java/com/thinkmorestupidless/ankka/javasdk/reference/CheckoutFanout.java#consumer -->
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

<!-- include: modules/sdk-java/src/test/java/com/thinkmorestupidless/ankka/javasdk/reference/CartGraph.java#graph-consumer -->
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

<!-- include: modules/sdk-java/src/test/java/com/thinkmorestupidless/ankka/javasdk/reference/Checkout.java#workflow -->
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

<!-- include: modules/sdk-java/src/test/java/com/thinkmorestupidless/ankka/javasdk/reference/Reminder.java#timed-action -->
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

<!-- include: samples/shopping-cart-java/src/main/java/shoppingcart/javasdk/CartMcpEndpoint.java#endpoint -->
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

<!-- include: modules/sdk-java/src/test/java/com/thinkmorestupidless/ankka/javasdk/reference/ConformanceEndpoint.java#service-call -->
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

<!-- include: samples/shopping-cart-java/src/main/java/shoppingcart/javasdk/Main.java#service -->
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
