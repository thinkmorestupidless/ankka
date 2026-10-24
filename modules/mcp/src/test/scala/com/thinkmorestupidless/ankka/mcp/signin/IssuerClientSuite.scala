package com.thinkmorestupidless.ankka.mcp.signin

import com.thinkmorestupidless.ankka.auth.oidc.TestIssuer

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.jdk.CollectionConverters.*

/**
 * The broker as a relying party of the service's issuer (R11): discovery read once, the
 * authorization URL it sends a person to, the code exchanged with PKCE and the secret when there is
 * one, and the ID token verified with its nonce.
 */
class IssuerClientSuite extends munit.FunSuite:

  private val customers = FunFixture[TestIssuer](
    _ =>
      val issuer = TestIssuer.serving("customers", "shop")
      issuer.registeredClients.put("broker", None)
      issuer.registeredClients.put("confidential", Some("s3cret"))
      issuer
    ,
    _.stop()
  )

  private val redirect = "http://127.0.0.1:9/_ankka/sign-in/mcp/callback"

  private def client(issuer: TestIssuer, id: String = "broker", secret: Option[String] = None) =
    IssuerClient(issuer.asIssuer().copy(clientId = Some(id), clientSecret = secret))

  private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

  /** Signs the issuer's configured person in at `url`, answering the code its redirect carries. */
  private def signIn(url: String): Map[String, String] =
    val response = http.send(
      HttpRequest.newBuilder(URI.create(url)).GET().build(),
      HttpResponse.BodyHandlers.discarding()
    )
    assertEquals(response.statusCode(), 303)
    val location = URI.create(response.headers().firstValue("Location").get)
    IssuerClient.form(location.getRawQuery)

  customers.test("discovery is read once, and the authorization URL is the broker's own") {
    issuer =>
      val broker = client(issuer)
      val url    = broker
        .authorizationUrl(redirect, "state-1", "nonce-1", "verifier-1")
        .fold(fail(_), identity)
      broker.authorizationUrl(redirect, "state-2", "nonce-2", "verifier-2"): Unit
      assertEquals(issuer.asked.asScala.count(_._1 == "discovery"), 1)
      assert(url.startsWith(s"${issuer.base}/authorize?"), url)
      val query = IssuerClient.form(URI.create(url).getRawQuery)
      assertEquals(query("client_id"), "broker")
      assertEquals(query("redirect_uri"), redirect)
      assertEquals(query("scope"), "openid")
      assertEquals(query("response_type"), "code")
      assertEquals(query("state"), "state-1")
      assertEquals(query("nonce"), "nonce-1")
      assertEquals(query("code_challenge"), IssuerClient.challenge("verifier-1"))
      assertEquals(query("code_challenge_method"), "S256")
  }

  customers.test("the code is exchanged for an ID token naming the person, with its nonce") {
    issuer =>
      val broker = client(issuer)
      val back   =
        signIn(broker.authorizationUrl(redirect, "s", "the-nonce", "v").fold(fail(_), identity))
      assertEquals(back("state"), "s")
      val idToken   = broker.exchange(back("code"), redirect, "v").fold(fail(_), identity)
      val principal = broker.verifyIdToken(idToken, "the-nonce").fold(fail(_), identity)
      assertEquals(principal.subject, "ada")
      assertEquals(principal.email, Some("ada@example.test"))
      assertEquals(principal.issuer, Some("customers"))
      assert(!principal.claims.contains("nonce"), principal.claims)
      val sent = issuer.asked.asScala.filter(_._1 == "token").map(_._2).head
      assert(sent.contains("code_verifier"), sent)
      assert(!sent.contains("client_secret"), "a public client sends no secret")
  }

  customers.test("an ID token with another nonce, or signed by another key, names nobody") {
    issuer =>
      val broker = client(issuer)
      val first  = signIn(broker.authorizationUrl(redirect, "s", "n1", "v").fold(fail(_), identity))
      val token  = broker.exchange(first("code"), redirect, "v").fold(fail(_), identity)
      assert(broker.verifyIdToken(token, "another").isLeft)
      issuer.signIdTokensWithForeignKey = true
      val second = signIn(broker.authorizationUrl(redirect, "s", "n2", "v").fold(fail(_), identity))
      val forged = broker.exchange(second("code"), redirect, "v").fold(fail(_), identity)
      assert(
        broker.verifyIdToken(forged, "n2").isLeft,
        "signed by a key the issuer does not publish"
      )
  }

  customers.test("a confidential client sends its secret, and a wrong one is refused") { issuer =>
    val right = client(issuer, "confidential", Some("s3cret"))
    val code  =
      signIn(right.authorizationUrl(redirect, "s", "n", "v").fold(fail(_), identity))("code")
    assert(right.exchange(code, redirect, "v").isRight)
    val wrong = client(issuer, "confidential", Some("guess"))
    val again =
      signIn(wrong.authorizationUrl(redirect, "s", "n", "v").fold(fail(_), identity))("code")
    assert(wrong.exchange(again, redirect, "v").isLeft)
  }

  test("an issuer that cannot be reached is said so, and nothing is cached") {
    val issuer = TestIssuer.serving("gone", "shop")
    val broker = client(issuer)
    issuer.stop()
    assert(broker.endpoints().isLeft)
  }
