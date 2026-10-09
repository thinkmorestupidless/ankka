package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.auth.oidc.{Oidc, OidcConfig, TestIssuer}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.core.personal.{KeyResult, PersonalCipher}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.erasure.Grant
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.concurrent.duration.*

/**
 * `POST /decrypt` (FR-028): a machine outside the installation, with a token its issuer signed and
 * a grant to decrypt in the envelope's project, is told one field's value; the key never leaves.
 * Refused without a grant, once the grant is revoked, and once the subject is erased; every answer
 * and refusal counted on the subject's key.
 */
class DecryptRouteSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private val issuer                           = TestIssuer()
  @volatile private var granted: Vector[Grant] = Vector.empty
  private val state = KeyringState(
    Grants.none,
    LogSources.none,
    ackWithin = 2.seconds,
    applySchema = false,
    machines = principal => granted.filter(_.principal == principal).toSet,
    machineAcl = Oidc.authenticate(OidcConfig(Vector(issuer.asIssuer())))
  )
  private var kit: AnkkaTestKit = null
  private val http              = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Keyring.components,
      Seq(
        KeyringRuntime(state),
        HttpServer.at("127.0.0.1", 0)(clients => KeyringEndpoint(clients, state))
      ),
      keyring = None
    )

  override def afterAll(): Unit =
    if kit != null then kit.stop()
    issuer.stop()

  private def base = kit.service.boundAddresses.find(_.startsWith("http")).get

  private val machine = "acme/ledger-sync"
  private def token   = issuer.token("svc-ledger", claims = Map("machine" -> machine))

  /** A personal field of `subject` in `brand`, as a service would have written it. */
  private def envelope(subject: String, value: String): String =
    val key = state.keys.subject("brand", subject, create = true) match
      case KeyResult.Available(k) => k
      case other                  => fail(s"no key: $other")
    val held = PersonalCipher.encrypt(
      key,
      PersonalCipher.associated(subject, "brand"),
      s"\"$value\"".getBytes(UTF_8)
    )
    s"""{"subject":"$subject","project":"brand","data":"${Base64.getEncoder.encodeToString(
        held
      )}"}"""

  private def decrypt(body: String, bearer: Option[String] = Some(token)): (Int, String) =
    val builder = HttpRequest
      .newBuilder(URI.create(base + "/decrypt"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
    bearer.foreach(t => builder.header("Authorization", s"Bearer $t"): Unit)
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def counts(subject: String): (Long, Long) =
    val key = kit.componentClient
      .forKeyValueEntity(EntityId(s"brand/$subject"))
      .call(SubjectKeyEntity.state)
      .invoke()
    (key.decryptions, key.refusals)

  private def allow(): Unit =
    granted = Vector(Grant("brand", s"machine:$machine", "players", Set("decrypt")))

  test("a machine granted decryption is told the value, and the decryption is counted") {
    allow()
    val (status, body) = decrypt(envelope("player/m1", "Ada Byron"))
    assertEquals(status, 200, body)
    assert(body.contains("Ada Byron"), body)
    assert(!body.contains("key"), "the answer carries the value, never the key")
    assertEquals(counts("player/m1")._1, 1L)
  }

  test("without a grant, or with one that does not allow decryption, the machine is refused") {
    granted = Vector(Grant("brand", s"machine:$machine", "players", Set("read")))
    val (status, body) = decrypt(envelope("player/m2", "Grace Hopper"))
    assertEquals(status, 403, body)
    assertEquals(counts("player/m2")._2, 1L)
  }

  test("a grant revoked refuses the next request") {
    allow()
    val held = envelope("player/m3", "Kept")
    assertEquals(decrypt(held)._1, 200)
    granted = Vector.empty
    assertEquals(decrypt(held)._1, 403)
  }

  test("a subject erased is refused, the grant notwithstanding") {
    allow()
    val held = envelope("player/m4", "Gone")
    state.apply("brand", "e-m4", "player/m4", 1L, reapply = false)
    val (status, body) = decrypt(held)
    assertEquals(status, 403, body)
    assert(body.contains("erased"), body)
  }

  test("no token, or a token naming no machine, is refused before anything is read") {
    allow()
    assertEquals(decrypt(envelope("player/m5", "x"), bearer = None)._1, 401)
    assertEquals(decrypt(envelope("player/m5", "x"), bearer = Some(issuer.token("svc")))._1, 403)
  }
