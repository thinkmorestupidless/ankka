package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.keyring.HandlerReport
import com.thinkmorestupidless.ankka.runtime.{ProjectionRuntime, ServiceIdentity}
import com.thinkmorestupidless.ankka.sdk.{ErasureHandler, ErasureReport, Finality}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Feature 042, T036 (R16): the service's erasure handler, run on one instance per application. */
final class ErasureHandlerSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private def eventually[A](what: String)(probe: => Option[A]): A =
    Iterator
      .continually { Thread.sleep(100); probe }
      .take(300)
      .collectFirst { case Some(a) => a }
      .getOrElse(fail(s"never: $what"))

  private def kitWith(handler: ErasureHandler, keyring: InMemoryKeyring) =
    AnkkaTestKit.start(
      Seq(MemberEntity.descriptor, Members.descriptor),
      Seq(ProjectionRuntime()),
      configure = _.withErasureHandler(handler),
      serviceIdentity = ServiceIdentity.deployed("brand", "members"),
      keyring = keyring
    )

  test("the handler runs for each application, re-runs included, and its report is acknowledged") {
    val runs    = ConcurrentLinkedQueue[(String, Long, Boolean)]()
    val keyring = InMemoryKeyring()
    val kit     = kitWith(
      ErasureHandler { ctx => subject =>
        runs.add((subject.value, ctx.erasureSeq, ctx.rerun))
        ErasureReport(2, Finality.After(7.days))
      },
      keyring
    )
    try
      val seq = kit.erase(DataSubject("member/8c1f"))
      val ack = eventually("the acknowledgement")(keyring.acks.find(_.seq == seq))
      assertEquals(ack.completion.handler, Some(HandlerReport(2, "after:PT168H")))
      val again = keyring.erase("brand", DataSubject("member/8c1f"), rerun = true)
      eventually("the re-run's acknowledgement")(keyring.acks.find(_.seq == again))
      assertEquals(
        runs.asScala.toVector,
        Vector(("member/8c1f", seq, false), ("member/8c1f", again, true))
      )
    finally kit.stop()
  }

  test("a handler that throws is reported as failed, and the platform's duties are acknowledged") {
    val keyring = InMemoryKeyring()
    val kit     =
      kitWith(ErasureHandler(_ => _ => throw IllegalStateException("no bucket 'kyc'")), keyring)
    try
      val seq = kit.erase(DataSubject("member/8c1f"))
      val ack = eventually("the acknowledgement")(keyring.acks.find(_.seq == seq))
      assertEquals(ack.completion.handler, Some(HandlerReport.failed("no bucket 'kyc'")))
    finally kit.stop()
  }

  test("a service with no handler reports none") {
    val keyring = InMemoryKeyring()
    val kit     = AnkkaTestKit.start(
      Seq(MemberEntity.descriptor, Members.descriptor),
      Seq(ProjectionRuntime()),
      serviceIdentity = ServiceIdentity.deployed("brand", "members"),
      keyring = keyring
    )
    try
      val seq = kit.erase(DataSubject("member/8c1f"))
      assertEquals(
        eventually("the acknowledgement")(keyring.acks.find(_.seq == seq)).completion.handler,
        None
      )
    finally kit.stop()
  }
