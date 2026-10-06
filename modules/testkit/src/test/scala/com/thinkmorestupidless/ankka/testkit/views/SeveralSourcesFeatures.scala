package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, EntityId}
import com.thinkmorestupidless.ankka.runtime.{Ankka, AnkkaService, ProjectionRuntime}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * `features/views/several-sources.feature`, run as it is written, against a real service.
 *
 * Every event a scenario records carries a script (`KeyedKit`) saying what `shipments` does with
 * it, so a step that says "an event that shipments writes the row s1 for" is exactly that. Each
 * scenario's keys and notes are its own, prefixed with its number, so no scenario reads another's
 * rows; what the view did is read from its rows and from the scripts' log of each handling.
 */
final class SeveralSourcesFeatures
    extends GherkinSuite("../../features/views/several-sources.feature")
    with LogCapturing:

  override val munitTimeout: Duration = 10.minutes

  private given ExecutionContext = ExecutionContext.global

  private var kit: AnkkaTestKit = null
  private val scenarios         = AtomicInteger()

  private var scope                                    = ""
  private var acted                                    = false
  private var peer: Option[AnkkaTestKit.Peer]          = None
  private var scripts                                  = Vector.empty[String]
  private var notes                                    = Vector.empty[String]
  private var before                                   = Map.empty[String, Option[ShipmentRow]]
  private var refusedView: Option[ComponentDescriptor] = None
  private var started: Option[Try[AnkkaService]]       = None
  private var earlier                                  = Vector.empty[String]

  override def beforeAll(): Unit =
    super.beforeAll()
    kit = AnkkaTestKit.start(
      Seq(Shipment.descriptor, Customer.descriptor, Shipments.descriptor, Customers.descriptor),
      Seq(ProjectionRuntime())
    )

  override def afterAll(): Unit =
    if kit != null then kit.stop()
    super.afterAll()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    scope = s"k${scenarios.incrementAndGet()}"
    acted = false
    scripts = Vector.empty
    notes = Vector.empty
    before = Map.empty
    refusedView = None
    started.foreach(_.foreach(_.terminate()))
    started = None
    earlier = Vector.empty

  override def afterEach(context: AfterEach): Unit =
    peer.foreach(_.stop())
    peer = None
    super.afterEach(context)

  private def scoped(value: String) = s"$scope-$value"

  private def companion(entity: String): ScriptedCompanion = entity match
    case "shipment" => Shipment
    case "customer" => Customer
    case other      => fail(s"no entity '$other'")

  private def shipments        = kit.service.viewClient.forView(Shipments)
  private def row(key: String) = shipments.get(scoped(key))
  private def keys: Set[String] =
    shipments
      .all(100000)
      .map(_.key)
      .filter(_.startsWith(s"$scope-"))
      .map(_.stripPrefix(s"$scope-"))
      .toSet

  /** Registers a script for this scenario, noted with a note of its own. */
  private def script(pauseMillis: Long, ops: Scripts.Op*): String =
    val note = s"$scope-n${notes.size + 1}"
    notes = notes :+ note
    val id = Scripts.add(Scripts.Script(note, ops.toVector, pauseMillis))
    scripts = scripts :+ id
    id

  private def record(
      entity: String,
      id: String,
      script: String,
      client: ComponentClient = kit.componentClient
  ) =
    client.forEventSourcedEntity(EntityId(scoped(id))).call(companion(entity).record).invoke(script)

  private def handled(script: String): Boolean = Scripts.handled(script)

  private def awaitHandled(script: String): Unit =
    kit.eventually(s"$script handled")(Option.when(handled(script))(()))

  /** Before the scenario's first action: what every row it may read held. */
  private def acting(): Unit =
    if !acted then
      acted = true
      before = keys.map(k => k -> row(k)).toMap

  private def touch(keys: String*) = Scripts.Op.Touch(keys.map(scoped).toVector)

  // ── The service ───────────────────────────────────────────────────────────

  Given(
    "a service {string} with an event sourced entity {string} and an event sourced entity {string}"
  )((_: String, _: String, _: String) => ())
  Given("a view {string} that reads the events of {string} and the events of {string}")(
    (_: String, _: String, _: String) => ()
  )
  Given(
    "{string} declares the query {string} with a statement that reads its own table for the rows holding the value {string}"
  )((_: String, query: String, _: String) => assertEquals(query, Shipments.ofCustomer.name))
  Given("{string} has a view {string} that reads the events of {string} and names no row key")(
    (_: String, view: String, _: String) => assertEquals(view, Customers.componentId.toString)
  )
  Given("{string} has a view {string} that reads the topic {string} and the events of {string}")(
    (_: String, _: String, _: String, _: String) => refusedView = Some(ordersOfTopicAndCustomer)
  )
  Given("two instances of {string}")((_: String) =>
    peer = Some(kit.startPeer(Seq(ProjectionRuntime())))
  )

  // ── What the view holds ───────────────────────────────────────────────────

  // A state: established before anything is done, and checked after.
  Given("{string} holds the row {string}")((view: String, key: String) =>
    if view == Customers.componentId.toString then
      // A plain view: its row under the entity id the event came from.
      kit.eventually(s"customers' row $key")(
        kit.service.viewClient.forView(Customers).get(scoped(key))
      ): Unit
    else if !acted then
      val made = script(0, touch(key))
      record("shipment", key, made)
      awaitHandled(made)
      kit.eventually(s"the row $key")(row(key)): Unit
    else kit.eventually(s"the row $key")(row(key)): Unit
  )
  Given("{string} holds the rows {string}, {string} and {string}")(
    (_: String, a: String, b: String, c: String) =>
      Seq(a, b, c).foreach { key =>
        val made = script(0, touch(key))
        record("shipment", key, made)
        awaitHandled(made)
      }
      kit.eventually("the three rows")(Option.when(Set(a, b, c).subsetOf(keys))(())): Unit
  )
  Given(
    "{string} holds the rows {string} and {string}, each holding {string}, and the row {string} holding {string}"
  )((_: String, a: String, b: String, customer: String, c: String, other: String) =>
    Seq(a -> customer, b -> customer, c -> other).foreach { (key, holding) =>
      val made = script(0, Scripts.Op.Touch(Vector(scoped(key)), Some(scoped(holding))))
      record("shipment", key, made)
      awaitHandled(made)
    }
    kit.eventually("the three rows")(Option.when(Set(a, b, c).subsetOf(keys))(())): Unit
  )
  Given(
    "the entity {string} of {string} has recorded an event that {string} writes the row {string} for"
  )((id: String, entity: String, _: String, key: String) =>
    val made = script(0, touch(key))
    record(entity, id, made)
    awaitHandled(made)
  )
  Given(
    "the entities {string}, {string} and {string} of {string} have each recorded an event that {string} writes a row under their entity id for"
  )((a: String, b: String, c: String, entity: String, _: String) =>
    Seq(a, b, c).foreach { id =>
      val made = script(0, touch(id))
      record(entity, id, made)
      awaitHandled(made)
    }
  )
  Given("{string} has read every event of {string} and every event of {string}")(
    (_: String, _: String, _: String) =>
      val a = script(0, touch("s1"))
      val b = script(0, touch("s1"))
      record("shipment", "s1", a)
      record("customer", "c1", b)
      awaitHandled(a)
      awaitHandled(b)
      earlier = Vector(a, b)
  )
  Given("{string} has since restarted")((_: String) => kit.restartService())

  // ── What is done ──────────────────────────────────────────────────────────

  When(
    "the entity {string} of {string} records an event that {string} writes the row {string} for"
  )((id: String, entity: String, _: String, key: String) =>
    acting()
    record(entity, id, script(0, touch(key)))
  )
  When(
    "the entity {string} of {string} records an event that {string} writes the rows {string} and {string} for"
  )((id: String, entity: String, _: String, a: String, b: String) =>
    acting()
    record(entity, id, script(0, touch(a, b)))
  )
  When(
    "the entity {string} of {string} records an event for which {string} asks its own query {string} with {string} as {string} and writes each row it is answered with"
  )((id: String, entity: String, _: String, query: String, value: String, _: String) =>
    assertEquals(query, Shipments.ofCustomer.name)
    acting()
    record(entity, id, script(0, Scripts.Op.TouchHolding(scoped(value))))
  )
  When(
    "the entity {string} of {string} records an event for which {string} reads the row {string} and writes it again with what the event says added"
  )((id: String, entity: String, _: String, key: String) =>
    acting()
    record(entity, id, script(0, touch(key)))
  )
  When(
    "the entity {string} of {string} records an event for which {string} writes the row {string} and a row that cannot be written"
  )((id: String, entity: String, _: String, key: String) =>
    acting()
    record(
      entity,
      id,
      script(0, touch(key), Scripts.Op.ForAttempts(2, Scripts.Op.Unwritable(scoped("unwritable"))))
    )
  )
  When(
    "the entity {string} of {string} records an event that {string} deletes the row {string} and writes the row {string} for"
  )((id: String, entity: String, _: String, old: String, moved: String) =>
    acting()
    record(entity, id, script(0, Scripts.Op.Delete(Vector(scoped(old))), touch(moved)))
  )
  When(
    "the entity {string} of {string} records an event that {string} writes the row {string} for and deletes no row for"
  )((id: String, entity: String, _: String, key: String) =>
    acting()
    record(entity, id, script(0, touch(key)))
  )
  When("the entity {string} of {string} records an event that {string} writes a row for")(
    (id: String, entity: String, _: String) =>
      acting()
      record(entity, id, script(0))
  )

  private def atOnce(shipmentId: String, customerId: String, ops: Scripts.Op*): Unit =
    acting()
    val a     = script(400, ops*)
    val b     = script(400, ops*)
    val other = peer.map(_.componentClient).getOrElse(kit.componentClient)
    val both = Seq(
      Future(record("shipment", shipmentId, a)),
      Future(record("customer", customerId, b, other))
    )
    Await.result(Future.sequence(both), 30.seconds): Unit

  When(
    "the entity {string} of {string} and the entity {string} of {string} each record an event at the same time"
  )((s: String, _: String, c: String, _: String) => atOnce(s, c))
  When(
    "the entity {string} of {string} and the entity {string} of {string} each record an event at the same time for which {string} reads the row {string} and writes it again with what the event says added"
  )((s: String, _: String, c: String, _: String, _: String, key: String) =>
    atOnce(s, c, touch(key))
  )
  When(
    "the entity {string} of {string} and the entity {string} of {string} each record one more event"
  )((s: String, _: String, c: String, _: String) =>
    acting()
    record("shipment", s, script(0, touch("s1")))
    record("customer", c, script(0, touch("s1")))
  )
  When("{string} is started")((_: String) =>
    val view    = refusedView.getOrElse(fail("no view was described"))
    val service = Ankka.service.registerAll(Seq(Customer.descriptor, view))
    started = Some(Try(service.start(s"refused-$scope", kit.serviceConfig)))
  )

  // ── What is seen ──────────────────────────────────────────────────────────

  private def last = scripts.lastOption.getOrElse(fail("no event was recorded"))

  Then("{string} holds one row {string}")((_: String, key: String) =>
    kit.eventually(s"the row $key")(row(key)): Unit
  )
  Then("the row {string} holds what was written for both events")((key: String) =>
    val wanted = notes.takeRight(2)
    kit.eventually(s"$key holding ${wanted.mkString(", ")}")(
      row(key).filter(r => wanted.forall(r.notes.contains))
    ): Unit
  )
  Then("{string} holds the rows {string}, {string} and {string} and no other")(
    (_: String, a: String, b: String, c: String) =>
      kit.eventually("those rows")(Option.when(keys == Set(a, b, c))(())): Unit
      scripts.foreach(awaitHandled)
      assertEquals(keys, Set(a, b, c))
  )
  Then("the rows {string} and {string} hold what was written for that event")(
    (a: String, b: String) =>
      Seq(a, b).foreach(key =>
        kit.eventually(s"$key holding ${notes.last}")(
          row(key).filter(_.notes.contains(notes.last))
        ): Unit
      )
  )
  Then("the row {string} is as it was")((key: String) =>
    awaitHandled(last)
    Thread.sleep(300)
    assertEquals(row(key), before.getOrElse(key, None))
  )
  Then("the row {string} holds what it held before and what was written for that event")(
    (key: String) =>
      val held = before.getOrElse(key, None).fold(Vector.empty[String])(_.notes)
      kit.eventually(s"$key holding ${notes.last}")(
        row(key).filter(_.notes.contains(notes.last))
      ): Unit
      assertEquals(row(key).map(_.notes), Some(held :+ notes.last))
  )
  Then("{string} holds the row {string} and the row {string}")((_: String, a: String, b: String) =>
    kit.eventually(s"$a and $b")(Option.when(keys(a) && keys(b))(())): Unit
  )
  Then("{string} holds no row {string}")((_: String, key: String) =>
    awaitHandled(last)
    kit.eventually(s"no row $key")(Option.when(!keys(key))(())): Unit
  )
  Then("{string} handles that event again")((_: String) =>
    kit.eventually(s"$last handled again", 2.minutes)(
      Option.when(Scripts.attemptsOf(last) >= 2)(())
    ): Unit
  )
  Then(
    "{string} handles one of the two events, with everything it reads and writes for it, before it handles the other"
  )((_: String) =>
    val Seq(a, b) = scripts.takeRight(2)
    Seq(a, b).foreach(awaitHandled)
    val order = Scripts.log.asScala.toVector
      .filter(e => e.startsWith("begin shipments ") || e.startsWith("end shipments "))
      .filter(e => e.endsWith(s" $a") || e.endsWith(s" $b"))
    val first = if order.head.endsWith(a) then a else b
    val other = if first == a then b else a
    assertEquals(
      order,
      Vector(
        s"begin shipments $first",
        s"end shipments $first",
        s"begin shipments $other",
        s"end shipments $other"
      )
    )
  )
  Then("{string} reads those two events")((_: String) => scripts.takeRight(2).foreach(awaitHandled))
  Then("{string} reads no event it had read before the restart")((_: String) =>
    earlier.foreach(id => assertEquals(Scripts.attemptsOf(id), 1, s"$id was read again"))
  )
  Then("{string} does not start")((_: String) =>
    assert(started.exists(_.isFailure), s"it started: $started")
  )
  Then("the developer is told that a topic and an entity may not be sources of one view")(() =>
    val problem =
      started.flatMap(_.failed.toOption).map(_.getMessage).getOrElse(fail("nothing failed"))
    assert(problem.contains("a topic and an entity may not be sources of one view"), problem)
  )
