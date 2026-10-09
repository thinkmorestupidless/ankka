package com.thinkmorestupidless.ankka.controlplane.erasure

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.{ControlPlane, TestIdentity}
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.ErasureWire.given
import com.thinkmorestupidless.ankka.controlplane.application.ErasureLogEntity
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  ErasureLogBucket,
  ErasureSettings,
  ErasureSweeper,
  KeyringCaller
}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.keyring.*
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  ProjectionRuntime,
  ServiceIdentity,
  SqlFragment,
  TopologyJson
}
import com.thinkmorestupidless.ankka.runtime.erasure.{KeyringApi, KeyringClient}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.*

/**
 * `features/erasure/restores.feature`: backups taken by copying a kit's database before an erasure
 * and put back after it, for the service's own database and for the keyring's. The keyring reads
 * the erasure log's control plane copy straight from the control plane's log entity, as its route
 * would hand it over; the union case and the failed write each start what they need of their own.
 */
class RestoresFeatures
    extends GherkinSuite("../features/erasure/restores.feature")
    with LogCapturing:

  override val munitTimeout = 5.minutes

  private lazy val identity = TestIdentity()
  private lazy val alice = identity.token("alice", Some("alice@example.test"), expiresIn = 2.hours)

  private var keyringKit: AnkkaTestKit   = null
  private var keyringState: KeyringState = null
  private var controlPlane: AnkkaTestKit = null
  private var players: AnkkaTestKit      = null
  private var sweeper: ErasureSweeper    = null
  private var keyringUrl                 = ""
  private var base                       = ""
  private val http                       = HttpClient.newHttpClient()

  /** The log's bucket copy: none, until a scenario makes its writes fail. */
  @volatile private var bucketRefuses = false
  private val bucket = new ErasureLogBucket:
    def append(entry: KeyringApi.LogEntry): Unit =
      if bucketRefuses then throw IllegalStateException("the platform bucket refused the write")
    def entries(): Vector[KeyringApi.LogEntry] = Vector.empty

  /** The control plane's copy of the log, as the keyring reads it after a restore. */
  private val fromControlPlane: LogSources = new LogSources:
    def copies = 1
    def controlPlane: Option[Vector[KeyringApi.LogEntry]] =
      Option(RestoresFeatures.this.controlPlane).map(logEntries)
    def bucket: Option[Vector[KeyringApi.LogEntry]] = None

  private def logEntries(kit: AnkkaTestKit): Vector[KeyringApi.LogEntry] =
    kit.componentClient
      .forEventSourcedEntity(EntityId(ErasureLogEntity.Id))
      .call(ErasureLogEntity.after)
      .invoke(0L)
      .entries
      .map(e =>
        KeyringApi.LogEntry(e.erasureId, e.projectId, e.subject, e.sequence, e.at.toEpochMilli)
      )

  /**
   * The keyring's port, chosen once: restoring its database restarts it, and every channel and the
   * sweeper must find it where it was, as they find a deployed keyring at its Service's address.
   */
  private lazy val keyringPort: Int =
    val socket = java.net.ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()

  override def beforeAll(): Unit =
    keyringState =
      KeyringState(Grants.none, fromControlPlane, ackWithin = 10.seconds, applySchema = false)
    keyringKit = AnkkaTestKit.start(
      Keyring.components,
      Seq(
        KeyringRuntime(keyringState),
        HttpServer.at("127.0.0.1", keyringPort)(clients => KeyringEndpoint(clients, keyringState))
      ),
      keyring = None
    )
    keyringUrl = keyringKit.service.boundAddresses.find(_.startsWith("http")).get
    sweeper = ErasureSweeper(
      KeyringCaller.Http(URI.create(keyringUrl), None),
      bucket,
      ErasureSettings(sweepInterval = 1.hour, reapplyInterval = 1.hour),
      identity.clock
    )
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        identity.acl(),
        auth = Some(identity.config()),
        clock = identity.clock,
        erasures = Some(sweeper)
      )*
    )
    controlPlane = AnkkaTestKit.start(
      ControlPlane.components,
      Seq(ProjectionRuntime(), server, sweeper),
      keyring = None
    )
    base = s"http://127.0.0.1:${server.boundPort.get}"
    players = AnkkaTestKit.start(
      Seq(PlayerAccount.descriptor, PlayerRows(1).descriptor),
      Seq(ProjectionRuntime()),
      serviceIdentity = ServiceIdentity.deployed("brand", "players"),
      keyring = None,
      configure = _.withKeyring(
        KeyringClient(URI.create(keyringUrl.replaceFirst("^http", "ws") + "/channel"), None)
      )
    )
    assertEquals(send("POST", "/organizations/acme", Some("""{"name":"Acme"}"""))._1, 204)
    assertEquals(
      send("POST", "/projects/brand", Some("""{"name":"Brand","organizationId":"acme"}"""))._1,
      204
    )

  override def afterAll(): Unit =
    Seq(players, controlPlane, keyringKit).filter(_ != null).foreach(_.stop())
    identity.stop()

  private def send(method: String, path: String, body: Option[String] = None): (Int, String) =
    val builder = HttpRequest
      .newBuilder(URI.create(base + path))
      .timeout(Duration.ofSeconds(30))
      .header("Authorization", s"Bearer $alice")
    body.fold(builder.method(method, HttpRequest.BodyPublishers.noBody()))(json =>
      builder
        .header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(json))
    ): Unit
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def subject(named: String): String =
    s"$named-${Integer.toHexString(scenarioId.hashCode)}"

  private def email(s: String) = s"${s.replace('/', '.')}@example.com"

  private def account(s: String) =
    players.componentClient.forEventSourcedEntity(EntityId(s)).call(PlayerAccount.read).invoke()

  private def rowText(s: String): Option[String] =
    given org.apache.pekko.actor.typed.ActorSystem[?] = players.service.system
    scala.concurrent.Await
      .result(
        Database().query(
          SqlFragment.raw(s"SELECT payload FROM ankka_view_player_rows WHERE row_key = '$s'")
        )(r => r.get("payload", classOf[String])),
        10.seconds
      )
      .headOption

  private def applied(erasureId: String): Boolean =
    given org.apache.pekko.actor.typed.ActorSystem[?] = players.service.system
    scala.concurrent.Await
      .result(
        Database().query(
          SqlFragment.raw(
            s"SELECT count(*) FROM ankka_erasures_applied WHERE erasure_id = '$erasureId'"
          )
        )(r => r.get(0, classOf[java.lang.Long]).longValue),
        10.seconds
      )
      .head > 0

  private def read(id: String): ErasureRequest =
    readFromString[ErasureRequest](send("GET", s"/projects/brand/erasures/$id")._2)

  private def ask(s: String): ErasureRequest =
    val (status, body) = send("POST", "/projects/brand/erasures", Some(s"""{"subject":"$s"}"""))
    assert(status == 201 || status == 200, s"$status $body")
    readFromString[ErasureRequest](body)

  // ── Background: written, backed up, then erased ──

  private var serviceBackup: AnkkaTestKit.DatabaseSnapshot = null
  private var keyringBackup: AnkkaTestKit.DatabaseSnapshot = null
  private var erasure: ErasureRequest                      = null

  Given("a project {string} with the service {string}") { (_: String, _: String) =>
    val s = subject("player/8c1f")
    players.componentClient
      .forEventSourcedEntity(EntityId(s))
      .call(PlayerAccount.open)
      .invoke(email(s)): Unit
    players.eventually("the row with its token")(rowText(s).filter(_.contains("\"lookup\""))): Unit
    serviceBackup = players.snapshotDatabase()
    keyringBackup = keyringKit.snapshotDatabase()
  }
  Given("{string} has been erased in {string}") { (named: String, _: String) =>
    val asked = ask(subject(named))
    erasure = players.eventually("the erasure applied", 60.seconds) {
      sweeper.sweep()
      Some(read(asked.id)).filter(_.state == ErasureState.Final)
    }
    assert(account(subject(named)).email.exists(_.isErased))
  }

  // ── A service restored ──

  Given("the keyring has not been restored")(() => ())
  When("the database of {string} is restored to a point before the erasure") { (_: String) =>
    players.restoreDatabase(serviceBackup)
  }
  Given("the database of {string} has been restored to a point before the erasure") { (_: String) =>
    players.restoreDatabase(serviceBackup)
  }
  When("{string} is switched to the restored database")((_: String) => ())
  Then(
    "{string} is not ready until it has applied the entries of the erasure log for {string} to its own tables"
  ) { (_: String, _: String) =>
    // The service's own readiness check, as a probe reads it: the first time it says ready, the
    // erasure log is already in the service's own tables.
    val readiness =
      com.thinkmorestupidless.ankka.runtime.ExtensionsReadiness(players.service.system)
    players.eventually("the restored service ready", 60.seconds)(
      Option.when(readiness.allReady)(())
    )
    assert(applied(erasure.id), "the service was ready before it applied the erasure log")
  }
  Then(
    "afterwards every row of every view of {string} holds erased in each personal field of {string}"
  ) { (_: String, named: String) =>
    val row = players.service.viewClient.forView(PlayerRows(1)).get(subject(named))
    assert(row.exists(_.email.isErased), row.toString)
  }
  Then("no lookup token for {string} remains in any row of {string}") {
    (named: String, _: String) =>
      val text = rowText(subject(named)).getOrElse(fail("the row is gone"))
      assert(!text.contains("\"lookup\""), text)
      assert(!text.contains("\"data\""), text)
  }
  Then("the status of the restore says when {string} finished applying the erasure log") {
    (_: String) =>
      val document = TopologyJson.of(players.service, "players", "1", "2026-10-09T00:00:00Z")
      assert(document.contains("\"erasures\":{\"appliedUpTo\":"), document)
      assert(document.contains("\"finishedAt\":"), document)
  }

  // ── An entity recovered ──

  When("an entity of {string} holding events of {string} is recovered") { (_: String, _: String) =>
    players.restartService()
  }
  Then("its state holds erased in each personal field of {string}") { (named: String) =>
    assert(account(subject(named)).email.exists(_.isErased))
  }

  // ── The keyring restored ──

  When("the database of the keyring is restored to a point before the erasure") { () =>
    keyringKit.restoreDatabase(keyringBackup)
  }
  Then("the keyring answers no request for a subject key until it has applied the erasure log") {
    () =>
      // Ready only after the replay: `restoreDatabase` returned on readiness, and it replayed.
      assert(keyringState.ready && keyringState.replayed >= 1, keyringState.replayed.toString)
  }
  Then("afterwards the keyring holds no subject key for {string}") { (named: String) =>
    val key = keyringKit.componentClient
      .forKeyValueEntity(EntityId(s"brand/${subject(named)}"))
      .call(SubjectKeyEntity.state)
      .invoke()
    assertEquals(key.wrapped, None)
    assert(key.destroyedAt.isDefined, key.toString)
  }
  Then("the next read of a personal field of {string} by any service of {string} is erased") {
    (named: String, _: String) =>
      players.restartService()
      assert(account(subject(named)).email.exists(_.isErased))
  }

  // ── The union of both copies ──

  private var unionState: KeyringState = null

  Given("one copy of the erasure log is behind the other") { () =>
    val all = logEntries(controlPlane)
    val behind = new LogSources:
      def copies                                            = 2
      def controlPlane: Option[Vector[KeyringApi.LogEntry]] = Some(all)
      def bucket: Option[Vector[KeyringApi.LogEntry]]       = Some(all.dropRight(1))
    unionState = KeyringState(Grants.none, behind, ackWithin = 10.seconds, applySchema = false)
  }
  When("the keyring applies the erasure log") { () =>
    AnkkaTestKit.start(Keyring.components, Seq(KeyringRuntime(unionState)), keyring = None).stop()
  }
  Then("it applies every entry that either copy holds") { () =>
    assertEquals(unionState.replayed, logEntries(controlPlane).size)
  }
  Then("it reports which copy was behind") { () =>
    assertEquals(unionState.behind, Some("bucket"))
  }

  // ── A failed log write ──

  private var failed: ErasureRequest = null

  Given("an erasure request for {string} in {string} whose write to the erasure log fails") {
    (named: String, _: String) =>
      val s = subject(named)
      players.componentClient
        .forEventSourcedEntity(EntityId(s))
        .call(PlayerAccount.open)
        .invoke(email(s)): Unit
      failed = ask(s)
  }
  When("the erasure request is applied") { () =>
    bucketRefuses = true
    // Swept until the sweeper has met it: a request reaches the listing it sweeps a moment late.
    try
      players.eventually("the request swept", 30.seconds) {
        sweeper.sweep()
        Some(read(failed.id)).filter(_.state == ErasureState.Failed)
      }: Unit
    finally bucketRefuses = false
  }
  Then("no subject key is destroyed") { () =>
    val key = keyringKit.componentClient
      .forKeyValueEntity(EntityId(s"brand/${failed.subject}"))
      .call(SubjectKeyEntity.state)
      .invoke()
    assertEquals(key.destroyedAt, None)
  }
  Then("the erasure request is not applied, and says why") { () =>
    val now = read(failed.id)
    assertEquals(now.state, ErasureState.Failed)
    assert(now.failure.exists(_.contains("bucket")), now.toString)
  }
