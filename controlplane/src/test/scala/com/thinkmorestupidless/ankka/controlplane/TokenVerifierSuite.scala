package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.auth.{Principals, Verification}

import java.time.Instant
import scala.concurrent.duration.DurationInt

/** Every way a token is refused, and the two ways keys are refetched (contracts/http-api.md). */
class TokenVerifierSuite extends munit.FunSuite:

  private var identity: TestIdentity = null

  override def beforeEach(context: BeforeEach): Unit = identity = TestIdentity()
  override def afterEach(context: AfterEach): Unit   = identity.stop()

  private def rejected(v: Verification): String = v match
    case Verification.Rejected(reason) => reason
    case other                         => fail(s"expected a rejection, got $other")

  test("a good token verifies and yields a principal") {
    val verifier = identity.verifier()
    val token = identity.token(
      "alice",
      Some("Alice@Example.test"),
      roles = Set("platform-admin"),
      name = Some("Alice")
    )
    verifier.verify(token) match
      case Verification.Verified(claims) =>
        val principal = Principals.from(claims)
        assertEquals(principal.subject, "alice")
        assertEquals(
          principal.email,
          Some("alice@example.test"),
          "email is lower-cased for matching"
        )
        assert(principal.emailVerified)
        assert(Principals.isPlatformAdmin(principal))
        assertEquals(principal.name, Some("Alice"))
      case other => fail(s"not verified: $other")
  }

  test("expired, not-yet-valid, wrong issuer, wrong audience, wrong type, missing type") {
    val verifier = identity.verifier(identity.config(skew = 0.seconds))
    def reasonFor(token: String, expectedWords: String*): Unit =
      val reason = rejected(verifier.verify(token)).toLowerCase
      assert(expectedWords.exists(reason.contains), s"'$reason' names none of $expectedWords")
    reasonFor(identity.token("a", expiresIn = (-1).minute), "expired")
    reasonFor(identity.token("a", notBefore = Some(Instant.now().plusSeconds(600))), "before")
    reasonFor(identity.token("a", issuer = "https://elsewhere/realms/x"), "iss")
    reasonFor(identity.token("a", audience = Seq("account")), "aud")
    reasonFor(identity.token("a", typ = Some("Refresh")), "not an access token")
    reasonFor(identity.token("a", typ = None), "no type")
  }

  test("clock skew is honoured") {
    val strict  = identity.verifier(identity.config(skew = 0.seconds))
    val lenient = identity.verifier(identity.config(skew = 120.seconds))
    val token   = identity.token("a", expiresIn = (-30).seconds)
    assert(rejected(strict.verify(token)).nonEmpty)
    assert(lenient.verify(token).isInstanceOf[Verification.Verified])
  }

  test("alg=none and HMAC are refused without fetching a key") {
    val verifier = identity.verifier()
    rejected(verifier.verify(identity.unsigned("a")))
    rejected(verifier.verify(identity.hmac("a")))
    assertEquals(
      identity.fetches.get(),
      0,
      "no key lookup for an algorithm the selector never accepts"
    )
  }

  test("something that is not a JWT at all is told the shared token is gone") {
    val reason = rejected(identity.verifier().verify("dev-local-token"))
    assert(reason.contains("run 'ankka login'"), reason)
  }

  test("an unknown kid causes one refetch; a rotation is then accepted") {
    val verifier = identity.verifier()
    assert(verifier.verify(identity.token("a")).isInstanceOf[Verification.Verified])
    val after = identity.fetches.get()
    identity.rotate("k2")
    Thread.sleep(300) // past the test source's rate limit
    assert(
      verifier.verify(identity.token("a")).isInstanceOf[Verification.Verified],
      "rotated key accepted"
    )
    assertEquals(identity.fetches.get(), after + 1, "exactly one refetch for the unknown kid")
    val stale = identity.token("a", kid = Some("k1"))
    Thread.sleep(300)
    rejected(verifier.verify(stale))
  }

  test("an unreachable issuer with nothing cached is Unavailable; with a cache it still verifies") {
    val verifier = identity.verifier()
    identity.setOffline(true)
    verifier.verify(identity.token("a")) match
      case Verification.Unavailable(reason) => assert(reason.contains("keys"), reason)
      case other                            => fail(s"expected Unavailable, got $other")
    identity.setOffline(false)
    assert(verifier.verify(identity.token("a")).isInstanceOf[Verification.Verified])
    identity.setOffline(true)
    assert(
      verifier.verify(identity.token("a")).isInstanceOf[Verification.Verified],
      "served from the cache"
    )
  }
