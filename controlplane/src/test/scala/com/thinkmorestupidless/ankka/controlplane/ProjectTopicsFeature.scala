package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.ProjectEndpoint
import com.thinkmorestupidless.ankka.controlplane.deploy.{DeployConfig, ServiceProjector}
import com.thinkmorestupidless.ankka.crd.AnkkaProjectSpec
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.{
  Action,
  BrokerStack,
  ProjectReconciler,
  ServiceRef,
  StrimziObjectState,
  TopicProvisioning,
  TopicState
}
import com.thinkmorestupidless.ankka.operator.strimzi.{KafkaTopicStatus, StrimziCondition}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import scala.concurrent.duration.DurationInt

/**
 * `features/broker/declaring.feature`, against the real routes, entity, trigger and projector, with
 * an in-memory cluster.
 *
 * Every declaration is made twice: through the CLI's `Main.run`, and as a raw request, so a refusal
 * is seen from both ends in the same words. "The installation's broker has the topic" follows the
 * real chain with no broker running: the `AnkkaProject` the control plane wrote, put through the
 * operator's own pass for an installation with a broker. What the broker reports back is the
 * operator's own decision over the observation a row describes, written where the operator writes
 * it; the broker's k3s suite runs the same scenarios against Strimzi.
 */
class ProjectTopicsFeature
    extends GherkinSuite("../features/broker/declaring.feature")
    with LogCapturing:

  override val munitTimeout = 4.minutes

  private lazy val identity = TestIdentity()
  private lazy val Token =
    identity.token("tester", Some("tester@example.test"), expiresIn = 2.hours)
  private lazy val OtherToken =
    identity.token("stranger", Some("stranger@example.test"), expiresIn = 2.hours)

  private val deployConfig          = DeployConfig.default
  private lazy val cluster          = new FakeAnkkaServiceClient
  private lazy val projector        = ServiceProjector.withClient(deployConfig, cluster)
  private var testKit: AnkkaTestKit = null
  private var url: String           = ""
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  private lazy val config = Files.createTempFile("ankka-project-topics", ".json")

  override def beforeAll(): Unit =
    Files.delete(config)
    sys.props("ankka.config") = config.toString
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        identity.acl(),
        deployConfig,
        auth = Some(identity.config()),
        clock = identity.clock,
        topics = Some(projector)
      )*
    )
    testKit = AnkkaTestKit.start(
      ControlPlane.componentsWith(projector),
      Seq(ProjectionRuntime(), projector, server)
    )
    url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
    assertEquals(send("POST", "/organizations/acme", Some("""{"name":"Acme"}"""))._1, 204)
    assertEquals(
      send("POST", "/organizations/elsewhere", Some("""{"name":"Elsewhere"}"""), OtherToken)._1,
      204
    )

  override def afterAll(): Unit =
    sys.props.remove("ankka.config"): Unit
    Files.deleteIfExists(config): Unit
    if testKit != null then
      testKit.stop()
      identity.stop()

  private def send(
      method: String,
      path: String,
      body: Option[String] = None,
      token: String = Token
  ): (Int, String) =
    val builder = JdkRequest
      .newBuilder(URI.create(url + path))
      .timeout(Duration.ofSeconds(30))
      .header("Authorization", s"Bearer $token")
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  // ── the scenario's state ──────────────────────────────────────────────────

  /** Each scenario has projects of its own, so no scenario sees another's topics. */
  private var count: Int                    = 0
  private var projects: Map[String, String] = Map.empty
  private def project(name: String)         = projects.getOrElse(name, fail(s"no project '$name'"))

  private var topic: String            = ""
  private var partitions: Int          = 0
  private var cliResult: (Int, String) = (0, "")
  private var rawResult: (Int, String) = (0, "")

  override def beforeEach(context: BeforeEach): Unit =
    count += 1
    projects = Map.empty
    topic = ""
    partitions = 0

  private def namespace(p: String) = deployConfig.namespaceFor(p)

  private def declare(name: String, p: String, n: Int): Unit =
    topic = name
    partitions = n
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code = Main.run(
      Seq(
        "projects",
        "topics",
        "set",
        name,
        "--partitions",
        n.toString,
        "-p",
        p,
        "--url",
        url,
        "--token",
        Token
      ),
      PrintStream(out, true, StandardCharsets.UTF_8),
      PrintStream(err, true, StandardCharsets.UTF_8)
    )
    cliResult = (code, err.toString(StandardCharsets.UTF_8))
    rawResult = send(
      "PUT",
      s"/projects/$p/topics/${java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")}",
      Some(s"""{"partitions":$n}""")
    )

  /** Waits for the trigger, which writes the declarations after the record, to have written `t`. */
  private def writtenWith(p: String, t: String): AnkkaProjectSpec =
    val deadline = 30.seconds.fromNow
    while !written(p).exists(_.topics.exists(_.name == t)) && deadline.hasTimeLeft() do
      Thread.sleep(200)
    written(p).getOrElse(fail(s"nothing was written for '$p'"))

  /** The project's declarations as the control plane wrote them for the operator. */
  private def written(p: String): Option[AnkkaProjectSpec] = cluster.project(namespace(p), p)

  /**
   * The topics the operator's pass makes from what was written, for an installation with a broker.
   */
  private def rendered(p: String): Vector[(String, Int)] =
    ProjectReconciler
      .actions(
        ServiceRef(namespace(p), p),
        written(p).getOrElse(fail(s"nothing was written for '$p'")),
        Some(BrokerStack.settings),
        Map.empty,
        None
      )
      .collect { case Action.EnsureKafkaTopic(t) =>
        t.getMetadata.getName -> t.getSpec.partitions
      }

  private def listed(p: String): Vector[(String, Int, Option[String])] =
    val (status, body) = send("GET", s"/projects/$p/topics")
    assertEquals(status, 200, body)
    """\{"name":"([^"]+)","partitions":(\d+)(?:,"phase":"([^"]+)")?""".r
      .findAllMatchIn(body)
      .map(m => (m.group(1), m.group(2).toInt, Option(m.group(3))))
      .toVector

  // ── Given ─────────────────────────────────────────────────────────────────

  // The broker itself is not needed by these rules: they are the project's own.
  Given("an installation with a broker")(() => ())

  Given("a project {string}") { (p: String) =>
    val id = s"$p-$count"
    assertEquals(
      send("POST", s"/projects/$id", Some(s"""{"name":"$p","organizationId":"acme"}"""))._1,
      204
    )
    projects += p -> id
  }

  Given("a project {string} of another organization") { (p: String) =>
    val id = s"$p-$count"
    assertEquals(
      send(
        "POST",
        s"/projects/$id",
        Some(s"""{"name":"$p","organizationId":"elsewhere"}"""),
        OtherToken
      )._1,
      204
    )
    projects += p -> id
  }

  Given("the topic {string} is declared on {string} with {int} partitions") {
    (t: String, p: String, n: Int) =>
      declare(t, project(p), n)
      assertEquals(rawResult._1, 204, rawResult._2)
  }

  private val stale = StrimziObjectState.found(
    Some(2L),
    Some(KafkaTopicStatus(Vector(StrimziCondition("Ready", "True")), Some(1L))),
    None
  )
  // Made for the declaration, not before it: no creation time reads as never recovered.
  private val ready = StrimziObjectState(exists = true, ready = Some(true))

  private val states: Vector[(String, Int => Map[String, TopicState])] = Vector(
    "has not yet made the topic" -> (_ => Map.empty),
    "has made the topic with fewer partitions so far" -> (n =>
      Map("t" -> TopicState(stale, Some(n)))
    ),
    "has made the topic" -> (n => Map("t" -> TopicState(ready, Some(n)))),
    "has a problem with the topic that will not clear" -> (n =>
      Map("t" -> TopicState(ready, Some(n + 1)))
    )
  )

  states.foreach { (state, observe) =>
    Given(s"the installation's broker $state") { () =>
      // The project the outline declared on is the one scenario's project.
      val p    = projects.values.head
      val spec = writtenWith(p, topic)
      val seen = observe(partitions).map((_, s) => s"$p.$topic" -> s)
      cluster.reportProject(
        namespace(p),
        p,
        TopicProvisioning.status(spec, Some(BrokerStack.settings), seen)
      )
    }
  }

  // ── When ──────────────────────────────────────────────────────────────────

  When("a member declares the topic {string} on {string} with {int} partitions") {
    (t: String, p: String, n: Int) =>
      declare(t, project(p), n)
  }

  When("a member reads the topics of {string}")((_: String) => ())

  // ── Then ──────────────────────────────────────────────────────────────────

  Then("the installation's broker has the topic {string} of {string} with {int} partitions") {
    (t: String, p: String, n: Int) =>
      assertEquals(cliResult._1, 0, cliResult._2)
      assertEquals(rawResult._1, 204, rawResult._2)
      val id       = project(p)
      val deadline = 30.seconds.fromNow
      while !written(id).exists(_.topics.exists(e => e.name == t && e.partitions == n)) &&
        deadline.hasTimeLeft()
      do Thread.sleep(200)
      assert(rendered(id).contains(s"$id.$t" -> n), rendered(id).toString)
  }

  Then("the broker holds that topic under the name {string}") { (name: String) =>
    val (p, t) = name.splitAt(name.indexOf('.'))
    assert(
      rendered(project(p)).map(_._1).contains(s"${project(p)}$t"),
      rendered(project(p)).toString
    )
  }

  /**
   * Refused at both ends: by the rules the CLI checks before sending and the control plane checks
   * again (400), or by the project's own record (409), or because the project is not the member's
   * (404).
   */
  private def refused(): Unit =
    assertEquals(cliResult._1, 1, s"the CLI accepted it: ${cliResult._2}")
    assert(Set(400, 404, 409)(rawResult._1), s"the control plane accepted it: $rawResult")

  private def refusalSays(words: String): Unit =
    val escaped = words.replace("\"", "\\\"")
    assert(cliResult._2.contains(words), s"the CLI's refusal lacks <$words>: ${cliResult._2}")
    assert(rawResult._2.contains(escaped), s"the control plane's lacks <$escaped>: ${rawResult._2}")

  Then("the member is refused")(() => refused())

  Then("the refusal names the topic") { () =>
    // The route answers with the name as it arrived in the path, so a name that had to be
    // escaped there is named escaped; either way it is this topic.
    val escaped = java.net.URLEncoder.encode(topic, StandardCharsets.UTF_8).replace("+", "%20")
    assert(cliResult._2.contains(s"topic '$topic'"), cliResult._2)
    assert(
      rawResult._2.contains(s"topic '$topic'") || rawResult._2.contains(s"topic '$escaped'"),
      rawResult._2
    )
  }

  Then("the refusal names the partitions")(() => refusalSays(s"partitions $partitions"))

  Then("nothing is made on the installation's broker") { () =>
    val p = projects.values.head
    // Given time to be written, had it been going to be.
    Thread.sleep(1000)
    assert(!written(p).exists(_.topics.exists(_.name == topic)), written(p).toString)
  }

  Then("{string} declares the topic {string} once, with {int} partitions") {
    (p: String, t: String, n: Int) =>
      assertEquals(rawResult._1, 204, rawResult._2)
      assertEquals(listed(project(p)).map(e => (e._1, e._2)), Vector(t -> n))
  }

  Then("the member is refused, and the refusal names the partitions") { () =>
    refused()
    refusalSays(s"topic '$topic' has")
    refusalSays(s"$partitions was asked")
  }

  Then("the topic {string} is {string}") { (t: String, word: String) =>
    val p = projects.values.head
    assertEquals(
      listed(p).collectFirst { case (`t`, _, phase) => phase },
      Some(Some(ProjectEndpoint.topicPhrase(word)))
    )
  }
