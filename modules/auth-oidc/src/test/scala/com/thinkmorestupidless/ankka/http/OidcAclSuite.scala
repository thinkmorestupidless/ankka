package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.auth.oidc.{Issuer, Oidc, OidcConfig, OidcVerifier, TestIssuer}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader

import java.time.Instant
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext}

// docs:start endpoint
/** An endpoint whose users sign in with an identity provider the service lists. */
final class AccountEndpoint(val acl: Acl = Oidc.authenticate()) extends HttpEndpoint("/account"):

  get("/me")(() =>
    s"${principal.subject} from ${principal.issuer.getOrElse("?")} " +
      s"roles=${principal.roles.toList.sorted.mkString(",")} " +
      s"tier=${principal.claims.getOrElse("tier", "")}"
  )
// docs:end endpoint

/**
 * `Oidc.authenticate` at the router: what a verified token, every kind of bad one, and an issuer
 * that cannot be reached each become, and what the handler is told (features/service-identity/
 * verifying.feature). In the http package because the router is that package's own.
 */
class OidcAclSuite extends munit.FunSuite:

  private given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "oidc-acl-suite")
  private given ExecutionContext             = system.executionContext

  override def afterAll(): Unit =
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  private var issuer: TestIssuer = null

  override def beforeEach(context: BeforeEach): Unit =
    issuer = TestIssuer(name = "customers", defaultAudience = "shop")
  override def afterEach(context: AfterEach): Unit = issuer.stop()

  private def acl(
      outageTolerance: FiniteDuration = OidcVerifier.OutageTolerance,
      cacheFor: FiniteDuration = 5.minutes
  ): Acl =
    val config = OidcConfig(Vector(issuer.asIssuer()))
    Oidc.authenticate(
      new OidcVerifier(config, _ => issuer.keySource(outageTolerance, cacheFor)),
      config.realm
    )

  private final class Open extends HttpEndpoint("/open"):
    val acl: Acl = Acl.AllowAll
    get("/asks")(() => principal.subject)

  private final class Callers extends HttpEndpoint("/callers"):
    val acl: Acl = Acl.allowCallers(CallerMatcher.NamedService(None, "web"))
    get("/who")(() => Caller.encode(caller))

  private def router(over: Acl) =
    Router(Vector(AccountEndpoint(over), new Open, new Callers), 5.seconds)

  private def call(
      router: Router,
      path: String,
      token: Option[String] = None
  ): (Int, String, Map[String, String]) =
    val headers = token.map(t => RawHeader("Authorization", s"Bearer $t")).toList
    val response =
      Await.result(router.handle(HttpRequest(uri = path).withHeaders(headers)), 10.seconds)
    val body = Await.result(response.entity.toStrict(5.seconds), 5.seconds).data.utf8String
    (response.status.intValue, body, response.headers.map(h => h.lowercaseName -> h.value).toMap)

  test("a request with a verified token reaches the handler with its principal") {
    val (status, body, _) = call(
      router(acl()),
      "/account/me",
      Some(issuer.token("ada", roles = Set("buyer")))
    )
    assertEquals(status, 200)
    assertEquals(body, "ada from customers roles=buyer tier=")
  }

  test("a claim the platform does not define reaches the handler by name") {
    val token        = issuer.token("ada", claims = Map("tier" -> "gold"))
    val (_, body, _) = call(router(acl()), "/account/me", Some(token))
    assert(body.endsWith("tier=gold"), body)
  }

  test("a request with no token is challenged") {
    val (status, body, headers) = call(router(acl()), "/account/me")
    assertEquals(status, 401)
    assertEquals(headers.get("www-authenticate"), Some("""Bearer realm="ankka""""))
    assert(!body.contains("ada"), body)
  }

  test("a token that does not verify is challenged") {
    val tokens = Map(
      "has expired"      -> issuer.token("ada", expiresIn = (-5).minutes),
      "is not yet valid" -> issuer.token("ada", notBefore = Some(Instant.now().plusSeconds(600))),
      "is for another audience" -> issuer.token("ada", audience = Seq("warehouse")),
      "has no subject"          -> issuer.withoutSubject()
    )
    val r = router(acl())
    tokens.foreach { (fault, token) =>
      val (status, _, headers) = call(r, "/account/me", Some(token))
      assertEquals(status, 401, fault)
      val challenge = headers.getOrElse("www-authenticate", "")
      assert(challenge.contains("""error="invalid_token""""), s"$fault: $challenge")
    }
  }

  test("a token from an issuer the service does not list is challenged") {
    val stranger = TestIssuer("https://auth.example.test/realms/strangers", "strangers", "shop")
    try
      assertEquals(call(router(acl()), "/account/me", Some(stranger.token("eve")))._1, 401)
      assertEquals(stranger.fetches.get(), 0)
    finally stranger.stop()
  }

  test(
    "a token signed with a shared secret is challenged without its issuer being asked for keys"
  ) {
    assertEquals(call(router(acl()), "/account/me", Some(issuer.hmac("ada")))._1, 401)
    assertEquals(issuer.fetches.get(), 0)
  }

  test("keys already fetched go on verifying tokens while the issuer cannot be reached") {
    val r = router(acl())
    assertEquals(call(r, "/account/me", Some(issuer.token("ada")))._1, 200)
    issuer.setOffline(true)
    assertEquals(call(r, "/account/me", Some(issuer.token("ada")))._1, 200)
  }

  test("a request is answered unavailable once the tolerance has passed") {
    val r = router(acl(outageTolerance = 1.second, cacheFor = 500.millis))
    assertEquals(call(r, "/account/me", Some(issuer.token("ada")))._1, 200)
    issuer.setOffline(true)
    Thread.sleep(2000)
    val (status, _, headers) = call(r, "/account/me", Some(issuer.token("ada")))
    assertEquals(status, 503)
    assertEquals(headers.get("retry-after"), Some("5"))
  }

  test("a service starts without waiting for its issuers' keys") {
    issuer.setOffline(true)
    Oidc.authenticate(OidcConfig(Vector(issuer.asIssuer())))
    router(acl())
    assertEquals(issuer.fetches.get(), 0, "nothing was asked of the issuer before a request")
  }

  test("a request is answered unavailable while an issuer's keys have never been fetched") {
    issuer.setOffline(true)
    assertEquals(call(router(acl()), "/account/me", Some(issuer.token("ada")))._1, 503)
  }

  test("a handler reads the principal only under an access rule that asks for a token") {
    // The router does not leak the exception's text, so the loud failure is the status alone.
    assertEquals(call(router(acl()), "/open/asks", Some(issuer.token("ada")))._1, 500)
  }

  test("a token attached to a call that an access rule admits by caller is ignored") {
    val stranger = TestIssuer("https://auth.example.test/realms/strangers", "strangers", "shop")
    try
      val (status, body, _) = call(router(acl()), "/callers/who", Some(stranger.token("eve")))
      assertEquals(status, 200, "the bearer token played no part in admitting the call")
      assertEquals(body, Caller.encode(Caller.Local))
      assertEquals(stranger.fetches.get(), 0)
    finally stranger.stop()
  }

  test("a service with an authenticated route and no issuer listed does not start") {
    val failure = intercept[IllegalStateException](Oidc.authenticate(OidcConfig.empty))
    assert(failure.getMessage.contains("ANKKA_AUTH_ISSUERS"), failure.getMessage)
  }

  test("a malformed set fails building the access rule, naming every problem") {
    val failure = intercept[IllegalStateException](
      Oidc.authenticate(OidcConfig(Vector(Issuer("staff", "", "https://s/certs", ""))))
    )
    assert(failure.getMessage.contains("no issuer string"), failure.getMessage)
    assert(failure.getMessage.contains("no audience"), failure.getMessage)
  }
