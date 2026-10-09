package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.{
  ControlPlaneAcl,
  PlatformStatus,
  SecretReadsPage
}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.auth.DeployTokenIndex
import com.thinkmorestupidless.ankka.controlplane.secrets.{
  InMemoryReadRecordStore,
  PostgresReadRecordStore,
  SecretRecords,
  SecretRecordsConfig
}
import com.thinkmorestupidless.ankka.core.secrets.ReadRecord
import com.thinkmorestupidless.ankka.http.{Caller, HttpServer}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.{Duration, Instant}
import scala.concurrent.duration.DurationInt

/**
 * `features/secrets/read-record.feature` on the control plane's side: a service writes its records
 * as itself and only as itself, an owner reads them, nobody else does, and they go once past the
 * installation's retention.
 */
abstract class SecretReadsBehaviours extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private lazy val identity = TestIdentity()
  private lazy val Owner = identity.token("owner", Some("owner@example.test"), expiresIn = 2.hours)
  private lazy val Outsider =
    identity.token("outsider", Some("outsider@example.test"), expiresIn = 2.hours)

  private lazy val tokens = new DeployTokenIndex(identity.clock)
  private val config      = SecretRecordsConfig(retention = 365.days, sweepInterval = 1.day)

  /** The records extension this suite's control plane keeps its records in. */
  protected def records(config: SecretRecordsConfig, clock: java.time.Clock): SecretRecords

  private var kept: SecretRecords   = scala.compiletime.uninitialized
  private var testKit: AnkkaTestKit = null
  private var baseUrl: String       = ""
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    kept = records(config, identity.clock)
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        ControlPlaneAcl.composite(tokens, identity.acl(), identity.config()),
        auth = Some(identity.config()),
        clock = identity.clock,
        tokens = Some(tokens),
        secretRecords = Some(kept),
        platform = () =>
          ControlPlane.defaultPlatformStatus.copy(secretRecordRetention = config.retentionText)
      )*
    )
    testKit = AnkkaTestKit.start(
      ControlPlane.components,
      Seq(ProjectionRuntime(), server, tokens, kept)
    )
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
    createProject("reads", "reads-shop")

  override def afterAll(): Unit =
    if testKit != null then
      testKit.stop()
      identity.stop()

  private def send(
      method: String,
      path: String,
      body: Option[String] = None,
      token: Option[String] = None,
      caller: Option[Caller] = None
  ): (Int, String) =
    val builder = JdkRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
    token.foreach(value => builder.header("Authorization", s"Bearer $value"): Unit)
    caller.foreach { c =>
      val (name, value) = testKit.asCaller(c)
      builder.header(name, value): Unit
    }
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def createProject(organization: String, project: String): Unit =
    assertEquals(
      send(
        "POST",
        s"/organizations/$organization",
        Some(s"""{"name":"$organization"}"""),
        Some(Owner)
      )._1,
      204
    )
    assertEquals(
      send(
        "POST",
        s"/projects/$project",
        Some(s"""{"name":"$project","organizationId":"$organization"}"""),
        Some(Owner)
      )._1,
      204
    )

  private def record(
      service: String,
      name: String,
      at: Instant = Instant.now(),
      project: String = "reads-shop"
  ): ReadRecord =
    ReadRecord(at, project, service, "embedded", name, "get", "read", "postgres")

  private def write(r: ReadRecord, as: Caller): Int =
    send("POST", "/secret-reads", Some(writeToString(r)), caller = Some(as))._1

  private def listing(query: String, token: String = Owner): (Int, Vector[ReadRecord]) =
    val (status, body) =
      send("GET", s"/projects/reads-shop/secret-reads$query", token = Some(token))
    (status, if status == 200 then readFromString[SecretReadsPage](body).records else Vector.empty)

  test(
    "a service writes the record of its own read, and an owner is answered which services read a secret"
  ) {
    val payments = Caller.Service("reads-shop", "payments")
    val before   = Instant.now().minusSeconds(1)
    (1 to 3).foreach(_ => assertEquals(write(record("payments", "acme"), payments), 204))
    assertEquals(write(record("wallet", "other"), Caller.Service("reads-shop", "wallet")), 204)
    val (status, read) = listing(s"?name=acme&from=$before")
    assertEquals(status, 200)
    assertEquals(read.map(r => (r.service, r.name)), Vector.fill(3)(("payments", "acme")))
    assert(read.map(_.at).sliding(2).forall { case Seq(a, b) => !a.isBefore(b); case _ => true })
    assertEquals(listing("?service=wallet")._2.map(_.name), Vector("other"))
  }

  test("a record naming another service or another project is refused, and kept nowhere") {
    val wallet = Caller.Service("reads-shop", "wallet")
    assertEquals(write(record("payments", "blamed"), wallet), 403)
    assertEquals(write(record("wallet", "elsewhere", project = "bank"), wallet), 403)
    assertEquals(listing("?name=blamed")._2, Vector.empty)
  }

  test("the internet cannot write a record") {
    assertEquals(write(record("payments", "from-outside"), Caller.Gateway), 403)
  }

  test("a record with an outcome the platform does not write is refused") {
    val odd = record("payments", "odd").copy(outcome = "leaked")
    assertEquals(write(odd, Caller.Service("reads-shop", "payments")), 400)
  }

  test("a member who is not an owner, a deploy token, and an outsider are refused the listing") {
    val (_, created) =
      send("POST", "/organizations/reads/tokens", Some("""{"label":"ci"}"""), Some(Owner))
    val marker      = "\"secret\":\""
    val from        = created.indexOf(marker) + marker.length
    val deployToken = created.substring(from, created.indexOf('"', from))
    assertEquals(listing("", deployToken)._1, 403)
    assertEquals(listing("", Outsider)._1, 404)
    assertEquals(send("GET", "/projects/reads-shop/secret-reads")._1, 401)
  }

  test("a listing's bounds are checked") {
    assertEquals(listing("?limit=0")._1, 400)
    assertEquals(listing("?limit=1001")._1, 400)
    assertEquals(listing("?from=yesterday")._1, 400)
    assertEquals(listing("?limit=1")._2.size, 1)
  }

  test(
    "a read record whose retention has passed is removed, and the installation's status shows the retention"
  ) {
    val payments = Caller.Service("reads-shop", "payments")
    val old      = identity.clock.instant().minus(Duration.ofDays(366))
    assertEquals(write(record("payments", "ancient", at = old), payments), 204)
    assertEquals(listing("?name=ancient")._2.size, 1)
    assert(kept.sweep() >= 1)
    assertEquals(listing("?name=ancient")._2, Vector.empty)
    val (status, body) = send("GET", "/platform", token = Some(Owner))
    assertEquals(status, 200, body)
    assertEquals(readFromString[PlatformStatus](body).secretRecordRetention, "365d")
  }

  /** Runs the real CLI against this control plane, with a config file of its own. */
  private def cli(args: String*): (Int, String) =
    val config = java.nio.file.Files.createTempFile("ankka-secret-reads", ".json")
    java.nio.file.Files.delete(config)
    sys.props("ankka.config") = config.toString
    try
      val out  = java.io.ByteArrayOutputStream()
      val outs = java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8)
      val errs = java.io.PrintStream(java.io.ByteArrayOutputStream(), true)
      val code = com.thinkmorestupidless.ankka.cli.Main
        .run(args ++ Seq("--url", baseUrl, "--token", Owner), outs, errs)
      outs.flush()
      (code, out.toString(java.nio.charset.StandardCharsets.UTF_8))
    finally
      java.nio.file.Files.deleteIfExists(config): Unit
      sys.props.remove("ankka.config"): Unit

  test("an owner lists the record and reads the installation's status with the CLI") {
    assertEquals(
      write(record("payments", "via-cli"), Caller.Service("reads-shop", "payments")),
      204
    )
    val (code, out) =
      cli("projects", "secret-reads", "list", "-p", "reads-shop", "--name", "via-cli")
    assertEquals(code, 0, out)
    assert(out.contains("payments") && out.contains("via-cli") && out.contains("OUTCOME"), out)
    val (jsonCode, json) =
      cli("projects", "secret-reads", "list", "-p", "reads-shop", "--name", "via-cli", "-o", "json")
    assertEquals(jsonCode, 0, json)
    assertEquals(readFromString[SecretReadsPage](json.trim).records.map(_.name), Vector("via-cli"))
    val (statusCode, status) = cli("platform", "status")
    assertEquals(statusCode, 0, status)
    assert(status.contains("secret backend") && status.contains("365d"), status)
  }

  test("the installation's status needs a caller who is signed in, and names no key") {
    assertEquals(send("GET", "/platform")._1, 401)
    val (_, body) = send("GET", "/platform", token = Some(Outsider))
    val status    = readFromString[PlatformStatus](body)
    assertEquals(
      (status.secretBackend, status.cloudProvider, status.auditLog),
      ("postgres", "none", "unknown")
    )
    assert(!body.toLowerCase.contains("kms"), body)
  }

final class InMemorySecretReadsSuite extends SecretReadsBehaviours:
  protected def records(config: SecretRecordsConfig, clock: java.time.Clock): SecretRecords =
    SecretRecords.over(InMemoryReadRecordStore(), config, clock)

final class PostgresSecretReadsSuite extends SecretReadsBehaviours:
  protected def records(config: SecretRecordsConfig, clock: java.time.Clock): SecretRecords =
    SecretRecords(
      config,
      service =>
        PostgresReadRecordStore.open(path = "pekko.persistence.r2dbc.connection-factory")(using
          service.system
        ),
      clock
    )
