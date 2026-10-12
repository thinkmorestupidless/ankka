package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{MachineRegistered, MachineSummary}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.auth.MachineSettings
import com.thinkmorestupidless.ankka.http.{Caller, MachineTokens}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.DurationInt

/**
 * Machines on an organization and the token route (feature 040), over HTTP against the shipped
 * assembly: who may register, list and delete one; a token by client credentials, in the form or as
 * HTTP Basic, that a service verifies against the published keys; every wrong client answered
 * alike; and the route's rate.
 */
class MachineTokenSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private val issuer = "https://api.example.test"
  private val cp     = GrantsHarness(machineSettings = MachineSettings(issuer, tokenRate = 12))

  override def beforeAll(): Unit =
    cp.start()
    cp.organization("affiliates", owner = "bo")
    cp.member("cy", "affiliates", owner = "bo")
    cp.organization("eitheror", owner = "ada")

  override def afterAll(): Unit = cp.stop()

  private def register(name: String, as: String = "bo"): (Int, String) =
    cp.send("POST", "/organizations/affiliates/machines", Some(s"""{"name":"$name"}"""), as)

  private def registered(name: String): MachineRegistered =
    val (status, body) = register(name)
    assertEquals(status, 200, body)
    readFromString[MachineRegistered](body)

  private def form(fields: (String, String)*): String =
    fields.map((k, v) => s"$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}").mkString("&")

  private def token(clientId: String, secret: String, grant: String = "client_credentials") =
    cp.raw(
      "POST",
      "/oauth/token",
      Some(form("grant_type" -> grant, "client_id" -> clientId, "client_secret" -> secret))
    )

  /** A service's verifier over the key set the control plane publishes. */
  private def verifier: MachineTokens =
    val (status, jwks, _) = cp.raw("GET", "/.well-known/jwks.json", None)
    assertEquals(status, 200, jwks)
    MachineTokens.withKeys(issuer, MachineTokens.keysOf(jwks))

  private def accessToken(body: String): String =
    "\"access_token\":\"([^\"]+)\"".r.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(body))

  test("an owner registers a machine and is shown its secret once; the listing never shows it") {
    val machine = registered("network")
    assertEquals(machine.clientId, "machine:affiliates/network")
    assert(machine.clientSecret.matches("[0-9a-f]{64}"), machine.clientSecret)
    assertEquals(machine.tokenUrl, s"$issuer/oauth/token")
    val (status, listed) = cp.send("GET", "/organizations/affiliates/machines", as = "cy")
    assertEquals(status, 200, listed)
    val summaries = cp.eventually("the machine listed") {
      val rows = readFromString[Vector[MachineSummary]](
        cp.send("GET", "/organizations/affiliates/machines", as = "cy")._2
      )
      Option.when(rows.exists(_.name == "network"))(rows)
    }
    assertEquals(summaries.find(_.name == "network").flatMap(_.registeredBy).isDefined, true)
    assert(!listed.contains(machine.clientSecret))
  }

  test("a member, a deploy token and another organization's owner may not register one") {
    assertEquals(register("by-member", as = "cy")._1, 403)
    val deploy = cp.deployToken("affiliates", owner = "bo")
    assertEquals(register("by-token", as = deploy)._1, 403)
    assertEquals(register("by-stranger", as = "ada")._1, 404)
  }

  test("a name registered and not deleted is refused; deleted, it is a new machine") {
    val first = registered("twice")
    assertEquals(register("twice")._1, 409)
    assertEquals(cp.send("DELETE", "/organizations/affiliates/machines/twice", as = "bo")._1, 204)
    val second = registered("twice")
    assert(second.clientSecret != first.clientSecret)
    assertEquals(token(first.clientId, first.clientSecret)._1, 401)
    assertEquals(token(second.clientId, second.clientSecret)._1, 200)
  }

  test(
    "client credentials in the form, or as HTTP Basic, are answered a token a service verifies"
  ) {
    val machine            = registered("verified")
    val (status, body, hs) = token(machine.clientId, machine.clientSecret)
    assertEquals(status, 200, body)
    assert(body.contains("\"token_type\":\"Bearer\"") && body.contains("\"expires_in\":900"), body)
    assertEquals(hs.get("cache-control"), Some("no-store"))
    assertEquals(
      verifier.verify(accessToken(body)),
      Right(Caller.Machine("affiliates", "verified"))
    )
    val basic = Base64.getEncoder.encodeToString(
      s"${URLEncoder.encode(machine.clientId, StandardCharsets.UTF_8)}:${machine.clientSecret}"
        .getBytes(StandardCharsets.UTF_8)
    )
    val (byBasic, basicBody, _) = cp.raw(
      "POST",
      "/oauth/token",
      Some("grant_type=client_credentials"),
      headers = Map("Authorization" -> s"Basic $basic")
    )
    assertEquals(byBasic, 200, basicBody)
    assertEquals(
      verifier.verify(accessToken(basicBody)),
      Right(Caller.Machine("affiliates", "verified"))
    )
    // Unencoded, as Apache Kafka's client sends it by default: the id's own colon is not the split.
    val raw = Base64.getEncoder.encodeToString(
      s"${machine.clientId}:${machine.clientSecret}".getBytes(StandardCharsets.UTF_8)
    )
    val (byRaw, rawBody, _) = cp.raw(
      "POST",
      "/oauth/token",
      Some("grant_type=client_credentials"),
      headers = Map("Authorization" -> s"Basic $raw")
    )
    assertEquals(byRaw, 200, rawBody)
    assertEquals(
      verifier.verify(accessToken(rawBody)),
      Right(Caller.Machine("affiliates", "verified"))
    )
  }

  test("a wrong secret, an unknown machine and a deleted one are all one answer") {
    val machine = registered("alike")
    val wrong   = token(machine.clientId, "0" * 64)
    val unknown = token("machine:affiliates/nobody", machine.clientSecret)
    cp.send("DELETE", "/organizations/affiliates/machines/alike", as = "bo"): Unit
    val deleted = token(machine.clientId, machine.clientSecret)
    for (status, body, _) <- Vector(wrong, unknown, deleted) do
      assertEquals((status, body), (401, """{"error":"invalid_client"}"""))
  }

  test(
    "a grant type other than client credentials is refused, and a request with none is invalid"
  ) {
    val machine = registered("grants")
    assertEquals(
      token(machine.clientId, machine.clientSecret, grant = "password")._2,
      """{"error":"unsupported_grant_type"}"""
    )
    val (status, body, _) =
      cp.raw("POST", "/oauth/token", Some(form("client_id" -> machine.clientId)))
    assertEquals((status, body), (400, """{"error":"invalid_request"}"""))
  }

  test("the thirteenth request in a minute for one client id is told to come back") {
    val machine = registered("eager")
    val answers = (1 to 13).map(_ => token(machine.clientId, machine.clientSecret))
    assertEquals(answers.take(12).map(_._1).distinct, Vector(200))
    val (status, _, headers) = answers.last
    assertEquals(status, 429)
    assert(headers.get("retry-after").exists(_.toLong >= 1), headers.toString)
    // Another client id has a bucket of its own.
    assertEquals(token("machine:affiliates/other", "x")._1, 401)
  }

  test(
    "the key set names the key a token names, and the issuer document says where everything is"
  ) {
    val machine = registered("discovering")
    val signed  = accessToken(token(machine.clientId, machine.clientSecret)._2)
    val kid     = String(Base64.getUrlDecoder.decode(signed.split('.')(0)), StandardCharsets.UTF_8)
    val (_, jwks, _) = cp.raw("GET", "/.well-known/jwks.json", None)
    val named        = "\"kid\":\"([^\"]+)\"".r.findFirstMatchIn(kid).map(_.group(1)).get
    assert(jwks.contains(s"\"kid\":\"$named\""), jwks)
    val (status, document, _) = cp.raw("GET", "/.well-known/openid-configuration", None)
    assertEquals(status, 200)
    assertEquals(
      document,
      s"""{"issuer":"$issuer","jwks_uri":"$issuer/.well-known/jwks.json","token_endpoint":"$issuer/oauth/token"}"""
    )
  }

  test("an owner limits a machine's byte rates, within the installation's ceiling") {
    registered("limited"): Unit
    val (ok, body) = cp.send(
      "PUT",
      "/organizations/affiliates/machines/limited/byte-rates",
      Some(
        """{"produceBytesPerSecond":1048576,"consumeBytesPerSecond":4194304,"requestPercentage":50}"""
      ),
      as = "bo"
    )
    assertEquals(ok, 200, body)
    assert(body.contains("\"requestPercentage\":50"), body)
    val (over, refusal) = cp.send(
      "PUT",
      "/organizations/affiliates/machines/limited/byte-rates",
      Some(
        """{"produceBytesPerSecond":999999999999,"consumeBytesPerSecond":1,"requestPercentage":101}"""
      ),
      as = "bo"
    )
    assertEquals(over, 400, refusal)
    assert(
      refusal.contains("over the installation's ceiling") && refusal.contains("from 1 to 100"),
      refusal
    )
  }

  test(
    "a registered machine is written to the cluster with its byte rates, and removed when deleted"
  ) {
    registered("projected"): Unit
    cp.eventually("the machine's resource") {
      cp.cluster.machine("affiliates.projected").filter(_.name == "projected")
    }: Unit
    cp.send(
      "PUT",
      "/organizations/affiliates/machines/projected/byte-rates",
      Some(
        """{"produceBytesPerSecond":1024,"consumeBytesPerSecond":2048,"requestPercentage":10}"""
      ),
      as = "bo"
    ): Unit
    cp.eventually("the machine's byte rates on its resource") {
      cp.cluster.machine("affiliates.projected").filter(_.requestPercentage.contains(10))
    }: Unit
    cp.send("DELETE", "/organizations/affiliates/machines/projected", as = "bo"): Unit
    cp.eventually("the machine's resource removed") {
      Option.when(cp.cluster.machine("affiliates.projected").isEmpty)(())
    }
  }
