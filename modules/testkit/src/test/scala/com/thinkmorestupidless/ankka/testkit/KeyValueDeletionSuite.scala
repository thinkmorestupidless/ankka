package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Done, EntityId}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * The deletion of a key value entity is a recorded change.
 *
 * It empties the entity, leaves its id usable and its revision counting, and is delivered to every
 * view and consumer over the entity at the revision after its last update — as an event sourced
 * entity's deletion is a journalled record with a sequence number of its own.
 */
class KeyValueDeletionSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(ProfileEntity.descriptor, ProfileRows.descriptor, ProfileWatcher.descriptor),
      Seq(ProjectionRuntime())
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def profile(id: String) = testKit.componentClient.forKeyValueEntity(EntityId(id))
  private def rows                = testKit.service.viewClient.forView(ProfileRows)

  private def register(id: String, name: String): Unit =
    assertEquals(
      profile(id).call(ProfileEntity.register).invoke(Profile(name, s"$name@example.com", 0)),
      Done
    )
  private def close(id: String): Unit =
    assertEquals(profile(id).call(ProfileEntity.close).invoke(), Done)
  private def revision(id: String): Long = profile(id).call(ProfileEntity.revision).invoke()

  private def seen(id: String): Vector[ProfileWatcher.Seen] =
    ProfileWatcher.seen.asScala.toVector.filter(_.subject == id)

  private def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  test("a deleted entity reads as empty") {
    register("kv-read", "Ada")
    close("kv-read")
    assertEquals(profile("kv-read").call(ProfileEntity.get).invoke(), Profile("", "", 0))
  }

  test("a write after a deletion succeeds, in the same incarnation of the entity") {
    register("kv-same", "Ada")
    close("kv-same")
    register("kv-same", "Grace")
    assertEquals(profile("kv-same").call(ProfileEntity.get).invoke().name, "Grace")
  }

  test("the revision goes on counting through a deletion and the write after it") {
    register("kv-revision", "Ada")
    val registered = revision("kv-revision")
    assert(registered >= 1, s"registered at $registered")
    close("kv-revision")
    val deleted = revision("kv-revision")
    assertEquals(deleted, registered + 1, "the deletion is the next revision")
    register("kv-revision", "Grace")
    assertEquals(revision("kv-revision"), deleted + 1, "and the write after it the one after")
  }

  test("a view's row is removed when the entity is deleted") {
    register("kv-row", "Ada")
    val row = eventually("the row appears")(rows.get("kv-row"))
    assertEquals(row.name, "Ada")
    close("kv-row")
    eventually("the row is removed")(if rows.get("kv-row").isEmpty then Some(()) else None)
  }

  test("a consumer is handed the revision of each state, never zero") {
    register("kv-sequence", "Ada")
    val first = eventually("the state arrives")(seen("kv-sequence").headOption)
    assertEquals(first.sequenceNumber, revision("kv-sequence"))
    assert(first.sequenceNumber >= 1, first.toString)
    assertEquals(profile("kv-sequence").call(ProfileEntity.recordLogin).invoke(), 1)
    val later = eventually("the later state arrives")(
      seen("kv-sequence").find(_.profile.exists(_.logins == 1))
    )
    assertEquals(later.sequenceNumber, revision("kv-sequence"))
    assert(later.sequenceNumber > first.sequenceNumber, s"$first then $later")
  }

  test("a consumer's deletion handler runs, at the deletion's revision") {
    register("kv-told", "Ada")
    eventually("the state arrives")(seen("kv-told").headOption)
    close("kv-told")
    val deletion = eventually("the deletion arrives")(seen("kv-told").find(_.profile.isEmpty))
    assertEquals(deletion.sequenceNumber, revision("kv-told"))
    assert(
      seen("kv-told").filter(_.profile.nonEmpty).forall(_.sequenceNumber < deletion.sequenceNumber),
      seen("kv-told").toString
    )
  }

  test("an entity created again is seen above its deletion") {
    register("kv-again", "Ada")
    close("kv-again")
    val deletion = eventually("the deletion arrives")(seen("kv-again").find(_.profile.isEmpty))
    register("kv-again", "Grace")
    val again = eventually("the new state arrives")(
      seen("kv-again").find(_.profile.exists(_.name == "Grace"))
    )
    assert(again.sequenceNumber > deletion.sequenceNumber, s"$deletion then $again")
    assertEquals(eventually("the row returns")(rows.get("kv-again")).name, "Grace")
  }

  // Last: it restarts the service the cases above share.
  test(
    "a write after a deletion succeeds across a restart, and the revision has not started again"
  ) {
    register("kv-restart", "Ada")
    val _ = profile("kv-restart").call(ProfileEntity.recordLogin).invoke()
    close("kv-restart")
    val deleted = revision("kv-restart")

    testKit.restartService()

    assertEquals(profile("kv-restart").call(ProfileEntity.get).invoke(), Profile("", "", 0))
    assertEquals(revision("kv-restart"), deleted, "the deletion's revision survives the restart")
    register("kv-restart", "Grace")
    assertEquals(revision("kv-restart"), deleted + 1)
    assertEquals(profile("kv-restart").call(ProfileEntity.get).invoke().name, "Grace")
  }
