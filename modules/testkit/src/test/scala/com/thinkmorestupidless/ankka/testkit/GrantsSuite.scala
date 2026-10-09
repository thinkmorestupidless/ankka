package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.core.secrets.DerivedIds
import com.thinkmorestupidless.ankka.runtime.secrets.{
  AccessTokens,
  SecretManager,
  SecretManagerException
}
import com.thinkmorestupidless.ankka.testkit.FakeSecretManager.Identity

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * `features/secrets/grants.feature`: a service reaches only its own secrets.
 *
 * Real services keep and read through their stores, on one Secret Manager fake that refuses what
 * Google Cloud's secret access would refuse; the attacker is "wallet" calling Secret Manager
 * directly with the identity its pod has — not through ankka code, which an attacker in the pod
 * would not use. Every refusal is Google Cloud's (the fake's), never the store's.
 */
final class GrantsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private val fake = FakeSecretManager.start()

  private val payments: Identity.Service = Identity.Service("spinvibe", "payments")
  private val wallet: Identity.Service   = Identity.Service("spinvibe", "wallet")
  private val ledger: Identity.Service   = Identity.Service("bank", "ledger")

  private var paymentsKit: AnkkaTestKit = scala.compiletime.uninitialized
  private var ledgerKit: AnkkaTestKit   = scala.compiletime.uninitialized

  private def kit(who: Identity.Service): AnkkaTestKit =
    AnkkaTestKit.start(
      Seq.empty,
      secretBackend = SecretBackendChoice.secretManager(fake, who.project, who.service)
    )

  override def beforeAll(): Unit =
    paymentsKit = kit(payments)
    ledgerKit = kit(ledger)

  override def afterAll(): Unit =
    Option(paymentsKit).foreach(_.stop())
    Option(ledgerKit).foreach(_.stop())
    fake.stop()

  /** Secret Manager as the identity a service's pod has, called directly. */
  private def asIdentity(who: Identity): SecretManager =
    SecretManager(fake.endpoint, fake.account, AccessTokens.fixed(who.token), 5.seconds)

  private def refusedByGoogle(work: => Any): Unit =
    val e = intercept[SecretManagerException](work)
    assertEquals(e.kind, SecretManagerException.Kind.Denied, e.getMessage)

  private def text(bytes: Array[Byte]) = String(bytes, StandardCharsets.UTF_8)

  private def setEntry(project: String, secret: String, entry: String, value: String): String =
    val id = DerivedIds.projectEntry(project, secret, entry)
    val cp = asIdentity(Identity.ControlPlane)
    cp.createSecret(id, DerivedIds.entryAnnotations(project, secret, entry)): Unit
    cp.addVersion(id, value.getBytes(StandardCharsets.UTF_8)): Unit
    id

  test("a service is refused another service's service secret of the same project") {
    paymentsKit.secrets.put("acme", "sk-acme-1")
    refusedByGoogle(
      asIdentity(wallet).accessLatest(DerivedIds.service("spinvibe", "payments", "acme"))
    )
  }

  test("a service is refused the secrets of another project") {
    ledgerKit.secrets.put("acme", "sk-acme-1")
    val entry = setEntry("bank", "checkout", "STRIPE_KEY", "sk_live_1")
    refusedByGoogle(asIdentity(wallet).accessLatest(DerivedIds.service("bank", "ledger", "acme")))
    refusedByGoogle(asIdentity(wallet).accessLatest(entry))
  }

  test("a service reads an entry of a project secret of its own project") {
    val entry = setEntry("spinvibe", "checkout", "STRIPE_KEY", "sk_live_1")
    assertEquals(asIdentity(wallet).accessLatest(entry).map(a => text(a.data)), Some("sk_live_1"))
  }

  test("a service is refused a write to an entry of a project secret of its own project") {
    val entry = setEntry("spinvibe", "checkout", "WEBHOOK_KEY", "wh_1")
    refusedByGoogle(asIdentity(wallet).addVersion(entry, "mine".getBytes(StandardCharsets.UTF_8)))
    assertEquals(fake.latestValue(entry), Some("wh_1"))
  }

  test("a service cannot list the secrets Google Cloud holds for the installation") {
    paymentsKit.secrets.put("listed", "sk-1")
    val response = HttpClient
      .newHttpClient()
      .send(
        HttpRequest
          .newBuilder(URI(s"${fake.endpoint}/v1/projects/${fake.account}/secrets"))
          .header("Authorization", s"Bearer ${wallet.token}")
          .GET()
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
    assertEquals(response.statusCode, 403)
    assert(response.body.contains("PERMISSION_DENIED"), response.body)
    assert(!response.body.contains("s_spinvibe_payments_"), response.body)
  }

  test(
    "a service that creates a secret under another service's name can neither read nor write it, and the other service keeps over it"
  ) {
    val id = DerivedIds.service("spinvibe", "payments", "squatted")
    assert(asIdentity(wallet).createSecret(id, Map.empty), "Google Cloud admits any create")
    refusedByGoogle(asIdentity(wallet).accessLatest(id))
    refusedByGoogle(asIdentity(wallet).addVersion(id, "x".getBytes(StandardCharsets.UTF_8)))
    refusedByGoogle(asIdentity(wallet).deleteSecret(id))
    paymentsKit.secrets.put("squatted", "sk-acme-1")
    assertEquals(paymentsKit.secrets.get("squatted"), Some("sk-acme-1"))
  }

  test("a service deleted and deployed again reads the service secrets it kept in Secret Manager") {
    paymentsKit.secrets.put("survives", "sk-acme-1")
    paymentsKit.stop()
    // A kit of its own: a new database, so what is read cannot have come from the old one.
    paymentsKit = kit(payments)
    assertEquals(paymentsKit.secrets.get("survives"), Some("sk-acme-1"))
  }

  test(
    "a service whose secret access is not written yet is told so, never that the secret is missing"
  ) {
    paymentsKit.secrets.put("pending", "sk-1")
    fake.access.withhold(payments)
    try
      val error = intercept[CommandError](paymentsKit.secrets.get("pending"))
      assertEquals(error.code, ErrorCode.Internal)
      assert(error.message.contains("s_spinvibe_payments_"), error.message)
      assert(error.message.contains("access"), error.message)
    finally fake.access.restore(payments)
    assertEquals(paymentsKit.secrets.get("pending"), Some("sk-1"))
  }
