package com.thinkmorestupidless.ankka.testkit.secrets

import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.secrets.{
  AccessTokens,
  DerivedIds,
  SecretBackend,
  SecretManager,
  SecretManagerException
}
import com.thinkmorestupidless.ankka.testkit.{
  AnkkaTestKit,
  FakeSecretManager,
  GherkinSuite,
  LogCapturing,
  SecretBackendChoice
}
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * `features/secrets/backend.feature`, whole: a developer's machine and a test need no Google Cloud.
 * A local platform is on the Postgres backend unless told otherwise, and a test that asks for the
 * Secret Manager backend is given the test kit's fake, which refuses what Google Cloud would.
 */
final class BackendFeatures
    extends GherkinSuite("../../features/secrets/backend.feature")
    with LogCapturing:

  override val munitTimeout: Duration = 3.minutes

  private var fake: Option[FakeSecretManager]         = None
  private var kits: Map[String, AnkkaTestKit]         = Map.empty
  private var backendSet: Boolean                     = true
  private var refused: Option[SecretManagerException] = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    fake = None
    kits = Map.empty
    backendSet = true
    refused = None

  override def afterEach(context: AfterEach): Unit =
    kits.values.foreach(_.stop())
    fake.foreach(_.stop())
    super.afterEach(context)

  private def theFake: FakeSecretManager =
    fake.getOrElse {
      val started = FakeSecretManager.start()
      fake = Some(started)
      started
    }

  private def kit(service: String): AnkkaTestKit =
    kits.getOrElse(service, fail(s"no service '$service' was started"))

  private def rows(service: String): Vector[String] =
    given org.apache.pekko.actor.typed.ActorSystem[?] = kit(service).service.system
    Await.result(
      Database().query(
        SqlFragment.raw("SELECT name || ':' || encode(ciphertext, 'hex') FROM ankka_secrets")
      )(_.get(0, classOf[String])),
      30.seconds
    )

  // ── a local platform ─────────────────────────────────────────────────────────

  Given("a local platform whose secret backend is not set") { () =>
    backendSet = false
  }

  When("a service {string} with a secret key starts") { (service: String) =>
    // Set empty, as on a machine where nobody named a backend: the kit otherwise names one.
    val settings =
      if backendSet then ConfigFactory.empty()
      else ConfigFactory.parseString("""ankka.secrets.backend = """"")
    kits += service -> AnkkaTestKit.start(Seq.empty, settings = settings)
  }

  Then("{string} keeps its service secrets in its database, encrypted with its secret key") {
    (service: String) =>
      kit(service).secrets.put("acme", "sk-acme-1")
      val stored = rows(service)
      assertEquals(stored.size, 1)
      assert(stored.head.startsWith("acme:01"), "encrypted, with its version in front")
      assert(!stored.head.contains("sk-acme-1"))
      assertEquals(kit(service).secrets.get("acme"), Some("sk-acme-1"))
  }

  Then("{string} reaches no Google Cloud") { (service: String) =>
    val built = kit(service).service.secretStores.getOrElse(fail("no secret store"))
    assertEquals(built.backend, SecretBackend.Postgres)
    assert(kit(service).recordedReads.forall(_.backend == "postgres"))
  }

  // ── a test on the Secret Manager backend ────────────────────────────────────

  Given("a test that starts the service {string} with the test kit") { (service: String) =>
    kits += service -> AnkkaTestKit.start(Seq.empty)
  }

  When("the test asks for the Secret Manager backend") { () =>
    kits.foreach { (service, started) =>
      started.restartOn(SecretBackendChoice.secretManager(theFake, "spinvibe", service))
    }
  }

  Then("{string} keeps its service secrets in the Secret Manager fake") { (service: String) =>
    kit(service).secrets.put("acme", "sk-acme-1")
    assertEquals(
      theFake.latestValue(DerivedIds.service("spinvibe", service, "acme")),
      Some("sk-acme-1")
    )
    assertEquals(rows(service), Vector.empty)
  }

  Then("{string} reaches no network and holds no credential for Google Cloud") {
    (service: String) =>
      val config = kit(service).service.system.settings.config
      assert(
        config.getString("ankka.secrets.secret-manager.endpoint").startsWith("http://127.0.0.1:"),
        "the fake is on loopback"
      )
      assert(config.getString("ankka.secrets.secret-manager.token").startsWith("fake:"))
      assert(!config.hasPath("GOOGLE_APPLICATION_CREDENTIALS"))
  }

  // ── the fake refuses what Google Cloud would ────────────────────────────────

  Given(
    "a test that starts the services {string} and {string} with the test kit on the Secret Manager backend"
  ) { (first: String, second: String) =>
    Vector(first, second).foreach { service =>
      // docs:start secret-manager-kit
      val kit = AnkkaTestKit.start(
        Seq.empty,
        secretBackend = SecretBackendChoice.secretManager(theFake, "spinvibe", service)
      )
      // docs:end secret-manager-kit
      kits += service -> kit
    }
  }

  Given("a handler of {string} has kept {string} as the service secret {string}") {
    (service: String, value: String, name: String) =>
      kit(service).secrets.put(name, value)
  }

  When(
    "{string} reads the service secret {string} of {string} from the Secret Manager fake as its own identity"
  ) { (reader: String, name: String, owner: String) =>
    val asReader = SecretManager(
      theFake.endpoint,
      theFake.account,
      AccessTokens.fixed(FakeSecretManager.tokenFor("spinvibe", reader)),
      5.seconds
    )
    refused =
      try
        asReader.accessLatest(DerivedIds.service("spinvibe", owner, name)): Unit
        None
      catch case e: SecretManagerException => Some(e)
  }

  Then("the Secret Manager fake refuses {string}, as Google Cloud would") { (_: String) =>
    val refusal = refused.getOrElse(fail("the fake admitted the read"))
    assertEquals(refusal.kind, SecretManagerException.Kind.Denied)
    assertEquals(refusal.status, "PERMISSION_DENIED")
  }
