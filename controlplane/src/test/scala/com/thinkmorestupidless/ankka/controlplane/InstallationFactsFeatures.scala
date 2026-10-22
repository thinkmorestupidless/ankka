package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.Installation
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.DeployConfig
import com.thinkmorestupidless.ankka.controlplane.tenancy.{BrokerFacts, TopicPolicy}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import com.typesafe.config.{Config, ConfigFactory}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt

/**
 * `features/installation/facts.feature`, offline: the shipped endpoints in this JVM, the
 * installation's settings given per scenario as its configuration would give them
 * (`ankka.controlplane.topics`, `ankka.controlplane.broker.nodes`) and read by the same readers the
 * control plane starts with. The endpoint reads them per request, so a scenario's settings are what
 * the next read reports.
 */
class InstallationFactsFeatures
    extends GherkinSuite("../features/installation/facts.feature")
    with LogCapturing:

  override val munitTimeout = 4.minutes

  private lazy val identity = TestIdentity()
  private lazy val caller =
    identity.token("caller", Some("caller@example.test"), expiresIn = 2.hours)

  /** The settings the scenario gives, over the shipped ones. */
  private var settings: Map[String, String] = Map.empty

  private def config: Config =
    settings
      .foldLeft(ConfigFactory.empty()) { case (c, (k, v)) =>
        c.withFallback(ConfigFactory.parseString(s"""$k = "$v""""))
      }
      .withFallback(ConfigFactory.load())

  private var testKit: AnkkaTestKit = null
  private var url: String           = ""
  private var seen: Installation    = null
  private var raw: String           = ""
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        identity.acl(),
        DeployConfig.default,
        auth = Some(identity.config()),
        topicPolicy = TopicPolicy.from(config),
        brokerFacts = BrokerFacts.from(config)
      )*
    )
    testKit = AnkkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), server))
    url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("no port"))}"

  override def afterAll(): Unit =
    if testKit != null then
      identity.stop()
      testKit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    settings = Map.empty
    seen = null
    raw = ""

  // ── the installation ──────────────────────────────────────────────────────

  Given(
    "an installation whose broker has {string} nodes and gives a topic {string} copies with {string} in sync by default"
  ) { (nodes: String, copies: String, inSync: String) =>
    settings ++= Map(
      BrokerFacts.NodesKey                          -> nodes,
      s"${TopicPolicy.Section}.default-copies"      -> copies,
      s"${TopicPolicy.Section}.default-min-in-sync" -> inSync
    )
  }

  Given("whose longest retention is unbounded") { () =>
    settings += s"${TopicPolicy.Section}.longest-retention" -> "everything"
  }

  Given("an installation whose number of broker nodes is not known to the control plane") { () =>
    settings -= BrokerFacts.NodesKey
  }

  // Erasure is spec 042's; this installation, like every one today, has none to report.
  Given("whose erasure the platform does not yet have")(() => ())

  When("a caller with a token reads the installation") { () =>
    val response = http.send(
      HttpRequest
        .newBuilder(URI.create(s"$url/installation"))
        .header("Authorization", s"Bearer $caller")
        .GET()
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(response.statusCode, 200, response.body)
    raw = response.body
    seen = readFromString[Installation](raw)
  }

  // ── what it reports ───────────────────────────────────────────────────────

  Then(
    "the installation reports its broker as {string} nodes, {string} copies and {string} in sync by default"
  ) { (nodes: String, copies: String, inSync: String) =>
    val broker = seen.broker.getOrElse(fail(s"no broker reported: $raw"))
    assertEquals(broker.nodes, Some(nodes.toInt), raw)
    assertEquals(broker.defaultCopies, copies.toInt, raw)
    assertEquals(broker.minInSync, inSync.toInt, raw)
  }

  Then("its longest retention as unbounded, beside its backups") { () =>
    val retention = seen.retention.getOrElse(fail(s"no retention reported: $raw"))
    assert(retention.unbounded, raw)
    assertEquals(retention.longest, None, raw)
    assert(seen.backups.nonEmpty, raw)
  }

  Then(
    "the installation reports its broker's nodes as not stated and its erasure as not stated"
  ) { () =>
    val broker = seen.broker.getOrElse(fail(s"no broker reported: $raw"))
    assertEquals(broker.nodes, None, raw)
    assertEquals(seen.erasure, None, raw)
    assert(!raw.contains("\"nodes\""), raw)
    assert(!raw.contains("\"erasure\""), raw)
  }

  Then("every other fact as it is") { () =>
    val shipped = TopicPolicy.from(ConfigFactory.load())
    val broker  = seen.broker.getOrElse(fail(s"no broker reported: $raw"))
    assertEquals(broker.defaultCopies, shipped.defaults.copies, raw)
    assertEquals(broker.minInSync, shipped.defaults.minInSync, raw)
    assert(seen.retention.nonEmpty, raw)
    assert(seen.platformVersion.nonEmpty, raw)
    assert(seen.backups.nonEmpty, raw)
  }
