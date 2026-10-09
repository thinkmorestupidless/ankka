package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.runtime.*
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import com.thinkmorestupidless.ankka.sdk.*
import org.apache.pekko.Done

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/**
 * Rebuilding a topic source by its version: `features/topics/versions.feature`.
 *
 * One service on one database and one in-memory broker, whose view and consumer are declared at the
 * version `Versions` says when it starts, so a restart can declare another. Each row says which
 * version of the handler wrote it, which is what "written at version N" is asserted on. Cases run
 * in order and each starts from where the last left the service.
 */
class ViewVersionSuite extends munit.FunSuite with LogCapturing:

  import ViewVersionSuite.*

  override val munitTimeout = 5.minutes

  private val broker            = InMemoryBroker()
  private val subscriber        = FlakyRetained(broker)
  private val log               = LogLines()
  private var kit: AnkkaTestKit = null

  private def publish(n: Int): Unit =
    val _ = broker.publish(
      Topic,
      serializer.toBytes(StockEvent(s"m$n", 1, "w1")),
      Metadata.empty.withSubject(s"m$n")
    )

  private def runtime() = ProjectionRuntime.withBroker(broker, subscriber)

  override def beforeAll(): Unit =
    // The earliest message the broker holds, at a time a rebuild's log line can be checked for.
    broker.setClock(() => Earliest)
    publish(1)
    broker.setClock(() => Instant.now())
    (2 to 50).foreach(publish)
    log.start()
    kit = AnkkaTestKit.start(
      Seq.empty,
      Seq(runtime()),
      configure = _.registerAll(Versions.descriptors()),
      serviceIdentity = ServiceIdentity.local("versions")
    )
    holds(50, version = 1)

  override def afterAll(): Unit =
    log.stop()
    if kit != null then kit.stop()

  private given ExecutionContext = ExecutionContext.global

  private def database(service: AnkkaService = kit.service) = Database()(using service.system)

  private def rows(view: String = "versioned"): Vector[VersionedRow] =
    kit.service.viewClient.forView(Versions.view(view, StartFrom.Earliest)).all()

  private def recorded(view: String = "versioned"): Option[Int] =
    Await.result(
      database().queryOne(
        SqlFragment.raw("SELECT version FROM ankka_view_versions WHERE component_id = ") ++
          sql"$view"
      )(_.get("version", classOf[Integer]).intValue),
      10.seconds
    )

  private def eventually(what: String, within: FiniteDuration = 30.seconds)(
      check: => Boolean
  ): Unit =
    val deadline = System.nanoTime() + within.toNanos
    while !check && System.nanoTime() < deadline do Thread.sleep(100)
    if !check then fail(s"$what did not happen within $within")

  /** The view holds `count` rows, every one written at `version`, and stays that way. */
  private def holds(count: Int, version: Int, view: String = "versioned"): Unit =
    eventually(s"$view holds $count rows at version $version") {
      val now = rows(view)
      now.size == count && now.forall(_.version == version)
    }
    Thread.sleep(500)
    val now = rows(view)
    assertEquals(now.size, count)
    assertEquals(now.map(_.version).distinct, if count == 0 then Vector.empty else Vector(version))

  private def restartAt(view: Int, consumer: Int = Versions.consumerVersion): Unit =
    Versions.viewVersion = view
    Versions.consumerVersion = consumer
    kit.restartService()

  private def status(service: AnkkaService = kit.service, id: String = "versioned") =
    TopicSources(service.system).all.find(_.componentId == id).get

  test("a view restarted at the same version is not rebuilt") {
    val handled = Versions.handled.get
    restartAt(1)
    Thread.sleep(1500)
    assertEquals(Versions.handled.get, handled, "no message was delivered again")
    holds(50, version = 1)
  }

  test("a view with no recorded version is taken to be at version 1") {
    Await.result(
      database().execute(sql"DELETE FROM ankka_view_versions WHERE component_id = ${"versioned"}"),
      10.seconds
    ): Unit
    val handled = Versions.handled.get
    restartAt(1)
    eventually("the version is recorded again")(recorded() == Some(1))
    Thread.sleep(1000)
    assertEquals(Versions.handled.get, handled)
    holds(50, version = 1)
  }

  test("a view at a higher version is rebuilt from every retained message") {
    val before = broker.positions(Topic)
    restartAt(2, consumer = 2)
    holds(50, version = 2)
    assertEquals(recorded(), Some(2))
    val groups = broker.positions(Topic)
    assert(groups.contains("ankka.local.versions.view-v2.versioned"), groups.toString)
    assertEquals(
      groups("ankka.local.versions.view.versioned"),
      before("ankka.local.versions.view.versioned"),
      "the group it read under at version 1 is left as it was"
    )
  }

  test("a rebuild says how far back the broker retains before it removes a row") {
    val line = log.containing("view rebuild: component=versioned ")
    assertEquals(line.size, 1, log.containing("view rebuild").mkString("\n"))
    assert(line.head.contains("from version=1 to version=2"), line.head)
    assert(line.head.contains(s"0=$Earliest"), line.head)
  }

  test("a view rebuilt from the latest message is empty until a message is published") {
    holds(0, version = 2, view = "versioned-latest")
  }

  test("a consumer at a higher version is delivered every retained message again") {
    eventually("the consumer has read everything again at version 2") {
      Versions.consumed.asScala.count(_._1 == 2) == 50
    }
    assertEquals(Versions.consumed.asScala.count(_._1 == 1), 50)
    assert(broker.positions(Topic).contains("ankka.local.versions.consumer-v2.consumed"))
  }

  test("a service rolled back to a lower version leaves the view as it is") {
    restartAt(1)
    eventually("the instance knows it is behind")(status().behind)
    publish(51)
    Thread.sleep(1500)
    holds(50, version = 2)
    assertEquals(status().recordedVersion, Some(2))
    val warned = log.containing("view behind its recorded version: component=versioned ", "WARN")
    assert(warned.exists(_.contains("declared=1 recorded=2")), warned.mkString("\n"))
  }

  test(
    "during a rolling update the higher version rebuilds the view once and the lower stops writing"
  ) {
    restartAt(2)
    holds(51, version = 2)
    Versions.viewVersion = 3
    val peer = kit.startPeer(Seq(runtime()))
    try
      eventually("the new instance has rebuilt the view")(recorded() == Some(3))
      (52 to 61).foreach(publish)
      eventually("the old instance has stopped writing")(status().behind)
      holds(61, version = 3)
      assertEquals(status().recordedVersion, Some(3))
    finally peer.stop()
  }

  test("instances starting together at a higher version rebuild the view once") {
    // Two new instances at once, beside the one still running at version 2, which is behind.
    def emptied =
      log.containing("view emptied for rebuild: component=versioned from version=3 to version=4")
    Versions.viewVersion = 4
    val first   = Future(kit.startPeer(Seq(runtime())))
    val second  = Future(kit.startPeer(Seq(runtime())))
    val started = Await.result(first.zip(second), 2.minutes)
    try
      holds(61, version = 4)
      // Whichever came second found the version already recorded, at the lock or before it.
      assertEquals(emptied.size, 1, log.containing("component=versioned").mkString("\n"))
    finally
      started._1.stop()
      started._2.stop()
  }

  test("a view is not emptied until the broker says what it holds") {
    // Both views ask; each is refused at least once, and waits a second before asking again.
    subscriber.failNext(4)
    Versions.viewVersion = 5
    kit.restartService()
    Thread.sleep(800)
    assertEquals(recorded(), Some(4))
    assertEquals(rows().size, 61)
    holds(61, version = 5)
    val waited = log.containing("view 'versioned' waits to rebuild", "WARN")
    assert(waited.nonEmpty, log.lines("WARN").mkString("\n"))
  }

  test("a write at a lower version never survives a rebuild, however the two interleave") {
    // Driven directly, many times: a writer at version 1 against a rebuild to version 2. With the
    // shared lock taken out of the write, a write that read version 1 just before the rebuild
    // committed lands just after it, and this finds the row.
    val db    = database()
    val table = ViewDescriptor.tableFor(ComponentId("race"))
    Await.result(
      db.executeAll(
        Seq(ViewStore.createTable(table), ViewVersions.createTable, ViewVersions.ensure("race"))
      ),
      10.seconds
    )
    val survivors = (1 to 200).flatMap { round =>
      Await.result(
        db.executeAll(
          Seq(
            SqlFragment.raw(s"TRUNCATE $table"),
            sql"UPDATE ankka_view_versions SET version = 1 WHERE component_id = ${"race"}"
          )
        ),
        10.seconds
      )
      val guard = ViewGuard(db, "race", 1, _ => ())
      val writes = Future.traverse(1 to 3)(n =>
        guard.write(ViewStore.upsert(table, s"r$round-$n", "{}")).recover(_ => Done)
      )
      val rebuild = ViewVersions.rebuild(db, table, "race", 2)
      Await.result(writes.zip(rebuild), 30.seconds): Unit
      Await.result(
        db.query(SqlFragment.raw(s"SELECT row_key FROM $table"))(_.get("row_key", classOf[String])),
        10.seconds
      )
    }
    assertEquals(survivors.toVector, Vector.empty)
  }

object ViewVersionSuite:

  val Topic: String     = "versioned-changes"
  val Earliest: Instant = Instant.parse("2026-09-01T00:00:00Z")

  val serializer: Serializer[StockEvent] = Codecs.serializer[StockEvent]("stock-event")

  /** A row, saying which version of the view's handler wrote it. */
  final case class VersionedRow(subject: String, version: Int)

  final class VersionedView(version: Int) extends View[StockEvent, VersionedRow]:
    def onChange(event: StockEvent): Effect =
      Versions.handled.incrementAndGet(): Unit
      effects.updateRow(VersionedRow(updateContext.subject, version))

  final class Consumed(version: Int) extends Consumer[StockEvent, Nothing]:
    def onMessage(event: StockEvent): Effect =
      Versions.consumed.add(version -> event.sku): Unit
      effects.ignore()

  /** The versions the next start declares, and what the handlers saw. */
  object Versions:
    @volatile var viewVersion: Int     = 1
    @volatile var consumerVersion: Int = 1
    val handled: AtomicInteger         = AtomicInteger()
    val consumed: ConcurrentLinkedQueue[(Int, String)] =
      ConcurrentLinkedQueue[(Int, String)]()

    def view(
        id: String,
        start: StartFrom
    ): View.Companion[VersionedView, StockEvent, VersionedRow] =
      val declared = viewVersion
      new View.Companion[VersionedView, StockEvent, VersionedRow](
        ComponentId(id),
        ChangeSource.Topic(Topic, serializer, Some(start)),
        Codecs.serializer[VersionedRow]("versioned-row")
      ):
        override def version                  = Some(declared)
        def create(ctx: ViewComponentContext) = new VersionedView(declared)

    def consumerCompanion(): Consumer.Companion[Consumed, StockEvent, Nothing] =
      val declared = consumerVersion
      new Consumer.Companion[Consumed, StockEvent, Nothing](
        ComponentId("consumed"),
        ChangeSource.Topic(Topic, serializer, Some(StartFrom.Earliest))
      ):
        override def version             = Some(declared)
        def create(ctx: ConsumerContext) = new Consumed(declared)

    /** What a start registers: read now, so each restart declares what this object says then. */
    def descriptors(): Seq[com.thinkmorestupidless.ankka.core.ComponentDescriptor] =
      Seq(
        view("versioned", StartFrom.Earliest).descriptor,
        view("versioned-latest", StartFrom.Latest).descriptor,
        consumerCompanion().descriptor
      )

  /** A broker that cannot say what it holds, the next `n` times it is asked. */
  final class FlakyRetained(inner: InMemoryBroker) extends MessageSubscriber:
    private val failures       = AtomicInteger()
    def failNext(n: Int): Unit = failures.set(n)
    def subscribe(
        subscription: TopicSubscription,
        handle: IncomingMessage => Future[Done]
    ): Subscribed = inner.subscribe(subscription, handle)
    def earliestRetained(topic: String): Future[Map[Int, Retained]] =
      if failures.getAndUpdate(n => (n - 1).max(0)) > 0 then
        Future.failed(RuntimeException("the broker is not answering"))
      else inner.earliestRetained(topic)
    override def topicConfig(topic: String): Future[Option[TopicConfig]] = inner.topicConfig(topic)
    def stop(): Unit                                                     = inner.stop()
