package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{ControlPlaneAcl, ProjectSecretSummary}
import com.thinkmorestupidless.ankka.controlplane.auth.DeployTokenIndex
import com.thinkmorestupidless.ankka.controlplane.deploy.ProjectProjection
import com.thinkmorestupidless.ankka.controlplane.secrets.SecretManagerProjectSecretWriter
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.runtime.secrets.{AccessTokens, DerivedIds, SecretManager}
import com.thinkmorestupidless.ankka.testkit.FakeSecretManager.Identity
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, FakeSecretManager, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.{Duration, Instant}
import scala.concurrent.duration.DurationInt

/**
 * `features/secrets/synced-project-secrets.feature`, the control plane's half: on the Secret
 * Manager backend a member's entry becomes a version in Secret Manager, written as the control
 * plane's own identity, which Google Cloud refuses a read of; the control plane records names only;
 * and the project's secrets are projected, names and entries, for the cloud provider's sync.
 */
final class SecretManagerProjectSecretsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private lazy val identity = TestIdentity()
  private lazy val Member =
    identity.token("member", Some("member@example.test"), expiresIn = 2.hours)
  private lazy val tokens = new DeployTokenIndex(identity.clock)
  private val fake        = FakeSecretManager.start()

  private val writer = SecretManagerProjectSecretWriter(
    SecretManager(
      fake.endpoint,
      fake.account,
      AccessTokens.fixed(Identity.ControlPlane.token),
      5.seconds
    )
  )

  private var testKit: AnkkaTestKit = null
  private var baseUrl: String       = ""
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        ControlPlaneAcl.composite(tokens, identity.acl(), identity.config()),
        auth = Some(identity.config()),
        clock = identity.clock,
        tokens = Some(tokens),
        secrets = Some(writer)
      )*
    )
    testKit = AnkkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), server, tokens))
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
    assertEquals(send("POST", "/organizations/shop", Some("""{"name":"shop"}"""))._1, 204)
    assertEquals(
      send("POST", "/projects/shop", Some("""{"name":"Shop","organizationId":"shop"}"""))._1,
      204
    )

  override def afterAll(): Unit =
    if testKit != null then
      testKit.stop()
      identity.stop()
    fake.stop()

  private def send(method: String, path: String, body: Option[String] = None): (Int, String) =
    val builder = JdkRequest
      .newBuilder(URI.create(baseUrl + path))
      .timeout(Duration.ofSeconds(30))
      .header("Authorization", s"Bearer $Member")
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private val stripe = DerivedIds.projectEntry("shop", "checkout", "STRIPE_KEY")

  test(
    "a member sets an entry and the value goes to Secret Manager, where the control plane cannot read it"
  ) {
    val (status, body) =
      send(
        "PUT",
        "/projects/shop/secrets/checkout",
        Some("""{"entries":{"STRIPE_KEY":"sk_live_1"}}""")
      )
    assertEquals(status, 204, body)
    assertEquals(fake.latestValue(stripe), Some("sk_live_1"))
    assertEquals(
      fake.annotationsOf(stripe).map(_.get(DerivedIds.EntryAnnotation)),
      Some(Some("STRIPE_KEY"))
    )
    // What the control plane asked of Secret Manager, and that it never asked to read.
    val asked = fake.calls.filter(_.identity.contains(Identity.ControlPlane)).map(_.operation)
    assert(asked.exists(_.startsWith("Add:")), asked.toString)
    assert(!asked.exists(_.startsWith("Access:")), asked.toString)
    // And that Google Cloud would refuse it one.
    val asControlPlane = SecretManager(
      fake.endpoint,
      fake.account,
      AccessTokens.fixed(Identity.ControlPlane.token),
      5.seconds
    )
    intercept[com.thinkmorestupidless.ankka.runtime.secrets.SecretManagerException](
      asControlPlane.accessLatest(stripe)
    ): Unit
    // The record and the listing hold names only.
    val (_, listing) = send("GET", "/projects/shop/secrets")
    assert(listing.contains("STRIPE_KEY") && !listing.contains("sk_live_1"), listing)
  }

  test("an entry set again is a new version; a removed one is disabled and no longer read") {
    send(
      "PUT",
      "/projects/shop/secrets/checkout",
      Some("""{"entries":{"STRIPE_KEY":"sk_live_2"}}""")
    ): Unit
    assertEquals(fake.latestValue(stripe), Some("sk_live_2"))
    assertEquals(fake.versionsOf(stripe).count(_._2 == "ENABLED"), 2)
    assertEquals(send("DELETE", "/projects/shop/secrets/checkout?entry=STRIPE_KEY")._1, 204)
    assertEquals(fake.versionsOf(stripe).map(_._2).distinct, Vector("DISABLED"))
    assertEquals(fake.latestValue(stripe), None)
  }

  test("a control plane not given access is refused, and records nothing") {
    fake.access.withhold(Identity.ControlPlane)
    try
      val (status, body) =
        send("PUT", "/projects/shop/secrets/refused", Some("""{"entries":{"KEY":"v"}}"""))
      assertEquals(status, 503, body)
      assert(body.contains("access"), body)
    finally fake.access.restore(Identity.ControlPlane)
    assert(!send("GET", "/projects/shop/secrets")._2.contains("refused"))
  }

  test(
    "the project's secrets are projected by name and entry, and their fingerprint moves when a value is set again"
  ) {
    val at = Instant.parse("2026-10-08T12:00:00Z")
    val first =
      Vector(ProjectSecretSummary("checkout", Vector("WEBHOOK_KEY", "STRIPE_KEY"), Some(at)))
    val spec = ProjectProjection.spec("shop", Map.empty, Map.empty, first)
    assertEquals(
      spec.secrets.map(s => s.name -> s.entries),
      List("checkout" -> List("STRIPE_KEY", "WEBHOOK_KEY"))
    )
    val again = first.map(_.copy(setAt = Some(at.plusSeconds(1))))
    assertNotEquals(
      spec.secretsFingerprint,
      ProjectProjection.spec("shop", Map.empty, Map.empty, again).secretsFingerprint
    )
    assertEquals(spec, ProjectProjection.spec("shop", Map.empty, Map.empty, first.reverse))
    assertEquals(ProjectProjection.spec("shop", Map.empty).secretsFingerprint, None)
    assertEquals(ProjectProjection.spec("shop", Map.empty).secrets, Nil)
  }
