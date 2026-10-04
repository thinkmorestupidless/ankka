package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.application.ServiceEntity
import com.thinkmorestupidless.ankka.controlplane.deploy.{DeployConfig, ServiceProjection}
import com.thinkmorestupidless.ankka.controlplane.domain.ServiceKey
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.crd.AnkkaService
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.{
  Action,
  BrokerObservation,
  BrokerProvisioning,
  BrokerStack,
  ProvisioningPlan,
  Rendering,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import scala.concurrent.duration.DurationInt

/**
 * `features/broker/descriptor.feature`, against the real apply route.
 *
 * Every descriptor is applied twice: through the CLI's `Main.run`, and as a raw request, so a
 * refusal is seen from both ends in the same words. "The installation's broker has the topic"
 * follows the real chain with no broker running: the service's recorded state, projected into the
 * resource the control plane writes, rendered by the operator's own rendering for an installation
 * with a broker — the `KafkaTopic` that rendering produces is what the broker's operator makes.
 */
class BrokerDescriptorFeature
    extends GherkinSuite("../features/broker/descriptor.feature")
    with LogCapturing:

  override val munitTimeout = 4.minutes

  private lazy val identity = TestIdentity()
  private lazy val Token =
    identity.token("tester", Some("tester@example.test"), expiresIn = 2.hours)

  private var testKit: AnkkaTestKit = null
  private var url: String           = ""
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  private lazy val config = Files.createTempFile("ankka-broker-descriptor", ".json")

  override def beforeAll(): Unit =
    Files.delete(config)
    sys.props("ankka.config") = config.toString
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        identity.acl(),
        DeployConfig.default,
        auth = Some(identity.config()),
        clock = identity.clock
      )*
    )
    testKit = AnkkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), server))
    url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
    assertEquals(send("POST", "/organizations/acme", Some("""{"name":"Acme"}"""))._1, 204)

  override def afterAll(): Unit =
    sys.props.remove("ankka.config"): Unit
    Files.deleteIfExists(config): Unit
    if testKit != null then
      testKit.stop()
      identity.stop()

  private def send(method: String, path: String, body: Option[String] = None): (Int, String) =
    val builder = JdkRequest
      .newBuilder(URI.create(url + path))
      .timeout(Duration.ofSeconds(30))
      .header("Authorization", s"Bearer $Token")
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  // ── the scenario's state ──────────────────────────────────────────────────

  /** Each scenario has a project of its own, so services from one never meet another's. */
  private var project: String = ""
  private var count: Int      = 0

  private var name: String           = ""
  private var fields: Vector[String] = Vector.empty
  private var env: Vector[String]    = Vector.empty
  private var topics: Vector[String] = Vector.empty
  private var topic: String          = ""
  private var partitions: Int        = 0

  private var cliResult: (Int, String) = (0, "")
  private var rawResult: (Int, String) = (0, "")

  override def beforeEach(context: BeforeEach): Unit =
    count += 1
    project = ""
    name = ""
    fields = Vector.empty
    env = Vector.empty
    topics = Vector.empty
    topic = ""
    partitions = 0

  private def json: String =
    val lists = Vector(
      Option.when(topics.nonEmpty)(topics.mkString(""""topics":[""", ",", "]")),
      Option.when(env.nonEmpty)(env.mkString(""""env":[""", ",", "]"))
    ).flatten
    val service = (s""""image":"$name:1.0"""" +: (fields ++ lists)).mkString(",")
    s"""{"name":"$name","service":{$service}}"""

  private def declaring(t: String, n: Int): String = s"""{"name":"$t","partitions":$n}"""

  private def apply(): Unit =
    val file = Files.createTempFile("ankka-descriptor", ".json")
    try
      Files.writeString(file, json): Unit
      val out = ByteArrayOutputStream()
      val err = ByteArrayOutputStream()
      val code = Main.run(
        Seq(
          "services",
          "apply",
          "-f",
          file.toString,
          "-p",
          project,
          "--url",
          url,
          "--token",
          Token
        ),
        PrintStream(out, true, StandardCharsets.UTF_8),
        PrintStream(err, true, StandardCharsets.UTF_8)
      )
      cliResult = (code, err.toString(StandardCharsets.UTF_8))
    finally Files.deleteIfExists(file): Unit
    rawResult = send("PUT", s"/services/$project/$name", Some(json))

  /** The topic the operator renders for `service`, for an installation with a broker. */
  private def renderedPartitions(service: String, topicName: String): Option[Int] =
    val state = testKit.componentClient
      .forEventSourcedEntity(EntityId(ServiceKey(project, service).id))
      .call(ServiceEntity.desiredState)
      .invoke()
      .getOrElse(fail(s"no service '$service'"))
    val spec = ServiceProjection
      .project(state, DeployConfig.default)
      .fold(problems => fail(problems.mkString("; ")), a => a)
    val resource = new AnkkaService
    resource.setMetadata(
      new ObjectMetaBuilder().withNamespace(s"ankka-$project").withName(service).build()
    )
    resource.setSpec(spec)
    val settings = OperatorSettings.default.copy(broker = Some(BrokerStack.settings))
    Rendering
      .render(
        resource,
        settings,
        ProvisioningPlan.Supplied,
        BrokerProvisioning.topicsToRender(spec, settings.broker, BrokerObservation.empty)
      )
      .fold(problems => fail(problems.mkString("; ")), a => a)
      .collectFirst {
        case Action.EnsureKafkaTopic(t) if t.getMetadata.getName == s"$project.$topicName" =>
          t.getSpec.partitions
      }

  // ── Given ─────────────────────────────────────────────────────────────────

  // The broker itself is not needed by these rules: they are checked before anything is made.
  Given("an installation with a broker")(() => ())

  Given("a project {string}") { (p: String) =>
    project = s"$p-$count"
    assertEquals(
      send(
        "POST",
        s"/projects/$project",
        Some(s"""{"name":"$p","organizationId":"acme"}""")
      )._1,
      204
    )
  }

  private val declares: Vector[(String, () => Unit)] = Vector(
    """declares a topic and gives the variable "ANKKA_KAFKA_BOOTSTRAP_SERVERS"""" -> { () =>
      topic = "transactions"
      topics :+= declaring(topic, 12)
      env :+= """{"name":"ANKKA_KAFKA_BOOTSTRAP_SERVERS","value":"kafka:9092"}"""
    },
    "is for a web-hosted service and declares a topic" -> { () =>
      topic = "transactions"
      fields :+= """"hosting":"web""""
      topics :+= declaring(topic, 12)
    },
    """declares the topic "not a topic name"""" -> { () =>
      topic = "not a topic name"
      topics :+= declaring(topic, 12)
    },
    "declares a topic with 0 partitions" -> { () =>
      topic = "transactions"
      partitions = 0
      topics :+= declaring(topic, 0)
    }
  )

  declares.foreach { (phrase, declare) =>
    Given(s"a descriptor for a service {string} in {string} that $phrase") {
      (s: String, _: String) =>
        name = s
        declare()
    }
  }

  Given(
    "a deployed service {string} in {string} that declares the topic {string} with {int} partitions"
  ) { (s: String, _: String, t: String, n: Int) =>
    name = s
    topics = Vector(declaring(t, n))
    apply()
    assertEquals(rawResult._1, 200, rawResult._2)
    // The other service's check reads the project's listing, which trails a write.
    val deadline = 30.seconds.fromNow
    while !send("GET", s"/services/$project")._2.contains(s""""name":"$s"""") &&
      deadline.hasTimeLeft()
    do Thread.sleep(200)
  }

  Given(
    "a descriptor for a service {string} in {string} that declares the topic {string} with {int} partitions"
  ) { (s: String, _: String, t: String, n: Int) =>
    name = s
    topic = t
    topics = Vector(declaring(t, n))
  }

  // ── When ──────────────────────────────────────────────────────────────────

  When("a member applies the descriptor")(() => apply())

  When("a member applies the descriptor of {string} with {int} partitions for {string}") {
    (s: String, n: Int, t: String) =>
      name = s
      topic = t
      partitions = n
      topics = Vector(declaring(t, n))
      apply()
  }

  // ── Then ──────────────────────────────────────────────────────────────────

  /**
   * Refused at both ends: by the descriptor's own rules, which the CLI checks before sending and
   * the control plane again (400), or by a rule that needs the project's other services, which only
   * the control plane can check (409).
   */
  private def refused(): Unit =
    assertEquals(cliResult._1, 1, s"the CLI accepted $json: ${cliResult._2}")
    assert(Set(400, 409)(rawResult._1), s"the control plane accepted $json: $rawResult")

  private def refusalSays(words: String): Unit =
    val escaped = words.replace("\"", "\\\"")
    assert(cliResult._2.contains(words), s"the CLI's refusal lacks <$words>: ${cliResult._2}")
    assert(rawResult._2.contains(escaped), s"the control plane's lacks <$escaped>: ${rawResult._2}")

  Then("the member is refused")(() => refused())

  Then("the refusal names the topic and the variable") { () =>
    refusalSays("topics are declared for the installation's broker")
    refusalSays("env var 'ANKKA_KAFKA_BOOTSTRAP_SERVERS'")
  }

  Then("the refusal names the topic")(() => refusalSays(s"topic"))

  Then("the refusal names the partitions") { () =>
    refusalSays(s"partitions $partitions")
  }

  Then("nothing is made on the installation's broker") { () =>
    // Refused before anything was recorded: there is no service to project, so nothing to render.
    assertEquals(send("GET", s"/services/$project/$name")._1, 404)
  }

  Then("the refusal names the topic and {string}") { (other: String) =>
    refusalSays(s"topic '$topic' is declared by '$other'")
  }

  Then(
    "the installation's broker has the topic {string} of {string} with {int} partitions"
  ) { (t: String, _: String, n: Int) =>
    assertEquals(rawResult._1, 200, rawResult._2)
    assertEquals(renderedPartitions(name, t), Some(n))
  }

  Then("the member is refused, and the refusal names the partitions") { () =>
    refused()
    refusalSays(s"topic '$topic' has")
    refusalSays(s"$partitions was asked")
  }
