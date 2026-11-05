package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{DataSubject, KeyAnswer}
import com.thinkmorestupidless.ankka.core.keyring.{KeyringAck, KeyringNotice}
import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.keyring.{
  ErasureDuties,
  ErasureHooks,
  ErasureReplay,
  KeyStoreClient,
  KeyringClient,
  KeyringSettings
}

import java.time.Instant
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*

/**
 * Feature 042, T055: a service on a database line the erasure log has not been applied to is not
 * ready, as `erasure-replay`, until the keyring's backlog has been handed over and every notice in
 * it carried out; the line is then recorded, and a later start on it does not wait.
 */
final class ErasureReplaySuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var kit: Option[AnkkaTestKit] = None

  override def beforeAll(): Unit = kit = Some(AnkkaTestKit.start(Seq.empty))
  override def afterAll(): Unit  = kit.foreach(_.stop())

  private def database: Database =
    Database()(using kit.getOrElse(fail("no kit")).service.system)

  private given ExecutionContext = ExecutionContext.global

  /** A keyring whose backlog ends when the test says, holding the notices it is given. */
  private final class Store(backlog: Vector[KeyringNotice]) extends KeyStoreClient:
    val ended                                                           = Promise[Unit]()
    @volatile var helloAfter: Option[Long]                              = None
    def key(project: String, subject: DataSubject, create: Boolean)     = KeyAnswer.Absent
    def lookupKey(project: String): IArray[Byte]                        = IArray.fill[Byte](32)(0)
    def subscribe(after: Long)(l: KeyringNotice => Unit): AutoCloseable =
      helloAfter = Some(after)
      backlog.filter(_.seq > after).foreach(l)
      () => ()
    def confirmedAt(): Option[Instant] = Some(Instant.now())
    def caughtUp: Future[Unit]         = ended.future
    def ack(ack: KeyringAck): Unit     = ()

  private val settings = KeyringSettings(
    address = None,
    maxKeys = 100,
    ttl = 5.minutes,
    outageBound = 60.seconds,
    noticeBound = 60.seconds,
    fetchTimeout = 1.second
  )

  /** A replay over `store` whose duties' session hook finishes when `sessions` does. */
  private def replay(line: String, store: Store, sessions: Future[Long] = Future.successful(0L)) =
    val client = KeyringClient(store, settings)
    client.start()
    val hooks = ErasureHooks()
    hooks.onSessions(_ => sessions)
    val duties = ErasureDuties("players", "players-0", client, () => database, Vector.empty, hooks)
    duties.start()
    ErasureReplay(() => database, line, "brand", client, duties, every = 20.millis)

  private def recordedLines: Vector[String] =
    Await.result(
      database.query(SqlFragment.raw("SELECT line FROM ankka_erasure_replay ORDER BY line"))(
        _.get("line", classOf[String])
      ),
      30.seconds
    )

  test("not ready until the backlog is handed over and every notice in it is carried out") {
    val store    = Store(Vector(KeyringNotice(1, "brand", "player/8c1f", rerun = false)))
    val sessions = Promise[Long]()
    val r        = replay("restored-1", store, sessions.future)
    val started  = r.start()
    assert(r.readiness().left.exists(_.startsWith(ErasureReplay.Name)), r.readiness().toString)
    store.ended.success(())
    Thread.sleep(200)
    // Handed over, and its notice not yet carried out: still not ready.
    assert(r.readiness().isLeft, "ready with a notice still being carried out")
    assertEquals(r.replayedAt, None)
    assert(!recordedLines.contains("restored-1"))
    sessions.success(0L)
    val at = Await.result(started, 30.seconds)
    assertEquals(r.readiness(), Right(()))
    assertEquals(r.replayedAt, Some(at))
    assert(recordedLines.contains("restored-1"))
    assert(r.json.contains(s""""replayedAt":"$at""""), r.json)
  }

  test("a line that has had the log applied does not wait for the keyring again") {
    Await.result(
      replay("restored-2", { val s = Store(Vector.empty); s.ended.success(()); s }).start(),
      30.seconds
    ): Unit
    // A keyring that never says its backlog ended: the record is enough.
    val again = replay("restored-2", Store(Vector.empty))
    Await.result(again.start(), 30.seconds): Unit
    assertEquals(again.readiness(), Right(()))
  }

  test(
    "a start says hello from what its line records as carried out, never past a failed notice"
  ) {
    val line   = "carried-1"
    val notice = (seq: Long) => KeyringNotice(seq, "brand", s"player/$seq", rerun = false)
    // Notice 9's duties fail until the test lets them succeed.
    @volatile var nineFails             = true
    def start(store: Store, from: Long) =
      val client = KeyringClient(store, settings)
      client.start(from)
      val hooks = ErasureHooks()
      hooks.onSessions(n =>
        if n.seq == 9 && nineFails then Future.failed(IllegalStateException("a duty failed"))
        else Future.successful(0L)
      )
      val duties = ErasureDuties(
        "players",
        "players-0",
        client,
        () => database,
        Vector.empty,
        hooks,
        Some(line),
        from
      )
      duties.start()
      (
        client,
        duties,
        ErasureReplay(() => database, line, "brand", client, duties, every = 20.millis)
      )
    def mark                       = ErasureReplay.carriedThrough(() => database, line)
    def eventually(expected: Long) =
      val deadline = System.nanoTime() + 10.seconds.toNanos
      while mark != expected && System.nanoTime() < deadline do Thread.sleep(20)
      assertEquals(mark, expected)

    assertEquals(mark, 0L, "a line with no record is asked for everything")
    val first = Store(Vector(notice(5)))
    first.ended.success(())
    val (_, duties, r) = start(first, mark)
    assertEquals(first.helloAfter, Some(0L))
    Await.result(r.start(), 30.seconds): Unit
    eventually(5L)
    // 9 fails, 12 succeeds: the mark stays at 5, so 9 is handed again on the next hello.
    Await.ready(duties.run(notice(9)), 30.seconds): Unit
    Await.result(duties.run(notice(12)), 30.seconds): Unit
    Thread.sleep(200)
    assertEquals(mark, 5L, "a failed notice holds the mark back")
    // A start now says hello after 5, and is handed 9 again.
    val second = Store(Vector(notice(5), notice(9), notice(12)))
    second.ended.success(())
    nineFails = false
    val (client2, _, again) = start(second, mark)
    assertEquals(second.helloAfter, Some(5L))
    Await.result(again.start(), 30.seconds): Unit
    eventually(12L)
    assertEquals(client2.lastNotice, 12L)
    // And a start after that is handed nothing it carried out.
    val third = Store(Vector(notice(5), notice(9), notice(12)))
    start(third, mark)
    assertEquals(third.helloAfter, Some(12L))
  }

  test("another line, as a restore is, waits again") {
    val r = replay("restored-3", Store(Vector.empty))
    r.start(): Unit
    Thread.sleep(300)
    assert(r.readiness().isLeft, "a new line was ready without the keyring's backlog")
    assertEquals(r.json, """{"line":"restored-3"}""")
  }
