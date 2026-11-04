package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{Database, ProjectionRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, InMemoryKeyring, LogCapturing}

import scala.concurrent.{Await, ExecutionContext}
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
      assertEquals(admin.ordered, Vector.empty)
      bucket.refuse = false
      assertEquals(
        Await.result(application.apply("brand/r2"), 30.seconds).state,
        Some(ErasureState.Applied)
      )
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
      admin.refuse = None
      assertEquals(
        Await.result(application.apply("brand/r3"), 30.seconds).state,
        Some(ErasureState.Applied)
      )
    }
  }

  test("finality: immediate now, after a duration from the run") {
    val at = java.time.Instant.parse("2026-11-04T10:00:00Z")
    assertEquals(ErasureApplication.finalAt("immediate", Some(at)), Some(at))
    assertEquals(ErasureApplication.finalAt("after:P7D", Some(at)), Some(at.plusSeconds(7 * 86400)))
    assertEquals(ErasureApplication.finalAt("failed", Some(at)), None)
  }
