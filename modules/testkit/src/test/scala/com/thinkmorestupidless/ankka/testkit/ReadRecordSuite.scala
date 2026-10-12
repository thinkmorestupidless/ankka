package com.thinkmorestupidless.ankka.testkit

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, writeToString}
import com.thinkmorestupidless.ankka.core.{CommandError, Done, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.core.secrets.ReadRecord
import com.thinkmorestupidless.ankka.core.secrets.DerivedIds
import com.thinkmorestupidless.ankka.sdk.ServiceResponse
import com.thinkmorestupidless.ankka.testkit.FakeSecretManager.Identity
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.*

/**
 * `features/secrets/read-record.feature`, on the side of the service that reads: every read, keep
 * and removal leaves a record naming what is known of it and never the value, on either backend,
 * and a value is never returned unless its record was acknowledged.
 *
 * The record's keeper is the control plane; here it is the test kit's local recorder, or a
 * `ScriptedService` standing where the control plane would, for the cases about what happens when
 * it does not answer.
 */
abstract class ReadRecordBehaviours(backendName: String) extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  protected def backend(): SecretBackendChoice
  protected def project: String
  protected def service: String

  private var testKit: AnkkaTestKit = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(ChargeWorkflow.descriptor, WalletEntity.descriptor, OrderEntity.descriptor),
      secretBackend = backend()
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  protected def kit: AnkkaTestKit = testKit

  private def recordsOf(name: String): Vector[ReadRecord] =
    testKit.recordedReads.filter(_.name == name)

  test(
    s"a read of a service secret is recorded with what is known of it and never the value ($backendName)"
  ) {
    testKit.secrets.put("provider/acme", "sk-acme-1")
    val charge = testKit.componentClient.forWorkflow(EntityId(s"ch-$backendName"))
    assertEquals(charge.call(ChargeWorkflow.start).invoke(Charge("acme", 10)), Done)
    testKit.eventually("the step read the credential") {
      Some(charge.call(ChargeWorkflow.status).invoke()).filter(_.status == "charged")
    }
    val read = testKit
      .eventually("the step's read was recorded") {
        recordsOf("provider/acme").find(r => r.operation == "get" && r.component.nonEmpty)
      }
    assertEquals(
      (read.project, read.service, read.hosting, read.outcome, read.backend),
      (project, service, "embedded", "read", backendName)
    )
    assertEquals(read.component, Some("charge"))
    assertEquals(read.componentKind, Some("workflow"))
    assert(read.traceId.exists(_.length == 32), read.toString)
    assert(read.spanId.exists(_.length == 16), read.toString)
    assert(!java.time.Instant.now().isBefore(read.at), read.toString)
    val everything = testKit.recordedReads.map(r => writeToString(r)).mkString("\n")
    assert(!everything.contains("sk-acme-1"), everything)
  }

  test(s"a keep and a removal are recorded as written and removed ($backendName)") {
    testKit.secrets.put("kept-then-removed", "sk-1")
    testKit.secrets.delete("kept-then-removed")
    assertEquals(
      recordsOf("kept-then-removed").map(r => r.operation -> r.outcome),
      Vector("put" -> "written", "delete" -> "removed")
    )
  }

  test(s"a read that finds none is recorded with its outcome ($backendName)") {
    assertEquals(testKit.secrets.get("never-kept"), None)
    assertEquals(recordsOf("never-kept").map(_.outcome), Vector("none"))
  }

  test(s"a call the rules refuse is not recorded: nothing was read ($backendName)") {
    intercept[CommandError](testKit.secrets.get("provider acme")): Unit
    assertEquals(recordsOf("provider acme"), Vector.empty)
  }

  test(s"the record is kept nowhere in the service's database ($backendName)") {
    testKit.secrets.put("not-in-the-database", "sk-1")
    testKit.secrets.get("not-in-the-database"): Unit
    given org.apache.pekko.actor.typed.ActorSystem[?] = testKit.service.system
    val database = com.thinkmorestupidless.ankka.runtime.Database()
    val tables = scala.concurrent.Await.result(
      database.query(
        com.thinkmorestupidless.ankka.runtime.SqlFragment.raw(
          "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
        )
      )(_.get(0, classOf[String])),
      30.seconds
    )
    assert(!tables.exists(_.contains("read")), tables.toString)
  }

  test(s"a value is not returned when its record is not acknowledged ($backendName)") {
    val keeper = ScriptedService.start()
    val reading = AnkkaTestKit.start(
      Seq.empty,
      settings = ConfigFactory.parseString(s"""ankka.secrets.records-url = "${keeper.address}"
                                              |ankka.secrets.record-timeout = 2s""".stripMargin),
      secretBackend = backend()
    )
    try
      keeper.answer(_ => ServiceResponse(204, "", Array.emptyByteArray, Vector.empty))
      reading.secrets.put("guarded", "sk-guarded-1")
      assertEquals(reading.secrets.get("guarded"), Some("sk-guarded-1"))
      val posted = keeper.requests.map(r => readFromArray[ReadRecord](r.body))
      assertEquals(posted.map(_.operation), Vector("put", "get"))
      assert(
        keeper.requests.forall(_.path == "/secret-reads"),
        keeper.requests.map(_.path).toString
      )
      assert(!keeper.requests.exists(_.text.contains("sk-guarded-1")))

      keeper.failNext(503)
      val refused = intercept[CommandError](reading.secrets.get("guarded"))
      assertEquals(refused.code, ErrorCode.Unavailable)
      assert(refused.message.contains("not acknowledged"), refused.message)
      assert(!refused.message.contains("sk-guarded-1"), refused.message)

      // A slow keeper is waited for: the value comes back only after the record is acknowledged.
      keeper.delay(1.second)
      val started = System.nanoTime()
      assertEquals(reading.secrets.get("guarded"), Some("sk-guarded-1"))
      assert((System.nanoTime() - started).nanos >= 1.second, "returned before the record")

      // And one slower than the bound is not.
      keeper.delay(3.seconds)
      assertEquals(
        intercept[CommandError](reading.secrets.get("guarded")).code,
        ErrorCode.Unavailable
      )
    finally
      reading.stop()
      keeper.stop()
  }

final class PostgresReadRecordSuite extends ReadRecordBehaviours("postgres"):
  protected def backend(): SecretBackendChoice = SecretBackendChoice.postgres
  protected def project: String                = "local"
  protected def service: String                = "unnamed"

final class SecretManagerReadRecordSuite extends ReadRecordBehaviours("secret-manager"):
  private lazy val fake = FakeSecretManager.start()
  protected def backend(): SecretBackendChoice =
    SecretBackendChoice.secretManager(fake, project, service)
  protected def project: String = "spinvibe"
  protected def service: String = "payments"

  override def afterAll(): Unit =
    super.afterAll()
    fake.stop()

  test("a read before the service's secret access was written is recorded as refused") {
    kit.secrets.put("pending", "sk-1")
    fake.access.withhold(Identity.Service(project, service))
    try intercept[CommandError](kit.secrets.get("pending")): Unit
    finally fake.access.restore(Identity.Service(project, service))
    assertEquals(
      kit.recordedReads.filter(_.name == "pending").map(r => r.operation -> r.outcome),
      Vector("put" -> "written", "get" -> "refused")
    )
  }

  test("a read that skipped a version disabled by hand says so") {
    kit.secrets.put("rotated", "sk-1")
    kit.secrets.put("rotated", "sk-2")
    fake.disableByHand(DerivedIds.service(project, service, "rotated"), 2)
    assertEquals(kit.secrets.get("rotated"), Some("sk-1"))
    val read = kit.recordedReads.filter(r => r.name == "rotated" && r.operation == "get").last
    assert(read.latestSkipped, read.toString)
  }

  test("an unreachable Secret Manager is recorded as unavailable") {
    fake.unreachable(true)
    try intercept[CommandError](kit.secrets.get("outage")): Unit
    finally fake.unreachable(false)
    assertEquals(kit.recordedReads.filter(_.name == "outage").map(_.outcome), Vector("unavailable"))
  }
