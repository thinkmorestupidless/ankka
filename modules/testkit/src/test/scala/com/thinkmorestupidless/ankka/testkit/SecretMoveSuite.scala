package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.{Database, ExtensionsReadiness, SqlFragment}
import com.thinkmorestupidless.ankka.core.secrets.DerivedIds
import com.thinkmorestupidless.ankka.runtime.secrets.MoveReport
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * `features/secrets/moving.feature`: a service moves its secrets from its database to Secret
 * Manager one phase at a time, each performed when it starts, with nothing of a value in any log,
 * report or status.
 *
 * One service, one database, restarted from phase to phase as an installation whose settings
 * changed would restart it. The cases build on each other in order, as a move does.
 */
final class SecretMoveSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private val fake                  = FakeSecretManager.start()
  private val Project               = "spinvibe"
  private val Service               = "payments"
  private val values                = (1 to 10).map(i => s"secret-$i" -> s"sk-move-$i-7f3a").toMap
  private var testKit: AnkkaTestKit = scala.compiletime.uninitialized

  private def onSecretManager = SecretBackendChoice.secretManager(fake, Project, Service)

  private def phase(word: String) =
    ConfigFactory.parseString(s"""ankka.secrets.move = "$word"
                                 |ankka.secrets.move-retry = 200ms""".stripMargin)

  private def idOf(name: String) = DerivedIds.service(Project, Service, name)

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(Seq.empty)
    values.foreach((name, value) => testKit.secrets.put(name, value))

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()
    fake.stop()

  private def rows(): Vector[String] =
    given org.apache.pekko.actor.typed.ActorSystem[?] = testKit.service.system
    Await.result(
      Database().query(SqlFragment.raw("SELECT name FROM ankka_secrets ORDER BY name"))(
        _.get(0, classOf[String])
      ),
      30.seconds
    )

  private def readiness = ExtensionsReadiness(testKit.service.system)

  private def report: MoveReport =
    testKit.eventually("the move has run its phase") {
      testKit.service.secretStores.flatMap(_.moveReport).filter(_.outcome != MoveReport.Running)
    }

  /** Nothing of any value in the log captured so far, nor in what the instance reports. */
  private def noValueAnywhere(): Unit =
    val text =
      LogCapture.capturedText + "\n" + testKit.service.secretStores.flatMap(_.moveReport).toString
    values.values.foreach(v => assert(!text.contains(v), s"$v appears in a log line or report"))

  test(
    "a service started on the Secret Manager backend in the move phase \"copy\" copies its service secrets before it is ready"
  ) {
    // Secret Manager already holds one of them, with another value: the copy must not overwrite it.
    fake.seed(idOf("secret-2"), "sk-already-there")
    testKit.restartOn(onSecretManager, phase("copy"))
    val done = report
    assertEquals(done.outcome, MoveReport.Copied)
    testKit.eventually("ready once copied")(Option.when(readiness.allReady)(()))
    values.foreach { (name, value) =>
      val expected = if name == "secret-2" then "sk-already-there" else value
      assertEquals(fake.latestValue(idOf(name)), Some(expected), name)
    }
    assertEquals(rows().size, 10)
    noValueAnywhere()
  }

  test(
    "the copy check reports for each name whether the database and Secret Manager hold the same value"
  ) {
    testKit.restartOn(onSecretManager, phase("check"))
    val checked = report
    assertEquals(checked.outcome, MoveReport.Checked)
    val states = checked.names.toMap
    assertEquals(states.keySet, values.keySet)
    assertEquals(states("secret-2"), "different")
    assert(states.removed("secret-2").values.forall(_ == "equal"), states.toString)
    noValueAnywhere()
  }

  test("the removal step refuses while a copy check reports a difference") {
    testKit.restartOn(onSecretManager, phase("remove"))
    val refused = report
    assertEquals(refused.outcome, MoveReport.Refused)
    assert(refused.detail.exists(_.contains("secret-2")), refused.toString)
    assertEquals(rows().size, 10)
    testKit.eventually("ready: a refused removal holds nothing up")(
      Option.when(readiness.allReady)(())
    )
  }

  test(
    "a moved service set back to the Postgres backend before the removal step reads its secrets from its database"
  ) {
    testKit.restartOn(onSecretManager, phase("check"))
    report: Unit
    // Kept on the Secret Manager backend during the move: Secret Manager alone holds it.
    testKit.secrets.put("newpay", "sk-new-1")
    testKit.restartOn(SecretBackendChoice.postgres, phase("check"))
    assertEquals(testKit.secrets.get("secret-1"), Some(values("secret-1")))
    val back = testKit.service.secretStores.flatMap(_.moveReport).getOrElse(fail("no report"))
    assertEquals(back.outcome, MoveReport.RolledBack)
    assertEquals(back.names, Vector("newpay" -> "only-in-secret-manager"))
  }

  test(
    "a service that cannot reach Secret Manager during the move does not become ready and leaves its rows"
  ) {
    fake.unreachable(true)
    try
      testKit.restartOn(onSecretManager, phase("remove"))
      val reason = testKit.eventually("not ready, saying why") {
        readiness.reasons.find(_.contains("Secret Manager"))
      }
      assert(reason.contains("remove"), reason)
      assert(!readiness.allReady)
      assertEquals(rows().size, 10)
    finally fake.unreachable(false)
    // And once it can be reached again, the phase runs and the instance becomes ready.
    testKit.eventually("ready once reachable")(Option.when(readiness.allReady)(()))
  }

  test("the removal step removes the rows once every name is equal") {
    // Put the one difference right, as an operator would, through the service's own store.
    testKit.restartOn(onSecretManager)
    testKit.secrets.put("secret-2", values("secret-2"))
    testKit.restartOn(onSecretManager, phase("remove"))
    val removed = report
    assertEquals(removed.outcome, MoveReport.Removed)
    assert(removed.names.forall(_._2 == "equal"), removed.toString)
    assertEquals(rows(), Vector.empty)
    values.foreach((name, value) => assertEquals(testKit.secrets.get(name), Some(value)))
    noValueAnywhere()
  }

  test("after the removal step a service will not start on the Postgres backend, and says why") {
    val refused = intercept[IllegalStateException](testKit.restartOn(SecretBackendChoice.postgres))
    assert(refused.getMessage.contains("live only in Secret Manager"), refused.getMessage)
    assert(refused.getMessage.contains("ANKKA_SECRET_BACKEND"), refused.getMessage)
    // Back on Secret Manager it starts, and reads what it kept.
    testKit.restartOn(onSecretManager)
    assertEquals(testKit.secrets.get("secret-7"), Some(values("secret-7")))
  }
