package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.auth.DeployTokenIndex
import com.thinkmorestupidless.ankka.controlplane.deploy.DeployConfig
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.*

/**
 * `features/topology/deployed-services.feature`, run as it is written against the real control
 * plane, with the instances' answers scripted: what each instance of a service reports, or that it
 * does not. The member and the stranger read the route as the console and the CLI do.
 */
final class DeployedTopologyFeatures
    extends GherkinSuite("../features/topology/deployed-services.feature")
    with LogCapturing:

  override val munitTimeout: scala.concurrent.duration.Duration = 5.minutes

  private lazy val identity = TestIdentity()
  private lazy val member =
    identity.token("member", Some("member@example.test"), expiresIn = 2.hours)
  private lazy val stranger =
    identity.token("stranger", Some("stranger@example.test"), expiresIn = 2.hours)
  private lazy val tokens = new DeployTokenIndex(identity.clock)
  private val topologies  = new ScriptedTopologies
  private val http        = HttpClient.newHttpClient()

  private var kit: AnkkaTestKit = null
  private var baseUrl: String   = ""

  // What the scenario has done and seen.
  private var project: String                 = ""
  private var service: String                 = ""
  private var instances: Vector[String]       = Vector.empty
  private var answered: Option[(Int, String)] = None

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        ControlPlaneAcl.composite(tokens, identity.acl(), identity.config()),
        DeployConfig.default,
        auth = Some(identity.config()),
        clock = identity.clock,
        tokens = Some(tokens),
        topology = Some(topologies)
      )*
    )
    kit = AnkkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), server, tokens))
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

  override def afterAll(): Unit =
    if kit != null then
      kit.stop()
      identity.stop()

  private def send(
      method: String,
      path: String,
      body: Option[String],
      token: String
  ): (Int, String) =
    val builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
    builder.header("Authorization", s"Bearer $token")
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, HttpRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, HttpRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def topology: ServiceTopology =
    val (status, body) = answered.getOrElse(fail("nobody has read the topology yet"))
    assertEquals(status, 200, body)
    readFromString[ServiceTopology](body)

  /** A deployed service: an organization the member owns, the project, the service applied. */
  private def deployed(name: String, projectId: String, count: Int): Unit =
    val unique = java.util.UUID.randomUUID().toString.take(8)
    val org    = s"org-$unique"
    project = s"$projectId-$unique"
    service = name
    assertEquals(send("POST", s"/organizations/$org", Some(s"""{"name":"Org"}"""), member)._1, 204)
    assertEquals(
      send(
        "POST",
        s"/projects/$project",
        Some(s"""{"name":"P","organizationId":"$org"}"""),
        member
      )._1,
      204
    )
    assertEquals(
      send(
        "PUT",
        s"/services/$project/$name",
        Some(s"""{"name":"$name","service":{"image":"cart:1"}}"""),
        member
      )._1,
      200
    )
    instances = (0 until count).map(i => s"$name-$i").toVector
    topologies.script(
      project,
      name,
      instances.map(pod => Topologies.ok(pod) -> Some(Topologies.cart(pod, 1L)))
    )

  private def current = topologies.read(project, service)

  Given("a service {string} deployed in the project {string}") { (name: String, projectId: String) =>
    deployed(name, projectId, 1)
  }

  Given("a service {string} deployed in the project {string} with {int} instances") {
    (name: String, projectId: String, count: Int) => deployed(name, projectId, count)
  }

  // Who reads is said again by the When; the member owns the organization the service was made in.
  Given("a member of {string}")((_: String) => ())
  Given("a person who is not a member of {string}")((_: String) => ())

  Given(
    "each instance has handled {int} calls from its endpoint to the event sourced entity {string}"
  ) { (calls: Int, entity: String) =>
    assertEquals(entity, "cart")
    topologies.script(
      project,
      service,
      instances.map(pod => Topologies.ok(pod) -> Some(Topologies.cart(pod, calls.toLong)))
    )
  }

  Given("{int} instance that does not answer") { (count: Int) =>
    topologies.script(
      project,
      service,
      current.zipWithIndex.map {
        case (_, i) if i < count =>
          InstanceTopology(
            instances(i),
            InstanceStatus.Unreachable,
            Some("no answer within 2s")
          ) -> None
        case (read, _) => read
      }
    )
  }

  Given("{int} instance too old to report its topology") { (count: Int) =>
    topologies.script(
      project,
      service,
      current.zipWithIndex.map {
        case (_, i) if i < count =>
          InstanceTopology(
            instances(i),
            InstanceStatus.Unsupported,
            Some("this instance's runtime serves no topology")
          ) -> None
        case (read, _) => read
      }
    )
  }

  Given("{int} instance with a view {string} that the other instance does not have") {
    (count: Int, view: String) =>
      topologies.script(
        project,
        service,
        current.zipWithIndex.map {
          case ((instance, _), i) if i < count =>
            instance -> Some(Topologies.cart(instances(i), 0L, Vector(Topologies.view(view))))
          case (read, _) => read
        }
      )
  }

  private def readAs(token: String, name: String): Unit =
    answered = Some(send("GET", s"/services/$project/$name/topology", None, token))

  When("the member reads the topology of {string}")((name: String) => readAs(member, name))
  When("a member of {string} reads the topology of {string}") { (_: String, name: String) =>
    readAs(member, name)
  }
  When("the person reads the topology of {string}")((name: String) => readAs(stranger, name))

  Then(
    "the member is shown the components, the declared connections and the observed calls of {string}"
  ) { (name: String) =>
    val shown = topology
    assertEquals(shown.service, name)
    assert(
      shown.nodes.exists(_.id == "cart") && shown.nodes.exists(_.id == "endpoint:/carts"),
      shown.toString
    )
    assert(shown.nodes.nonEmpty && shown.calls.nonEmpty, shown.toString)
  }

  Then("the member is given no credential for the service, its instances or the cluster") { () =>
    val (_, body) = answered.get
    for secret <- Seq("BEGIN", "token", "secret", "password", "kubeconfig", "https://") do
      assert(
        !body.toLowerCase.contains(secret.toLowerCase),
        s"the response carries '$secret': $body"
      )
  }

  Then("the observed call from the endpoint to {string} is handled {int} times, as ok") {
    (entity: String, times: Int) =>
      val call = topology.calls
        .find(c => c.from == "endpoint:/carts" && c.to == entity)
        .getOrElse(fail(topology.toString))
      assertEquals(call.pairs.map(_.handled.ok).sum, times.toLong)
  }

  Then("the topology says that {int} of {int} instances answered") {
    (answeredCount: Int, running: Int) =>
      assertEquals((topology.contributing, topology.running), (answeredCount, running))
  }

  Then("the topology is marked as partial")(() => assert(topology.partial, topology.toString))

  Then("the topology names the instance that did not answer") { () =>
    val missing = topology.instances.filter(_.status == InstanceStatus.Unreachable)
    assertEquals(missing.map(_.pod), Vector(instances.head))
  }

  Then("the topology names that instance as unsupported") { () =>
    val old = topology.instances.filter(_.status == InstanceStatus.Unsupported)
    assertEquals(old.map(_.pod), Vector(instances.head))
    assert(old.head.problem.exists(_.contains("serves no topology")), old.toString)
  }

  Then("the person is refused") { () =>
    assertEquals(answered.get._1, 404, answered.get._2)
  }

  Then("the refusal is the one given for a service that does not exist") { () =>
    val (status, body) = send("GET", s"/services/$project/no-such-service", None, stranger)
    assertEquals(status, 404)
    assertEquals(
      answered.get._2,
      body,
      "a stranger cannot tell a service they may not see from none"
    )
  }

  Then("the topology shows {string} as a view") { (view: String) =>
    assertEquals(topology.nodes.find(_.id == view).map(_.kind), Some("View"), topology.toString)
  }

  Then("the topology names the instance that has {string}") { (view: String) =>
    assertEquals(
      topology.differences.find(_.node == view).map(_.presentOn),
      Some(Vector(instances.head)),
      topology.toString
    )
  }
