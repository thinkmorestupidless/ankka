package com.thinkmorestupidless.ankka.http

import com.sun.net.httpserver.HttpServer as JdkServer

import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets
import java.time.{Clock, Instant, ZoneOffset}
import java.util.concurrent.atomic.AtomicReference

/**
 * A machine's token, verified with the JDK alone (feature 040): every rule that refuses one, and
 * the key set fetched once, again for a key not yet seen, and held through the issuer's absence.
 */
class MachineTokensSuite extends munit.FunSuite:

  private val issuer = "https://api.example.test"
  private val signer = MachineTokenSigner()
  private val tokens = MachineTokens.withKeys(issuer, Map(signer.kid -> signer.publicKey))

  private val machine = Caller.Machine("eitheror", "affiliate-network")

  test("a token the installation signed proves its machine") {
    assertEquals(tokens.verify(signer.token(issuer)), Right(machine))
    assertEquals(tokens.issuerOf(signer.token(issuer)), Some(issuer))
  }

  test("a token signed with another algorithm, or none, is refused") {
    val claims = signer.claims(issuer)
    assertEquals(
      tokens.verify(signer.sign(claims, alg = "HS256")),
      Left("algorithm 'HS256' is not RS256")
    )
    assertEquals(
      tokens.verify(signer.sign(claims, alg = "none")),
      Left("algorithm 'none' is not RS256")
    )
  }

  test("a token signed by a key the installation does not hold is refused") {
    val stranger = MachineTokenSigner()
    assertEquals(tokens.verify(stranger.token(issuer)), Left("a bad signature"))
    val unknown = stranger.sign(stranger.claims(issuer), kidOf = "other")
    assertEquals(tokens.verify(unknown), Left("key 'other' is not one of the installation's"))
  }

  test("an expired token, one not yet valid, and one for another audience are refused") {
    val past = Instant.now().minusSeconds(3600)
    assertEquals(tokens.verify(signer.sign(signer.claims(issuer, now = past))), Left("expired"))
    val future = Instant.now().plusSeconds(3600)
    assertEquals(
      tokens.verify(signer.sign(signer.claims(issuer, now = future))),
      Left("not yet valid")
    )
    assertEquals(
      tokens.verify(signer.sign(signer.claims(issuer, audience = "\"elsewhere\""))),
      Left("audience is not 'ankka'")
    )
    // A lone audience is a string, and reads as one.
    assertEquals(
      tokens.verify(signer.sign(signer.claims(issuer, audience = "\"ankka\""))),
      Right(machine)
    )
  }

  test("a minute's difference between clocks is allowed, and no more") {
    val issued = Instant.parse("2026-10-09T10:00:00Z")
    def at(t: Instant) =
      MachineTokens.withKeys(
        issuer,
        Map(signer.kid -> signer.publicKey),
        Clock.fixed(t, ZoneOffset.UTC)
      )
    val token = signer.sign(signer.claims(issuer, now = issued))
    assertEquals(at(issued.plusSeconds(900 + 59)).verify(token), Right(machine))
    assertEquals(at(issued.plusSeconds(900 + 61)).verify(token), Left("expired"))
    assertEquals(at(issued.minusSeconds(59)).verify(token), Right(machine))
    assertEquals(at(issued.minusSeconds(61)).verify(token), Left("not yet valid"))
  }

  test("another issuer's token, one with no type, and one naming no machine are refused") {
    assertEquals(
      tokens.verify(signer.token("https://elsewhere.test")),
      Left("issuer 'https://elsewhere.test' is not this installation's")
    )
    assertEquals(
      tokens.verify(signer.sign(signer.claims(issuer, typ = None))),
      Left("not a bearer token")
    )
    assertEquals(
      tokens.verify(signer.token(issuer, subject = "service:payments/merchant")),
      Left("subject 'service:payments/merchant' is not a machine")
    )
    assertEquals(tokens.verify("not.a"), Left("not a signed token"))
  }

  test(
    "the keys are fetched once, again for a key not yet seen, and held while the issuer is away"
  ) {
    val served  = AtomicReference(signer.jwks)
    val counted = java.util.concurrent.atomic.AtomicInteger()
    val server  = JdkServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/.well-known/jwks.json",
      exchange =>
        counted.incrementAndGet(): Unit
        val body = served.get.getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.write(body)
        exchange.close()
    )
    server.start()
    val now = AtomicReference(Instant.parse("2026-10-09T10:00:00Z"))
    val clock = new Clock:
      def getZone                                = ZoneOffset.UTC
      override def withZone(z: java.time.ZoneId) = this
      def instant(): Instant                     = now.get
    val fetching = MachineTokens.fetching(
      issuer,
      URI.create(s"http://127.0.0.1:${server.getAddress.getPort}/.well-known/jwks.json"),
      None,
      clock
    )
    def eventually(check: => Boolean): Unit =
      val deadline = System.nanoTime() + 5_000_000_000L
      while !check && System.nanoTime() < deadline do Thread.sleep(20)
      assert(check)
    try
      fetching.start()
      eventually(fetching.held == Set(signer.kid))
      val issuedAt = now.get
      assertEquals(
        fetching.verify(signer.sign(signer.claims(issuer, now = issuedAt))),
        Right(machine)
      )
      assertEquals(counted.get, 1)

      // A rotation: a new key, which a token names before the verifier has fetched it.
      val rotated = MachineTokenSigner("rotated")
      served.set(s"""{"keys":[${signer.jwk},${rotated.jwk}]}""")
      now.set(issuedAt.plusSeconds(31))
      val first = fetching.verify(rotated.sign(rotated.claims(issuer, now = issuedAt)))
      assertEquals(first, Left("key 'rotated' is not one of the installation's"))
      eventually(fetching.held == Set(signer.kid, "rotated"))
      assertEquals(
        fetching.verify(rotated.sign(rotated.claims(issuer, now = issuedAt))),
        Right(machine)
      )

      // Asked again within thirty seconds for a key nobody has, it fetches nothing more.
      val before = counted.get
      fetching.verify(MachineTokenSigner("nobody").token(issuer)): Unit
      Thread.sleep(200)
      assertEquals(counted.get, before)

      // The issuer gone, the keys it served are still the ones held.
      server.stop(0)
      now.set(issuedAt.plusSeconds(120))
      fetching.verify(
        MachineTokenSigner("later").sign(MachineTokenSigner("later").claims(issuer, now = issuedAt))
      ): Unit
      Thread.sleep(500)
      assertEquals(fetching.held, Set(signer.kid, "rotated"))
      assertEquals(
        fetching.verify(signer.sign(signer.claims(issuer, now = issuedAt))),
        Right(machine)
      )
    finally server.stop(0)
  }

  test("a bearer header is read with any case of its scheme, and nothing else is a bearer") {
    assertEquals(CallerSource.bearer(Some("Bearer abc")), Some("abc"))
    assertEquals(CallerSource.bearer(Some("bearer  abc ")), Some("abc"))
    assertEquals(CallerSource.bearer(Some("Basic abc")), None)
    assertEquals(CallerSource.bearer(Some("Bearer ")), None)
    assertEquals(CallerSource.bearer(None), None)
  }
