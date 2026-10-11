package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{
  ControlPlaneAcl,
  RestoreView,
  ServiceStatus,
  TopicDivergence
}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.auth.DeployTokenIndex
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  DivergenceReader,
  ProjectTopicsReader,
  RehearsalNamespaces
}
import com.thinkmorestupidless.ankka.crd.{
  AnkkaProjectStatus,
  BackupsStatus,
  LineStatus,
  RestoreStatus,
  ServiceVerification
}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * The restore and switch routes over real HTTP (feature 041): who may ask, and what each refuses
 * before anything is recorded. What the operator reports is scripted, as the projector would read
 * it from the project's resource.
 */
class RestoreRoutesSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private lazy val identity = TestIdentity()
  private lazy val alice = identity.token("alice", Some("alice@example.test"), expiresIn = 2.hours)
  private lazy val bob   = identity.token("bob", Some("bob@example.test"), expiresIn = 2.hours)

  private var testKit: AnkkaTestKit = null
  private var baseUrl: String       = ""
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  private val first = "2026-10-01T00:00:00Z"
  private val last  = "2026-10-08T10:00:00Z"

  private def line(name: String) =
    LineStatus(name, name, "BackingUp", firstRestorable = Some(first), lastRestorable = Some(last))

  /** What the operator last reported for `shop`. */
  @volatile private var reported = AnkkaProjectStatus(
    backups = Some(BackupsStatus("object-store", List(line("ankka-db"))))
  )

  private val reader: ProjectTopicsReader = projectId => Option.when(projectId == "shop")(reported)

  /** What the services say of the broker: the wallet publishes, and cannot be asked once. */
  private val divergence: DivergenceReader = (_, service, _) =>
    service match
      case "wallet" =>
        Right(
          Vector(
            TopicDivergence("transactions", None, 3L),
            TopicDivergence("transactions", Some("sums"), 3L, Some(2L))
          )
        )
      case other => Left(s"$other has no running instance")

  /** Every rehearsal namespace asked for; the cluster refuses while `refusing` is set. */
  private val namespaces         = new java.util.concurrent.ConcurrentLinkedQueue[String]()
  @volatile private var refusing = false
  private val rehearsalWriter: RehearsalNamespaces = projectId =>
    if refusing then throw IllegalStateException("forbidden")
    namespaces.add(projectId): Unit

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        ControlPlaneAcl.composite(
          new DeployTokenIndex(identity.clock),
          identity.acl(),
          identity.config()
        ),
        DeployConfig.default,
        auth = Some(identity.config()),
        clock = identity.clock,
        topics = Some(reader),
        backups = BackupConfig(target = "object-store"),
        divergence = Some(divergence),
        rehearsals = Some(rehearsalWriter)
      )*
    )
    testKit = AnkkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), server))
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
    assertEquals(send("POST", "/organizations/acme", alice, Some("""{"name":"Acme"}"""))._1, 204)
    assertEquals(
      send("POST", "/projects/shop", alice, Some("""{"name":"Shop","organizationId":"acme"}"""))._1,
      204
    )
    for service <- Seq("wallet", "rewards") do
      val (code, body) = send(
        "PUT",
        s"/services/shop/$service",
        alice,
        Some(s"""{"name":"$service","service":{"image":"$service:1"}}""")
      )
      assertEquals(code, 200, body)
    assertEquals(
      send(
        "POST",
        "/organizations/acme/members",
        alice,
        Some("""{"email":"bob@example.test"}""")
      )._1,
      204
    )
    // Bob's first listing claims the invitation; he is a member, not an owner.
    val deadline = System.nanoTime() + 30.seconds.toNanos
    while send("GET", "/projects/shop", bob)._1 != 200 && System.nanoTime() < deadline do
      send("GET", "/organizations", bob): Unit
      Thread.sleep(200)

  override def afterAll(): Unit =
    if testKit != null then
      testKit.stop()
      identity.stop()

  private def send(
      method: String,
      path: String,
      token: String,
      body: Option[String] = None
  ): (Int, String) =
    val builder = JdkRequest
      .newBuilder(URI.create(baseUrl + path))
      .timeout(Duration.ofSeconds(30))
      .header("Authorization", s"Bearer $token")
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def restore(token: String, moment: String, lineName: Option[String] = None) =
    val named = lineName.fold("")(l => s""","line":"$l"""")
    send("POST", "/projects/shop/restores", token, Some(s"""{"moment":"$moment"$named}"""))

  private def switch(token: String, service: String, cluster: String) =
    send("POST", s"/services/shop/$service/switch", token, Some(s"""{"cluster":"$cluster"}"""))

  // ── Restores ──────────────────────────────────────────────────────────────

  test("a member who is not an owner may not restore, and is told who may") {
    val (code, body) = restore(bob, "2026-10-05T00:00:00Z")
    assertEquals(code, 403, body)
    assert(body.contains("owner"), body)
  }

  test("a moment outside the window is refused, naming both of its ends") {
    for moment <- Seq("2026-09-01T00:00:00Z", "2027-01-01T00:00:00Z") do
      val (code, body) = restore(alice, moment)
      assertEquals(code, 400, body)
      assert(body.contains(first) && body.contains(last), body)
  }

  test("with services on two clusters a restore names the line, or is refused naming both") {
    val before = reported
    reported = before.copy(backups =
      Some(BackupsStatus("object-store", List(line("ankka-db"), line("ankka-db-r202610010000"))))
    )
    try
      val (code, body) = restore(alice, "2026-10-05T00:00:00Z")
      assertEquals(code, 400, body)
      assert(body.contains("ankka-db") && body.contains("ankka-db-r202610010000"), body)
      val (unknown, why) = restore(alice, "2026-10-05T00:00:00Z", Some("ankka-db-x"))
      assertEquals(unknown, 400, why)
    finally reported = before
  }

  test("an owner's restore is recorded, read back as reported, and switched to and back") {
    val (code, body) = restore(alice, "2026-10-05T00:00:00Z")
    assertEquals(code, 200, body)
    val name = readFromString[RestoreView](body).name
    reported = reported.copy(restores =
      List(
        RestoreStatus(
          name,
          "ankka-db",
          "2026-10-05T00:00:00Z",
          "Verified",
          reachedAt = Some("2026-10-04T23:59:58Z"),
          services = List(
            ServiceVerification("wallet", present = true, journalRows = 4, highestSequence = 4),
            ServiceVerification("rewards", present = false)
          )
        )
      )
    )
    val (read, view) = send("GET", s"/projects/shop/restores/$name", alice)
    assertEquals(read, 200, view)
    val restored = readFromString[RestoreView](view)
    assertEquals(restored.phase, "Verified")
    assertEquals(restored.services.find(_.name == "wallet").map(_.journalRows), Some(4L))
    // What the broker holds past the moment, as the services present in the restore say it.
    assertEquals(
      restored.broker,
      Vector(
        TopicDivergence("transactions", None, 3L, None, Vector("wallet")),
        TopicDivergence("transactions", Some("sums"), 3L, Some(2L), Vector("wallet"))
      )
    )
    assertEquals(restored.notAsked, Vector.empty, "rewards was not in the restore, so not asked")
    assert(restored.note.exists(_.contains("read again by every view and consumer")), view)
    assertEquals(send("GET", "/projects/shop/restores/ankka-db-r000000000000", alice)._1, 404)

    val (member, refusal) = switch(bob, "wallet", name)
    assertEquals(member, 403, refusal)
    val (absent, noSuch) = switch(alice, "wallet", "ankka-db-r000000000000")
    assertEquals(absent, 400, noSuch)
    val (missing, why) = switch(alice, "rewards", name)
    assertEquals(missing, 400, why)
    assert(why.contains("no database at 2026-10-05T00:00:00Z"), why)

    val (switched, status) = switch(alice, "wallet", name)
    assertEquals(switched, 200, status)
    assertEquals(readFromString[ServiceStatus](status).databaseCluster, Some(name))
    val (_, history) = send("GET", "/services/shop/wallet/history", alice)
    assert(history.contains("switched") && history.contains(s"from ankka-db to $name"), history)
    val (back, again) = switch(alice, "wallet", "ankka-db")
    assertEquals(back, 200, again)
    assertEquals(readFromString[ServiceStatus](again).databaseCluster, None)
  }

  test("a member sets what the project asks of its database, under the installation's rules") {
    def put(token: String, body: String) = send("PUT", "/projects/shop/database", token, Some(body))
    val (refused, why) = put(bob, """{"replicas":0,"synchronous":true,"retentionDays":7}""")
    assertEquals(refused, 400, why)
    assert(why.contains("at least one replica") && why.contains("at least 30 days"), why)
    namespaces.clear()
    val (code, body) = put(bob, """{"replicas":2,"synchronous":true,"rehearse":"weekly"}""")
    assertEquals(code, 200, body)
    // The operator rehearses on the schedule in a namespace only the control plane can make.
    assert(namespaces.contains("shop"), namespaces.toString)
    val (_, read) = send("GET", "/projects/shop/database", alice)
    assert(read.contains("\"replicas\":2") && read.contains("\"weekly\""), read)
    val (_, history) = send("GET", "/projects/shop/history", alice)
    assert(history.contains("database-set") && history.contains("2 replicas, synchronous"), history)
  }

  test("a restore still restoring cannot be switched to") {
    reported = reported.copy(restores =
      reported.restores :+ RestoreStatus("ankka-db-r202610090000", "ankka-db", first, "Restoring")
    )
    val (code, body) = switch(alice, "wallet", "ankka-db-r202610090000")
    assertEquals(code, 409, body)
    assert(body.contains("Restoring"), body)
  }

  test("a member rehearses: the namespace is made first, and a refused one records nothing") {
    def rehearse() = send("POST", "/projects/shop/rehearsals", bob, Some("{}"))
    refusing = true
    val (refused, why) = rehearse()
    assertEquals(refused, 503, why)
    val (_, none) = send("GET", "/projects/shop/rehearsals", alice)
    assertEquals(none, "[]")
    refusing = false
    val (code, body) = rehearse()
    assertEquals(code, 200, body)
    assert(body.contains(s"\"moment\":\"$last\""), "the latest restorable moment by default")
    assertEquals(namespaces.asScala.toVector.last, "shop")
    val (_, listed) = send("GET", "/projects/shop/rehearsals", alice)
    assert(listed.contains("Running") && listed.contains("requestedBy"), listed)
    val (again, running) = rehearse()
    assertEquals(again, 409, running)
  }
