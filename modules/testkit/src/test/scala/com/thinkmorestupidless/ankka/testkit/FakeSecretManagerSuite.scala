package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.secrets.DerivedIds
import com.thinkmorestupidless.ankka.runtime.secrets.{
  AccessTokens,
  SecretManager,
  SecretManagerException
}
import com.thinkmorestupidless.ankka.testkit.FakeSecretManager.Identity

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * The fake, driven by the platform's own client: the calls the store makes, the answers it reads,
 * and the refusals Google Cloud would make. If the two disagreed about a path or a shape, every
 * suite built on the fake would be testing a conversation Google never has.
 */
final class FakeSecretManagerSuite extends munit.FunSuite:

  private var fake: FakeSecretManager = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit = fake = FakeSecretManager.start()
  override def afterEach(context: AfterEach): Unit   = fake.stop()

  private def client(identity: Identity): SecretManager =
    SecretManager(fake.endpoint, fake.account, AccessTokens.fixed(identity.token), 5.seconds)

  private val payments = Identity.Service("spinvibe", "payments")
  private val wallet   = Identity.Service("spinvibe", "wallet")
  private val ledger   = Identity.Service("bank", "ledger")

  private def bytes(text: String)     = text.getBytes(StandardCharsets.UTF_8)
  private def text(data: Array[Byte]) = String(data, StandardCharsets.UTF_8)

  private def refusal(kind: SecretManagerException.Kind)(work: => Any): SecretManagerException =
    val e = intercept[SecretManagerException](work)
    assertEquals(e.kind, kind, e.getMessage)
    e

  test("a secret is made, given versions, read at its newest, and deleted with every version") {
    val sm = client(payments)
    val id = DerivedIds.service("spinvibe", "payments", "acme")
    assert(sm.createSecret(id, DerivedIds.serviceAnnotations("spinvibe", "payments", "acme")))
    assertEquals(sm.addVersion(id, bytes("sk-1")), 1L)
    assertEquals(sm.addVersion(id, bytes("sk-2")), 2L)
    val read = sm.accessLatest(id).getOrElse(fail("nothing read"))
    assertEquals((read.version, text(read.data)), (2L, "sk-2"))
    assertEquals(sm.listEnabledVersions(id), Vector(2L, 1L))
    assertEquals(fake.annotationsOf(id).flatMap(_.get(DerivedIds.NameAnnotation)), Some("acme"))
    assert(sm.deleteSecret(id))
    assertEquals(sm.accessLatest(id), None)
    assert(!sm.deleteSecret(id), "a second delete found a secret")
  }

  test("a second create answers that the secret exists; a version for none is NotFound") {
    val sm = client(payments)
    val id = DerivedIds.service("spinvibe", "payments", "acme")
    assert(sm.createSecret(id, Map.empty))
    assert(!sm.createSecret(id, Map.empty))
    refusal(SecretManagerException.Kind.NotFound)(
      sm.addVersion(DerivedIds.service("spinvibe", "payments", "never"), bytes("x"))
    )
  }

  test("latest skips a version disabled by hand; destroy and disable leave the rest") {
    val sm = client(payments)
    val id = DerivedIds.service("spinvibe", "payments", "acme")
    sm.createSecret(id, Map.empty): Unit
    Seq("a", "b", "c").foreach(v => sm.addVersion(id, bytes(v)): Unit)
    fake.disableByHand(id, 3)
    assertEquals(sm.accessLatest(id).map(a => (a.version, text(a.data))), Some((2L, "b")))
    sm.destroyVersion(id, 1)
    assertEquals(sm.listEnabledVersions(id), Vector(2L))
    assertEquals(fake.versionsOf(id).map(_._2), Vector("DESTROYED", "ENABLED", "DISABLED"))
  }

  test("a service is refused another service's secret, another project's, and the list") {
    val id = DerivedIds.service("spinvibe", "payments", "acme")
    client(payments).createSecret(id, Map.empty): Unit
    client(payments).addVersion(id, bytes("sk")): Unit
    val denied = refusal(SecretManagerException.Kind.Denied)(client(wallet).accessLatest(id))
    assert(denied.getMessage.contains(id), denied.getMessage)
    refusal(SecretManagerException.Kind.Denied)(client(ledger).accessLatest(id))
    refusal(SecretManagerException.Kind.Denied)(client(wallet).deleteSecret(id))
    refusal(SecretManagerException.Kind.Denied)(client(wallet).addVersion(id, bytes("mine")))
    assertEquals(fake.latestValue(id), Some("sk"))
  }

  test(
    "a service reads its project's entries and may not write them; the control plane the reverse"
  ) {
    val entry = DerivedIds.projectEntry("spinvibe", "checkout", "STRIPE_KEY")
    val cp    = client(Identity.ControlPlane)
    assert(
      cp.createSecret(entry, DerivedIds.entryAnnotations("spinvibe", "checkout", "STRIPE_KEY"))
    )
    cp.addVersion(entry, bytes("sk_live_1")): Unit
    assertEquals(client(payments).accessLatest(entry).map(a => text(a.data)), Some("sk_live_1"))
    refusal(SecretManagerException.Kind.Denied)(client(payments).addVersion(entry, bytes("x")))
    refusal(SecretManagerException.Kind.Denied)(cp.accessLatest(entry))
    refusal(SecretManagerException.Kind.Denied)(client(ledger).accessLatest(entry))
    cp.disableVersion(entry, 1)
    assertEquals(client(payments).accessLatest(entry), None)
  }

  test("the provider reads entries and never a service secret") {
    val entry  = DerivedIds.projectEntry("spinvibe", "checkout", "STRIPE_KEY")
    val secret = DerivedIds.service("spinvibe", "payments", "acme")
    fake.seed(entry, "sk_live_1")
    fake.seed(secret, "sk")
    assertEquals(
      client(Identity.Provider).accessLatest(entry).map(a => text(a.data)),
      Some("sk_live_1")
    )
    refusal(SecretManagerException.Kind.Denied)(client(Identity.Provider).accessLatest(secret))
  }

  test("a create under another's prefix is admitted, and the creator can do nothing else with it") {
    val squatted = DerivedIds.service("spinvibe", "payments", "acme")
    assert(client(wallet).createSecret(squatted, Map.empty))
    refusal(SecretManagerException.Kind.Denied)(client(wallet).addVersion(squatted, bytes("x")))
    refusal(SecretManagerException.Kind.Denied)(client(wallet).accessLatest(squatted))
    client(payments).addVersion(squatted, bytes("sk")): Unit
    assertEquals(client(payments).accessLatest(squatted).map(a => text(a.data)), Some("sk"))
  }

  test("withheld access refuses everything, create included, until it is restored") {
    val id = DerivedIds.service("spinvibe", "payments", "acme")
    fake.access.withhold(payments)
    refusal(SecretManagerException.Kind.Denied)(client(payments).createSecret(id, Map.empty))
    fake.access.restore(payments)
    assert(client(payments).createSecret(id, Map.empty))
  }

  test(
    "an id Google would refuse, an unknown token, an outage and a failure answer as Google's do"
  ) {
    refusal(SecretManagerException.Kind.Invalid)(client(payments).accessLatest("bad.id"))
    val stranger = SecretManager(fake.endpoint, fake.account, AccessTokens.fixed("who"), 5.seconds)
    refusal(SecretManagerException.Kind.Denied)(stranger.accessLatest("s_x_y_z"))
    fake.failNext(503)
    refusal(SecretManagerException.Kind.Unavailable)(
      client(payments).accessLatest("s_spinvibe_payments_a")
    )
    fake.failNext(429)
    refusal(SecretManagerException.Kind.Unavailable)(
      client(payments).accessLatest("s_spinvibe_payments_a")
    )
    fake.unreachable(true)
    refusal(SecretManagerException.Kind.Unavailable)(
      client(payments).accessLatest("s_spinvibe_payments_a")
    )
    fake.unreachable(false)
    assertEquals(client(payments).accessLatest("s_spinvibe_payments_a"), None)
  }

  test("an annotation key Google refuses is refused; a payload over 64 KiB is refused") {
    val id = DerivedIds.service("spinvibe", "payments", "acme")
    refusal(SecretManagerException.Kind.Invalid)(
      client(payments).createSecret(id, Map("ankka.thinkmorestupidless.com/name" -> "acme"))
    )
    client(payments).createSecret(id, Map.empty): Unit
    refusal(SecretManagerException.Kind.Invalid)(
      client(payments).addVersion(id, Array.fill(65537)('x'.toByte))
    )
  }

  test("the calls are recorded with who made them") {
    val id = DerivedIds.service("spinvibe", "payments", "acme")
    client(payments).accessLatest(id): Unit
    intercept[SecretManagerException](client(wallet).accessLatest(id)): Unit
    assertEquals(
      fake.calls.map(c => (c.identity, c.operation, c.status)),
      Vector((Some(payments), s"Access:$id", 404), (Some(wallet), s"Access:$id", 403))
    )
  }
