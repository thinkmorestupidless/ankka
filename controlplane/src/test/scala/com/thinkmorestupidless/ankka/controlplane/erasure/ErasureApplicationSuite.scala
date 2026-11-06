package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{Database, ProjectionRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, InMemoryKeyring, LogCapturing}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Feature 042, T039 (R22): the log written twice, then the key destroyed, then completions. */
class ErasureApplicationSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private def withPlane(
      test: (
          AnkkaTestKit,
          InMemoryKeyring,
          TestKeyringAdmin,
          MemoryErasureLogBucket,
          ErasureApplication
      ) => Unit
  ): Unit =
    val keyring = InMemoryKeyring()
    val kit     = AnkkaTestKit.start(
      Seq(ErasureRequestEntity.descriptor, ErasureRequestsView.descriptor),
      Seq(ProjectionRuntime()),
      keyring = keyring
    )
    try
      given ExecutionContext = kit.service.system.executionContext
      val bucket             = MemoryErasureLogBucket()
      val admin              = TestKeyringAdmin(keyring)
      val log                = ErasureLog(Database()(using kit.service.system), bucket)
      test(kit, keyring, admin, bucket, ErasureApplication(kit.componentClient, log, admin))
    finally kit.stop()

  private def ask(kit: AnkkaTestKit, id: String, subject: String = "player/8c1f") =
    kit.componentClient
      .forEventSourcedEntity(EntityId(id))
      .call(ErasureRequestEntity.ask)
      .invoke(AskErasure("brand", subject))

  test("applying writes the table and the bucket, then destroys the key, then is applied") {
    withPlane { (kit, keyring, admin, bucket, application) =>
      ask(kit, "brand/r1"): Unit
      val applied = Await.result(application.apply("brand/r1"), 30.seconds)
      assertEquals(applied.state, Some(ErasureState.Applied))
      val seq = applied.erasureSeq.getOrElse(fail("no log entry"))
      assertEquals(
        admin.ordered.map(o => (o.seq, o.project, o.subject)),
        Vector((seq, "brand", "player/8c1f"))
      )
      assert(
        !keyring.holdsKey("brand", com.thinkmorestupidless.ankka.core.DataSubject("player/8c1f"))
      )
      val names = bucket.objects.keySet.asScala.toVector
      assertEquals(names.size, 1)
      assert(!names.head.contains("player"), names.head)
      assertEquals(
        bucket.entries().map(e => (e.seq, e.subject, e.rid)),
        Vector((seq, "player/8c1f", "r1"))
      )
      // Applying again changes nothing: the same entry, no second order.
      Await.result(application.apply("brand/r1"), 30.seconds): Unit
      assertEquals(admin.ordered.size, 1)
    }
  }

  test("a log write that fails leaves the request applying and destroys nothing") {
    withPlane { (kit, _, admin, bucket, application) =>
      ask(kit, "brand/r2"): Unit
      bucket.refuse = true
      intercept[IllegalStateException](Await.result(application.apply("brand/r2"), 30.seconds))
      val still = kit.componentClient
        .forEventSourcedEntity(EntityId("brand/r2"))
        .call(ErasureRequestEntity.get)
        .invoke()
      assertEquals(still.state, Some(ErasureState.Applying))
      assertEquals(
        still.lastFailure.map(_.reason),
        Some("the erasure log could not be written: the bucket refused the write")
      )
      assertEquals(admin.ordered, Vector.empty)
      // The same failure again keeps the time it began.
      intercept[IllegalStateException](Await.result(application.apply("brand/r2"), 30.seconds))
      val again = kit.componentClient
        .forEventSourcedEntity(EntityId("brand/r2"))
        .call(ErasureRequestEntity.get)
        .invoke()
      assertEquals(again.lastFailure, still.lastFailure)
      bucket.refuse = false
      val applied = Await.result(application.apply("brand/r2"), 30.seconds)
      assertEquals(applied.state, Some(ErasureState.Applied))
      assertEquals(applied.lastFailure, None)
      assertEquals(admin.ordered.size, 1)
    }
  }

  test("a keyring that refuses leaves the request applying, logged") {
    withPlane { (kit, _, admin, _, application) =>
      ask(kit, "brand/r3"): Unit
      admin.refuse = Some("the keyring is down")
      intercept[IllegalStateException](Await.result(application.apply("brand/r3"), 30.seconds))
      val still = kit.componentClient
        .forEventSourcedEntity(EntityId("brand/r3"))
        .call(ErasureRequestEntity.get)
        .invoke()
      assertEquals(still.state, Some(ErasureState.Applying))
      assert(still.erasureSeq.isDefined)
      assertEquals(
        still.lastFailure.map(_.reason),
        Some("the keyring did not destroy the subject key: the keyring is down")
      )
      admin.refuse = None
      assertEquals(
        Await.result(application.apply("brand/r3"), 30.seconds).state,
        Some(ErasureState.Applied)
      )
    }
  }

  test("one service's quiet re-run hides no other service's earlier failing one") {
    import com.thinkmorestupidless.ankka.core.keyring.*
    withPlane { (kit, _, _, _, _) =>
      given ExecutionContext = kit.service.system.executionContext
      val t0                 = java.time.Instant.parse("2031-01-01T00:00:00Z")
      val (t1, t2)           = (t0.plusSeconds(600), t0.plusSeconds(900))
      @volatile var answer   = ErasureStatus()
      val scripted           = new KeyringAdmin:
        def destroy(order: ErasureOrder) = Future.failed(IllegalStateException("not asked"))
        def resend(seq: Long)            = Future.unit
        def status(seq: Long)            = Future.successful(answer)
      val application = ErasureApplication(
        kit.componentClient,
        ErasureLog(Database()(using kit.service.system), MemoryErasureLogBucket()),
        scripted
      )
      val entity = kit.componentClient.forEventSourcedEntity(EntityId("brand/q1"))
      ask(kit, "brand/q1"): Unit
      entity.call(ErasureRequestEntity.logWritten).invoke(1L): Unit
      entity.call(ErasureRequestEntity.keyDestroyed).invoke(t0): Unit
      // kyc's completion erased something, so its quiet run after it is recorded.
      Seq("players" -> None, "kyc" -> Some(HandlerReport(1, "immediate"))).foreach((s, h) =>
        entity
          .call(ErasureRequestEntity.recordCompletion)
          .invoke(RecordCompletion(s, ServiceCompletion(Some(t0), handler = h))): Unit
      )
      def run(service: String, at: java.time.Instant, report: HandlerReport) =
        ServiceErasureStatus(service, Some(at), Some(Completion(0, 0, 0, Some(report))))
      // kyc's quiet run at t2 is read first; players' failing run, at t1, is read a pass later.
      answer = ErasureStatus(Vector(run("kyc", t2, HandlerReport(0, "immediate"))))
      val first = Await.result(
        application.recordRuns(entity.call(ErasureRequestEntity.get).invoke()),
        30.seconds
      )
      assertEquals(first.quietRuns, Map("kyc" -> t2))
      answer = ErasureStatus(
        Vector(
          run("kyc", t2, HandlerReport(0, "immediate")),
          run("players", t1, HandlerReport.failed("no bucket 'kyc'"))
        )
      )
      val second = Await.result(application.recordRuns(first), 30.seconds)
      assertEquals(
        second.handlerRuns.map(r => (r.service, r.report.error)),
        Vector("players" -> Some("no bucket 'kyc'")),
        "players' failing run was hidden by kyc's later quiet one"
      )
    }
  }

  test("a personal service that never connected keeps the request open, and is listed as awaited") {
    val keyring = InMemoryKeyring()
    val kit     = AnkkaTestKit.start(
      Seq(ErasureRequestEntity.descriptor, ErasureRequestsView.descriptor),
      Seq(ProjectionRuntime()),
      keyring = keyring
    )
    try
      given ExecutionContext = kit.service.system.executionContext
      val admin              = TestKeyringAdmin(keyring)
      val log = ErasureLog(Database()(using kit.service.system), MemoryErasureLogBucket())
      // "kyc" declares personal and is paused: no instance of it hears the notice.
      val application = ErasureApplication(
        kit.componentClient,
        log,
        admin,
        expected = project => if project == "brand" then Set("players", "kyc") else Set.empty
      )
      ask(kit, "brand/r9"): Unit
      val applied = Await.result(application.apply("brand/r9"), 30.seconds)
      val seq     = keyring.sent.last.seq
      keyring.ack(
        com.thinkmorestupidless.ankka.core.keyring.KeyringAck(
          seq,
          "players",
          "players-0",
          com.thinkmorestupidless.ankka.core.keyring.Completion(1, 0, 0, None)
        )
      )
      val after = Await.result(application.complete(applied), 30.seconds)
      // The kit's own service hears the notice too and completes; kyc never does.
      assert(after.completions.contains("players"), after.toString)
      assert(!after.completions.contains("kyc"), after.toString)
      assertEquals(after.finalAt, None, "final without kyc, which never carried it out")
      assertEquals(ErasureApplication.awaiting(after, Set("players", "kyc")), Vector("kyc"))
      val certificate = ErasureRoutes.certificate(after, Vector("kyc"))
      assertEquals(
        certificate.services
          .filter(s => Set("kyc", "players")(s.service))
          .map(s => (s.service, s.completedAt.isDefined)),
        Vector("kyc" -> false, "players" -> true)
      )
      // Once kyc completes, the request is final and waits on no one.
      keyring.ack(
        com.thinkmorestupidless.ankka.core.keyring.KeyringAck(
          seq,
          "kyc",
          "kyc-0",
          com.thinkmorestupidless.ankka.core.keyring.Completion(0, 0, 0, None)
        )
      )
      val done = Await.result(application.complete(after), 30.seconds)
      assert(done.finalAt.isDefined, done.toString)
      assertEquals(ErasureApplication.awaiting(done, Set("players", "kyc")), Vector.empty)
    finally kit.stop()
  }

  test("finality: immediate now, after a duration from the run") {
    val at = java.time.Instant.parse("2026-11-04T10:00:00Z")
    assertEquals(ErasureApplication.finalAt("immediate", Some(at)), Some(at))
    assertEquals(ErasureApplication.finalAt("after:P7D", Some(at)), Some(at.plusSeconds(7 * 86400)))
    assertEquals(ErasureApplication.finalAt("failed", Some(at)), None)
  }
