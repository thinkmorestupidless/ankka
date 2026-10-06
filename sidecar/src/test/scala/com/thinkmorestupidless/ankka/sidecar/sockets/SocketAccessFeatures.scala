package com.thinkmorestupidless.ankka.sidecar.sockets

import ch.qos.logback.classic.{Logger as LogbackLogger, LoggerContext}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.thinkmorestupidless.ankka.auth.oidc.{Oidc, OidcConfig, OidcVerifier, TestIssuer}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.Observability
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing, TestSocket}
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * `features/sockets/access.feature`, against an embedded service whose socket route is under the
 * real token verifier and the test issuer. It runs here because this is the project that sees both
 * the test kit and `auth-oidc`'s tests; the two scenarios about named services need certificates,
 * and run in `http`'s `SocketCallerSuite`.
 */
final class SocketAccessFeatures
    extends GherkinSuite("../features/sockets/access.feature")
    with LogCapturing:

  override val munitTimeout = 3.minutes

  override protected def ranElsewhere: Map[String, String] = Map(
    "a socket route that admits a named service tells its handler the calling workload" ->
      "SocketCallerSuite (http)",
    "a socket route that admits a named service refuses any other service" ->
      "SocketCallerSuite (http)"
  )

  // No clock skew, so a token that has expired is refused at once: what proves that a socket opened
  // before the expiry stays open because nothing re-verifies it, not because the token still passes.
  private val issuer = TestIssuer(name = "customers", defaultAudience = "shop")
  private val config = OidcConfig(Vector(issuer.asIssuer(skew = 0.seconds)))
  private val verified =
    Oidc.authenticate(new OidcVerifier(config, _ => issuer.keySource()), config.realm)

  @volatile private var answer = "allow"
  private val stub: RequestContext => AuthDecision = _ =>
    answer match
      case "forbidden"   => AuthDecision.Forbidden("not this one")
      case "unavailable" => AuthDecision.Unavailable("cannot tell")
      case _             => AuthDecision.Allow(Principal("someone"))

  private val runs                    = AtomicInteger()
  private val subjects                = ConcurrentLinkedQueue[String]()
  @volatile private var authenticated = true

  private final class Notices extends HttpEndpoint("/notices"):
    val acl: Acl = Acl.AllowAll
    withAcl(verified) {
      socket("/stream") { socket =>
        runs.incrementAndGet(): Unit
        socket.send(principal.subject)
        while socket.receive().isDefined do
          subjects.add(principal.subject): Unit
          socket.send(principal.subject)
      }
    }
    withAcl(Acl.Authenticate(stub)) {
      socket("/judged") { socket =>
        runs.incrementAndGet(): Unit
        socket.receive(): Unit
      }
    }

  private var testKit: AnkkaTestKit = null
  private var base                  = ""

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(_ => Notices())
    testKit = AnkkaTestKit.start(Nil, Seq(server))
    base = s"ws://127.0.0.1:${server.boundPort.getOrElse(fail("not bound"))}/notices"

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()
    issuer.stop()

  @volatile private var token: String = ""
  @volatile private var opened: Either[TestSocket.Refused, TestSocket] = Left(
    TestSocket.Refused(0, Map.empty)
  )
  @volatile private var runsBefore = 0
  private val captured             = ListAppender[ILoggingEvent]()

  override def beforeEach(context: BeforeEach): Unit =
    answer = "allow"
    authenticated = true
    subjects.clear()
    runsBefore = runs.get
    captured.list.clear()

  override def afterEach(context: AfterEach): Unit =
    opened.foreach(s => scala.util.Try(s.abort()))
    root.detachAppender(captured): Unit

  private def root: LogbackLogger =
    LoggerFactory.getILoggerFactory
      .asInstanceOf[LoggerContext]
      .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)

  private def path(route: String) = if authenticated then route else "/judged"

  private def current: TestSocket = opened.fold(r => fail(s"no socket was opened: $r"), identity)

  // ── the world ────────────────────────────────────────────────────────────────

  Given("an issuer {string} that signs tokens for the audience {string}")((_: String, _: String) =>
    ()
  )
  Given("a service {string} that lists the issuer {string} with the audience {string}") {
    (_: String, _: String, _: String) => ()
  }
  Given("an HTTP endpoint of {string} that declares the socket route {string}") {
    (_: String, _: String) => ()
  }
  Given("the socket route {string} is an authenticated route")((_: String) => authenticated = true)
  Given("the ACL of the socket route {string} is an authenticator") { (_: String) =>
    authenticated = false
  }
  Given("the authenticator answers {string}")((a: String) => answer = a)

  Given(
    "a person has opened a socket to {string} with a token from {string} whose subject is {string}"
  ) { (route: String, _: String, subject: String) =>
    token = issuer.token(subject, expiresIn = 3.seconds)
    opened = TestSocket.open(s"$base$route", Map("Authorization" -> s"Bearer $token"))
    assertEquals(current.receive(), Some(subject))
  }

  // ── what happens ─────────────────────────────────────────────────────────────

  When("a person opens a socket to {string} with a token from {string} whose subject is {string}") {
    (route: String, _: String, subject: String) =>
      token = issuer.token(subject)
      opened = TestSocket.open(s"$base$route", Map("Authorization" -> s"Bearer $token"))
  }
  When(
    "a browser opens a socket to {string} with a token from {string} whose subject is {string}"
  ) { (route: String, _: String, subject: String) =>
    root.addAppender(captured)
    captured.start()
    token = issuer.token(subject)
    // A browser cannot set a header on a socket: the token is offered as a subprotocol.
    opened =
      TestSocket.open(s"$base$route", subprotocols = Seq("ankka.socket", s"ankka.bearer.$token"))
  }
  When("a person opens a socket to {string} with no token") { (route: String) =>
    opened = TestSocket.open(s"$base${path(route)}")
  }
  When("a person opens a socket to {string}") { (route: String) =>
    opened = TestSocket.open(s"$base${path(route)}")
  }
  When("the person sends {string} frames") { (n: String) =>
    (1 to n.toInt).foreach(i => current.send(s"frame $i"))
  }
  When("the token expires") { () =>
    Thread.sleep(4000)
    // The token really has expired: a socket opened with it now is challenged.
    val again = TestSocket.open(s"$base/stream", Map("Authorization" -> s"Bearer $token"))
    assertEquals(again.left.map(_.status), Left(401))
  }

  // ── what is seen ─────────────────────────────────────────────────────────────

  Then("the handler is told a principal whose subject is {string}") { (subject: String) =>
    assertEquals(current.receive(), Some(subject))
  }
  Then("the handler reads a principal whose subject is {string} after each frame") {
    (subject: String) =>
      assertEquals((1 to 3).map(_ => current.receive()).toVector, Vector.fill(3)(Some(subject)))
      assertEquals(subjects.asScala.toVector, Vector.fill(3)(subject))
  }
  Then("the socket is still open")(() => current.send("still here"))
  Then("the handler reads a principal whose subject is {string}") { (subject: String) =>
    assertEquals(current.receive(), Some(subject))
  }
  Then("the request is challenged") { () =>
    val refused = opened.left.getOrElse(fail("a socket was opened"))
    assertEquals(refused.status, 401)
    assert(refused.headers.keys.exists(_.equalsIgnoreCase("www-authenticate")), refused.toString)
  }
  Then("the person is refused")(() => assertEquals(opened.left.map(_.status), Left(403)))
  Then("no socket is opened")(() => assert(opened.isLeft, "a socket was opened"))
  Then("the handler is not run")(() => assertEquals(runs.get, runsBefore))
  Then("the token is not in what the service records of the socket") { () =>
    assertEquals(current.subprotocol, Some("ankka.socket"), "the 101 never selects the bearer one")
    val observability = Observability(testKit.service.system)
    val names = observability.recorder
      .snapshot()
      .flatMap(s =>
        observability.names.nameOf(s.handlerRef) ++ observability.names.nameOf(s.componentRef)
      )
    assert(!names.exists(_.contains(token)), "the token was recorded in a span's name")
    val logged = captured.list.asScala.map(_.getFormattedMessage)
    assert(!logged.exists(_.contains(token)), "the token was logged")
  }
