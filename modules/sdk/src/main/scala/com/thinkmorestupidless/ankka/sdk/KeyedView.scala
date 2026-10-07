package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.effect.*

/**
 * A view of one or more entities, whose handlers name every row they write or delete by key.
 *
 * The second shape of view. A plain [[View]] reads one source and keeps one row per entity of it,
 * under the entity's id. A keyed view reads several entities' events or states, each through a
 * handler of its own, and writes whichever rows a change is about: one event can write several
 * rows, and several sources can write one row. A handler is handed no row; it reads the rows it
 * needs from its own view's table, by key or by a declared query, while it handles the change.
 *
 * A keyed view handles one change at a time, across every source and every instance of the service,
 * so that nothing another change writes falls between what a handler reads and what it writes. That
 * is one writer for the view: use a plain view where one will do.
 *
 * {{{
 * final class Shipments extends KeyedView[ShipmentRow]:
 *   def onCustomer(event: CustomerEvent, change: Change): Effect = event match
 *     case CustomerRenamed(name) =>
 *       val theirs = change.rows.ask(Shipments.ofCustomer, "customer" -> change.subject)
 *       effects.updateRows(theirs.map(row => row.shipmentId -> row.copy(name = Some(name))))
 * }}}
 */
abstract class KeyedView[Row]:

  final type Effect = KeyedViewEffect[Row]
  final type Change = KeyedChange[Row]

  protected final val effects: KeyedViewEffects[Row] = new KeyedViewEffects[Row]()

/** A keyed view's own rows, for the length of one change. It reaches no other view. */
trait ViewRows[Row]:

  /** The row under `key`, or `None`. */
  def get(key: String): Option[Row]

  /** The rows of one of this view's declared queries, at most 1000 of them. */
  def ask(query: DeclaredQuery, values: (String, String)*): Vector[Row]

/** What a keyed view's handler is handed with the change: valid for that change only. */
trait KeyedChange[Row]:

  /** The id of the entity the change came from. */
  def subject: String

  /** The event's sequence number, or the state's revision. */
  def sequenceNumber: Long

  /** The view's own rows. */
  def rows: ViewRows[Row]

/**
 * One source of a keyed view, with what the view does with each of its changes and with its
 * entity's deletion.
 */
final class KeyedSource[V, Row] private[ankka] (
    val source: ChangeSource[?],
    private[ankka] val onChange: (V, Any, KeyedChange[Row]) => KeyedViewEffect[Row],
    private[ankka] val onDelete: (V, KeyedChange[Row]) => KeyedViewEffect[Row]
):

  /** The component the source reads; for a topic, its name. */
  def componentId: ComponentId = source match
    case ChangeSource.EventSourced(id, _)   => id
    case ChangeSource.KeyValue(id, _)       => id
    case ChangeSource.Topic(topic, _, _, _) => ComponentId(topic)

  /** Decodes one change of this source. */
  private[ankka] def decode(bytes: Array[Byte]): Any = source.decoder.fromBytes(bytes)

object KeyedView:

  /**
   * Declares a keyed view to the runtime.
   *
   * {{{
   * object Shipments extends KeyedView.Companion[Shipments, ShipmentRow](
   *   ComponentId("shipments"),
   *   Codecs.serializer[ShipmentRow]("shipment-row")
   * ):
   *   val shipments = source(ChangeSource.eventsOf(ShipmentEntity))(_.onShipment)
   *   val customers = source(ChangeSource.eventsOf(CustomerEntity))(_.onCustomer)
   *   val ofCustomer = query("of-customer")(
   *     s"SELECT payload FROM $table WHERE payload::jsonb->>'customerId' = :customer"
   *   )
   *   def create(ctx: ViewComponentContext) = new Shipments
   * }}}
   */
  abstract class Companion[V <: KeyedView[Row], Row](
      val componentId: ComponentId,
      val rowSerializer: Serializer[Row]
  ):

    def create(ctx: ViewComponentContext): V

    /** The table holding this view's rows, for a declared query's statement to name. */
    final def table: String = ViewDescriptor.tableFor(componentId)

    /**
     * Raise it to have this view emptied and every source read again from its beginning. `None` is
     * version 1.
     */
    def version: Option[Int] = None

    private val sources  = scala.collection.mutable.ArrayBuffer.empty[KeyedSource[V, Row]]
    private val declared = scala.collection.mutable.ArrayBuffer.empty[DeclaredQuery]
    @volatile private var taken: Boolean = false

    private def declaring[A](what: String)(add: => A): A =
      synchronized {
        if taken then
          throw IllegalStateException(
            s"view '$componentId' declares $what after it was registered; declare every source " +
              "and query as a val of the companion"
          )
        add
      }

    /**
     * Declares a source and what the view does with each of its changes. When the source entity is
     * deleted the view does nothing unless `onDelete` names rows.
     */
    protected final def source[Src](
        source: ChangeSource[Src],
        onDelete: V => KeyedChange[Row] => KeyedViewEffect[Row] = (_: V) =>
          (_: KeyedChange[Row]) => KeyedViewEffect.Nothing
    )(onChange: V => (Src, KeyedChange[Row]) => KeyedViewEffect[Row]): KeyedSource[V, Row] =
      declaring(s"the source ${source.describe}") {
        val declared = KeyedSource[V, Row](
          source,
          (view, change, context) => onChange(view)(change.asInstanceOf[Src], context),
          (view, context) => onDelete(view)(context)
        )
        sources += declared
        declared
      }

    /**
     * Declares a query this view can be asked by name, by its own handlers among others, and
     * returns the handle to ask it with (see [[DeclaredQuery]]).
     */
    protected final def query(name: String)(statement: String): DeclaredQuery =
      declaring(s"the query '$name'") {
        val query = DeclaredQuery(componentId, name, statement)
        declared += query
        query
      }

    final def descriptor: KeyedViewDescriptor[V, Row] =
      val (taking, queries) = synchronized {
        taken = true
        (sources.toVector, declared.toVector)
      }
      KeyedViewDescriptor(componentId, taking, rowSerializer, create, version, queries)

/** The registered form of a keyed view. */
final case class KeyedViewDescriptor[V <: KeyedView[Row], Row](
    componentId: ComponentId,
    sources: Vector[KeyedSource[V, Row]],
    rowSerializer: Serializer[Row],
    create: ViewComponentContext => V,
    version: Option[Int] = None,
    queries: Vector[DeclaredQuery] = Vector.empty
) extends ComponentDescriptor:
  val kind: ComponentKind = ComponentKind.View

  /** One handler per source, named for the component it reads. */
  override def declaredHandlers: Vector[DeclaredHandler] =
    sources.map(s => DeclaredHandler(s.componentId.toString, HandlerKind.Update)).sortBy(_.name)

  /** The Postgres table holding this view's rows: the same derivation as a plain view's. */
  def tableName: String = ViewDescriptor.tableFor(componentId)
