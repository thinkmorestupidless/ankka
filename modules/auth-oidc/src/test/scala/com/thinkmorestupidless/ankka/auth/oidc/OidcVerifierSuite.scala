package com.thinkmorestupidless.ankka.auth.oidc

import com.nimbusds.jwt.JWTClaimsSet

import java.time.Instant
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}

/**
 * Every way a token is refused, the two ways keys are refetched, and what several issuers mean.
 *
 * The first eight cases are the control plane's own verifier suite, carried over when its verifier
 * became this module, so the control plane loses nothing it was tested for.
 */
class OidcVerifierSuite extends munit.FunSuite:

  private var issuer: TestIssuer = null

  override def beforeEach(context: BeforeEach): Unit =
    issuer = TestIssuer(defaultAudience = "shop")
  override def afterEach(context: AfterEach): Unit = issuer.stop()

  /** One issuer, as the control plane configures its own: the `typ` check on. */
  private def verifier(
      skew: FiniteDuration = 60.seconds,
      typ: Option[String] = Some("Bearer")
  ): OidcVerifier =
    val listed = issuer.asIssuer(typ = typ, skew = skew)
    new OidcVerifier(OidcConfig(Vector(listed)), _ => issuer.keySource())

  private def rejected(v: Verification): String = v match
    case Verification.Rejected(reason) => reason
    case other                         => fail(s"expected a rejection, got $other")

  private def verified(v: Verification): Principal = v match
    case Verification.Verified(claims, i) => Principals.from(claims, i)
    case other                            => fail(s"expected verified, got $other")

  private type Principal = com.thinkmorestupidless.ankka.http.Principal

  test("a good token verifies and yields a principal") {
    val token = issuer.token(
      "alice",
      Some("Alice@Example.test"),
      roles = Set("buyer"),
      name = Some("Alice"),
      claims = Map("tier" -> "gold")
    )
    val principal = verified(verifier().verify(token))
    assertEquals(principal.subject, "alice")
    assertEquals(principal.email, Some("alice@example.test"), "email is lower-cased for matching")
    assert(principal.emailVerified)
    assertEquals(principal.roles, Set("buyer"))
    assertEquals(principal.name, Some("Alice"))
    assertEquals(principal.claims.get("tier"), Some("gold"))
    assertEquals(principal.issuer, Some("test"))
  }

  test("expired, not-yet-valid, wrong issuer, wrong audience, wrong type, missing type") {
    val strict = verifier(skew = 0.seconds)
    def reasonFor(token: String, expectedWords: String*): Unit =
      val reason = rejected(strict.verify(token)).toLowerCase
      assert(expectedWords.exists(reason.contains), s"'$reason' names none of $expectedWords")
    reasonFor(issuer.token("a", expiresIn = (-1).minute), "expired")
    reasonFor(issuer.token("a", notBefore = Some(Instant.now().plusSeconds(600))), "before")
    reasonFor(issuer.token("a", issuer = "https://elsewhere/realms/x"), "issuer not accepted")
    reasonFor(issuer.token("a", audience = Seq("account")), "aud")
    reasonFor(issuer.token("a", typ = Some("Refresh")), "not accepted")
    reasonFor(issuer.token("a", typ = None), "no type")
  }

  test("a token with no subject is refused") {
    assert(rejected(verifier().verify(issuer.withoutSubject())).toLowerCase.contains("sub"))
  }

  test("clock skew is honoured") {
    val token = issuer.token("a", expiresIn = (-30).seconds)
    assert(rejected(verifier(skew = 0.seconds).verify(token)).nonEmpty)
    assert(verifier(skew = 120.seconds).verify(token).isInstanceOf[Verification.Verified])
  }

  test("alg=none and HMAC are refused without fetching a key") {
    val v = verifier()
    rejected(v.verify(issuer.unsigned("a"))): Unit
    rejected(v.verify(issuer.hmac("a"))): Unit
    assertEquals(issuer.fetches.get(), 0, "no key lookup for an algorithm never accepted")
  }

  test("something that is not a token at all is refused as not well formed") {
    assertEquals(rejected(verifier().verify("dev-local-token")), "not a well-formed token")
  }

  test("an unknown kid causes one refetch; a rotation is then accepted") {
    val v = verifier()
    assert(v.verify(issuer.token("a")).isInstanceOf[Verification.Verified])
    val after = issuer.fetches.get()
    issuer.rotate("k2")
    Thread.sleep(300) // past the test source's rate limit
    assert(v.verify(issuer.token("a")).isInstanceOf[Verification.Verified], "rotated key accepted")
    assertEquals(issuer.fetches.get(), after + 1, "exactly one refetch for the unknown kid")
    val stale = issuer.token("a", kid = Some("k1"))
    Thread.sleep(300)
    rejected(v.verify(stale)): Unit
  }

  test("an unreachable issuer with nothing held is Unavailable; with keys held it still verifies") {
    val v = verifier()
    issuer.setOffline(true)
    v.verify(issuer.token("a")) match
      case Verification.Unavailable(reason) => assert(reason.contains("keys"), reason)
      case other                            => fail(s"expected Unavailable, got $other")
    issuer.setOffline(false)
    assert(v.verify(issuer.token("a")).isInstanceOf[Verification.Verified])
    issuer.setOffline(true)
    assert(v.verify(issuer.token("a")).isInstanceOf[Verification.Verified], "from the cache")
  }

  test("a verifier is built without asking the issuer for anything") {
    issuer.setOffline(true)
    OidcVerifier.remote(OidcConfig(Vector(issuer.asIssuer())))
    assertEquals(issuer.fetches.get(), 0)
  }

  test("keys that are slow to arrive answer Unavailable rather than waiting for them") {
    val v = verifier()
    issuer.delayResponses(30.seconds)
    val started = System.nanoTime()
    v.verify(issuer.token("a")) match
      case Verification.Unavailable(_) => ()
      case other                       => fail(s"expected Unavailable, got $other")
    val took = (System.nanoTime() - started).nanos
    assert(took < 20.seconds, s"waited $took for keys that never came")
  }

  test("keys fetched over TLS are trusted by the named root alone") {
    import com.thinkmorestupidless.ankka.testpki.TestPki
    import com.sun.net.httpserver.{HttpsConfigurator, HttpsServer}

    val authority = TestPki.root("identity-provider")
    val leaf      = authority.issue(dnsNames = Seq("localhost"))
    val tlsDir    = leaf.writeTo(java.nio.file.Files.createTempDirectory("idp"))
    val context   = com.thinkmorestupidless.ankka.runtime.RotatingTls(tlsDir, 1.minute).sslContext
    val https     = HttpsServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    https.setHttpsConfigurator(new HttpsConfigurator(context))
    https.createContext(
      "/jwks",
      exchange =>
        val body = scala.io.Source.fromURL(issuer.jwksUrl).mkString.getBytes("UTF-8")
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.write(body)
        exchange.close()
    )
    https.start()
    try
      val url = s"https://localhost:${https.getAddress.getPort}/jwks"
      val root = java.nio.file.Files
        .writeString(java.nio.file.Files.createTempFile("root", ".crt"), authority.pem)
      def trusting(ca: Option[java.nio.file.Path]) =
        val listed = issuer.asIssuer().copy(jwksUrl = url, ca = ca)
        new OidcVerifier(
          OidcConfig(Vector(listed)),
          i => OidcVerifier.keySource(i, minTimeBetweenFetches = 200.millis)
        )
      assert(
        trusting(Some(root)).verify(issuer.token("a")).isInstanceOf[Verification.Verified],
        "the named root should verify the identity provider"
      )
      // The JVM's own trust store knows nothing of the installation's authority.
      trusting(None).verify(issuer.token("a")) match
        case Verification.Unavailable(_) => ()
        case other                       => fail(s"an unverifiable key server was trusted: $other")
    finally https.stop(0)
  }

  // ── Several issuers ──────────────────────────────────────────────────────

  private def twoIssuers(test: (TestIssuer, TestIssuer, OidcVerifier) => Unit): Unit =
    val staff = TestIssuer("https://auth.example.test/realms/staff", "staff", "backoffice")
    val customers =
      TestIssuer("https://auth.example.test/realms/customers", "customers", "shop")
    try
      val listed  = Vector(staff.asIssuer(typ = Some("Bearer")), customers.asIssuer())
      val sources = Map("staff" -> staff.keySource(), "customers" -> customers.keySource())
      test(staff, customers, new OidcVerifier(OidcConfig(listed), i => sources(i.name)))
    finally
      staff.stop()
      customers.stop()

  test("a token is verified against the keys and the audience of the issuer that signed it") {
    twoIssuers { (staff, customers, v) =>
      assertEquals(verified(v.verify(customers.token("ada"))).issuer, Some("customers"))
      assertEquals(verified(v.verify(staff.token("bo"))).issuer, Some("staff"))
    }
  }

  test("a token that names one issuer and is signed with another's keys is refused") {
    twoIssuers { (staff, customers, v) =>
      val forged = staff.signed(
        new JWTClaimsSet.Builder()
          .subject("ada")
          .issuer(customers.issuer)
          .audience("shop")
          .expirationTime(java.util.Date.from(Instant.now().plusSeconds(300)))
          .build()
      )
      rejected(v.verify(forged)): Unit
    }
  }

  test("a token for one issuer's audience is not accepted from the other issuer") {
    twoIssuers { (staff, _, v) =>
      rejected(v.verify(staff.token("bo", audience = Seq("shop")))): Unit
    }
  }

  test("an issuer the service does not list is refused without its keys being asked for") {
    twoIssuers { (_, _, v) =>
      val stranger = TestIssuer("https://auth.example.test/realms/strangers", "strangers", "shop")
      try
        assertEquals(rejected(v.verify(stranger.token("eve"))), "issuer not accepted")
        assertEquals(stranger.fetches.get(), 0)
      finally stranger.stop()
    }
  }

  test("one issuer that cannot be reached does not stop the other's tokens being admitted") {
    twoIssuers { (staff, customers, v) =>
      verified(v.verify(staff.token("bo"))): Unit
      customers.setOffline(true)
      verified(v.verify(staff.token("bo"))): Unit
    }
  }

  test("a token's type is checked only for an issuer that asks for it") {
    twoIssuers { (staff, customers, v) =>
      verified(v.verify(staff.token("bo", typ = Some("Bearer")))): Unit
      rejected(v.verify(staff.token("bo", typ = Some("Other")))): Unit
      verified(v.verify(customers.token("ada", typ = Some("Other")))): Unit
      verified(v.verify(customers.token("ada", typ = None))): Unit
    }
  }
