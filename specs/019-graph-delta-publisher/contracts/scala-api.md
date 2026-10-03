# Contract: the Scala surface

Packages are under `com.thinkmorestupidless.ankka`. Signatures are the contract; bodies are not.
The rules behind the graph types are in [graph-builder.md](graph-builder.md).

## Several messages — `core.effect`

```scala
/** One message of several: what to publish, its attributes, and optionally its record key. */
final case class Outgoing[+Out](
    payload: Out,
    metadata: Metadata = Metadata.empty,
    key: Option[String] = None
):
  def withKey(key: String): Outgoing[Out]               // empty: IllegalArgumentException
  def withMetadata(metadata: Metadata): Outgoing[Out]

object ConsumerEffect:
  final case class Produce[Out](payload: Out, metadata: Metadata) extends ConsumerEffect[Out]  // unchanged
  final case class ProduceAll[Out](messages: Seq[Outgoing[Out]]) extends ConsumerEffect[Out]   // new
  case object Done; case object Ignore                                                         // unchanged

final class ConsumerEffects[Out]:
  def produce(payload: Out): ConsumerEffect[Out]                         // unchanged
  def produce(payload: Out, metadata: Metadata): ConsumerEffect[Out]     // unchanged
  def message(payload: Out): Outgoing[Out]                               // new
  def produceAll(messages: Seq[Outgoing[Out]]): ConsumerEffect[Out]      // new; empty behaves as done
  def done(): ConsumerEffect[Out]; def ignore(): ConsumerEffect[Out]     // unchanged
```

```scala
def onMessage(event: ShoppingCartEvent): Effect = event match
  case CheckedOut =>
    effects.produceAll(
      lines.map(line => effects.message(LineShipped(line)).withKey(s"line:${line.productId}"))
    )
  case _ => effects.ignore()
```

`Consumer`, its companion and `ConsumerDescriptor` are unchanged. `onDelete` may return
`produceAll` like any handler.

## Publishing — `runtime`

```scala
trait MessagePublisher:
  def publish(topic: String, payload: Array[Byte], metadata: Metadata): Future[Done]   // unchanged

  /** As above, under `key` when one is given. A publisher that cannot key a message apart from
    * its subject fails rather than publish it under the subject. */
  def publish(topic: String, key: Option[String], payload: Array[Byte], metadata: Metadata): Future[Done] =
    key match
      case None    => publish(topic, payload, metadata)
      case Some(_) => Future.failed(UnsupportedOperationException(…))

object InMemoryPublisher:
  final case class Published(
      topic: String, payload: Array[Byte], metadata: Metadata, key: Option[String] = None
  ):
    def text: String
    def recordKey: Option[String]      // key, else the subject: what a broker is given
```

`KafkaPublisher`, `InMemoryPublisher` and `InMemoryBroker` override the keyed method.
`InMemoryBroker` delivers `IncomingMessage(key = recordKey, …)`. It gains
`failNext(topic: String, after: Int = 0): Unit`, for tests of redelivery: the publication after
`after` successful ones fails once.

## Deltas — `core.graph`

```scala
type Scalar        = String | Boolean | Int | Long | Double
type PropertyValue = Scalar | Seq[Scalar]

/** Always valid: made only by the builder and the reader. */
final case class GraphDelta private[ankka] (
    kind: GraphDelta.Kind,               // Node, Edge, Tombstone
    element: GraphDelta.Element,         // Node, Edge: which id space
    id: String,
    version: Long,
    labels: Vector[String],
    edgeType: Option[String],            // an edge and an edge's tombstone
    from: Option[String],
    to: Option[String],
    properties: Map[String, PropertyValue]   // normalised: integers are Long, a whole float is that Long
):
  def key: String                        // "node:<id>" or "edge:<id>"
  def isTombstone: Boolean

object GraphDelta:
  val SchemaName: String = "ankka.graph-delta.v1"
  val serializer: Serializer[GraphDelta]                       // manifest SchemaName, JSON
  def nodeKey(id: String): String
  def edgeKey(id: String): String
  def read(value: Array[Byte]): Either[String, GraphDelta]
  def read(key: Option[String], value: Array[Byte]): Either[String, GraphDelta]   // also checks the key

/** `why` is the name of the rule broken, as `refused.json` names them. */
final class GraphElementRefused(val why: String, message: String) extends IllegalArgumentException
```

One flat case class rather than a case per kind: a test reads `delta.key`, `delta.version`,
`delta.properties` without matching, and it is the shape the other three SDKs' readers return.
Two deltas are equal when the sink would read them the same.

## Publishing a graph — `sdk.graph`

```scala
/** An element as the author describes it: valid, and not yet at a version. */
final class GraphElement:
  def at(version: Long): GraphElement          // state the version; below 1: IllegalArgumentException
  def kind: String; def id: String; def key: String

final class GraphElements:                     // `graph` inside a GraphConsumer
  def node(id: String, labels: Seq[String] = Nil,
           properties: Map[String, PropertyValue] = Map.empty): GraphElement
  def edge(id: String, `type`: String, from: String, to: String,
           properties: Map[String, PropertyValue] = Map.empty): GraphElement
  def tombstoneNode(id: String): GraphElement
  def tombstoneEdge(id: String, `type`: String, from: String, to: String): GraphElement

sealed trait GraphEffect
final class GraphEffects:                      // `effects` inside a GraphConsumer
  def publish(elements: GraphElement*): GraphEffect         // none at all behaves as done
  def done(): GraphEffect
  def ignore(): GraphEffect

abstract class GraphConsumer[Src]:
  final type Effect = GraphEffect
  protected final val graph: GraphElements
  protected final val effects: GraphEffects
  protected final def messageContext: ChangeContext
  def onMessage(message: Src): Effect
  def onDelete: Effect = effects.ignore()

object GraphConsumer:
  abstract class Companion[C <: GraphConsumer[Src], Src](
      val componentId: ComponentId,
      val source: ChangeSource[Src],
      val topic: String
  ):
    def create(ctx: ConsumerContext): C
    def parallelism: Int = 4
    final lazy val descriptor: ConsumerDescriptor[?, Src, GraphDelta]
```

```scala
final class CartGraph extends GraphConsumer[ShoppingCartEvent]:
  def onMessage(event: ShoppingCartEvent): Effect =
    val id = messageContext.subject
    event match
      case _: ItemAdded | _: ItemRemoved => effects.publish(cart(id, checkedOut = false))
      case CheckedOut =>
        effects.publish(
          cart(id, checkedOut = true),
          graph.node(s"checkout:$id", Seq("Checkout"), Map("cartId" -> id)),
          graph.edge(s"checked-out:$id", "CHECKED_OUT", from = s"cart:$id", to = s"checkout:$id")
        )
      case Discarded => effects.ignore()

  override def onDelete: Effect = effects.publish(graph.tombstoneNode(s"cart:${messageContext.subject}"))

  private def cart(id: String, checkedOut: Boolean) =
    graph.node(s"cart:$id", Seq("Cart"), Map("cartId" -> id, "checkedOut" -> checkedOut))

object CartGraph
    extends GraphConsumer.Companion[CartGraph, ShoppingCartEvent](
      componentId = ComponentId("cart-graph"),
      source = ChangeSource.eventsOf(ShoppingCartEntity),
      topic = "cart-graph"
    ):
  def create(ctx: ConsumerContext) = new CartGraph
```

The descriptor is an ordinary consumer's: kind consumer, `produceTo = Some(topic)`,
`outputSerializer = Some(GraphDelta.serializer)`. It is registered with `.register(CartGraph.descriptor)`
and is refused at startup without a broker. `GraphElements` raise `IllegalArgumentException` naming
the fault. `publish` builds inert data; the adapter between the graph consumer and the descriptor
resolves versions and refuses duplicates once the handler has returned, by raising as the handler
would have, so the change fails before anything is published.

## Testing — `testkit`

```scala
object ConsumerTestKit:
  def of[C <: Consumer[Src, Out], Src, Out](companion: Consumer.Companion[C, Src, Out],
                                            client: ComponentClient = TestTransport.unroutedClient): ConsumerTestKit[Src, Out]
  def graph[C <: GraphConsumer[Src], Src](companion: GraphConsumer.Companion[C, Src],
                                          client: ComponentClient = TestTransport.unroutedClient): GraphConsumerTestKit[Src]

  final case class Produced[Out](payload: Out, key: Option[String], metadata: Metadata, bytes: Array[Byte]):
    def text: String                     // key: the record key a broker is given — the one named, else the subject
  final case class Result[Out](effect: ConsumerEffect[Out], messages: Vector[Produced[Out]]):
    def payloads: Vector[Out]; def keys: Vector[Option[String]]

final class ConsumerTestKit[Src, Out]:
  def onMessage(message: Src, subject: String = "test", sequenceNumber: Long = 1): Result[Out]
  def onDelete(subject: String = "test", sequenceNumber: Long = 1): Result[Out]

final class GraphConsumerTestKit[Src]:
  def onMessage(message: Src, subject: String = "test", sequenceNumber: Long = 1): Vector[GraphDelta]
  def onDelete(subject: String = "test", sequenceNumber: Long = 1): Vector[GraphDelta]
  def records: ConsumerTestKit[Src, GraphDelta]     // the same consumer as the records it publishes
```

Neither starts a runtime. The kit applies the handler's effect with the function the runtime
applies it with (`ProjectionSupport.applyConsumer`), against a publisher that only records, so a
result the runtime would refuse — no topic, an empty key, over the limit — throws here. The graph
kit returns deltas read from the bytes that would be published, under the key they would be
published under, so a test asserts on what a sink would read.

## What changes for an existing service

- A consumer over a key value entity reads the revision from `messageContext.sequenceNumber`,
  where it read 0.
- A view and a consumer over a key value entity are told of its deletion.
- An implementation of `MessagePublisher` compiles unchanged and fails a keyed message.
