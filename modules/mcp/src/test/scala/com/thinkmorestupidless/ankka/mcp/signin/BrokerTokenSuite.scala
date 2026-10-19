package com.thinkmorestupidless.ankka.mcp.signin

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.mcp.TestTools.MovableClock

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{KeyPair, KeyPairGenerator}
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.duration.*

/**
 * The broker's access token (contracts/http-wire.md): its shape, and every way a token that is not
 * this endpoint's own is refused.
 */
class BrokerTokenSuite extends munit.FunSuite:

  private def pair(): KeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()

  private val ours    = pair()
  private val foreign = pair()
  private val clock   = MovableClock(Instant.parse("2026-10-19T10:00:00Z"))
  private val kid     = "20261019100000abcd"

  private val tokens = BrokerTokens(
    () => kid -> ours.getPrivate,
    asked => Option.when(asked == kid)(ours.getPublic),
    clock,
    1.hour
  )

  private val issuer   = "https://cart.shop.example/_ankka/sign-in/mcp"
  private val resource = "https://cart.shop.example/mcp"
  private val ada = SignedIn(
    "ada",
    Some("Ada"),
    Some("ada@example.com"),
    true,
    Set("staff"),
    Map("org" -> "o1"),
    "customers"
  )

  private def decoded(part: String): Json =
    Json.parse(String(Jwt.decode(part).get, UTF_8)).toOption.get

  test("a token names the broker, the person, the endpoint and the service's issuer") {
    val token                     = tokens.mint(issuer, resource, ada)
    val Array(header, claims, _*) = token.split('.'): @unchecked
    assertEquals(
      decoded(header),
      Json.obj("alg" -> Json.str("RS256"), "kid" -> Json.str(kid), "typ" -> Json.str("JWT"))
    )
    val c = decoded(claims)
    assertEquals(c("iss"), Some(Json.str(issuer)))
    assertEquals(c("sub"), Some(Json.str("ada")))
    assertEquals(c("aud"), Some(Json.str(resource)))
    assertEquals(c("typ"), Some(Json.str("Bearer")))
    assertEquals(c("sign_in_issuer"), Some(Json.str("customers")))
    assertEquals(c("name"), Some(Json.str("Ada")))
    assertEquals(c("email"), Some(Json.str("ada@example.com")))
    assertEquals(c("org"), Some(Json.str("o1")))
    val iat = c("iat").flatMap(_.asDouble).get.toLong
    assertEquals(c("exp").flatMap(_.asDouble).map(_.toLong), Some(iat + 3600))
    assert(c("jti").flatMap(_.asString).exists(_.nonEmpty))
  }

  test("a token is accepted at its own endpoint as the person, issuer sign-in") {
    val principal =
      tokens.verify(tokens.mint(issuer, resource, ada), "/mcp").fold(fail(_), identity)
    assertEquals(principal.subject, "ada")
    assertEquals(principal.name, Some("Ada"))
    assertEquals(principal.email, Some("ada@example.com"))
    assert(principal.emailVerified)
    assertEquals(principal.roles, Set("staff"))
    assertEquals(principal.issuer, Some("sign-in"))
    assertEquals(principal.claims.get("sign_in_issuer"), Some("customers"))
    assertEquals(principal.claims.get("org"), Some("o1"))
  }

  test("a token for another endpoint, or from another endpoint's broker, is refused") {
    val token = tokens.mint(issuer, resource, ada)
    assert(tokens.verify(token, "/office").isLeft)
    val elsewhere = tokens.mint("https://cart.shop.example/_ankka/sign-in/office", resource, ada)
    assert(tokens.verify(elsewhere, "/mcp").isLeft)
    val otherHost = tokens.mint("https://evil.example/_ankka/sign-in/mcp", resource, ada)
    assert(tokens.verify(otherHost, "/mcp").isLeft, "issuer and audience of two services")
  }

  test("a token another service's keys signed is refused, though it names the same key") {
    val forged = BrokerTokens(() => kid -> foreign.getPrivate, _ => None, clock, 1.hour)
      .mint(issuer, resource, ada)
    assertEquals(tokens.verify(forged, "/mcp"), Left("a bad signature"))
  }

  test("a key this service does not hold is refused, and nothing else is tried") {
    val unknown = BrokerTokens(() => "nope" -> ours.getPrivate, _ => None, clock, 1.hour)
      .mint(issuer, resource, ada)
    assertEquals(tokens.verify(unknown, "/mcp"), Left("key 'nope' is not this service's"))
  }

  test("an expired token is refused, a minute's skew allowed") {
    val token = tokens.mint(issuer, resource, ada)
    clock.advance(java.time.Duration.ofMinutes(60))
    assert(tokens.verify(token, "/mcp").isRight, "within the skew")
    clock.advance(java.time.Duration.ofMinutes(2))
    assertEquals(tokens.verify(token, "/mcp"), Left("expired"))
    clock.advance(java.time.Duration.ofMinutes(-62))
  }

  test("alg none and HS256 are refused before any key is looked up") {
    val claims = Jwt.encode(
      Json
        .obj(
          "iss" -> Json.str(issuer),
          "sub" -> Json.str("ada"),
          "aud" -> Json.str(resource),
          "typ" -> Json.str("Bearer")
        )
        .render
        .getBytes(UTF_8)
    )
    def header(alg: String) =
      Jwt.encode(Json.obj("alg" -> Json.str(alg), "kid" -> Json.str(kid)).render.getBytes(UTF_8))
    val none = s"${header("none")}.$claims."
    assert(
      tokens.verify(none, "/mcp").left.exists(_.contains("algorithm")),
      tokens.verify(none, "/mcp").toString
    )
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(ours.getPublic.getEncoded, "HmacSHA256"))
    val input = s"${header("HS256")}.$claims"
    val hs    = s"$input.${Jwt.encode(mac.doFinal(input.getBytes(UTF_8)))}"
    assertEquals(tokens.verify(hs, "/mcp"), Left("algorithm 'HS256' is not RS256"))
  }

  test("an unbound authenticator answers unavailable, and one with no bearer challenges") {
    import com.thinkmorestupidless.ankka.http.{AuthDecision, QueryParams, SimpleRequestContext}
    val auth = BrokerAuthenticator("/mcp")
    def request(headers: (String, String)*) =
      SimpleRequestContext("POST", "/mcp", QueryParams.empty, headers.toVector, None)
    assert(auth(request()).isInstanceOf[AuthDecision.Unauthenticated])
    assert(
      auth(request("Authorization" -> s"Bearer ${tokens.mint(issuer, resource, ada)}"))
        .isInstanceOf[AuthDecision.Unavailable]
    )
  }
