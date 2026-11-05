package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.keyring.{HandlerReport, KeyringNotice}
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  HistoryLines,
  ProjectionRuntime,
  ServiceIdentity
}
import com.thinkmorestupidless.ankka.runtime.keyring.{ErasureHooks, ErasureReplay}

import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * Feature 042, T035: what an instance does for an erasure notice, and what it tells the keyring.
 */
final class ErasureDutiesSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private def eventually[A](what: String)(probe: => Option[A]): A =
    Iterator
      .continually { Thread.sleep(100); probe }
      .take(300)
      .collectFirst { case Some(a) => a }
      .getOrElse(fail(s"never: $what"))

  test("a notice redacts the views, runs the hooks, and is acknowledged with what was done") {
    val keyring = InMemoryKeyring()
    val kit     = AnkkaTestKit.start(
      Seq(MemberEntity.descriptor, Members.descriptor),
      Seq(ProjectionRuntime()),
      serviceIdentity = ServiceIdentity.deployed("brand", "members"),
      keyring = keyring
    )
    try
      val hooks = ErasureHooks.of(kit.service.system)
      val heard = java.util.concurrent.ConcurrentLinkedQueue[KeyringNotice]()
      hooks.onSessions { n => heard.add(n); Future.successful(2L) }
      hooks.onHandler(_ => Future.successful(Some(HandlerReport(3, "immediate"))))
      kit.componentClient
        .forEventSourcedEntity(EntityId("8c1f"))
        .call(MemberEntity.join)
        .invoke("ada@example.com"): Unit
      kit.componentClient
        .forEventSourcedEntity(EntityId("9d2e"))
        .call(MemberEntity.join)
        .invoke("grace@example.com"): Unit
      val rows = kit.service.viewClient.forView(Members)
      eventually("both rows")(Option.when(rows.all().size == 2)(()))

      val seq = kit.erase(DataSubject("member/8c1f"))
      val ack = eventually("the acknowledgement")(keyring.acks.find(_.seq == seq))
      assertEquals(ack.service, "members")
      assertEquals(ack.completion.rowsRedacted, 1L)
      assertEquals(ack.completion.tokensDropped, 1L)
      assertEquals(ack.completion.sessionsEnded, 2L)
      assertEquals(ack.completion.handler, Some(HandlerReport(3, "immediate")))
      assertEquals(heard.peek().subject, "member/8c1f")

      assert(rows.get("8c1f").exists(_.email.isErased))
      assertEquals(rows.get("9d2e").map(_.email.orElse("erased")), Some("grace@example.com"))
      assertEquals(rows.ask(Members.byEmail, "email" -> "ada@example.com"), Vector.empty)
      assertEquals(rows.ask(Members.byEmail, "email" -> "grace@example.com").size, 1)
      kit.assertNoPersonalValue("ada@example.com")
    finally kit.stop()
  }

  test("a duty that fails acknowledges nothing, and the next notice is still carried out") {
    val keyring = InMemoryKeyring()
    val kit     = AnkkaTestKit.start(
      Seq(MemberEntity.descriptor, Members.descriptor),
      Seq(ProjectionRuntime()),
      serviceIdentity = ServiceIdentity.deployed("brand", "members"),
      keyring = keyring
    )
    try
      val hooks = ErasureHooks.of(kit.service.system)
      hooks.onHandler(n =>
        if n.subject == "member/a" then Future.failed(IllegalStateException("the bucket is gone"))
        else Future.successful(None)
      )
      val failed = kit.erase(DataSubject("member/a"))
      val done   = kit.erase(DataSubject("member/b"))
      eventually("the second acknowledgement")(keyring.acks.find(_.seq == done))
      assertEquals(keyring.acks.find(_.seq == failed), None)
    finally kit.stop()
  }

  test("a notice whose ack the keyring never had is handed again to an instance restarted") {
    val keyring = InMemoryKeyring()
    val kit     = AnkkaTestKit.start(
      Seq(MemberEntity.descriptor, Members.descriptor),
      Seq(ProjectionRuntime()),
      serviceIdentity = ServiceIdentity.deployed("brand", "members"),
      keyring = keyring
    )
    // What the database records this service carried through: what a start says hello from.
    def mark(): Long =
      given system: org.apache.pekko.actor.typed.ActorSystem[?] = kit.service.system
      ErasureReplay.carriedThrough(() => Database(), HistoryLines(system).current)
    try
      val first = kit.erase(DataSubject("member/first"))
      eventually("the first acknowledgement")(keyring.acks.find(_.seq == first))
      eventually("the first mark")(Option.when(mark() >= first)(()))

      // The duties of the next notice end in an outage: the ack waits in the instance's client.
      val gate = java.util.concurrent.CountDownLatch(1)
      ErasureHooks
        .of(kit.service.system)
        .onHandler(_ =>
          Future {
            gate.await()
            None
          }(using scala.concurrent.ExecutionContext.global)
        )
      val seq = kit.erase(DataSubject("member/second"))
      keyring.outage(1.hour)
      gate.countDown()
      eventually("the acknowledgement held")(keyring.heldAcks.find(_.seq == seq))
      Thread.sleep(500)
      assertEquals(mark(), first, "recorded as carried through before the keyring had the ack")

      // The instance stops with its ack unsent; the keyring comes back while nothing runs.
      kit.restartService(whileStopped = {
        keyring.forgetHeldAcks()
        keyring.recover()
      })
      eventually("the notice handed again, and acknowledged")(keyring.acks.find(_.seq == seq))
      eventually("the mark")(Option.when(mark() >= seq)(()))
    finally kit.stop()
  }
