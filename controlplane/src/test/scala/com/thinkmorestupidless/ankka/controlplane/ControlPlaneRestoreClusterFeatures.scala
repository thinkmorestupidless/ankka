package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{ControlPlaneAcl, RestoreHoldStatus}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.auth.DeployTokenIndex
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  Fabric8AnkkaServiceClient,
  RestoreHold,
  ServiceProjector
}
import com.thinkmorestupidless.ankka.crd.AnkkaService
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.PkiStack
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import com.typesafe.config.ConfigFactory
import org.testcontainers.images.builder.Transferable

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * `features/databases/control-plane-restore.feature` on k3s (feature 041): the control plane's own
 * database restored by a platform administrator with `kustomization/recovery/controlplane`, as
 * `docs/operate/recovery.md` says, and the control plane started on it held.
 *
 * The control plane runs in this JVM, as in every suite here, but on the database in the cluster
 * (`ankka-controlplane-db`, archived by the `backups` component), reached by a port forward: one
 * records a world, the database is restored to a moment before part of it, and a second starts on
 * the restored cluster and must change nothing. The kustomization is applied with the node's own
 * `kubectl`, with its two values set as the documentation says to set them.
 *
 * The erasure log is feature 042's: the hold's reconcilers are empty, so the scenario that names it
 * holds that nothing was reconciled, and is written to take 042's line when it exists.
 *
 * Disable with `-Dankka.cluster.tests=off`, which also skips building the sample's image.
 */
class ControlPlaneRestoreClusterFeatures
    extends BackupClusterSteps("../features/databases/control-plane-restore.feature"):

  private val Minute =
    DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(java.time.ZoneOffset.UTC)
  private var restoreName = ""
  private var moment      = Instant.EPOCH
  private var rootToken   = ""
  private var running
      : Option[(AnkkaTestKit, String, io.fabric8.kubernetes.client.LocalPortForward)] = None
  private val http = HttpClient.newHttpClient()

  // The base's own control plane, on a database of its own, would sweep away what this suite's
  // writes to the cluster: it removes every resource its database does not know.
  override def beforeAll(): Unit =
    super.beforeAll()
    if !munitIgnore && testKit != null then
      testKit.stop()
      testKit = null

  override def afterAll(): Unit =
    stopControlPlane()
    if !munitIgnore then identity.stop()
    super.afterAll()

  override def afterEach(context: AfterEach): Unit =
    stopControlPlane()
    super.afterEach(context)

  /** The control plane's database credential, as CNPG wrote it for the owner. */
  private def owner: (String, String) =
    val data = k8s
      .secrets()
      .inNamespace("ankka-controlplane")
      .withName("ankka-controlplane-db-app")
      .get()
      .getData
      .asScala
    def decode(k: String) =
      new String(java.util.Base64.getDecoder.decode(data(k)), StandardCharsets.UTF_8)
    (decode("username"), decode("password"))

  /** A control plane on the cluster `database` in the cluster, its hold read from it at start. */
  private def startControlPlane(database: String): String =
    stopControlPlane()
    waitFor(10.minutes, s"$database ready")(
      Option(
        k8s
          .resources(classOf[com.thinkmorestupidless.ankka.operator.cnpg.PostgresCluster])
          .inNamespace("ankka-controlplane")
          .withName(database)
          .get()
      ).flatMap(c => Option(c.getStatus)).exists(_.readyInstances >= 1)
    )
    val forward =
      k8s.services().inNamespace("ankka-controlplane").withName(s"$database-rw").portForward(5432)
    val (user, password) = owner
    val settings = ConfigFactory.parseString(
      s"""pekko.persistence.r2dbc.connection-factory {
         |  host = "127.0.0.1"
         |  port = ${forward.getLocalPort}
         |  database = "ankka"
         |  user = "$user"
         |  password = "$password"
         |}""".stripMargin
    )
    val deploy = DeployConfig.default.copy(namespacePrefix = Prefix, sweepInterval = 2.seconds)
    val hold   = new RestoreHold()
    val projector = ServiceProjector.withClient(
      deploy,
      new Fabric8AnkkaServiceClient(k8s, Prefix, resyncMillis = 2000L),
      controlPlaneBackups,
      hold
    )
    val tokens = new DeployTokenIndex(identity.clock)
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        ControlPlaneAcl.composite(tokens, identity.acl(), identity.config()),
        deploy,
        auth = Some(identity.config()),
        topics = Some(projector),
        backups = controlPlaneBackups,
        platform = Some(projector),
        hold = hold
      )*
    )
    val kit = AnkkaTestKit.start(
      ControlPlane.componentsWith(projector),
      Seq(hold, ProjectionRuntime(), projector, server, tokens),
      settings = settings
    )
    val address = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
    running = Some((kit, address, forward))
    address

  private def stopControlPlane(): Unit =
    running.foreach { (kit, _, forward) =>
      kit.stop()
      forward.close()
    }
    running = None

  private def call(
      method: String,
      path: String,
      token: String,
      body: Option[String] = None
  ): (Int, String) =
    val address = running.map(_._2).getOrElse(fail("no control plane is running"))
    val request = HttpRequest
      .newBuilder(URI.create(address + path))
      .header("Authorization", s"Bearer $token")
      .header("Content-Type", "application/json")
      .method(
        method,
        body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString)
      )
      .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def hold: RestoreHoldStatus =
    readFromString[RestoreHoldStatus](call("GET", "/installation/restore", rootToken)._2)

  private def cartImage: Option[String] =
    Option(
      k8s
        .resources(classOf[AnkkaService])
        .inNamespace(namespace(projectOf("shop")))
        .withName("cart")
        .get()
    )
      .map(_.getSpec.image)

  /** The recovery kustomization with its two values set, applied with the node's `kubectl`. */
  private def restoreControlPlane(at: Instant): Unit =
    restoreName = s"ankka-controlplane-db-r${Minute.format(Instant.now())}"
    val source = repoRoot.resolve("kustomization/recovery/controlplane")
    for file <- Vector("kustomization.yaml", "cluster.yaml", "marker-job.yaml") do
      val text = Files.readString(source.resolve(file))
      val set =
        if file == "kustomization.yaml" then
          text
            .replace("restoreName=SET", s"restoreName=$restoreName")
            .replace("targetTime=SET", s"targetTime=$at")
        else text
      k3s.copyFileToContainer(
        Transferable.of(set.getBytes(StandardCharsets.UTF_8)),
        s"/tmp/recovery/$file"
      )
    PkiStack.kubectl(k3s, "apply", "-k", "/tmp/recovery"): Unit
    waitFor(30.minutes, "the restore marker written") {
      PkiStack.jsonPath(
        k3s,
        "-n",
        "ankka-controlplane",
        "job",
        "ankka-controlplane-restore-marker",
        "{.status.succeeded}"
      ) == "1"
    }

  /** Records a world with the archive past it, as the restore point then a later one. */
  private var recorded = false

  // One world for every scenario: it is recorded once, and restored once.
  override protected def projectOf(logical: String): String = logical
  override protected def serviceOf(logical: String): String = logical

  private def recordUpToTheMoment(): Unit =
    if !recorded then
      recordOnce()
      recorded = true

  private def recordOnce(): Unit =
    controlPlaneDatabase
    // A completed base backup, not only a ready instance: a recovery has nothing to start from
    // without one, and waits for ever rather than failing.
    waitFor(10.minutes, "the control plane's database backed up") {
      k8s
        .resources(classOf[com.thinkmorestupidless.ankka.operator.cnpg.PostgresBackup])
        .inNamespace("ankka-controlplane")
        .list()
        .getItems
        .asScala
        .exists(b =>
          Option(b.getSpec).exists(_.cluster.name == "ankka-controlplane-db") &&
            Option(b.getStatus).flatMap(_.phase).contains("completed")
        )
    }
    startControlPlane("ankka-controlplane-db")
    val owner = identity.token("root", roles = Set("platform-admin"), expiresIn = 3.hours)
    rootToken = owner
    // Made once; a scenario after one that failed finds it there.
    assert(
      Set(204, 409)(call("POST", "/organizations/acme", owner, Some("""{"name":"Acme"}"""))._1)
    )
    val shop = projectOf("shop")
    assertEquals(
      call(
        "POST",
        s"/projects/$shop",
        owner,
        Some("""{"name":"Shop","organizationId":"acme"}""")
      )._1,
      204
    )
    def apply(image: String) =
      val (code, body) =
        call(
          "PUT",
          s"/services/$shop/cart",
          owner,
          Some(s"""{"name":"cart","service":{"image":"$image"},"http":false}""")
        )
      assert(code == 200, body)
    apply("cart:1")
    waitFor(2.minutes, "cart:1 in the cluster")(cartImage.contains("cart:1"))
    Thread.sleep(5000)
    moment = Instant.now()
    Thread.sleep(5000)
    apply("cart:2")
    waitFor(2.minutes, "cart:2 in the cluster")(cartImage.contains("cart:2"))
    assertEquals(
      call(
        "POST",
        s"/projects/${projectOf("lab")}",
        owner,
        Some("""{"name":"Lab","organizationId":"acme"}""")
      )._1,
      204
    )
    // A namespace for lab, as its first service would make.
    k8s
      .namespaces()
      .resource(
        new io.fabric8.kubernetes.api.model.NamespaceBuilder()
          .withMetadata(
            new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
              .withName(namespace(projectOf("lab")))
              .withLabels(java.util.Map.of("app.kubernetes.io/managed-by", "ankka"))
              .build()
          )
          .build()
      )
      .serverSideApply(): Unit
    // The archive past the moment, read where it is known: the database's own archiver. The
    // control plane's line carries no latest restorable moment, since nothing reads inside it.
    def archivedAt: Option[Instant] =
      val r = k3s.execInContainer(
        "kubectl",
        "exec",
        "-n",
        "ankka-controlplane",
        "ankka-controlplane-db-1",
        "-c",
        "postgres",
        "--",
        "psql",
        "-U",
        "postgres",
        "-tA",
        "-c",
        "select pg_switch_wal(); select to_char(last_archived_time at time zone 'UTC', " +
          "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') from pg_stat_archiver"
      )
      r.getStdout.linesIterator
        .map(_.trim)
        .filter(_.endsWith("Z"))
        .toVector
        .lastOption
        .flatMap(t => scala.util.Try(Instant.parse(t)).toOption)
    waitFor(5.minutes, "the archive past the moment")(
      archivedAt.exists(_.isAfter(moment.plusSeconds(5)))
    )
    stopControlPlane()

  private def startHeld(): Unit =
    if restoreName.isEmpty then
      recordUpToTheMoment()
      restoreControlPlane(moment)
    startControlPlane(restoreName): Unit

  // ── Given ─────────────────────────────────────────────────────────────────

  Given("a platform administrator {string}") { (name: String) =>
    rootToken = identity.token(name, roles = Set("platform-admin"), expiresIn = 3.hours)
  }

  Given(
    "a service {string} applied at generation {int} after the restore point, running the image {string}"
  ) { (_: String, _: Int, _: String) =>
    if restoreName.isEmpty then recordUpToTheMoment()
  }

  Given("a project {string} created after the restore point")((_: String) => ())

  Given("the database of the control plane restored to the restore point") { () =>
    if restoreName.isEmpty then restoreControlPlane(moment)
  }

  Given("an erasure filed after the restore point") { () =>
    // Feature 042's: nothing files an erasure yet.
    if restoreName.isEmpty then recordUpToTheMoment()
  }

  Given("the control plane started with its projection held")(() => startHeld())

  // ── When ──────────────────────────────────────────────────────────────────

  When(
    "{string} restores the database of the control plane to a moment, as the documentation says"
  ) { (_: String) =>
    recordUpToTheMoment()
    restoreControlPlane(moment)
  }

  When("the control plane starts with its projection held")(() => startHeld())

  When("{string} releases the projection") { (_: String) =>
    val (code, body) = call("POST", "/installation/restore/release", rootToken)
    assertEquals(code, 200, body)
  }

  // ── Then ──────────────────────────────────────────────────────────────────

  Then("the restore ends by writing the restore marker") { () =>
    val pod = s"$restoreName-1"
    val r = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      "ankka-controlplane",
      pod,
      "-c",
      "postgres",
      "--",
      "psql",
      "-U",
      "postgres",
      "-d",
      "ankka",
      "-tA",
      "-c",
      "select target_time from ankka_restore_marker where released_at is null"
    )
    assert(r.getStdout.trim.nonEmpty, r.getStdout + r.getStderr)
  }

  Then("the control plane starts on the restored database with its projection held") { () =>
    startControlPlane(restoreName): Unit
    assert(hold.held, hold.toString)
  }

  Then(
    "it lists {string} as a service whose recorded descriptor differs from what runs in the cluster"
  ) { (service: String) =>
    waitFor(2.minutes, "the difference listed")(
      hold.services.exists(d =>
        d.service == service && d.recordedImage.contains("cart:1") && d.clusterImage.contains(
          "cart:2"
        )
      )
    )
  }

  Then("it lists every declared topic that differs from what the broker holds") { () =>
    // No topic is declared in this world, so none differs.
    assertEquals(hold.topics, Vector.empty)
  }

  Then("it lists the project {string} as one the cluster holds and its database does not know") {
    (project: String) =>
      waitFor(2.minutes, "the unknown project listed")(
        hold.unknownProjects.contains(projectOf(project))
      )
  }

  Then("it changes none of them") { () =>
    Thread.sleep(10000)
    assertEquals(cartImage, Some("cart:2"))
  }

  Then("{string} keeps running the image {string}") { (_: String, image: String) =>
    assertEquals(cartImage, Some(image))
  }

  Then(
    "its erasure log is brought up to date from the copy in its bucket before {string} can release it"
  ) { (_: String) =>
    // Pending feature 042: nothing to bring up to date, and the hold says so by listing nothing.
    assertEquals(hold.reconciled, Vector.empty)
  }

  Then(
    "the listing of what differs says that the erasure log was brought up to date, and how many erasures it gained"
  ) { () =>
    assertEquals(hold.reconciled, Vector.empty, "no reconciler until feature 042")
  }

  Then("the control plane makes the cluster what it recorded again") { () =>
    waitFor(2.minutes, "the recorded image projected")(cartImage.contains("cart:1"))
  }

  Then("the restore marker is removed") { () =>
    assert(!hold.held && hold.releasedAt.isDefined, hold.toString)
  }

  Then("the control plane records that {string} released the projection") { (name: String) =>
    assertEquals(hold.releasedBy, Some(name))
  }
