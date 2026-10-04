# Contract: the Scala API

In `sdk`, beside `ChangeSource`. What each declaration causes is
[topic-sources.md](topic-sources.md).

## A start position

```scala
/** Where a topic source starts the first time its group reads the topic. */
enum StartFrom:
  /** The oldest message the broker still holds. */
  case Earliest
  /** After the newest: only what is published from now on. */
  case Latest
  /** The first message published at or after this time. */
  case At(time: Instant)
```

It is an argument of the topic source, so it cannot be written on a source that reads an entity:

```scala
object ChangeSource:
  final case class Topic[Src](
      topic: String,
      decoder: Serializer[Src],
      startFrom: Option[StartFrom] = None
  ) extends ChangeSource[Src]

  def fromTopic[Src](name: String, decoder: Serializer[Src]): ChangeSource[Src]
  def fromTopic[Src](name: String, decoder: Serializer[Src], startFrom: StartFrom): ChangeSource[Src]
```

`ChangeSource.fromTopic(name, decoder)` keeps compiling. For a view it means `Earliest`. For a
consumer it is refused when the service starts, naming the consumer (T1): the two-argument form is
kept for views, and so that a consumer's missing start position is a sentence telling its author
what to write and not an overload error.

`describe` includes the start position, so it appears wherever a source is already described.

## A version

On the companion, beside `parallelism`, for a view and a consumer alike:

```scala
abstract class Companion[...](...):
  /** Raise it to have this view rebuilt from its topic. Only for a topic source. */
  def version: Option[Int] = None
```

carried to `ViewDescriptor.version` and `ConsumerDescriptor.version`. `None` is version 1.
`Some(n)` on a component that reads an entity (T4), and `Some(n)` with `n < 1` (T5), are refused
when the service starts.

It is an `Option` and not an `Int` defaulting to 1 because T4 has to tell a version that was
declared from one that was not: `Some(1)` on an entity-sourced view is refused, and an undeclared
one is not.

## In use

```scala
object OrderSummaries
    extends View.Companion[OrderSummaries, OrderChange, OrderSummary](
      ComponentId("summary"),
      ChangeSource.fromTopic("order-changes", OrderChange.serializer),
      OrderSummary.serializer
    ):
  override def version = Some(2)
  def create(ctx: ViewComponentContext) = new OrderSummaries

object OrderNotifier
    extends Consumer.Companion[OrderNotifier, OrderChange, Nothing](
      ComponentId("notifier"),
      ChangeSource.fromTopic("order-changes", OrderChange.serializer, StartFrom.Latest)
    ):
  def create(ctx: ConsumerContext) = new OrderNotifier
```

## A service's name, for a local run

```hocon
ankka.service.name = "orders"     # or ANKKA_SERVICE_NAME=orders
```

Read once at startup into `ServiceIdentity`. See [group-names.md](group-names.md) for what it
names and for why a deployed service's is not read from here.

## The test kit

```scala
AnkkaTestKit.start(
  descriptors,
  extensions,
  identity = ServiceIdentity.local("orders")      // or ServiceIdentity.deployed("shop", "orders")
)
```

`identity` defaults to none stated, which is today's group names. `ServiceIdentity.deployed` is
`private[ankka]`: outside ankka's own tests the only way to be a deployed service is to be
deployed.

`EventSourcedTestKit`, `KeyValueEntityTestKit` and the consumer and view unit kits are unchanged.
They hand a change to a handler; a group, a start position and a version are properties of a
running service.
