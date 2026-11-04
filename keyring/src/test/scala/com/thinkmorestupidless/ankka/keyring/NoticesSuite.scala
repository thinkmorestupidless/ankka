package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.core.keyring.*
import com.thinkmorestupidless.ankka.runtime.Database
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Feature 042, T050: the notices, the backlog after a reconnect, and when a service is complete.
 */
class NoticesSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var kit: AnkkaTestKit  = null
  private given ExecutionContext = ExecutionContext.global

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(Seq.empty)
  override def afterAll(): Unit  = if kit != null then kit.stop()

  private def database: Database        = Database()(using kit.service.system)
  private def await[A](f: Future[A]): A = Await.result(f, 30.seconds)

  private final class Instance(val project: String, val service: String, val name: String):
    val heard                               = ConcurrentLinkedQueue[NoticeFrame]()
    def notices: Vector[NoticeFrame.Notice] = heard.asScala.toVector.collect {
      case n: NoticeFrame.Notice => n
    }
    def connect(n: Notices, after: Long = 0) =
      await(n.connect(NoticeFrame.Hello(project, service, name, after), f => heard.add(f): Unit))

  private def ack(c: Notices#Connection, seq: Long, completion: Completion = Completion(1, 0, 0)) =
    await(c.received(NoticeFrame.Ack(seq, c.hello.service, c.hello.instance, completion)))

  private val at = Instant.parse("2026-11-04T10:00:00Z")

  test(
    "an instance is sent each erasure as it is applied, and the backlog after its last ack on reconnect"
  ) {
    val store   = KeyStore(database)
    val notices = Notices(database, store)
    val a       = Instance("p1", "players", "players-a")
    val first   = a.connect(notices)
    await(store.destroy(101, "p1", "player/1", at)): Unit
    await(notices.publish(101, "p1", "player/1", rerun = false))
    assertEquals(a.notices.map(_.seq), Vector(101L))
    ack(first, 101)
    await(first.close())
    // Two erasures while it was away; it comes back saying it acknowledged 101.
    await(store.destroy(102, "p1", "player/2", at)): Unit
    await(notices.publish(102, "p1", "player/2", rerun = false))
    await(store.destroy(103, "p1", "player/3", at)): Unit
    await(notices.publish(103, "p1", "player/3", rerun = false))
    val back = Instance("p1", "players", "players-a")
    back.connect(notices, after = 101)
    assertEquals(
      back.notices.map(n => (n.seq, n.subject)),
      Vector(102L -> "player/2", 103L -> "player/3")
    )
    // Another project's instance hears nothing of these.
    val other = Instance("p2", "players", "other-a")
    other.connect(notices)
    assertEquals(other.notices, Vector.empty)
  }

  test("a service is complete when every instance connected at the notice has acked or is gone") {
    val store   = KeyStore(database)
    val notices = Notices(database, store)
    val a       = Instance("p3", "players", "players-a").connect(notices)
    val b       = Instance("p3", "players", "players-b").connect(notices)
    val w       = Instance("p3", "wallet", "wallet-a").connect(notices)
    await(store.destroy(201, "p3", "player/1", at)): Unit
    await(notices.publish(201, "p3", "player/1", rerun = false))
    def of(service: String) = await(notices.status(201)).services.find(_.service == service).get
    assertEquals(await(notices.status(201)).services.map(_.service), Vector("players", "wallet"))
    ack(a, 201, Completion(2, 1, 0, Some(HandlerReport(3, "immediate"))))
    assertEquals(of("players").completedAt, None, "players-b has not acknowledged")
    await(b.close()) // replaced by a rolling deploy
    val players = of("players")
    assert(players.completedAt.nonEmpty)
    assertEquals(players.completion, Some(Completion(2, 1, 0, Some(HandlerReport(3, "immediate")))))
    assertEquals(of("wallet").completedAt, None)
    ack(w, 201)
    assert(of("wallet").completedAt.nonEmpty)
  }

  test("a re-run's acknowledgement replaces the first, and an instance cannot ack as another") {
    val store   = KeyStore(database)
    val notices = Notices(database, store)
    val i       = Instance("p4", "players", "players-a")
    val c       = i.connect(notices)
    await(store.destroy(301, "p4", "player/1", at)): Unit
    await(notices.publish(301, "p4", "player/1", rerun = false))
    ack(c, 301, Completion(1, 0, 0, Some(HandlerReport(2, "immediate"))))
    await(notices.publish(301, "p4", "player/1", rerun = true))
    assertEquals(i.notices.map(n => (n.seq, n.rerun)), Vector(301L -> false, 301L -> true))
    ack(c, 301, Completion(0, 0, 0, Some(HandlerReport(0, "immediate"))))
    assertEquals(
      await(notices.status(301)).services.head.completion.flatMap(_.handler).map(_.objectsErased),
      Some(0L)
    )
    intercept[IllegalArgumentException](
      await(c.received(NoticeFrame.Ack(301, "wallet", "wallet-a", Completion.nothing)))
    )
    await(notices.heartbeat())
    assert(i.heard.asScala.exists(_.isInstanceOf[NoticeFrame.Heartbeat]))
  }

  test(
    "a notice published by one replica of the keyring reaches an instance connected to another"
  ) {
    val store = KeyStore(database)
    val a     = Notices(database, store, replica = "keyring-0")
    val b     = Notices(database, store, replica = "keyring-1")
    val i     = Instance("p5", "players", "players-a")
    val c     = i.connect(b)
    await(store.destroy(401, "p5", "player/1", at)): Unit
    await(a.publish(401, "p5", "player/1", rerun = false))
    assertEquals(i.notices, Vector.empty, "b has not polled yet")
    await(b.poll())
    assertEquals(i.notices.map(_.seq), Vector(401L))
    // Asked of the replica it is not connected to, the status still waits for it.
    assertEquals(await(a.status(401)).services.head.completedAt, None)
    ack(c, 401)
    assert(await(a.status(401)).services.head.completedAt.nonEmpty)
    await(b.poll())
    assertEquals(i.notices.size, 1, "a notice is sent once by each poll")
  }
