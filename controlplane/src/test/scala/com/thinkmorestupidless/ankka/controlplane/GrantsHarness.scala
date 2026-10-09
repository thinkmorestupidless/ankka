package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.ControlPlaneAcl
import com.thinkmorestupidless.ankka.controlplane.auth.DeployTokenIndex
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  ServiceProjector,
  TopologyReader
}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * A control plane in this JVM for the cross-project suites (feature 040): the shipped components,
 * consumers and endpoints over a real Postgres, an in-memory cluster behind the projector, deploy
 * tokens verified as a node verifies them, and as many people as a suite names, each with a token
 * of their own. A person is known by a short name: `ada` is subject `ada`, email
 * `ada@example.test`.
 */
final class GrantsHarness(
    topology: Option[TopologyReader] = None,
    /** Machines' settings (feature 040): the issuer tokens name, and the token route's rate. */
    val machineSettings: com.thinkmorestupidless.ankka.controlplane.auth.MachineSettings =
      com.thinkmorestupidless.ankka.controlplane.auth.MachineSettings.local
):

  val identity       = TestIdentity()
  val deployConfig   = DeployConfig.default
  val cluster        = new FakeAnkkaServiceClient
  val projector      = ServiceProjector.withClient(deployConfig, cluster)
  private val tokens = new DeployTokenIndex(identity.clock)

  /**
   * The keys machine tokens are signed with, held in memory as a local control plane holds them.
   */
  val machineKeys =
    com.thinkmorestupidless.ankka.controlplane.auth.MachineKeys.inMemory(identity.clock)
  private var kit: AnkkaTestKit = null
  private var url: String       = ""
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  private var people = Map.empty[String, String]

  def start(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        ControlPlaneAcl.composite(tokens, identity.acl(), identity.config()),
        deployConfig,
        auth = Some(identity.config()),
        clock = identity.clock,
        tokens = Some(tokens),
        topics = Some(projector),
        topology = topology,
        machineSettings = machineSettings,
        machineKeys = Some(machineKeys)
      )*
    )
    kit = AnkkaTestKit.start(
      ControlPlane.componentsWith(projector),
      Seq(ProjectionRuntime(), projector, server, tokens)
    )
    url = s"http://127.0.0.1:${server.boundPort.getOrElse(sys.error("server did not bind"))}"

  def stop(): Unit =
    if kit != null then kit.stop()
    identity.stop()

  /** A person's bearer token: theirs alone, minted once. */
  def tokenOf(person: String): String =
    people.getOrElse(
      person, {
        val token = identity.token(person, Some(s"$person@example.test"), expiresIn = 2.hours)
        people = people.updated(person, token)
        token
      }
    )

  /** A request as `person`, or as a bearer the caller already has. */
  def send(
      method: String,
      path: String,
      body: Option[String] = None,
      as: String = ""
  ): (Int, String) =
    request(method, path, body, Some(if as.startsWith("ankka_") then as else tokenOf(as)))

  def request(
      method: String,
      path: String,
      body: Option[String],
      bearer: Option[String]
  ): (Int, String) =
    val builder = JdkRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(30))
    bearer.foreach(b => builder.header("Authorization", s"Bearer $b"): Unit)
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  /** A service as the operator reports it: `ready` instances, and whether they read grants. */
  def observe(project: String, service: String, ready: Int, grants: Option[String]): Unit =
    val entity = kit.componentClient
      .forEventSourcedEntity(
        com.thinkmorestupidless.ankka.core.EntityId(
          com.thinkmorestupidless.ankka.controlplane.domain.ServiceKey(project, service).id
        )
      )
    def current =
      entity.call(com.thinkmorestupidless.ankka.controlplane.application.ServiceEntity.get).invoke()
    val namespace = s"${deployConfig.namespacePrefix}-$project"
    // As the operator reports it: on the resource, which the projector folds into the service, and
    // on the service at once. Only the resource's report survives the projector's next pass, which
    // would otherwise read a resource nothing has reported on.
    eventually(s"$service of $project reported ready", 60.seconds) {
      val generation = current.generation
      if cluster.current(namespace, service).isDefined then
        cluster.setStatus(
          namespace,
          service,
          com.thinkmorestupidless.ankka.crd.AnkkaServiceStatus(
            generation = generation,
            observedGeneration = generation,
            lifecycle = "Ready",
            readyInstances = ready,
            desiredInstances = ready.max(1),
            grants = grants
          )
        )
      entity
        .call(com.thinkmorestupidless.ankka.controlplane.application.ServiceEntity.observe)
        .invoke(
          com.thinkmorestupidless.ankka.controlplane.domain.ServiceObservation(
            generation = generation,
            lifecycle = com.thinkmorestupidless.ankka.controlplane.api.ServiceLifecycle.Ready,
            readyInstances = ready,
            desiredInstances = ready.max(1),
            grants = grants
          )
        ): Unit
      val now = current
      Option.when(
        cluster.current(namespace, service).exists(_.status.exists(_.readyInstances == ready)) &&
          now.readyInstances == ready && now.grants == grants
      )(())
    }

  /**
   * A request with its own content type and headers, answering the response's headers too: the
   * token route takes a form, and says when to come back.
   */
  def raw(
      method: String,
      path: String,
      body: Option[String],
      contentType: String = "application/x-www-form-urlencoded",
      headers: Map[String, String] = Map.empty
  ): (Int, String, Map[String, String]) =
    val builder = JdkRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(30))
    headers.foreach((k, v) => builder.header(k, v): Unit)
    body match
      case Some(text) =>
        builder.header("Content-Type", contentType)
        builder.method(method, JdkRequest.BodyPublishers.ofString(text)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    val answered = response
      .headers()
      .map()
      .entrySet()
      .toArray
      .collect { case e: java.util.Map.Entry[?, ?] => e }
      .map(e =>
        e.getKey.toString.toLowerCase -> e.getValue.toString.stripPrefix("[").stripSuffix("]")
      )
      .toMap
    (response.statusCode, response.body, answered)

  private def ok(result: (Int, String), what: String): String =
    if result._1 / 100 != 2 then sys.error(s"$what: ${result._1} ${result._2}")
    result._2

  /** An organization whose first owner is `owner`. */
  def organization(id: String, owner: String): Unit =
    ok(send("POST", s"/organizations/$id", Some(s"""{"name":"$id"}"""), owner), s"create $id"): Unit

  /** A project of an organization, created by one of its owners. */
  def project(id: String, organization: String, owner: String): Unit =
    ok(
      send(
        "POST",
        s"/projects/$id",
        Some(s"""{"name":"$id","organizationId":"$organization"}"""),
        owner
      ),
      s"create project $id"
    ): Unit

  /**
   * `person` invited to `organization` as `role` and seated, by claiming at their first request.
   */
  def member(person: String, organization: String, owner: String, role: String = "member"): Unit =
    ok(
      send(
        "POST",
        s"/organizations/$organization/members",
        Some(s"""{"email":"$person@example.test","role":"$role"}"""),
        owner
      ),
      s"invite $person"
    ): Unit
    ok(send("GET", s"/organizations/$organization", as = person), s"$person claims"): Unit

  /** A deploy token of `organization`, minted by `owner`: its bearer secret. */
  def deployToken(organization: String, owner: String): String =
    val body = ok(
      send("POST", s"/organizations/$organization/tokens", Some("""{"label":"ci"}"""), owner),
      "token"
    )
    val marker = "\"secret\":\""
    val from   = body.indexOf(marker) + marker.length
    body.substring(from, body.indexOf('"', from))

  /** A topic declared on a project. */
  def topic(project: String, name: String, owner: String, partitions: Int = 3): Unit =
    ok(
      send(
        "PUT",
        s"/projects/$project/topics/$name",
        Some(s"""{"partitions":$partitions}"""),
        owner
      ),
      s"declare $name"
    ): Unit

  /** Polls until `check` holds or the deadline passes. */
  def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(200)
    last.getOrElse(sys.error(s"$description did not happen within $within"))
