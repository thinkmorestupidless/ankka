package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.controlplane.MutableClock
import com.thinkmorestupidless.ankka.controlplane.domain.{Actor, Attribution}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.core.keyring.{Completion, HandlerReport, KeyringAck}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, InMemoryKeyring, LogCapturing}

import java.time.{Duration, Instant}
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Feature 042, T059: the sweep carries requests on at the control plane's clock (SC-006): a lapsed
 * hold applied and a hold not yet lapsed left, the handlers of a request applied within the window
 * asked to run again and none older, the window's edge on both sides, and a quiet re-run apart from
 * one that erased.
 */
class ErasureSweepSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val Start = Instant.parse("2031-01-01T00:00:00Z")

  private final class Plane(
      val kit: AnkkaTestKit,
      val keyring: InMemoryKeyring,
      val admin: TestKeyringAdmin,
      val clock: MutableClock,
      val plane: ErasurePlane,
      val sweep: ErasureSweep
  ):
    def entity(id: String) = kit.componentClient.forEventSourcedEntity(EntityId(id))
    def read(id: String)   = entity(id).call(ErasureRequestEntity.get).invoke()
    def pass()             = Await.result(sweep.runOnce(), 2.minutes)
    def ask(id: String, subject: String, notBefore: Option[Instant] = None) =
      entity(id)
        .call(ErasureRequestEntity.ask)
        .withMetadata(Attribution(Actor("ada"), clock.instant()).metadata)
        .invoke(AskErasure("brand", subject, notBefore)): Unit

    /** Waits until the requests' view shows `id` in `state`, so a pass reads what was done. */
    def listed(id: String, state: String): Unit =
      val views    = plane.viewClient.getOrElse(fail("no views"))
      val deadline = System.nanoTime() + 30.seconds.toNanos
      def found    = views
        .forView(ErasureRequestsView)
        .ask(ErasureRequestsView.bySubject, "project" -> "brand", "subject" -> read(id).subject)
        .exists(r => r.id == id && r.state == state)
      while !found do
        if System.nanoTime() > deadline then fail(s"$id never listed as $state")
        Thread.sleep(50)
    def applied(id: String): Unit =
      Await.result(plane.application.getOrElse(fail("no application")).apply(id), 30.seconds): Unit
      listed(id, "applied")

    /** The re-run notices the keyring sent, by subject. */
    def reruns(subject: String): Int =
      keyring.sent.count(n => n.rerun && n.subject == subject)
    def ackLatest(subject: String, handler: HandlerReport): Unit =
      val n = keyring.sent.filter(_.subject == subject).last
      keyring.ack(KeyringAck(n.seq, "players", "players-0", Completion(1, 0, 0, Some(handler))))

  private def withSweep(test: Plane => Unit): Unit =
    val keyring = InMemoryKeyring()
    val clock   = MutableClock(Start)
    val admin   = TestKeyringAdmin(keyring, clock = clock)
    val plane   = ErasurePlane(admin, Some(MemoryErasureLogBucket()), () => clock)
    val kit     = AnkkaTestKit.start(
      Seq(ErasureRequestEntity.descriptor, ErasureRequestsView.descriptor),
      Seq(ProjectionRuntime(), plane),
      keyring = keyring
    )
    try
      test(
        Plane(
          kit,
          keyring,
          admin,
          clock,
          plane,
          ErasureSweep(plane, () => 30.days, rerunEvery = 0.millis)
        )
      )
    finally kit.stop()

  test("a hold whose date has passed is applied by the control plane, and one before it is left") {
    withSweep { p =>
      p.ask("brand/due", "player/due", Some(Start.plusSeconds(3600)))
      p.ask("brand/later", "player/later", Some(Start.plusSeconds(7200)))
      p.listed("brand/due", "held")
      p.listed("brand/later", "held")
      val before = p.pass()
      assertEquals(before.lapsed, Vector.empty)
      assertEquals(p.read("brand/due").state, Some(ErasureState.Held))
      p.clock.advance(Duration.ofMinutes(61))
      val after = p.pass()
      assertEquals(after.lapsed, Vector("brand/due"))
      val due = p.read("brand/due")
      assertEquals(due.state, Some(ErasureState.Applied))
      assert(due.lapsedAt.isDefined, due.toString)
      // At the control plane's clock, by the control plane.
      assertEquals(due.lapsedAt, Some(p.clock.instant()))
      assertEquals(p.read("brand/later").state, Some(ErasureState.Held))
      assertEquals(p.admin.ordered.map(_.subject), Vector("player/due"))
    }
  }

  test("a request left applying is applied on the next pass") {
    withSweep { p =>
      p.ask("brand/r1", "player/r1")
      p.admin.refuse = Some("the keyring is away")
      intercept[Throwable](
        Await.result(p.plane.application.getOrElse(fail("none")).apply("brand/r1"), 30.seconds)
      ): Unit
      p.listed("brand/r1", "applying")
      p.admin.refuse = None
      assertEquals(p.pass().retried, Vector("brand/r1"))
      assertEquals(p.read("brand/r1").state, Some(ErasureState.Applied))
    }
  }

  test("the handlers run again within the window, at its edge, and never after it") {
    withSweep { p =>
      p.ask("brand/old", "player/old")
      p.applied("brand/old")
      p.clock.advance(Duration.ofMinutes(1))
      p.ask("brand/new", "player/new")
      p.applied("brand/new")
      val newAt = p.read("brand/new").destroyedAt.getOrElse(fail("not destroyed"))
      // Exactly thirty days after the newer one: it is within, the older one a minute past.
      p.clock.set(newAt.plus(Duration.ofDays(30)))
      val edge = p.pass()
      assertEquals(edge.rerun, Vector("brand/new"))
      assertEquals(p.reruns("player/new"), 1)
      assertEquals(p.reruns("player/old"), 0)
      // A millisecond later the newer one is past it too.
      p.clock.advance(Duration.ofMillis(1))
      assertEquals(p.pass().rerun, Vector.empty)
      assertEquals(p.reruns("player/new"), 1)
    }
  }

  test("a quiet re-run sets only when it ran; one that erased is a line of the history") {
    withSweep { p =>
      p.ask("brand/r1", "player/r1")
      p.applied("brand/r1")
      p.ackLatest("player/r1", HandlerReport(0, "immediate"))
      p.pass(): Unit // reads the first completion in, and asks for a re-run
      assert(p.read("brand/r1").completions.contains("players"))
      assertEquals(p.reruns("player/r1"), 1)

      p.clock.advance(Duration.ofMinutes(15))
      p.ackLatest("player/r1", HandlerReport(0, "immediate"))
      p.pass(): Unit
      val quiet = p.read("brand/r1")
      assertEquals(quiet.lastRunAt, Some(p.clock.instant()))
      assertEquals(quiet.handlerRuns, Vector.empty)

      p.clock.advance(Duration.ofMinutes(15))
      p.ackLatest("player/r1", HandlerReport(2, "immediate"))
      p.pass(): Unit
      val loud = p.read("brand/r1")
      assertEquals(
        loud.handlerRuns.map(r => (r.service, r.report.objectsErased)),
        Vector("players" -> 2L)
      )
      assertEquals(loud.lastRunAt, quiet.lastRunAt)
    }
  }
