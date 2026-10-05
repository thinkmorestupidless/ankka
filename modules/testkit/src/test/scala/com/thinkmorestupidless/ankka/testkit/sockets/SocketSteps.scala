package com.thinkmorestupidless.ankka.testkit.sockets

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.{Observability, RecordedSpan}
import com.thinkmorestupidless.ankka.testkit.*
import com.typesafe.config.ConfigFactory

import java.lang.management.ManagementFactory
import java.net.http.HttpClient
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The steps of `features/sockets/frames.feature` and `traces.feature`, over one service on loopback
 * whose `/stream` handler does what the scenario's `Given` says.
 *
 * The limits are small and the server's idle timeout short, as `SocketSuite` sets them, so "longer
 * than the platform leaves a socket quiet" is seconds.
 */
abstract class SocketSteps(features: String) extends GherkinSuite(features) with LogCapturing:

  override val munitTimeout = 3.minutes

  private val Limits = Map(
    "ankka.http.socket.max-frame-size" -> "1KiB",
    "ankka.http.socket.unread-frames"  -> "16",
    "ankka.http.socket.keep-alive"     -> "1s",
    "pekko.http.server.idle-timeout"   -> "3s"
  )

  /** What the `/stream` handler does, as the scenario's `Given` says. */
  private enum Behaviour:
    case Echo
    case FinishAfter(frames: Int)
    case FailAfter(frames: Int)
    case Waiting
    case NoRead
    case CallAfterEach
    case FinishOnClose

  private val behaviour         = AtomicReference(Behaviour.Echo)
  private val received          = ConcurrentLinkedQueue[String]()
  private val handler           = ConcurrentLinkedQueue[String]() // what the handler noticed
  private val rooms             = ConcurrentLinkedQueue[String]()
  @volatile private var sendNow = CountDownLatch(1)
  private val release           = CountDownLatch(1)
  @volatile private var current: TestSocket           = null
  @volatile private var failedToStart: Option[String] = None
  @volatile private var spansBefore: Set[Long]        = Set.empty
  @volatile private var threadsBefore                 = 0
  @volatile private var many: Vector[TestSocket]      = Vector.empty

  private final class Notices(client: com.thinkmorestupidless.ankka.sdk.ComponentClient)
      extends HttpEndpoint("/notices"):
    val acl: Acl = Acl.AllowAll

    socket("/stream") { s =>
      def frames = Iterator.continually(s.receive()).takeWhile(_.isDefined).flatten
      behaviour.get match
        case Behaviour.Echo =>
          frames.foreach { f =>
            received.add(f); s.send(f)
          }
          handler.add("told closed"): Unit
          // Waits for a scenario that asks the handler to send over the closed socket.
          if sendNow.await(5, TimeUnit.SECONDS) then
            try
              s.send("too late")
              handler.add("sent"): Unit
            catch case _: SocketClosed => handler.add("SocketClosed"): Unit
        case Behaviour.FinishAfter(n) => frames.take(n).foreach(received.add)
        case Behaviour.FailAfter(n) =>
          frames.take(n).foreach(received.add)
          throw IllegalStateException("the handler broke")
        case Behaviour.Waiting =>
          frames.foreach(received.add)
          handler.add("told closed"): Unit
        case Behaviour.NoRead => release.await(60, TimeUnit.SECONDS): Unit
        case Behaviour.CallAfterEach =>
          frames.foreach { f =>
            received.add(f)
            client
              .forKeyValueEntity(EntityId("socket-calls"))
              .call(ProfileEntity.recordLogin)
              .invoke(): Unit
          }
        case Behaviour.FinishOnClose => frames.foreach(received.add)
    }

    socket("/rooms/{room}") { (room: String, s: Socket) =>
      while s.receive().isDefined do rooms.add(room): Unit
    }

  private var testKit: AnkkaTestKit = null
  private var base                  = ""

  override def beforeAll(): Unit =
    Limits.foreach((key, value) => System.setProperty(key, value): Unit)
    ConfigFactory.invalidateCaches()
    val server = HttpServer.at("127.0.0.1", 0)(clients => Notices(clients.componentClient))
    testKit = AnkkaTestKit.start(Seq(ProfileEntity.descriptor), Seq(server))
    base = s"ws://127.0.0.1:${server.boundPort.getOrElse(fail("not bound"))}"

  override def afterAll(): Unit =
    release.countDown()
    sendNow.countDown()
    if testKit != null then testKit.stop()
    Limits.keys.foreach(System.clearProperty)
    ConfigFactory.invalidateCaches()

  override def beforeEach(context: BeforeEach): Unit =
    behaviour.set(Behaviour.Echo)
    received.clear()
    handler.clear()
    rooms.clear()
    sendNow = CountDownLatch(1)
    current = null
    failedToStart = None
    spansBefore = spans().map(_.spanId).toSet

  override def afterEach(context: AfterEach): Unit =
    many.foreach(s => scala.util.Try(s.abort()))
    many = Vector.empty
    if current != null then scala.util.Try(current.abort()): Unit

  private def open(path: String, client: Option[HttpClient] = None): TestSocket =
    TestSocket
      .open(s"$base/notices$path", client = client)
      .fold(refused => fail(s"the socket was not opened: $refused"), identity)

  private def eventually(description: String, within: FiniteDuration = 10.seconds)(
      assertion: => Unit
  ): Unit =
    val deadline        = within.fromNow
    var last: Throwable = null
    while
      try
        assertion
        last = null
      catch case failure: AssertionError => last = failure
      last != null && deadline.hasTimeLeft()
    do Thread.sleep(50)
    if last != null then throw AssertionError(s"$description: ${last.getMessage}", last)

  private def spans(): Vector[RecordedSpan] =
    if testKit == null then Vector.empty
    else Observability(testKit.service.system).recorder.snapshot()

  private def nameOf(span: RecordedSpan): String =
    Observability(testKit.service.system).names.nameOf(span.handlerRef).getOrElse("unknown")

  private def newSpans(): Vector[RecordedSpan] = spans().filterNot(s => spansBefore(s.spanId))

  private def socketRoots(): Vector[RecordedSpan] =
    newSpans().filter(s => nameOf(s) == "SOCKET /stream")

  // ── the world ────────────────────────────────────────────────────────────────

  Given("a service {string} with an HTTP endpoint that declares the socket route {string}") {
    (_: String, _: String) => ()
  }
  Given("the ACL of the socket route {string} allows all")((_: String) => ())

  Given(
    "the HTTP endpoint of the service {string} declares the socket route {string} with an ACL that allows all"
  ) { (_: String, _: String) =>
    ()
  }

  Given("the handler of the socket route {string} sends one frame for each frame it reads") {
    (_: String) => behaviour.set(Behaviour.Echo)
  }
  Given("the handler of the socket route {string} is waiting for a frame") { (_: String) =>
    behaviour.set(Behaviour.Waiting)
  }
  Given("the handler of the socket route {string} finishes after it reads {string} frame") {
    (_: String, n: String) => behaviour.set(Behaviour.FinishAfter(n.toInt))
  }
  Given("the handler of the socket route {string} fails after it reads {string} frame") {
    (_: String, n: String) => behaviour.set(Behaviour.FailAfter(n.toInt))
  }
  Given("the handler of the socket route {string} reads no frame") { (_: String) =>
    behaviour.set(Behaviour.NoRead)
  }
  Given("the handler of the socket route {string} calls a component after each frame it reads") {
    (_: String) => behaviour.set(Behaviour.CallAfterEach)
  }
  Given(
    "the handler of the socket route {string} finishes when it is told that the socket is closed"
  ) { (_: String) =>
    behaviour.set(Behaviour.FinishOnClose)
  }

  Given("a developer has opened a socket to {string}") { (path: String) =>
    current = open(path)
  }
  Given("a developer has opened a socket to {string} and closed it") { (path: String) =>
    current = open(path)
    current.close()
    current.closed(): Unit
    eventually("the handler is told")(assert(handler.contains("told closed")))
  }

  Given("the HTTP endpoint of the service {string} declares the route {string}") {
    (_: String, _: String) => ()
  }

  // ── what happens ─────────────────────────────────────────────────────────────

  When("a developer opens a socket to {string} and sends {string} frame(s)") {
    (path: String, n: String) =>
      current = open(path)
      (1 to n.toInt).foreach(i => current.send(s"frame $i"))
  }
  When("a developer opens a socket to {string}, sends {string} frames and closes the socket") {
    (path: String, n: String) =>
      current = open(path)
      (1 to n.toInt).foreach(i => current.send(s"frame $i"))
      eventually("the handler read them")(assertEquals(received.size, n.toInt))
      current.close()
      current.closed(): Unit
  }
  When("a developer opens a socket to {string} and closes the socket") { (path: String) =>
    current = open(path)
    current.close()
    current.closed(): Unit
  }
  When("a developer opens a socket to {string} and sends more frames than a socket holds unread") {
    (path: String) =>
      current = open(path)
      (1 to 20).foreach(i => scala.util.Try(current.send(s"frame $i")))
  }
  When("the developer closes the socket")(() => current.close())
  When("the developer sends {string} frames") { (n: String) =>
    (1 to n.toInt).foreach(i => current.send(s"frame $i"))
  }
  When("the developer sends a frame larger than a frame may be")(() => current.send("x" * 1025))
  When("the developer sends a frame that is not text")(() => current.sendBinary(Array[Byte](1, 2)))
  When("the handler of the socket route {string} sends a frame") { (_: String) =>
    sendNow.countDown()
  }
  When("no frame crosses the socket for longer than the platform leaves a socket quiet") { () =>
    current.quietFor(5.seconds) // the server's idle timeout is 3 seconds here
  }
  When("a developer starts the service {string}") { (_: String) =>
    val clash = new HttpEndpoint("/notices"):
      val acl: Acl = Acl.AllowAll
      get("/stream")(() => "a route")
      socket("/stream")(_ => ())
    failedToStart =
      try
        val server = HttpServer.at("127.0.0.1", 0)(_ => clash)
        val other  = AnkkaTestKit.start(Nil, Seq(server))
        other.stop()
        None
      catch
        case failure: Throwable =>
          Some(
            Iterator
              .iterate(failure)(_.getCause)
              .takeWhile(_ != null)
              .map(_.getMessage)
              .mkString(" / ")
          )
  }
  When("developers open {string} sockets to {string} and send no frame") {
    (n: String, path: String) =>
      // One client with a fixed executor, so the client's own threads cannot grow with the
      // sockets and be counted as the server's.
      val client = HttpClient.newBuilder().executor(Executors.newFixedThreadPool(2)).build()
      // Warm the server's pools first: carriers and dispatchers start lazily, up to a bound.
      val warm = (1 to 100).map(_ => open(path, Some(client))).toVector
      Thread.sleep(500)
      warm.foreach(_.abort())
      Thread.sleep(500)
      threadsBefore = ManagementFactory.getThreadMXBean.getThreadCount
      many = (1 to n.toInt).map(_ => open(path, Some(client))).toVector
      Thread.sleep(1000) // every handler has started and is parked in receive()
  }

  // ── what is seen ─────────────────────────────────────────────────────────────

  Then("the handler reads the {string} frame(s) in the order the developer sent them") {
    (n: String) =>
      eventually("the handler read them") {
        assertEquals(received.asScala.toVector, (1 to n.toInt).map(i => s"frame $i").toVector)
      }
  }
  Then("the developer is given {string} frames in the order the handler sent them") { (n: String) =>
    assertEquals(
      (1 to n.toInt).map(_ => current.receive()).toVector,
      (1 to n.toInt).map(i => Some(s"frame $i")).toVector
    )
  }
  Then("the handler is told that the socket is closed") { () =>
    eventually("the handler is told") {
      assert(handler.contains("told closed") || handler.contains("SocketClosed"), handler.toString)
    }
  }
  Then("the frame is not ignored without the handler being told") { () =>
    eventually("the handler is told")(assert(handler.contains("SocketClosed"), handler.toString))
    assert(!handler.contains("sent"), "a send over a closed socket must not succeed")
  }
  Then("the socket is closed with the close reason {string}") { (word: String) =>
    val expected =
      CloseReason.values.find(_.word == word).getOrElse(fail(s"no close reason '$word'"))
    assertEquals(current.closed(), TestSocket.Closed(expected.code, expected.word))
  }
  Then("the socket is not cut off")(() =>
    current.closed(): Unit
  ) // `closed` fails for a cut-off socket
  Then("the handler does not read the frame") { () =>
    Thread.sleep(200)
    assertEquals(received.size, 0)
  }
  Then("the socket is still open") { () =>
    current.send("still here")
    behaviour.get match
      case Behaviour.Echo => assertEquals(current.receive(), Some("still here"))
      case _              => eventually("it arrived")(assert(received.contains("still here")))
  }
  Then("the handler reads no frame")(() => assert(!received.asScala.exists(_ != "still here")))
  Then("the developer is given no frame")(() => current.quietFor(200.millis))
  Then("the handler reads the path's {string} as {string} after each frame") {
    (_: String, value: String) =>
      eventually("the handler read each") {
        assertEquals(rooms.asScala.toVector, Vector.fill(3)(value))
      }
  }
  Then("the service {string} does not start") { (_: String) =>
    assert(failedToStart.isDefined, "the service started")
  }
  Then("the reason names the path {string}") { (path: String) =>
    assert(failedToStart.exists(_.contains(s"GET $path")), failedToStart.toString)
  }
  Then("the service {string} holds no more threads than it held with no socket open") {
    (_: String) =>
      // The measure must be able to move: a thousand platform threads parked by the test raise it
      // by about a thousand.
      val parked = CountDownLatch(1)
      val proof  = (1 to 1000).map(_ => Thread.ofPlatform().start(() => parked.await()))
      val raised = ManagementFactory.getThreadMXBean.getThreadCount - threadsBefore
      parked.countDown()
      proof.foreach(_.join())
      assert(raised >= 990, s"the measure did not move: $raised")
      val withSockets = ManagementFactory.getThreadMXBean.getThreadCount - threadsBefore
      assert(withSockets <= 8, s"${many.size} open sockets added $withSockets platform threads")
  }

  Then("the service records a trace whose root is the socket to {string}") { (_: String) =>
    eventually("the socket's span is recorded")(assertEquals(socketRoots().size, 1))
  }
  Then("the trace shows {string} calls to the component under the root") { (n: String) =>
    val root     = socketRoots().head
    val children = spans().filter(s => s.traceId == root.traceId && s.parentSpanId == root.spanId)
    assertEquals(children.size, n.toInt, children.map(nameOf).toString)
  }
  Then("the service records a trace with {string} root") { (n: String) =>
    eventually("the socket's span is recorded")(assertEquals(socketRoots().size, n.toInt))
    val root = socketRoots().head
    assertEquals(spans().count(s => s.traceId == root.traceId && s.parentSpanId == 0L), n.toInt)
  }
  Then("the service records a trace whose root is marked {word}") { (outcome: String) =>
    eventually("the socket's span is recorded") {
      assertEquals(socketRoots().map(_.outcome.toString.toLowerCase), Vector(outcome))
    }
  }

final class SocketFeatures extends SocketSteps("../../features/sockets/frames.feature")

final class SocketTraceFeatures extends SocketSteps("../../features/sockets/traces.feature")
