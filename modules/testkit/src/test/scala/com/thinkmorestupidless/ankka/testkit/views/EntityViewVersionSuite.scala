package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.{Database, ProjectionRuntime, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing, LogLines}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * Rebuilding a view that reads entities: `features/views/rebuilding.feature`, a case named for each
 * scenario, on 024's harness — one service on one database, its views declared at the versions
 * `EntityVersions` says when an instance starts, so a restart or a new instance can declare
 * another. Every row says which version of its handler wrote it, which is what "written at version
 * N" is asserted on. Cases run in order and each starts from where the last left the service.
 */
class EntityViewVersionSuite extends munit.FunSuite with LogCapturing:

  import EntityViewVersionSuite.*

  override val munitTimeout = 10.minutes

  private given ExecutionContext = ExecutionContext.global

  private val log               = LogLines()
  private var kit: AnkkaTestKit = null

  private def runtime() = ProjectionRuntime()

  override def beforeAll(): Unit =
    log.start()
    // Registered at every start, so each start declares the versions `EntityVersions` says then.
    kit = AnkkaTestKit.start(
      Seq.empty,
      Seq(runtime()),
      configure = _.registerAll(
        Seq(
          Shipment.descriptor,
          Customer.descriptor,
          Rebuilt.descriptor,
          RebuiltPlain.descriptor,
          Unversioned.descriptor
        )
      )
    )
    Seq("s1", "s2", "s3").foreach(id => record(Shipment, id))
    Seq("c1", "c2").foreach(id => record(Customer, id))
    holds("rebuilt", 5, version = 1)
    holds("rebuilt-plain", 2, version = 1)
    holds("unversioned", 2, version = 1)

  override def afterAll(): Unit =
    log.stop()
    if kit != null then kit.stop()

  private def record(entity: ScriptedCompanion, id: String, client: ComponentClient = null): Unit =
    val via = Option(client).getOrElse(kit.componentClient)
    via.forEventSourcedEntity(EntityId(id)).call(entity.record).invoke("versioned"): Unit

  private def rows(view: String): Vector[VersionRow] =
    val database = Database()(using kit.service.system)
    Await.result(
      database.query(
        SqlFragment.raw(s"SELECT payload FROM ${ViewDescriptor.tableFor(ComponentId(view))}")
      )(r => VersionRow.serializer.fromBytes(r.get("payload", classOf[String]).getBytes("UTF-8"))),
      10.seconds
    )

  private def recorded(view: String): Option[Int] =
    Await.result(
      Database()(using kit.service.system).queryOne(
        SqlFragment.raw(
          "SELECT version FROM ankka_view_versions WHERE component_id = "
        ) ++ sql"$view"
      )(_.get("version", classOf[Integer]).intValue),
      10.seconds
    )

  private def eventually(what: String, within: FiniteDuration = 60.seconds)(
      check: => Boolean
  ): Unit =
    val deadline = System.nanoTime() + within.toNanos
    while !check && System.nanoTime() < deadline do Thread.sleep(100)
    if !check then fail(s"$what did not happen within $within")

  /** The view holds `count` rows, every one written at `version`, and stays that way. */
  private def holds(view: String, count: Int, version: Int): Unit =
    eventually(s"$view holds $count rows at version $version") {
      val now = rows(view)
      now.size == count && now.forall(_.version == version)
    }
    Thread.sleep(500)
    val now = rows(view)
    assertEquals(now.size, count, now.toString)
    assertEquals(now.map(_.version).distinct, Vector(version))

  /** How many changes each row has been written for: what "reads no event again" is read off. */
  private def counts(view: String): Map[String, Int] = rows(view).map(r => r.key -> r.count).toMap

  private def restartAt(keyed: Int, plain: Option[Int] = EntityVersions.plain): Unit =
    EntityVersions.keyed = keyed
    EntityVersions.plain = plain
    kit.restartService()

  test("a view of several sources at a higher version is rebuilt from every source") {
    restartAt(2)
    holds("rebuilt", 5, version = 2)
    assertEquals(recorded("rebuilt"), Some(2))
    // Every source read again from its beginning: each row written once, at version 2.
    assertEquals(counts("rebuilt").values.toSet, Set(1))
  }

  test("a view of one source at a higher version is rebuilt") {
    restartAt(2, plain = Some(2))
    holds("rebuilt-plain", 2, version = 2)
    assertEquals(recorded("rebuilt-plain"), Some(2))
  }

  test("a view restarted at the same version is not rebuilt") {
    val before = counts("rebuilt")
    restartAt(2, plain = Some(2))
    Thread.sleep(3000)
    assertEquals(counts("rebuilt"), before)
    holds("rebuilt", 5, version = 2)
  }

  test("a view that declares no version is never rebuilt") {
    val before = counts("unversioned")
    assertEquals(before.size, 2, "the unversioned view's rows")
    restartAt(2, plain = Some(2))
    Thread.sleep(3000)
    assertEquals(counts("unversioned"), before)
    assertEquals(recorded("unversioned"), Some(1))
  }

  test("instances starting together at a higher version rebuild a view that reads entities once") {
    def emptied =
      log.containing("view emptied for rebuild: component=rebuilt from version=2 to version=3")
    EntityVersions.keyed = 3
    val first   = Future(kit.startPeer(Seq(runtime())))
    val second  = Future(kit.startPeer(Seq(runtime())))
    val started = Await.result(first.zip(second), 2.minutes)
    try
      eventually("the view emptied")(emptied.nonEmpty)
      // While the instance at version 2 runs, a slice it holds is refused by its guard; whatever
      // the view holds is written at version 3.
      // A slice the instance at version 2 holds waits, paused, until it leaves, so the view may hold
      // nothing yet; whatever it holds was written at version 3.
      Thread.sleep(3000)
      assertEquals(rows("rebuilt").map(_.version).distinct.filter(_ != 3), Vector.empty)
    finally
      started._1.stop()
      started._2.stop()
    // The roll complete: every instance at version 3, every row rebuilt, and emptied once.
    restartAt(3)
    holds("rebuilt", 5, version = 3)
    assertEquals(emptied.size, 1, log.containing("component=rebuilt").mkString("\n"))
  }

  test(
    "during a rolling update the instance at the lower version stops writing a view that reads entities"
  ) {
    EntityVersions.keyed = 4
    val peer = kit.startPeer(Seq(runtime()))
    try
      eventually("the new instance has rebuilt the view")(recorded("rebuilt") == Some(4))
      // One more event during the roll: no instance at version 3 writes for it, or for anything.
      record(Shipment, "s4", peer.componentClient)
      Thread.sleep(3000)
      assertEquals(rows("rebuilt").map(_.version).distinct.filter(_ != 4), Vector.empty)
    finally peer.stop()
    // The roll complete: every row, the new event's included, written at version 4.
    restartAt(4)
    holds("rebuilt", 6, version = 4)
  }

  test("a service rolled back to a lower version leaves a view that reads entities as it is") {
    val before = rows("rebuilt").sortBy(_.key)
    restartAt(3)
    Thread.sleep(3000)
    assertEquals(rows("rebuilt").sortBy(_.key), before)
    assertEquals(recorded("rebuilt"), Some(4))
    record(Shipment, "s5")
    Thread.sleep(3000)
    assertEquals(
      rows("rebuilt").map(_.key).toSet,
      before.map(_.key).toSet,
      "the instance behind wrote"
    )
    assert(
      log
        .containing(
          "view behind its recorded version: component=rebuilt declared=3 recorded=4",
          "WARN"
        )
        .nonEmpty
    )
    // The service is ready: it answers.
    assertEquals(kit.service.viewClient.forView(Rebuilt).get("s1").map(_.version), Some(4))
  }

  test("during a rolling update the instance at the lower version stops writing a plain view") {
    // A plain view's slices are spread across the instances, so the one at the lower version holds
    // some of them while the roll lasts; enough new entities that some of their changes reach it.
    val behind = EntityVersions.plain.getOrElse(1)
    EntityVersions.keyed = 4
    EntityVersions.plain = Some(behind + 1)
    val more = (1 to 32).map(n => s"c-roll-$n")
    val peer = kit.startPeer(Seq(runtime()))
    try
      eventually("the new instance has rebuilt the plain view")(
        recorded("rebuilt-plain") == Some(behind + 1)
      )
      more.foreach(id => record(Customer, id, peer.componentClient))
      Thread.sleep(3000)
      assertEquals(
        rows("rebuilt-plain").map(_.version).distinct.filter(_ != behind + 1),
        Vector.empty,
        "a row written by the instance at the lower version"
      )
    finally peer.stop()
    restartAt(4)
    holds("rebuilt-plain", 2 + more.size, version = behind + 1)
  }

object EntityViewVersionSuite:

  /** The versions each view declares when an instance of the service starts. */
  object EntityVersions:
    @volatile var keyed: Int         = 1
    @volatile var plain: Option[Int] = Some(1)

  final case class VersionRow(key: String, version: Int, count: Int)

  object VersionRow:
    val serializer: Serializer[VersionRow] = Codecs.serializer[VersionRow]("version-row")

  /** Writes the row of the entity a change is about, at the version this instance declared. */
  final class RebuiltView(version: Int) extends KeyedView[VersionRow]:
    def touch(@scala.annotation.unused event: Recorded, change: Change): Effect =
      val count = change.rows.get(change.subject).fold(0)(_.count)
      effects.updateRow(change.subject, VersionRow(change.subject, version, count + 1))

  object Rebuilt
      extends KeyedView.Companion[RebuiltView, VersionRow](
        ComponentId("rebuilt"),
        VersionRow.serializer
      ):
    val shipments                         = source(ChangeSource.eventsOf(Shipment))(_.touch)
    val customers                         = source(ChangeSource.eventsOf(Customer))(_.touch)
    override def version                  = Some(EntityVersions.keyed)
    def create(ctx: ViewComponentContext) = new RebuiltView(EntityVersions.keyed)

  final class PlainView(version: Int) extends View[Recorded, VersionRow]:
    def onChange(event: Recorded): Effect =
      val count = rowState.fold(0)(_.count)
      effects.updateRow(VersionRow(updateContext.subject, version, count + 1))

  object RebuiltPlain
      extends View.Companion[PlainView, Recorded, VersionRow](
        ComponentId("rebuilt-plain"),
        ChangeSource.eventsOf(Customer),
        VersionRow.serializer
      ):
    override def version                  = EntityVersions.plain
    def create(ctx: ViewComponentContext) = new PlainView(EntityVersions.plain.getOrElse(1))

  /** A plain view declaring no version at all. */
  object Unversioned
      extends View.Companion[PlainView, Recorded, VersionRow](
        ComponentId("unversioned"),
        ChangeSource.eventsOf(Customer),
        VersionRow.serializer
      ):
    def create(ctx: ViewComponentContext) = new PlainView(1)
