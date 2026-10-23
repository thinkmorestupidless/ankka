package com.thinkmorestupidless.ankka.testkit.replication

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  InMemoryBroker,
  JournalRecord,
  Observability,
  ProjectionRuntime,
  RuntimeExtension,
  RecordedSpan,
  SpanKind,
  SqlFragment
}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.sdk.{EventSourcedEntityCalls, ReplicationStatus}
import com.thinkmorestupidless.ankka.testkit.{
  AnkkaTestKit,
  EventSourcedTestKit,
  GherkinSuite,
  LogCapturing
}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.serialization.SerializationExtension

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

/**
 * The steps of `features/replication`, shared by one suite per feature file (feature 051).
 *
 * Each scenario has installations of its own, each a service on a database of its own, started on
 * first use: a scenario's `Given`s say what the service is — what it holds, how a handover waits,
 * whether a consumer reacts to mirrored changes — before anything runs. A caller's command is sent
 * without waiting, so a `When` that sends one returns at once and the `Then` waits for its answer;
 * a journal is read from the installation's own database, as the bytes Postgres holds.
 */
abstract class ReplicationSteps(features: String) extends GherkinSuite(features) with LogCapturing:

  override val munitTimeout = 3.minutes

  // ── what a scenario holds ─────────────────────────────────────────────────

  /** One call a caller made: where, what, and its answer once it has one. */
  final case class Call(at: String, command: String, answer: Future[Any], sentAt: Long):
    def result(within: FiniteDuration = 30.seconds): Try[Any] =
      Try(Await.result(answer, within))

  private var names: Vector[String]                     = Vector.empty
  private var group: Option[AnkkaTestKit.Installations] = None
  private var kind: VehicleKind                         = Fleet.vehicles
  private var projections                               = false
  private var mirrored                                  = false
  private var ledger                                    = false
  private var loopback                                  = false
  private var supplied                                  = false
  private var only: Map[String, Config]                 = Map.empty
  private var desks: Set[String]                        = Set.empty
  private var servers: Map[String, HttpServer]          = Map.empty
  private var personal: Option[(Int, String)]           = None
  private val inspected = java.util.concurrent.atomic.AtomicReference[Option[Receipt]](None)
  private var beside: Seq[ComponentDescriptor]    = Nil
  private var settings: Config                    = ConfigFactory.empty()
  private var broker: InMemoryBroker              = InMemoryBroker()
  private val changes                             = ChangesGiven()
  private var plain: Option[AnkkaTestKit]         = None
  private var homes: Map[String, String]          = Map.empty
  private var calls: Vector[Call]                 = Vector.empty
  private var queries: Vector[Call]               = Vector.empty
  private var counter                             = 0
  private var before: Map[(String, String), Int]  = Map.empty
  private var history: Vector[Row]                = Vector.empty
  private var spansBefore: Map[String, Set[Long]] = Map.empty
  private var refusal: Option[Throwable]          = None
  private var pureKit: Option[EventSourcedTestKit[VehicleEntity, Vehicle, VehicleEvent]] = None
  private var pureResult: Option[Either[CommandError, Option[Any]]]                      = None
  private var compared: Option[String]                                                   = None

  override def afterEach(context: AfterEach): Unit =
    try
      group.foreach(_.stopAll())
      plain.foreach(p => Try(p.stop()))
    finally
      group = None
      plain = None
      names = Vector.empty
      kind = Fleet.vehicles
      projections = false
      mirrored = false
      ledger = false
      loopback = false
      supplied = false
      only = Map.empty
      desks = Set.empty
      servers = Map.empty
      personal = None
      inspected.set(None)
      beside = Nil
      RecordingTransport.clear()
      settings = ConfigFactory.empty()
      broker = InMemoryBroker()
      changes.clear()
      homes = Map.empty
      calls = Vector.empty
      queries = Vector.empty
      before = Map.empty
      history = Vector.empty
      spansBefore = Map.empty
      refusal = None
      pureKit = None
      pureResult = None
      compared = None
      super.afterEach(context)

  /** Starts every installation the scenario named, once, with what its `Given`s configured. */
  private def started: AnkkaTestKit.Installations =
    group.getOrElse {
      assert(names.nonEmpty, "the scenario names no installation")
      val named = AnkkaTestKit.installations(names*)
      val fleet = if supplied then named.using(RecordingTransport.factory) else named
      group = Some(fleet)
      if !supplied then fleet.transport.loopbackOnly(loopback)
      names.foreach(start(fleet, _, None))
      fleet
    }

  /** What the installation `name` hosts: the components, and the extensions that run them. */
  private def parts(name: String): (Seq[ComponentDescriptor], Seq[RuntimeExtension]) =
    val components = Seq(kind.descriptor) ++
      (if projections then
         Seq(FloorKit(kind).descriptor, BuildsKit(name, changes, kind, mirrored).descriptor)
       else Nil) ++
      (if ledger then Seq(LedgerKit(name, changes, kind).descriptor) else Nil)
    val desk = Option.when(desks.contains(name)) {
      val server =
        HttpServer.at("127.0.0.1", 0)(clients => InspectionsEndpoint(clients, kind, inspected))
      servers += name -> server
      server
    }
    val extensions =
      (if projections || ledger then Seq(ProjectionRuntime.withPublisher(broker)) else Nil) ++
        desk.toSeq
    (components, extensions)

  private def start(
      fleet: AnkkaTestKit.Installations,
      name: String,
      database: Option[AnkkaTestKit]
  ): AnkkaTestKit =
    val (components, extensions) = parts(name)
    fleet.start(name, components, extensions, settings = settingsOf(name), database = database)

  /** The scenario's settings, under what it gave this installation alone. */
  private def settingsOf(name: String): Config =
    only.getOrElse(name, ConfigFactory.empty()).withFallback(settings)

  private def restart(fleet: AnkkaTestKit.Installations, name: String): AnkkaTestKit =
    val (components, extensions) = parts(name)
    fleet.restart(name, components, extensions, settings = settingsOf(name))

  private def at(name: String): AnkkaTestKit = started(name)

  private def entity(name: String, id: String): EventSourcedEntityCalls =
    at(name).componentClient.forEventSourcedEntity(EntityId(id))

  private def status(name: String, id: String): ReplicationStatus =
    entity(name, id).replication(kind)

  private def homeOf(id: String): String =
    homes.getOrElse(id, fail(s"the scenario created no vehicle '$id'"))

  private def numberOf(word: String): Int = word match
    case "one"    => 1
    case "two"    => 2
    case "three"  => 3
    case "five"   => 5
    case "twelve" => 12
    case other    => other.toIntOption.getOrElse(fail(s"'$other' is not a number this suite knows"))

  private def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    AnkkaTestKit.eventually(description, within)(Try(check).toOption.flatten)

  // ── a caller's calls ──────────────────────────────────────────────────────

  private def nextStep(): String =
    counter += 1
    s"step-$counter"

  /** Sends `command` to `id` at `name` without waiting, and remembers the call. */
  private def send(name: String, command: String, id: String): Call =
    noteJournals(id)
    val target              = entity(name, id)
    val answer: Future[Any] = command match
      case "create"            => target.call(kind.createVehicle).invokeAsync(CreateVehicle())
      case "add-step"          => target.call(kind.addStep).invokeAsync(nextStep())
      case "record-inspection" =>
        counter += 1
        target.call(kind.recordInspection).invokeAsync(s"inspection-$counter")
      case "record-sale" => target.call(kind.recordSale).invokeAsync()
      case other         => fail(s"no command '$other' on the vehicle")
    val call = Call(name, command, answer, System.nanoTime())
    calls :+= call
    call

  private def query(name: String, id: String): Call =
    val call = Call(name, "get", entity(name, id).call(kind.get).invokeAsync(), System.nanoTime())
    queries :+= call
    call

  private def answered[A](call: Call): A =
    call.result() match
      case Success(value) => value.asInstanceOf[A]
      case Failure(e)     => fail(s"${call.command} at ${call.at} was not answered: $e")

  private def refused(call: Call): CommandError =
    call.result() match
      case Failure(e: CommandError) => e
      case Failure(e) => fail(s"${call.command} at ${call.at} failed, not refused: $e")
      case Success(v) => fail(s"${call.command} at ${call.at} was answered: $v")

  private def lastCall: Call = calls.lastOption.getOrElse(fail("no caller has sent a command"))

  private def lastQuery: Call = queries.lastOption.getOrElse(fail("no caller has sent a query"))

  /**
   * Creates the vehicle at `name` with `replicateTo` in its replication set, and waits until each
   * of them holds it: a vehicle "created with them in its replication set" is one they follow.
   */
  private def create(name: String, id: String, replicateTo: Vector[String]): Receipt =
    val receipt = entity(name, id).call(kind.createVehicle).invoke(CreateVehicle(replicateTo))
    homes += id -> name
    replicateTo.foreach(follower =>
      eventually(s"$follower follows $id")(
        Option.when(
          journal(follower, id).exists(_.record.kind == JournalRecord.KindReplicationSet)
        )(())
      )
    )
    receipt

  private def commands(name: String, id: String, n: Int): Unit =
    (1 to n).foreach(_ => answered[Receipt](send(name, "add-step", id)))

  private def handOver(id: String, to: String): Try[Receipt] =
    handoverSent = System.nanoTime()
    Try(entity(homeOf(id), id).call(kind.handOver).invoke(to))

  /** Hands the home over and waits until it is recorded, at the old home and at the new. */
  private def handedOver(id: String, to: String): Unit =
    val from = homeOf(id)
    handOver(id, to).get: Unit
    entity(from, id).awaitHandover(kind, to, 30.seconds)
    eventually(s"$to is the home of $id, as its own copy says")(
      Option.when(status(to, id).home.contains(to))(())
    )
    homes += id -> to

  // ── journals ──────────────────────────────────────────────────────────────

  /** One record of a journal: its sequence number, the record, and its bytes as stored. */
  final case class Row(seq: Long, record: JournalRecord, bytes: Vector[Byte]):
    def writer: Option[String]      = record.installation
    def isDomain: Boolean           = record.kind == JournalRecord.KindDomain
    def event: Option[VehicleEvent] =
      Option.when(isDomain)(FleetCodecs.event.fromBytes(record.payload))

  private def journalIn(kit: AnkkaTestKit, id: String): Vector[Row] =
    given org.apache.pekko.actor.typed.ActorSystem[?] = kit.service.system
    val serialization = SerializationExtension(kit.service.system.classicSystem)
    val pid           = s"${kind.componentId}|$id".replace("'", "''")
    Await.result(
      Database().query(
        SqlFragment.raw(
          "SELECT seq_nr, event_ser_id, event_ser_manifest, event_payload FROM event_journal " +
            s"WHERE persistence_id = '$pid' ORDER BY seq_nr"
        )
      ) { row =>
        val bytes  = row.get("event_payload", classOf[Array[Byte]])
        val record = serialization
          .deserialize(
            bytes,
            row.get("event_ser_id", classOf[java.lang.Integer]).intValue,
            row.get("event_ser_manifest", classOf[String])
          )
          .get
          .asInstanceOf[JournalRecord]
        Row(row.get("seq_nr", classOf[java.lang.Long]).longValue, record, bytes.toVector)
      },
      10.seconds
    )

  private def journal(name: String, id: String): Vector[Row] = journalIn(at(name), id)

  private def noteJournals(id: String): Unit =
    group.foreach(g =>
      g.names.filter(g.isStarted).foreach(n => before += (n, id) -> journal(n, id).size)
    )

  /**
   * Whether two journals hold the same records at the same sequence numbers: byte for byte, but for
   * a record written before replication, which the home holds unstamped and a follower holds
   * stamped with the installation it mirrored it from.
   */
  private def same(a: Vector[Row], b: Vector[Row]): Boolean =
    a.size == b.size && a.zip(b).forall { (x, y) =>
      x.seq == y.seq && (x.bytes == y.bytes ||
        ((x.writer.isEmpty || y.writer.isEmpty) && x.record.kind == y.record.kind &&
          x.record.manifest == y.record.manifest &&
          x.record.payload.sameElements(y.record.payload)))
    }

  private def sameAs(target: String, source: String, id: String, within: FiniteDuration): Unit =
    compared = Some(target)
    try
      eventually(s"the journal of $id at $target is the one at $source", within) {
        val (t, s) = (journal(target, id), journal(source, id))
        Option.when(t.nonEmpty && same(t, s))(())
      }
    catch
      case e: AssertionError =>
        def show(rows: Vector[Row]) = rows.map(r => (r.seq, r.record.kind, r.writer))
        throw AssertionError(
          s"${e.getMessage}\n  $target: ${show(journal(target, id))}\n  $source: ${show(journal(source, id))}"
        )

  private def homeRecord(rows: Vector[Row], named: String): Option[Row] =
    rows.find(r => r.record.kind == JournalRecord.KindHome && r.record.named == named)

  // ── the service and its installations ─────────────────────────────────────

  Given("a service {string} with a replicated entity {string}") { (_: String, entity: String) =>
    kind = if entity == "vehicle" then Fleet.vehicles else VehicleKind(entity)
  }

  Given(
    "a service {string} with a replicated entity {string}, a view {string} of its changes and a consumer {string} that publishes each of its changes to the topic {string}"
  ) { (_: String, entity: String, view: String, consumer: String, topic: String) =>
    assertEquals((entity, view, consumer, topic), ("vehicle", "floor", "builds", "vehicle-builds"))
    projections = true
  }

  Given("{string} runs at the installations {string} and {string}") {
    (_: String, a: String, b: String) => names = Vector(a, b)
  }

  Given("{string} runs at the installations {string}, {string} and {string}") {
    (_: String, a: String, b: String, c: String) => names = Vector(a, b, c)
  }

  Given("{string} runs at the installation {string} alone") { (_: String, a: String) =>
    names = Vector(a)
  }

  Given("the vehicle {string} was created at {string}") { (id: String, name: String) =>
    create(name, id, Vector.empty): Unit
  }

  Given("the vehicle {string} was created at {string} with {string} in its replication set") {
    (id: String, name: String, other: String) => create(name, id, Vector(other)): Unit
  }

  Given(
    "the vehicle {string} was created at {string} with {string} and {string} in its replication set"
  )((id: String, name: String, b: String, c: String) => create(name, id, Vector(b, c)): Unit)

  Given("a caller at {string} sent {word} commands to {string}") {
    (name: String, count: String, id: String) => commands(name, id, numberOf(count))
  }

  Given("a caller at {string} sent three more commands to {string}") { (name: String, id: String) =>
    commands(name, id, 3)
  }

  When("a caller at {string} sends {word} commands to {string}") {
    (name: String, count: String, id: String) => commands(name, id, numberOf(count))
  }

  When("a caller at {string} sends the command {string} to {string}") {
    (name: String, command: String, id: String) => send(name, command, id): Unit
  }

  When("a caller at {string} sends the query {string} to {string}") {
    (name: String, q: String, id: String) =>
      assertEquals(q, "get")
      spansBefore = started.names.map(n => n -> spanIds(n)).toMap
      query(name, id): Unit
  }

  Given("a caller at {string} sent the command {string} to the vehicle {string}") {
    (name: String, command: String, id: String) =>
      answered[Receipt](send(name, command, id)): Unit
      if command == "create" then homes += id -> name
  }

  When(
    "a caller at {string} sends the command {string} to the vehicle {string} that no installation has seen"
  ) { (name: String, command: String, id: String) =>
    names.foreach(n => assert(journal(n, id).isEmpty, s"$n holds $id already"))
    answered[Receipt](send(name, command, id)): Unit
    homes += id -> name
  }

  // ── answers ───────────────────────────────────────────────────────────────

  Then("the caller is answered as the handler of {string} replied") { (command: String) =>
    val receipt = answered[Receipt](lastCall)
    assertEquals(receipt.command, command)
    assertEquals(receipt.installation, Some(lastCall.at))
    assert(receipt.home)
  }

  Then("the caller is answered as the handler of {string} at {string} replied") {
    (command: String, name: String) =>
      val receipt = answered[Receipt](lastCall)
      assertEquals(receipt.command, command)
      assertEquals(receipt.installation, Some(name), "the installation whose handler ran")
      assert(receipt.home, "the handler ran at the home")
  }

  Then("the command is handled at {string}") { (name: String) =>
    assertEquals(answered[Receipt](lastCall).installation, Some(name))
  }

  Then("the caller is refused with an error naming {string} as the home of {string}") {
    (home: String, id: String) =>
      val call  = calls.lastOption.filter(c => queries.isEmpty || c.sentAt > lastQuery.sentAt)
      val error = refused(call.getOrElse(lastQuery))
      assertEquals(error.code, ErrorCode.NotHome, error.toString)
      assert(error.message.contains(s"'$home'"), error.message)
      assert(error.message.contains(id), error.message)
  }

  Then("the caller is answered with the state of {string}") { (id: String) =>
    val vehicle = answered[Vehicle](lastQuery)
    assertEquals(vehicle.id, id)
    // Read from the home's journal, not asked of the home: no call may cross to it.
    val steps = journal(homeOf(id), id).flatMap(_.event).collect {
      case VehicleEvent.StepAdded(step) => step
    }
    assertEquals(vehicle.steps, steps)
  }

  Then("the caller is answered with the state {string} holds for {string}") {
    (name: String, id: String) =>
      assertEquals(answered[Vehicle](lastQuery), entity(name, id).call(kind.get).invoke())
  }

  // ── the journal ───────────────────────────────────────────────────────────

  Then(
    "the journal of {string} at {string} holds the event the handler persisted as its next record"
  ) { (id: String, name: String) =>
    answered[Receipt](lastCall): Unit
    val rows = journal(name, id)
    assertEquals(rows.size, before((name, id)) + 1, "one more record")
    assert(rows.last.event.exists(_.isInstanceOf[VehicleEvent.StepAdded]), rows.last.toString)
    assertEquals(rows.last.writer, Some(name))
  }

  Then("the journal of {string} at {string} holds the inspection as its next record") {
    (id: String, name: String) =>
      answered[Receipt](lastCall): Unit
      val rows = journal(name, id)
      assertEquals(rows.size, before((name, id)) + 1, "one more record")
      assert(
        rows.last.event.exists(_.isInstanceOf[VehicleEvent.InspectionRecorded]),
        rows.last.toString
      )
  }

  Then(
    "the journal of {string} at {string} holds the same records at the same sequence numbers as at {string}"
  ) { (id: String, target: String, source: String) =>
    calls.foreach(answered[Any])
    sameAs(target, source, id, 30.seconds)
  }

  Given("{string} holds every record of {string}") { (name: String, id: String) =>
    sameAs(name, homeOf(id), id, 30.seconds)
  }

  Then("{string} holds no record of {string}") { (name: String, id: String) =>
    answered[Any](lastCall): Unit
    assert(journal(name, id).isEmpty, journal(name, id).toString)
  }

  Then("neither {string} nor {string} holds a record of {string}") {
    (a: String, b: String, id: String) =>
      assert(journal(a, id).isEmpty && journal(b, id).isEmpty)
  }

  // ── reads made no call ────────────────────────────────────────────────────

  private def spans(name: String): Vector[RecordedSpan] =
    Observability(at(name).service.system).recorder.snapshot()

  private def spanIds(name: String): Set[Long] = spans(name).map(_.spanId).toSet

  private def nameOf(name: String, ref: Int): String =
    Observability(at(name).service.system).names.nameOf(ref).getOrElse("")

  Then("no call was made to {string}") { (name: String) =>
    answered[Any](lastQuery): Unit
    val made = spans(name).filterNot(s => spansBefore.getOrElse(name, Set.empty)(s.spanId))
    assert(
      made.forall(s => nameOf(name, s.handlerRef) != "get"),
      s"$name ran ${made.map(s => nameOf(name, s.handlerRef))}"
    )
  }

  // ── one installation alone ────────────────────────────────────────────────

  private var aloneAnswers: Vector[Any] = Vector.empty

  private def sequence(calls: EventSourcedEntityCalls, k: VehicleKind): Vector[Any] =
    Vector(
      calls.call(k.createVehicle).invoke(CreateVehicle()),
      calls.call(k.addStep).invoke("one"),
      calls.call(k.addStep).invoke("two"),
      calls.call(k.get).invoke()
    )

  When("a caller at {string} sends commands and queries to the vehicle {string}") {
    (name: String, id: String) => aloneAnswers = sequence(entity(name, id), kind)
  }

  /**
   * The same commands against the same entity, declared not replicated, on a service of its own.
   */
  private lazy val unreplicated: (Vector[Any], Vector[Row]) =
    val notReplicated = VehicleKind(kind.componentId.toString, replicatedHere = false)
    val kit           = AnkkaTestKit.start(Seq(notReplicated.descriptor))
    try
      val answers =
        sequence(kit.componentClient.forEventSourcedEntity(EntityId("v2")), notReplicated)
      (answers, journalIn(kit, "v2"))
    finally kit.stop()

  Then("each is answered as it would be for an entity that is not replicated") { () =>
    assertEquals(aloneAnswers, unreplicated._1)
  }

  Then(
    "the journal of {string} holds the same bytes an entity that is not replicated would write"
  ) { (id: String) =>
    val rows = journal(names.head, id)
    assertEquals(rows.map(_.seq), unreplicated._2.map(_.seq))
    assertEquals(rows.map(_.bytes), unreplicated._2.map(_.bytes))
    assert(rows.forall(_.writer.isEmpty), "no record names an installation")
  }

  // ── creation and conflict ─────────────────────────────────────────────────

  Then("{string} is the home of {string}") { (name: String, id: String) =>
    eventually(s"$name is the home of $id")(Option.when(status(name, id).home.contains(name))(()))
  }

  Then("the replication set of {string} is {string} alone") { (id: String, name: String) =>
    assertEquals(status(homeOf(id), id).replicationSet, Set(name))
  }

  Given("{string} cannot be reached from {string}") { (to: String, from: String) =>
    started.transport.block(from = from, to = to)
  }

  When("{string} can be reached from {string} again") { (to: String, from: String) =>
    started.transport.unblock(from = from, to = to)
  }

  private def inConflict(id: String, a: String, b: String): Unit =
    eventually(s"$id is in conflict at $a and at $b", 60.seconds)(
      Option.when(status(a, id).inConflict && status(b, id).inConflict)(())
    )

  Then("{string} is marked in conflict at {string} and at {string}") {
    (id: String, a: String, b: String) => inConflict(id, a, b)
  }

  Then("an operator can see the conflict") { () =>
    val id     = homes.keys.head
    val (a, b) = (names(0), names(1))
    assertEquals(status(a, id).conflict, Some(b))
    assertEquals(status(b, id).conflict, Some(a))
  }

  Given("the vehicle {string} is marked in conflict at {string} and at {string}") {
    (id: String, a: String, b: String) =>
      started.transport.block(from = b, to = a)
      answered[Receipt](send(a, "create", id)): Unit
      answered[Receipt](send(b, "create", id)): Unit
      started.transport.unblock(from = b, to = a)
      homes += id -> a
      inConflict(id, a, b)
  }

  Then("the command is refused with an error saying {string} is in conflict") { (id: String) =>
    val error = refused(lastCall)
    assertEquals(error.code, ErrorCode.Conflict, error.toString)
    assert(error.message.contains(id) && error.message.contains("conflict"), error.message)
  }

  Then("the query is answered with the state {string} holds for {string}") {
    (name: String, id: String) =>
      val vehicle = answered[Vehicle](lastQuery)
      assertEquals(vehicle.id, id)
      assertEquals(vehicle, entity(name, id).call(kind.get).invoke())
  }

  // ── the trace of a forwarded call ─────────────────────────────────────────

  Given("a caller at {string} sent the command {string} to {string} and was answered") {
    (name: String, command: String, id: String) =>
      spansBefore = started.names.map(n => n -> spanIds(n)).toMap
      answered[Receipt](send(name, command, id)): Unit
  }

  When("a developer reads the trace of the call")(() => ())

  Then(
    "the trace shows one call from the caller to {string}, forwarded from {string} to {string}"
  ) { (component: String, from: String, to: String) =>
    val command             = lastCall.command
    def fresh(name: String) = eventually(s"$name recorded the call") {
      val found = spans(name)
        .filterNot(s => spansBefore.getOrElse(name, Set.empty)(s.spanId))
        .filter(s =>
          nameOf(name, s.componentRef) == component && nameOf(name, s.handlerRef) == command
        )
      Option.when(found.nonEmpty)(found)
    }
    val forwarder = fresh(from)
    val home      = fresh(to)
    assertEquals(forwarder.size, 1, s"one span at $from: $forwarder")
    assertEquals(forwarder.head.kind, SpanKind.Client, "the forwarding installation called")
    assertEquals(home.size, 1, s"one span at $to: $home")
    assertEquals(home.head.kind, SpanKind.Internal, "the home ran the handler")
    assertEquals(home.head.traceId, forwarder.head.traceId, "one trace")
    assertEquals(home.head.parentSpanId, forwarder.head.spanId, "the home's span is the call's")
  }

  // ── the handover ──────────────────────────────────────────────────────────

  When("a handler of {string} hands its home to {string}") { (id: String, to: String) =>
    noteJournals(id)
    handOver(id, to) match
      case Failure(e) => refusal = Some(e)
      case Success(_) =>
        if !started.transport.isPaused(to) then
          entity(homeOf(id), id).awaitHandover(kind, to, 30.seconds)
          eventually(s"$to is the home of $id")(Option.when(status(to, id).home.contains(to))(()))
          homes += id -> to
  }

  Given("a handler of {string} handed its home to {string}") { (id: String, to: String) =>
    handOver(id, to).get: Unit
  }

  Given("the home of {string} was handed to {string}") { (id: String, to: String) =>
    handedOver(id, to)
  }

  Then("the journal of {string} at {string} holds the handover and then the sale") {
    (id: String, name: String) =>
      answered[Receipt](lastCall): Unit
      val rows = eventually(s"$name holds the sale")(
        Some(journal(name, id)).filter(_.exists(_.event.contains(VehicleEvent.Sold)))
      )
      val handover = homeRecord(rows, name).getOrElse(fail(s"no handover to $name"))
      val sale     = rows.find(_.event.contains(VehicleEvent.Sold)).get
      assert(handover.seq < sale.seq, s"handover at ${handover.seq}, sale at ${sale.seq}")
  }

  Then(
    "the journal of {string} at {string} holds no record written at {string} after the handover"
  ) { (id: String, name: String, writer: String) =>
    answered[Receipt](lastCall): Unit
    val rows = eventually(s"$name holds the sale")(
      Some(journal(name, id)).filter(_.exists(_.event.contains(VehicleEvent.Sold)))
    )
    val handover =
      rows.find(_.record.kind == JournalRecord.KindHome).filter(_.writer.contains(writer))
    val at = rows.filter(r => homeRecord(Vector(r), homeOf(id)).nonEmpty).map(_.seq).max
    assert(handover.nonEmpty)
    val after = rows.filter(_.seq > at)
    assert(after.nonEmpty, "records after the handover")
    assert(after.forall(!_.writer.contains(writer)), after.toString)
  }

  Given("the replication transport to {string} is paused") { (name: String) =>
    started.transport.pause(name)
  }

  When("the replication transport to {string} is resumed") { (name: String) =>
    started.transport.resume(name)
  }

  Then(
    "the five commands from {string} come before the handover and the three from {string} after it"
  ) { (from: String, to: String) =>
    val name          = compared.getOrElse(fail("no journal was compared"))
    val id            = homes.keys.head
    val rows          = journal(name, id)
    val handover      = homeRecord(rows, to).getOrElse(fail(s"no handover to $to at $name"))
    val steps         = rows.filter(_.event.exists(_.isInstanceOf[VehicleEvent.StepAdded]))
    val (early, late) = steps.partition(_.seq < handover.seq)
    assertEquals(early.map(_.writer).distinct, Vector(Some(from)))
    assertEquals(early.size, 5)
    assertEquals(late.map(_.writer).distinct, Vector(Some(to)))
    assertEquals(late.size, 3)
  }

  Then("the handover is not recorded while {string} is behind") { (to: String) =>
    val id = homes.keys.head
    Thread.sleep(3000)
    val rows = journal(homeOf(id), id)
    assert(
      rows.exists(r => r.record.kind == JournalRecord.KindHandover && r.record.named == to),
      "the handover is pending"
    )
    assert(homeRecord(rows, to).isEmpty, "the handover is not recorded")
  }

  Then(
    "the handover is recorded once the replication transport to {string} is resumed and {string} holds every record before it"
  ) { (paused: String, to: String) =>
    val id   = homes.keys.head
    val from = homeOf(id)
    started.transport.resume(paused)
    val recorded = eventually(s"the handover to $to is recorded at $from")(
      homeRecord(journal(from, id), to)
    )
    val held = eventually(s"$to holds the handover")(
      Some(journal(to, id)).filter(rows => homeRecord(rows, to).nonEmpty)
    )
    val home = journal(from, id)
    assert(same(held.filter(_.seq < recorded.seq), home.filter(_.seq < recorded.seq)))
  }

  Given("{string} was removed from the replication set of {string}") { (name: String, id: String) =>
    entity(homeOf(id), id).call(kind.stopReplicatingTo).invoke(name): Unit
    if journal(name, id).nonEmpty then
      eventually(s"$name holds its removal")(
        journal(name, id).lastOption.filter(r =>
          r.record.kind == JournalRecord.KindReplicationSet && !r.record.names.contains(name)
        )
      ): Unit
  }

  Then(
    "the handler's command is refused naming {string} as outside the replication set of {string}"
  ) { (name: String, id: String) =>
    val error = refusal match
      case Some(e: CommandError) => e
      case other                 => fail(s"the handover was not refused: $other")
    assertEquals(error.code, ErrorCode.BadRequest)
    assert(error.message.contains(s"'$name'"), error.message)
    assert(error.message.contains("outside the replication set"), error.message)
    assert(error.message.contains(id), error.message)
  }

  Then(
    "the journal of {string} holds a handover record naming {string} after every record {string} wrote"
  ) { (id: String, to: String, from: String) =>
    val rows = eventually(s"the handover to $to is recorded")(
      Some(journal(from, id)).filter(homeRecord(_, to).nonEmpty)
    )
    val handover = homeRecord(rows, to).get
    assert(
      rows.filter(r => r.isDomain && r.writer.contains(from)).forall(_.seq < handover.seq),
      rows.toString
    )
  }

  Then(
    "the history of {string} at every installation in its replication set holds the handover at the same sequence number"
  ) { (id: String) =>
    val to   = homeOf(id)
    val set  = status(to, id).replicationSet
    val seqs = set.toVector.map { name =>
      eventually(s"$name holds the handover")(homeRecord(journal(name, id), to)).seq
    }
    assertEquals(seqs.distinct.size, 1, seqs.toString)
  }

  Then(
    "the handover is recorded after that command once the replication transport to {string} is resumed"
  ) { (to: String) =>
    val id   = homes.keys.head
    val from = homeOf(id)
    answered[Receipt](lastCall): Unit
    val step = journal(from, id).filter(_.event.exists(_.isInstanceOf[VehicleEvent.StepAdded])).last
    started.transport.resume(to)
    val handover = eventually(s"the handover to $to is recorded")(homeRecord(journal(from, id), to))
    assert(handover.seq > step.seq, s"handover at ${handover.seq}, step at ${step.seq}")
  }

  /** When the scenario's last handover was asked for. */
  private var handoverSent = 0L

  Given("{string} configures a handover to hold writes and to be abandoned after {string}") {
    (_: String, after: String) =>
      settings = ConfigFactory.parseString(
        s"ankka.replication.handover { hold-writes = on, timeout = ${after.replace(" ", "")} }"
      )
      // Configured as a deploy would: each installation started again with the setting.
      group.foreach(fleet => fleet.names.foreach(restart(fleet, _)))
  }

  Then("a caller waiting for the handover is told it did not happen after {string}") {
    (after: String) =>
      val id     = homes.keys.head
      val target = started.names.find(n => started.transport.isPaused(n)).get
      val error  = intercept[CommandError](
        entity(homeOf(id), id).awaitHandover(kind, target, 30.seconds)
      )
      assertEquals(error.code, ErrorCode.Unavailable, error.toString)
      assert(error.message.contains(target), error.message)
      // Abandoned at its timeout, and not before: the handover was sent before this waited.
      val waited = (System.nanoTime() - handoverSent).nanos
      assert(waited >= Duration(after) - 500.millis, s"abandoned after $waited")
      assert(waited < Duration(after) + 15.seconds, s"abandoned after $waited")
  }

  Then("{string} is still the home of {string}") { (name: String, id: String) =>
    assertEquals(status(name, id).home, Some(name))
    assertEquals(status(name, id).pendingHandover, None)
  }

  Then("the command {string} is handled at {string} after the handover is abandoned") {
    (command: String, name: String) =>
      val receipt = answered[Receipt](lastCall)
      assertEquals((receipt.command, receipt.installation), (command, Some(name)))
      val rows      = journal(name, homes.keys.head)
      val abandoned = rows.find(_.record.kind == JournalRecord.KindHandoverAbandoned)
      assert(abandoned.nonEmpty, "the handover was abandoned")
      val step = rows.filter(_.event.exists(_.isInstanceOf[VehicleEvent.StepAdded])).last
      assert(abandoned.get.seq < step.seq, "the held command ran after the handover was abandoned")
  }

  // ── the replication set ───────────────────────────────────────────────────

  When("a handler of {string} adds {string} to its replication set") { (id: String, name: String) =>
    entity(homeOf(id), id).call(kind.replicateTo).invoke(name): Unit
  }

  Then("{string} holds every record of {string} from the first") { (name: String, id: String) =>
    sameAs(name, homeOf(id), id, 30.seconds)
    assertEquals(journal(name, id).head.seq, 1L)
  }

  Given("{string} was added to the replication set of {string} after the twelve commands") {
    (name: String, id: String) => entity(homeOf(id), id).call(kind.replicateTo).invoke(name): Unit
  }

  When("{string} has caught up") { (name: String) =>
    val id = homes.keys.head
    eventually(s"$name has caught up")(
      Option.when(journal(name, id).size == journal(homeOf(id), id).size)(())
    )
  }

  Given("{string} is in the replication set of {string} and holds every record") {
    (name: String, id: String) =>
      entity(homeOf(id), id).call(kind.replicateTo).invoke(name): Unit
      sameAs(name, homeOf(id), id, 30.seconds)
  }

  private var heldBeforeRemoval: Vector[Row] = Vector.empty

  When("a handler of {string} removes {string} from its replication set") {
    (id: String, name: String) =>
      entity(homeOf(id), id).call(kind.stopReplicatingTo).invoke(name): Unit
      heldBeforeRemoval = journal(homeOf(id), id)
  }

  Then("the journal of {string} at {string} does not grow") { (id: String, name: String) =>
    answered[Receipt](lastCall): Unit
    val removal = heldBeforeRemoval.last
    eventually(s"$name holds its removal")(
      Option.when(journal(name, id).lastOption.exists(_.seq == removal.seq))(())
    )
    Thread.sleep(3000)
    assertEquals(journal(name, id).last.seq, removal.seq, "nothing after the removal")
    assert(journal(homeOf(id), id).size > removal.seq, "the home went on writing")
  }

  Then("{string} keeps the records of {string} it held") { (name: String, id: String) =>
    assert(same(journal(name, id), heldBeforeRemoval))
  }

  Then(
    "the journal of {string} holds a record naming {string} as removed from the replication set"
  ) { (id: String, name: String) =>
    val sets = journal(homeOf(id), id).filter(_.record.kind == JournalRecord.KindReplicationSet)
    assert(sets.size >= 2, sets.toString)
    assert(sets.init.last.record.names.contains(name))
    assert(!sets.last.record.names.contains(name))
  }

  Then(
    "the record is at {string} and at {string} at the same sequence number, the last record {string} holds"
  ) { (home: String, name: String, _: String) =>
    val id      = homes.keys.head
    val removal = heldBeforeRemoval.last
    assertEquals(removal.record.kind, JournalRecord.KindReplicationSet)
    val held = eventually(s"$name holds the removal")(
      journal(name, id).lastOption.filter(_.seq == removal.seq)
    )
    assertEquals(held.bytes, journal(home, id).find(_.seq == removal.seq).get.bytes)
  }

  // ── views and consumers ───────────────────────────────────────────────────

  Then(
    "the view {string} at {string} holds the step within {string} of the view {string} at {string}"
  ) { (_: String, name: String, within: String, _: String, home: String) =>
    answered[Receipt](lastCall): Unit
    val step  = s"step-$counter"
    val id    = homes.keys.head
    val limit = Duration(within).asInstanceOf[FiniteDuration]
    // Both views are watched at once, every 20ms: the follower's lag behind the home's is what
    // replication adds to a change's way to a view.
    def holds(at: String) =
      this.at(at).service.viewClient.forView(FloorKit(kind)).get(id).exists(_.steps.contains(step))
    var homeAt, followerAt = 0L
    val deadline           = 30.seconds.fromNow
    while (homeAt == 0L || followerAt == 0L) && deadline.hasTimeLeft() do
      if homeAt == 0L && holds(home) then homeAt = System.nanoTime()
      if followerAt == 0L && holds(name) then followerAt = System.nanoTime()
      Thread.sleep(20)
    assert(homeAt > 0L, s"the view at $home never held $step")
    assert(followerAt > 0L, s"the view at $name never held $step")
    val lag = (followerAt - homeAt).nanos
    assert(lag <= limit, s"the view at $name held the step $lag after the view at $home")
  }

  Then("the topic {string} holds three messages") { (topic: String) =>
    calls.foreach(answered[Any])
    eventually(s"three messages on $topic")(Option.when(broker.publishedTo(topic).size == 3)(()))
    // Settled: a follower publishing too would add three more.
    Thread.sleep(3000)
    assertEquals(broker.publishedTo(topic).size, 3)
  }

  Then("each message was published from {string}") { (name: String) =>
    val builds =
      broker.publishedTo("vehicle-builds").map(d => FleetCodecs.build.fromBytes(d.message.payload))
    assert(builds.forall(b => b.installation.contains(name) && b.local), builds.toString)
    assertEquals(changes.at(name).count(_.event.startsWith("StepAdded")), 3)
  }

  Given("the consumer {string} declares that it reacts to mirrored changes") { (_: String) =>
    mirrored = true
    // Declared as a deploy would declare it: each installation started again with the consumer.
    group.foreach(fleet => fleet.names.foreach(restart(fleet, _)))
  }

  Given("the consumer {string} does not declare that it reacts to mirrored changes") {
    (_: String) => mirrored = false
  }

  private def stepGiven(name: String): Option[Handed] =
    changes.at(name).find(_.event == s"StepAdded(step-$counter)")

  Then("the consumer {string} at {string} is given the change as a mirrored change") {
    (_: String, name: String) =>
      answered[Receipt](lastCall): Unit
      val change = eventually(s"$name is given the step")(stepGiven(name))
      assert(!change.local, change.toString)
      assertEquals(change.installation, Some(homeOf(homes.keys.head)))
  }

  Then(
    "the consumer {string} at {string} is given the change as a local change written at {string}"
  ) { (_: String, name: String, writer: String) =>
    answered[Receipt](lastCall): Unit
    val change = eventually(s"$name is given the step")(stepGiven(name))
    assert(change.local, change.toString)
    assertEquals(change.installation, Some(writer))
  }

  Then("the consumer {string} at {string} is not given the change") { (_: String, name: String) =>
    val id = homes.keys.head
    // Once the copy there holds the change and its projection has had time to read it.
    eventually(s"$name holds the step")(
      Option.when(
        journal(name, id).exists(_.event.contains(VehicleEvent.StepAdded(s"step-$counter")))
      )(())
    )
    Thread.sleep(3000)
    assertEquals(stepGiven(name), None)
  }

  // ── the transport ─────────────────────────────────────────────────────────

  Given(
    "a service {string} with a replicated entity {string} started twice on one machine as the installations {string} and {string}, each with its own database"
  ) { (_: String, _: String, a: String, b: String) =>
    names = Vector(a, b)
    loopback = true
  }

  When(
    "a caller at {string} creates the vehicle {string} with {string} in its replication set and sends it a command"
  ) { (name: String, id: String, other: String) =>
    create(name, id, Vector(other)): Unit
    answered[Receipt](send(other, "add-step", id)): Unit
  }

  Then(
    "the journal of {string} at {string} holds the same records as at {string} within {string}"
  ) { (id: String, target: String, source: String, within: String) =>
    val call = lastCall
    sameAs(target, source, id, 30.seconds)
    val took = (System.nanoTime() - call.sentAt).nanos
    assert(took <= Duration(within) + 1.second, s"took $took")
  }

  private var refusedStart: Option[Throwable] = None

  Given(
    "a service {string} with a replicated entity {string} that names the installations {string} and {string}"
  ) { (_: String, _: String, a: String, b: String) =>
    settings = ConfigFactory.parseString(
      s"""ankka.replication { installation = "$a", installations { "$b" {} } }"""
    )
  }

  Given("no replication transport can reach {string}")((_: String) => ())

  When("{string} starts") { (_: String) =>
    refusedStart =
      Try(AnkkaTestKit.start(Seq(kind.descriptor) ++ beside, settings = settings)) match
        case Success(kit) =>
          kit.stop()
          None
        case Failure(e) => Some(e)
  }

  Then("{string} refuses to start") { (_: String) =>
    assert(refusedStart.nonEmpty, "the service started")
  }

  Then("the reason names the replication transport as missing") { () =>
    val message = refusedStart.get.getMessage
    assert(message.contains("replication transport"), message)
    assert(
      message.contains(
        settings.getConfig("ankka.replication.installations").root.keySet.iterator.next
      ),
      message
    )
  }

  // ── a transport beside the runtime's own (spec 057) ───────────────────────

  Given(
    "a service {string} with a replicated entity {string} and an entity {string} that is not replicated"
  ) { (_: String, entity: String, other: String) =>
    kind = if entity == "vehicle" then Fleet.vehicles else VehicleKind(entity)
    beside = Seq(VehicleKind(other, replicatedHere = false).descriptor)
    // Replicating at all, so that a transport is asked for: here and one other, named alone.
    settings = ConfigFactory
      .parseString("""ankka.replication { installation = "leipzig", named = "bratislava" }""")
      .withFallback(settings)
  }

  Given("a transport beside the runtime's own") { () =>
    settings = ConfigFactory
      .parseString(s"${RecordingTransportProvider.Switch} = on")
      .withFallback(settings)
  }

  Then("the transport is given {string} as the replicated components and not {string}") {
    (entity: String, other: String) =>
      assertEquals(refusedStart, None, "the service started")
      val made = RecordingTransport.made.getOrElse("leipzig", fail("no transport was made"))
      assertEquals(made.replicated, Set(ComponentId(entity)))
      assert(!made.replicated.contains(ComponentId(other)))
  }

  Given(
    "a service {string} running at {string} and {string} over a transport beside the runtime's own"
  ) { (_: String, a: String, b: String) =>
    names = Vector(a, b)
    supplied = true
  }

  Given("the vehicle {string} homed at {string} with {string} in its replication set") {
    (id: String, name: String, other: String) => create(name, id, Vector(other)): Unit
  }

  When("the home of {string} is handed over to {string}") { (id: String, to: String) =>
    handedOver(id, to)
  }

  Then(
    "the transport delivers the handover's report to the copy at {string} through the runtime's public operation"
  ) { (home: String) =>
    val delivered = RecordingTransport.reports.getOrElse(home, Vector.empty)
    assert(delivered.nonEmpty, s"no report reached $home: ${RecordingTransport.reports}")
    assert(delivered.forall(_._2 != home), delivered.toString)
  }

  Then("the next write to {string} is handled at {string}") { (id: String, name: String) =>
    val receipt = answered[Receipt](send(names.find(_ != name).get, "add-step", id))
    assertEquals(receipt.installation, Some(name))
    assert(receipt.home)
  }

  Given(
    "a service {string} started with the environment naming the installations {string} and {string} and no database for them"
  ) { (_: String, a: String, b: String) =>
    // What ANKKA_INSTALLATION and ANKKA_INSTALLATIONS set, through the settings they feed.
    settings = ConfigFactory
      .parseString(s"""ankka.replication { installation = "$a", named = "$a,$b" }""")
      .withFallback(settings)
  }

  Then("{string} names {string} and {string} as installations it may replicate with") {
    (_: String, a: String, b: String) =>
      assertEquals(refusedStart, None, "the service started with no database for either")
      val made = RecordingTransport.made.getOrElse(a, fail("no transport was made"))
      assertEquals(made.settings.installation, Some(a))
      assertEquals(made.settings.installations, Set(b))
      assertEquals(made.settings.databases, Map.empty)
  }

  Given(
    "a test that supplies its own replication transport to the test kit's installations group"
  ) { () =>
    supplied = true
  }

  When("the test starts {string} at {string} and {string}") { (_: String, a: String, b: String) =>
    names = Vector(a, b)
    started: Unit
  }

  Then("every installation of the group replicates over the transport the test supplied") { () =>
    val fleet = started
    assertEquals(RecordingTransport.made.keySet.toSet, fleet.names.toSet)
    val Vector(a, b) = fleet.names: @unchecked
    create(a, "v1", Vector(b)): Unit
    commands(a, "v1", 2)
    sameAs(b, a, "v1", 30.seconds)
    // The runtime's own transport's switches are not this group's.
    val e = intercept[UnsupportedOperationException](fleet.transport.pause(b))
    assert(e.getMessage.contains("using"), e.getMessage)
  }

  // ── who acted on a forwarded write, and admission at the home (spec 057) ──

  Given("a person {string} calling at {string}") { (_: String, name: String) =>
    // The installation is started already: it is started again with the person's desk.
    desks += name
    restart(started, name): Unit
  }

  When("{string} sends the command {string} to {string}") {
    (person: String, command: String, id: String) =>
      assertEquals(command, "record-inspection")
      val (name, server) = servers.headOption.getOrElse(fail("no person is calling anywhere"))
      noteJournals(id)
      val http     = java.net.http.HttpClient.newHttpClient()
      val response = http.send(
        java.net.http.HttpRequest
          .newBuilder(
            java.net.URI.create(
              s"http://127.0.0.1:${server.boundPort.get}/vehicles/$id/inspections"
            )
          )
          .header("Authorization", s"Bearer $person-token")
          .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
          .build(),
        java.net.http.HttpResponse.BodyHandlers.ofString()
      )
      personal = Some((response.statusCode, response.body))
      assertEquals(response.statusCode, 200, s"at $name: ${response.body}")
  }

  private def inspection: Receipt =
    inspected.get().getOrElse(fail(s"no inspection was recorded: $personal"))

  Then("the handler at {string} sees the caller as the installation {string}") {
    (home: String, forwarder: String) =>
      assertEquals(inspection.installation, Some(home), "the handler ran at the home")
      assert(inspection.home)
      assertEquals(inspection.forwardedFrom, Some(forwarder))
  }

  Then("the handler at {string} reads {string} as the attribution of the command") {
    (home: String, person: String) =>
      assertEquals(inspection.installation, Some(home))
      assertEquals(inspection.attribution, Some(person))
  }

  Then("the token of {string} never reaches {string}") { (person: String, _: String) =>
    val heard = inspection.heard
    assert(!heard.exists(_.toLowerCase.startsWith("authorization=")), heard.toString)
    assert(!heard.exists(_.contains(s"$person-token")), heard.toString)
  }

  Then(
    "the record of the inspection in the journal of {string} carries {string} as its attribution and {string} as where it was forwarded from"
  ) { (id: String, person: String, forwarder: String) =>
    val home = homeOf(id)
    val row  = journal(home, id)
      .findLast(_.event.exists(_.isInstanceOf[VehicleEvent.InspectionRecorded]))
      .getOrElse(fail(s"no inspection in the journal of $id at $home"))
    assertEquals(row.record.attribution, Some(person))
    assertEquals(row.record.forwardedFrom, Some(forwarder))
    // And the event handler was told who acted, as the record says.
    assertEquals(entity(home, id).call(kind.get).invoke().actedBy.last, Some(person))
  }

  Given("{string} does not admit the installation {string}") { (home: String, other: String) =>
    only += home -> ConfigFactory.parseString(
      s"""ankka.replication.installations."$other" = null"""
    )
    restart(started, home): Unit
  }

  Then("the caller is refused with an error naming {string} as not admitted at {string}") {
    (other: String, home: String) =>
      val error = refused(lastCall)
      assertEquals(error.code, ErrorCode.Forbidden, error.toString)
      assert(error.message.contains(s"'$other' is not admitted at '$home'"), error.message)
  }

  Then("the error is not that the home cannot be reached") { () =>
    val error = refused(lastCall)
    assert(error.code != ErrorCode.NotHome && error.code != ErrorCode.Timeout, error.toString)
    assert(!error.message.contains("cannot be reached"), error.message)
  }

  private var declared: Option[ComponentDescriptor] = None
  private var problems: Vector[String]              = Vector.empty

  private val kinds = Map(
    "key value entity" -> ComponentKind.KeyValueEntity,
    "workflow"         -> ComponentKind.Workflow,
    "agent"            -> ComponentKind.Agent,
    "timer"            -> ComponentKind.TimedAction
  )

  Given("a service whose descriptor declares a {} replicated") { (component: String) =>
    val k = kinds.getOrElse(component, fail(s"no component kind '$component'"))
    declared = Some(new ComponentDescriptor:
      def componentId: ComponentId     = ComponentId("ledger")
      def kind: ComponentKind          = k
      override def replicated: Boolean = true)
  }

  When("the descriptor is validated") { () =>
    problems = ComponentRegistry.from(declared.toSeq).left.getOrElse(Vector.empty)
  }

  Then("the descriptor is refused naming the {}") { (component: String) =>
    assert(problems.nonEmpty, "the descriptor was accepted")
    val k = kinds(component)
    assert(
      problems.exists(p => p.contains(s"$k 'ledger'") && p.contains("replicated")),
      problems.toString
    )
  }

  // ── an existing entity made replicated ────────────────────────────────────

  private def plainHistory(entity: String, at: String, id: String, records: Int): Unit =
    kind = VehicleKind(entity, replicatedHere = false)
    val kit = AnkkaTestKit.start(Seq(kind.descriptor))
    plain = Some(kit)
    val calls = kit.componentClient.forEventSourcedEntity(EntityId(id))
    calls.call(kind.createVehicle).invoke(CreateVehicle()): Unit
    (2 to records).foreach(n => calls.call(kind.addStep).invoke(s"legacy-$n"): Unit)
    history = journalIn(kit, id)
    names = Vector(at)
    homes += id -> at

  private def replicatedNow(entity: String, a: String, b: String): Unit =
    kind = VehicleKind(entity, replicatedHere = true)
    names = Vector(a, b)
    val fleet = AnkkaTestKit.installations(a, b)
    group = Some(fleet)
    start(fleet, a, plain)
    start(fleet, b, None): Unit

  Given(
    "a service {string} with an entity {string} that is not replicated, running at the installation {string}"
  ) { (_: String, entity: String, name: String) =>
    kind = VehicleKind(entity, replicatedHere = false)
    names = Vector(name)
  }

  Given("the order {string} has a history of twelve records at {string}") {
    (id: String, name: String) => plainHistory(kind.componentId.toString, name, id, 12)
  }

  When(
    "{string} is declared replicated and {string} names the installations {string} and {string}"
  ) { (entity: String, _: String, a: String, b: String) =>
    replicatedNow(entity, a, b)
  }

  Then("the history of {string} at {string} is as it was") { (id: String, name: String) =>
    val now = journal(name, id)
    assertEquals(now.take(history.size).map(_.bytes), history.map(_.bytes))
  }

  Then("{string} holds the same twelve records and the change to the replication set after them") {
    (name: String) =>
      val id   = homes.keys.head
      val home = homeOf(id)
      sameAs(name, home, id, 30.seconds)
      val rows = journal(name, id)
      assert(same(rows.take(12), history), "the twelve records")
      assertEquals(
        rows.drop(12).map(_.record.kind),
        Vector(JournalRecord.KindHome, JournalRecord.KindReplicationSet)
      )
      assertEquals(rows.last.record.names, Set(home, name))
  }

  Given(
    "a service {string} whose entity {string} was declared replicated after the order {string} had a history at the installation {string}"
  ) { (_: String, entity: String, id: String, name: String) =>
    plainHistory(entity, name, id, 3)
    ledger = true
    replicatedNow(entity, name, "b")
  }

  When(
    "a consumer at {string} is given a change from a record written before {string} was replicated"
  ) { (name: String, _: String) =>
    eventually(s"the ledger at $name is given the creation")(
      changes.at(name).find(_.event.startsWith("Created"))
    ): Unit
  }

  Then("the change is a local change written at {string}") { (name: String) =>
    val change = changes.at(name).find(_.event.startsWith("Created")).get
    assert(change.local, change.toString)
    assertEquals(change.installation, Some(name))
  }

  // ── the test kit ──────────────────────────────────────────────────────────

  Given(
    "a test that starts the service {string} at the installations {string}, {string} and {string} through the test kit"
  ) { (_: String, a: String, b: String, c: String) =>
    names = Vector(a, b, c)
    started: Unit
  }

  When(
    "the test creates the vehicle {string} at {string}, hands its home to {string} and reads it at {string}"
  ) { (id: String, a: String, b: String, c: String) =>
    create(a, id, Vector(b, c)): Unit
    commands(a, id, 2)
    handedOver(id, b)
    commands(b, id, 2)
    eventually(s"$c reads the four steps")(
      Some(entity(c, id).call(kind.get).invoke()).filter(_.steps.size == 4)
    ): Unit
  }

  Then("the test observes the same history of {string} at {string}, {string} and {string}") {
    (id: String, a: String, b: String, c: String) =>
      sameAs(a, b, id, 30.seconds)
      sameAs(c, b, id, 30.seconds)
  }

  Given("a test driving the entity {string} in the entity test kit as a follower") { (_: String) =>
    // docs:start follower-kit
    val kit = EventSourcedTestKit.of(
      Fleet.vehicles,
      "v1",
      mode = EventSourcedTestKit.Mode.Follower(home = "leipzig"),
      installation = "bratislava"
    )
    given EventSourcedTestKit.WrittenAt = EventSourcedTestKit.WrittenAt(Some("leipzig"))
    kit.replay(VehicleEvent.Created("v1"), VehicleEvent.StepAdded("frame"))
    // docs:end follower-kit
    pureKit = Some(kit)
  }

  When("the test sends a persisting command") { () =>
    pureResult = pureKit.map(_.call(Fleet.vehicles.addStep)("wheels").reply)
  }

  Then("the command is refused as not at the home") { () =>
    pureResult match
      case Some(Left(error)) =>
        assertEquals(error.code, ErrorCode.NotHome)
        assert(error.message.contains("leipzig"), error.message)
      case other => fail(s"not refused: $other")
  }

  Then("a query is answered from the state the test gave the follower") { () =>
    val vehicle = pureKit.get.call(Fleet.vehicles.get).replyValue
    assertEquals(vehicle.steps, Vector("frame"))
    assertEquals(vehicle.writtenAt, Vector(Some("leipzig"), Some("leipzig")))
    assertEquals(vehicle.local, Vector(false, false))
  }

  // ── every language ────────────────────────────────────────────────────────

  Given(
    "a service {string} written in {string} with a replicated entity {string} at the installations {string} and {string}"
  ) { (_: String, language: String, _: String, a: String, b: String) =>
    assume(
      language == "Scala",
      s"the $language row is held by the conformance suite's es.replicated-context case against " +
        s"the $language reference (${LanguagesFeatures.commands.getOrElse(language, "?")}), and " +
        s"the $language SDK's own tests of an event mirrored from another installation"
    )
    names = Vector(a, b)
  }

  Then("the handler of {string} at {string} was told it is at the home of {string}") {
    (command: String, name: String, _: String) =>
      val receipt = answered[Receipt](lastCall)
      assertEquals(
        (receipt.command, receipt.installation, receipt.home),
        (command, Some(name), true)
      )
  }

  Then(
    "the event handler of {string} at {string} and at {string} was told the event was written at {string}"
  ) { (_: String, a: String, b: String, writer: String) =>
    val id  = homes.keys.head
    val atA = entity(a, id).call(kind.get).invoke()
    assertEquals(atA.writtenAt.last, Some(writer))
    assertEquals(atA.local.last, a == writer)
    val atB = eventually(s"$b holds the step")(
      Some(entity(b, id).call(kind.get).invoke()).filter(_.steps.size == atA.steps.size)
    )
    assertEquals(atB.writtenAt.last, Some(writer))
    assertEquals(atB.local.last, b == writer)
  }

final class HomeFeatures     extends ReplicationSteps("../../features/replication/home.feature")
final class CreationFeatures extends ReplicationSteps("../../features/replication/creation.feature")
final class ForwardingFeatures
    extends ReplicationSteps("../../features/replication/forwarding.feature")
final class HandoverFeatures extends ReplicationSteps("../../features/replication/handover.feature")
final class ReplicationSetFeatures
    extends ReplicationSteps("../../features/replication/replication-set.feature")
final class ProjectionsFeatures
    extends ReplicationSteps("../../features/replication/projections.feature")
final class TransportFeatures
    extends ReplicationSteps("../../features/replication/transport.feature")
final class HistoryFeatures extends ReplicationSteps("../../features/replication/history.feature")
final class TestkitFeatures extends ReplicationSteps("../../features/replication/testkit.feature")
object LanguagesFeatures:
  /** The command that runs the conformance suite against each language's reference. */
  val commands: Map[String, String] = Map(
    "Python"     -> "cd sdks/python && uv run conformance",
    "TypeScript" -> "cd sdks/typescript && npm run conformance",
    "Rust"       -> "cd sdks/rust && ./conformance.sh",
    "Java" ->
      "modules/sdk-java: sbt -Dankka.conformance.reference=java 'sidecar/testOnly *.ConformanceSuite'"
  )

final class LanguagesFeatures
    extends ReplicationSteps("../../features/replication/languages.feature"):
  override protected def ranElsewhere: Map[String, String] = Map(
    "a process declaring replication beside an older runtime is refused naming the protocol version" ->
      ("the sidecar's ProtocolSuite (sidecar/src/test/scala/.../sidecar/ProtocolSuite.scala): " +
        "replication refused under 1.19, and the version the runtime states, now 1.22, which " +
        "adds who acted and where a write was forwarded from; each language's es.attribution " +
        "conformance case holds those fields (" + LanguagesFeatures.commands.values.mkString("; ") +
        ")")
  )
