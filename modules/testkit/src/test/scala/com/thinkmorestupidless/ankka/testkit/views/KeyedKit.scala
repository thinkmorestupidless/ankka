package com.thinkmorestupidless.ankka.testkit.views

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}

/**
 * Two entities a keyed view reads, whose every event carries the id of a script saying what the
 * view does with it. A scenario writes the script, records an event naming it, and reads what the
 * view did — so each scenario says exactly which rows one event writes, reads and deletes, and no
 * fixture has to be written for each.
 */
object Scripts:

  enum Op:
    /** Read each row and write it again with the script's note added; a missing row is made. */
    case Touch(keys: Vector[String], customer: Option[String] = None)

    /** Ask the view's own query for the rows holding `customer`, and touch each. */
    case TouchHolding(customer: String)

    /** Delete each row. */
    case Delete(keys: Vector[String])

    /** Write a row the view's serializer refuses. */
    case Unwritable(key: String)

    /** Read the row and write it again with its count one higher. */
    case Count(key: String)

    /** Ask a query of another view through this view's own rows. */
    case AskOther

    /** Ask the view's own query that never ends. */
    case AskForever

    /** Write `rows` rows of 64 KiB each. */
    case Big(rows: Int)

    /** `op` for the first `times` attempts at the event, and nothing after. */
    case ForAttempts(times: Int, op: Op)

  /** What a view does with one event, and how long it takes over it. */
  final case class Script(note: String, ops: Vector[Op], pauseMillis: Long = 0)

  val scripts: ConcurrentHashMap[String, Script] = ConcurrentHashMap()

  /** "begin <view> <script>" and "end <view> <script>", as each view handles each event. */
  val log: ConcurrentLinkedQueue[String] = ConcurrentLinkedQueue()

  /** How many times each view has handled each script, a retried one included: `view:script`. */
  val attempts: ConcurrentHashMap[String, AtomicInteger] = ConcurrentHashMap()

  private val ids = AtomicInteger()

  /** Registers a script and answers the id an event names it by. */
  def add(script: Script): String =
    val id = s"script-${ids.incrementAndGet()}"
    scripts.put(id, script): Unit
    id

  def attemptsOf(id: String, view: String = "shipments"): Int =
    Option(attempts.get(s"$view:$id")).fold(0)(_.get)

  /** Whether `view` has finished handling `id` at least once. */
  def handled(id: String, view: String = "shipments"): Boolean = log.contains(s"end $view $id")

enum Recorded:
  case Ran(script: String)

/** An entity whose events are scripts; `shipment` and `customer` are two of them. */
final class ScriptedEntity extends EventSourcedEntity[Int, Recorded]:
  def emptyState: Int                  = 0
  def applyEvent(event: Recorded): Int = currentState + 1
  def record(script: String): Effect[Done] =
    effects.persist(Recorded.Ran(script)).thenReply(_ => Done)

abstract class ScriptedCompanion(id: String)
    extends EventSourcedEntity.Companion[ScriptedEntity, Int, Recorded](
      componentId = ComponentId(id),
      stateSerializer = Codecs.serializer[Int]("count"),
      eventSerializer = Codecs.serializer[Recorded]("recorded")
    ):
  def create(context: EventSourcedEntityContext) = new ScriptedEntity
  val record                                     = command("record")(_.record)

object Shipment extends ScriptedCompanion("shipment")

/** A key value entity whose state is the last script it was given. */
final class ScriptedState extends KeyValueEntity[String]:
  def emptyState: String                   = ""
  def record(script: String): Effect[Done] = effects.updateState(script).thenReply(_ => Done)

object Account
    extends KeyValueEntity.Companion[ScriptedState, String](
      componentId = ComponentId("account"),
      stateSerializer = Codecs.serializer[String]("script")
    ):
  def create(context: KeyValueEntityContext) = new ScriptedState
  val record                                 = command("record")(_.record)
object Customer extends ScriptedCompanion("customer")
object Supplier extends ScriptedCompanion("supplier")

/** A row of `shipments`: who it is for, what was written to it, and a count. */
final case class ShipmentRow(
    key: String,
    customer: Option[String],
    notes: Vector[String],
    count: Int
)

object ShipmentRow:
  private val plain = Codecs.serializer[ShipmentRow]("shipment-row")

  /** Refuses a row noted `unwritable`: a row the view cannot write. */
  val serializer: Serializer[ShipmentRow] = new Serializer[ShipmentRow]:
    def manifest: String = plain.manifest
    def toBytes(row: ShipmentRow): Array[Byte] =
      if row.notes.contains("unwritable") then
        throw IllegalArgumentException(s"row '${row.key}' cannot be written")
      plain.toBytes(row)
    def fromBytes(bytes: Array[Byte]): ShipmentRow = plain.fromBytes(bytes)

final class ShipmentsView(view: String) extends KeyedView[ShipmentRow]:

  /** Every source's handler: what the event's script says, from the rows as they are. */
  def run(event: Recorded, change: Change): Effect = event match
    case Recorded.Ran(id) =>
      val script = Scripts.scripts.get(id)
      Scripts.attempts.computeIfAbsent(s"$view:$id", _ => AtomicInteger()).incrementAndGet(): Unit
      Scripts.log.add(s"begin $view $id"): Unit
      // Ended however the handler ends, a refusal included: a retry is another handling, not one
      // that overlaps it.
      try
        if script.pauseMillis > 0 then Thread.sleep(script.pauseMillis)
        script.ops
          .map(op => perform(id, script.note, op, change))
          .foldLeft(effects.ignore())(_ ++ _)
      finally Scripts.log.add(s"end $view $id"): Unit

  private def touched(
      key: String,
      current: Option[ShipmentRow],
      customer: Option[String],
      note: String
  ) =
    val row = current.getOrElse(ShipmentRow(key, None, Vector.empty, 0))
    row.copy(customer = customer.orElse(row.customer), notes = row.notes :+ note)

  private def perform(id: String, note: String, op: Scripts.Op, change: Change): Effect = op match
    case Scripts.Op.Touch(keys, customer) =>
      effects.updateRows(keys.map(key => key -> touched(key, change.rows.get(key), customer, note)))
    case Scripts.Op.TouchHolding(customer) =>
      // The rows this event is about, found by asking the view's own query.
      val theirs = change.rows.ask(Shipments.ofCustomer, "customer" -> customer)
      effects.updateRows(theirs.map(row => row.key -> row.copy(notes = row.notes :+ note)))
    case Scripts.Op.Delete(keys) => effects.deleteRows(keys)
    case Scripts.Op.Unwritable(key) =>
      effects.updateRow(key, ShipmentRow(key, None, Vector("unwritable"), 0))
    case Scripts.Op.Count(key) =>
      val row = change.rows.get(key).getOrElse(ShipmentRow(key, None, Vector.empty, 0))
      effects.updateRow(key, row.copy(count = row.count + 1))
    case Scripts.Op.AskOther =>
      change.rows.ask(Nodes.ofKind, "kind" -> "x"): Unit
      effects.ignore()
    case Scripts.Op.AskForever =>
      change.rows.ask(Shipments.forever): Unit
      effects.ignore()
    case Scripts.Op.Big(rows) =>
      val note = "x" * 65536
      effects.updateRows(
        (1 to rows).map(n => s"$id-big-$n" -> ShipmentRow(s"$id-big-$n", None, Vector(note), 0))
      )
    case Scripts.Op.ForAttempts(times, inner) =>
      if Scripts.attemptsOf(id, view) <= times then perform(id, note, inner, change)
      else effects.ignore()

object Shipments
    extends KeyedView.Companion[ShipmentsView, ShipmentRow](
      ComponentId("shipments"),
      ShipmentRow.serializer
    ):
  val shipments = source(ChangeSource.eventsOf(Shipment))(_.run)
  val customers = source(ChangeSource.eventsOf(Customer))(_.run)

  /** The rows held for one customer. */
  val ofCustomer = query("of-customer")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'customer' = :customer ORDER BY row_key"
  )

  /** Never ends: what a handler waiting on its own view's read is bounded by. */
  val forever = query("forever")(s"""
    WITH RECURSIVE counting(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM counting)
    SELECT payload FROM $table WHERE row_key = (SELECT max(n)::text FROM counting)""")

  def create(ctx: ViewComponentContext) = new ShipmentsView(ctx.componentId.toString)

/** A keyed view of a key value entity's state, beside an event sourced entity's events. */
object AccountShipments
    extends KeyedView.Companion[ShipmentsView, ShipmentRow](
      ComponentId("account-shipments"),
      ShipmentRow.serializer
    ):
  val shipments = source(ChangeSource.eventsOf(Shipment))(_.run)
  val accounts = source(ChangeSource.stateOf(Account))((view: ShipmentsView) =>
    (script: String, change) => view.run(Recorded.Ran(script), change)
  )
  def create(ctx: ViewComponentContext) = new ShipmentsView(ctx.componentId.toString)

/** A keyed view of three sources, the third a supplier's. */
object ThreeSourced
    extends KeyedView.Companion[ShipmentsView, ShipmentRow](
      ComponentId("three-sourced"),
      ShipmentRow.serializer
    ):
  val shipments                         = source(ChangeSource.eventsOf(Shipment))(_.run)
  val customers                         = source(ChangeSource.eventsOf(Customer))(_.run)
  val suppliers                         = source(ChangeSource.eventsOf(Supplier))(_.run)
  def create(ctx: ViewComponentContext) = new ShipmentsView(ctx.componentId.toString)

/** A plain view of `customer`, naming no row key: one row per customer, under its id. */
final case class CustomerRow(id: String, events: Int)

final class CustomersView extends View[Recorded, CustomerRow]:
  def onChange(event: Recorded): Effect =
    val events = rowState.fold(0)(_.events)
    effects.updateRow(CustomerRow(updateContext.subject, events + 1))

object Customers
    extends View.Companion[CustomersView, Recorded, CustomerRow](
      ComponentId("customers"),
      ChangeSource.eventsOf(Customer),
      Codecs.serializer[CustomerRow]("customer-row")
    ):
  def create(ctx: ViewComponentContext) = new CustomersView

/** A keyed view reading a topic and an entity: what a service must refuse to start with. */
def ordersOfTopicAndCustomer: KeyedViewDescriptor[ShipmentsView, ShipmentRow] =
  new KeyedView.Companion[ShipmentsView, ShipmentRow](
    ComponentId("orders"),
    ShipmentRow.serializer
  ):
    @annotation.nowarn("msg=unused")
    val orders =
      source(ChangeSource.fromTopic("orders", Codecs.serializer[Recorded]("recorded")))(_.run)
    @annotation.nowarn("msg=unused")
    val customers                         = source(ChangeSource.eventsOf(Customer))(_.run)
    def create(ctx: ViewComponentContext) = new ShipmentsView(ctx.componentId.toString)
  .descriptor
