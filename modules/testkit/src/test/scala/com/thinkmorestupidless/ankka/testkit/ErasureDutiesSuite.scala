package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.keyring.{HandlerReport, KeyringNotice}
import com.thinkmorestupidless.ankka.runtime.{ProjectionRuntime, ServiceIdentity}
import com.thinkmorestupidless.ankka.runtime.keyring.ErasureHooks

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
