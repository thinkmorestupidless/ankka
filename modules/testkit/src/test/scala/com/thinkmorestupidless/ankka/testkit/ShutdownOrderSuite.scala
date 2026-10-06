package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.{AnkkaService, RuntimeExtension}
import munit.FunSuite
import org.apache.pekko.actor.CoordinatedShutdown

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * A service stopped by its actor system's coordinated shutdown — what a SIGTERM runs, beside the
 * service's own shutdown hook — stops its extensions before the actor system terminates.
 *
 * On SIGTERM the JVM runs every shutdown hook at once, and Pekko's ends by terminating the actor
 * system. A gRPC server stopping in the service's own hook was still inside its shutdown grace when
 * that happened, so the materializer under a stream was gone and the caller was told `INTERNAL`
 * instead of `UNAVAILABLE` — seen on k3s, and only on some runs, because it depended on which hook
 * got there first. Here the race is decided against the extension on purpose: nothing but
 * coordinated shutdown runs, and the extension takes two seconds to stop.
 */
class ShutdownOrderSuite extends FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  /** Stops slowly, and records whether the actor system was still running when it finished. */
  private final class SlowToStop extends RuntimeExtension:
    val name: String                       = "slow-to-stop"
    private var service: AnkkaService      = null
    val systemAliveAtStop                  = AtomicReference[Option[Boolean]](None)
    def start(service: AnkkaService): Unit = this.service = service
    override def stop(): Unit =
      Thread.sleep(2000)
      systemAliveAtStop.set(Some(!service.system.whenTerminated.isCompleted))

  test("coordinated shutdown stops every extension before the actor system terminates") {
    val extension = SlowToStop()
    val testKit   = AnkkaTestKit.start(Seq(ProfileEntity.descriptor), Seq(extension))
    try
      val system = testKit.service.system
      CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason): Unit
      Await.ready(system.whenTerminated, 60.seconds): Unit
      assertEquals(
        extension.systemAliveAtStop.get,
        Some(true),
        "the extension was not stopped, or was stopped after the actor system had terminated"
      )
    finally testKit.stop()
  }

  /** A socket whose handler waits for frames until it is told the socket is closed. */
  private final class Waiting(toldClosed: java.util.concurrent.CountDownLatch)
      extends com.thinkmorestupidless.ankka.http.HttpEndpoint("/waiting"):
    val acl: com.thinkmorestupidless.ankka.http.Acl =
      com.thinkmorestupidless.ankka.http.Acl.AllowAll
    socket("/socket") { socket =>
      while socket.receive().isDefined do ()
      toldClosed.countDown()
    }

  test("coordinated shutdown closes an open socket 1001, going away, and tells its handler") {
    val toldClosed = java.util.concurrent.CountDownLatch(1)
    val server =
      com.thinkmorestupidless.ankka.http.HttpServer.at("127.0.0.1", 0)(_ => Waiting(toldClosed))
    val testKit = AnkkaTestKit.start(Nil, Seq(server))
    try
      val socket = testKit.socket("/waiting/socket").fold(r => fail(s"refused: $r"), identity)
      val system = testKit.service.system
      CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason): Unit
      assertEquals(socket.closed(10.seconds), TestSocket.Closed(1001, "going away"))
      assert(
        toldClosed.await(10, java.util.concurrent.TimeUnit.SECONDS),
        "the handler was not told"
      )
      Await.ready(system.whenTerminated, 60.seconds): Unit
    finally testKit.stop()
  }
