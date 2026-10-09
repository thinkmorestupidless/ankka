package com.thinkmorestupidless.ankka.controlplane.erasure

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.{ControlPlane, TestIdentity}
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.ErasureWire.given
import com.thinkmorestupidless.ankka.http.{Caller, HttpServer, LocalCallers}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.runtime.erasure.{Grant, GrantReader}
import com.thinkmorestupidless.ankka.sdk.{Erasures, ServiceClient, ServiceClients, ServiceResponse}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.*

/**
 * `features/erasure/asking.feature`: the control plane with the grants a test gives it, and a
 * service asking through the SDK's `Erasures.ask` — its calls arriving as that service, as its
 * certificate would make them arrive in a cluster (the local caller header stands for the
 * certificate here).
 */
class AskingFeatures extends GherkinSuite("../features/erasure/asking.feature") with LogCapturing:

  override val munitTimeout = 3.minutes

  private lazy val identity = TestIdentity()
  private lazy val alice = identity.token("alice", Some("alice@example.test"), expiresIn = 2.hours)

  @volatile private var granted: Vector[Grant] = Vector.empty
  private val grants: GrantReader = principal => granted.filter(_.principal == principal).toSet

  private var controlPlane: AnkkaTestKit = null
  private var base                       = ""
  private val http                       = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        identity.acl(),
        auth = Some(identity.config()),
        clock = identity.clock,
        grants = grants
      )*
    )
    controlPlane =
      AnkkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), server), keyring = None)
    base = s"http://127.0.0.1:${server.boundPort.get}"
    assertEquals(member("POST", "/organizations/acme", Some("""{"name":"Acme"}"""))._1, 204)
    Seq("brand", "payments").foreach(p =>
      assertEquals(
        member("POST", s"/projects/$p", Some(s"""{"name":"$p","organizationId":"acme"}"""))._1,
        204
      )
    )

  override def afterAll(): Unit =
    if controlPlane != null then controlPlane.stop()
    identity.stop()

  override def beforeEach(context: BeforeEach): Unit = granted = Vector.empty

  private def member(method: String, path: String, body: Option[String] = None): (Int, String) =
    send(method, path, body, Seq("Authorization" -> s"Bearer $alice"))

  private def send(
      method: String,
      path: String,
      body: Option[String],
      headers: Seq[(String, String)]
  ): (Int, String) =
    val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
    headers.foreach((k, v) => builder.header(k, v))
    body.fold(builder.method(method, HttpRequest.BodyPublishers.noBody()))(json =>
      builder
        .header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(json))
    ): Unit
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  /** `project/name`'s service client: every call arrives at the control plane as that service. */
  private def servicesOf(project: String, name: String): ServiceClients = new ServiceClients:
    def apply(other: String): ServiceClient = apply(project, other)
    def apply(targetProject: String, targetName: String): ServiceClient = new ServiceClient:
      val target = s"$targetProject/$targetName"
      def request(
          method: String,
          path: String,
          body: Option[Array[Byte]],
          contentType: Option[String],
          headers: Seq[(String, String)]
      ): ServiceResponse =
        val (status, text) = send(
          method,
          path,
          body.map(String(_, "UTF-8")),
          headers :+ LocalCallers.header(Caller.Service(project, name))
        )
        ServiceResponse(status, "application/json", text.getBytes("UTF-8"), Vector.empty)

  private def suffix                 = Integer.toHexString(scenarioId.hashCode)
  private def subject(named: String) = s"$named-$suffix"

  private var answer: ServiceResponse = null
  private def asked: ErasureRequest   = readFromString[ErasureRequest](answer.text)

  private def ask(project: String, named: String, correlation: Option[String] = None) =
    answer = Erasures.ask(servicesOf("brand", "players"), project, subject(named), correlation)

  // ── Background ──

  Given("a project {string} with the service {string}")((_: String, _: String) => ())
  Given("a project {string}")((_: String) => ())

  // ── Grants ──

  Given("{string} grants {string} the right to ask for an erasure") {
    (project: String, service: String) =>
      granted :+= Grant(project, GrantReader.service(project, service), "*", Set("erasure"))
  }
  Given("{string} grants {string} of {string} the right to ask for an erasure") {
    (project: String, service: String, of: String) =>
      granted :+= Grant(project, GrantReader.service(of, service), "*", Set("erasure"))
  }
  Given("{string} grants {string} of {string} nothing")((_: String, _: String, _: String) => ())

  // ── Asking ──

  When("{string} asks for an erasure request for {string} in {string}") {
    (_: String, named: String, project: String) => ask(project, named)
  }
  Then("the erasure request is accepted") { () =>
    assert(answer.status == 201 || answer.status == 200, s"${answer.status} ${answer.text}")
  }
  Then("it names the service {string} as who asked for it") { (service: String) =>
    assertEquals((asked.askedBy.kind, asked.askedBy.subject), ("service", service))
  }
  Then("it names the service {string} of {string} as who asked for it") {
    (service: String, project: String) =>
      assertEquals(
        (asked.askedBy.kind, asked.askedBy.subject, asked.askedBy.project),
        ("service", service, Some(project))
      )
  }
  Then("{string} is refused") { (_: String) =>
    assertEquals(answer.status, 403, answer.text)
  }
  Then("the refusal is recorded in the history of {string}") { (project: String) =>
    controlPlane.eventually("the refusal in the history") {
      val (status, body) = member("GET", s"/projects/$project/history")
      assertEquals(status, 200, body)
      Some(readFromString[Vector[ProjectHistoryEntry]](body))
        .filter(_.exists(e => e.kind == "erasure-refused" && e.subject.endsWith(suffix)))
    }: Unit
  }

  // ── One person in two projects ──

  Given(
    "{string} has asked for an erasure request for {string} in {string} with the correlation id {string}"
  ) { (_: String, named: String, project: String, correlation: String) =>
    granted :+= Grant(project, GrantReader.service("brand", "players"), "*", Set("erasure"))
    ask(project, named, Some(s"$correlation-$suffix"))
    assert(answer.status == 201 || answer.status == 200, s"${answer.status} ${answer.text}")
  }
  private var listed: Vector[ErasureRequest] = Vector.empty
  When("a member lists the erasure requests of {string} with the correlation id {string}") {
    (project: String, correlation: String) =>
      listed = controlPlane.eventually("both listed") {
        val (status, body) =
          member("GET", s"/projects/$project/erasures?correlation=$correlation-$suffix")
        assertEquals(status, 200, body)
        Some(readFromString[Vector[ErasureRequest]](body)).filter(_.size >= 2)
      }
  }
  Then("both erasure requests are listed, each with where it stands") { () =>
    assertEquals(listed.map(_.projectId).toSet, Set("brand", "payments"))
    assert(listed.forall(_.state == ErasureState.Applying), listed.map(_.state).toString)
  }
