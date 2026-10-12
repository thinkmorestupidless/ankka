package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.ServiceLifecycle
import com.thinkmorestupidless.ankka.crd.Buckets
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  DeleteObjectRequest,
  GetObjectRequest,
  ListObjectsV2Request
}

import java.net.URI
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * `features/databases/backups.feature` on k3s (feature 041): every project database archived, and a
 * failure seen. Each scenario has a project of its own (`shop` is `shop-<n>`).
 *
 * Disable with `-Dankka.cluster.tests=off`, which also skips building the sample's image.
 */
class BackupsClusterFeatures extends BackupClusterSteps("../features/databases/backups.feature"):

  // ── Given ─────────────────────────────────────────────────────────────────

  Given("a project {string} with no services") { (project: String) =>
    ensureProject(real(project))
  }

  Given("{string} has recorded an event") { (service: String) =>
    addItem(real("shop"), real(service), "c1", "widget")
  }

  Given("a deployed service {string} in the project {string} that declares a database of its own") {
    (service: String, project: String) =>
      ensureProject(real(project))
      val env = Vector(
        """{"name":"ANKKA_DB_HOST","value":"postgres.example.test"}""",
        """{"name":"ANKKA_DB_NAME","value":"ledger"}"""
      )
      ok(applyJson(real(project), descriptor(real(service), env, http = false))): Unit
  }

  Given("a deployed service {string} with a bucket in the project {string}") {
    (service: String, project: String) =>
      deploy(
        real(project),
        real(service),
        s"""{"name":"${real(
            service
          )}","service":{"image":"$SampleImage","provisionObjectStorage":true}}"""
      )
  }

  // ── When ──────────────────────────────────────────────────────────────────

  When(
    "a member applies a descriptor for the service {string} in the project {string} that says nothing of a database"
  ) { (service: String, project: String) =>
    deploy(real(project), real(service), descriptor(real(service)))
  }

  When("a member applies a descriptor for the service {string} in the project {string}") {
    (service: String, project: String) =>
      deploy(real(project), real(service), descriptor(real(service)))
  }

  When("the installation names a backup target") { () =>
    podsBefore = pods(real("shop"), real("cart")).map(_.getMetadata.getUid).toSet
    assert(podsBefore.nonEmpty)
    installationBacksUp(true)
  }

  When("the backup target refuses writes") { () =>
    permit(Buckets.backup(real("shop")), allowed = false)
    keepWriting(real("shop"))
    failingSince = System.nanoTime()
  }

  When("the status of the installation is read") { () =>
    controlPlaneDatabase
    try
      waitFor(10.minutes, "the control plane's database being backed up") {
        installationStatus().controlPlane.exists(l => l.lastBaseBackup.isDefined)
      }
    catch
      case e: munit.FailException =>
        // What the namespace holds, so a timeout says where the archive stopped.
        val seen = k3s.execInContainer(
          "kubectl",
          "get",
          "cluster,objectstore,scheduledbackup,backup,secret,pod",
          "-n",
          "ankka-controlplane",
          "-o",
          "wide"
        )
        val cluster = k3s.execInContainer(
          "kubectl",
          "get",
          "cluster",
          "ankka-controlplane-db",
          "-n",
          "ankka-controlplane",
          "-o",
          "jsonpath={.status.conditions}"
        )
        // What the projector reads itself, and what the same client sees of the two objects.
        val direct = scala.util.Try(projectorOf.flatMap(_.controlPlaneBackups()))
        def raw(apiVersion: String, kind: String, name: String) =
          scala.util.Try(
            Option(
              k8s
                .genericKubernetesResources(apiVersion, kind)
                .inNamespace("ankka-controlplane")
                .withName(name)
                .get()
            ).map(r => String.valueOf(r.getAdditionalProperties.get("status")))
          )
        fail(
          s"${e.getMessage}\n${seen.getStdout}${seen.getStderr}\n${cluster.getStdout}\n${installationStatus()}" +
            s"\nthe projector reads: $direct" +
            s"\nthe ObjectStore: ${raw("barmancloud.cnpg.io/v1", "ObjectStore", "ankka-backups")}" +
            s"\nthe Cluster: ${raw("postgresql.cnpg.io/v1", "Cluster", "ankka-controlplane-db")}"
        )
    installation = installationStatus()
  }

  When("the project database of {string} takes a base backup") { (project: String) =>
    deploy(real(project), real("base"), descriptor(real("base")))
    awaitBackedUp(real(project))
  }

  When("{string} reads the backup bucket of {string} with its own storage credential") {
    (service: String, project: String) =>
      val secret = k8s
        .secrets()
        .inNamespace(namespace(real(project)))
        .withName(Buckets.secret(real(service)))
        .get()
      assert(secret != null, "the service has no storage credential")
      val data = secret.getData.asScala.view.mapValues(v =>
        new String(java.util.Base64.getDecoder.decode(v), StandardCharsets.UTF_8)
      )
      val client = S3Client
        .builder()
        .endpointOverride(URI.create(s"http://127.0.0.1:${s3Forward.getLocalPort}"))
        .region(Region.of("garage"))
        .forcePathStyle(true)
        .requestChecksumCalculation(
          software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED
        )
        .responseChecksumValidation(
          software.amazon.awssdk.core.checksums.ResponseChecksumValidation.WHEN_REQUIRED
        )
        .credentialsProvider(
          StaticCredentialsProvider.create(
            AwsBasicCredentials.create(data("ANKKA_S3_ACCESS_KEY"), data("ANKKA_S3_SECRET_KEY"))
          )
        )
        .build()
      val bucket = Buckets.backup(real(project))
      def status(attempt: => Unit): Int =
        try
          attempt
          200
        catch case e: software.amazon.awssdk.services.s3.model.S3Exception => e.statusCode()
      try
        refusals = Vector(
          "list" -> status(
            client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()): Unit
          ),
          "get" -> status(
            client.getObjectAsBytes(
              GetObjectRequest.builder().bucket(bucket).key("ankka-db/base/x").build()
            ): Unit
          ),
          "delete" -> status(
            client.deleteObject(
              DeleteObjectRequest.builder().bucket(bucket).key("ankka-db/wals/x").build()
            ): Unit
          )
        )
      finally client.close()
  }

  // ── Then ──────────────────────────────────────────────────────────────────

  Then("the project database of {string} archives every write as it is made") { (project: String) =>
    // Under the sample's writes for two minutes, then the lag: SC-002's bound, measured with load.
    val name  = real(project)
    val until = System.nanoTime() + 2.minutes.toNanos
    var n     = 0
    while System.nanoTime() < until do
      addItem(name, real("cart"), s"load-${n % 10}", s"item-$n")
      n += 1
    waitFor(3.minutes, s"$name's archive lag under a minute") {
      line(name).flatMap(_.archiveLagSeconds).exists(_ < 60)
    }
    println(
      s"MEASURED SC-002: $n writes over two minutes, lag ${line(name).flatMap(_.archiveLagSeconds)}s"
    )
  }

  Then("the project database of {string} completes a first base backup") { (project: String) =>
    awaitBackedUp(real(project))
  }

  Then(
    "the status of the project {string} says when its last base backup completed and the earliest moment it can be restored to"
  ) { (project: String) =>
    val status = projectStatus(real(project))
    assert(status.backedUp, status.toString)
    val l = status.lines.head
    assert(l.lastBaseBackup.isDefined && l.firstRestorable.isDefined, l.toString)
    assert(!l.firstRestorable.get.isAfter(l.lastBaseBackup.get), l.toString)
  }

  Then("the project database of {string} begins archiving and takes a base backup") {
    (project: String) =>
      awaitBackedUp(real(project))
  }

  Then("no instance of {string} is replaced") { (service: String) =>
    assertEquals(pods(real("shop"), real(service)).map(_.getMetadata.getUid).toSet, podsBefore)
  }

  Then("{string} still holds the event") { (service: String) =>
    val (code, body) = cart(real("shop"), real(service), "GET", "/carts/c1")
    assertEquals(code, 200, body)
    assert(body.contains("widget"), body)
  }

  Then(
    "within 5 minutes the status of the project {string} says that its backups are failing, and why"
  ) { (project: String) =>
    waitFor(5.minutes, s"${real(project)}'s backups failing") {
      line(real(project)).exists(l => l.phase == "failing" && l.failing.exists(_.nonEmpty))
    }
    println(
      s"MEASURED SC-003: failing on the status ${(System.nanoTime() - failingSince) / 1_000_000_000}s " +
        s"after the key was refused: ${line(real(project)).flatMap(_.failing)}"
    )
  }

  Then("the metric the platform exports for the backups of {string} reports the failure") {
    (project: String) =>
      waitFor(1.minute, "the failing gauge")(
        gauge("ankka.backups.failing", real(project)).contains(1.0)
      )
      // And it clears: the key allowed again, the status and the gauge both say so (SC-003's other half).
      permit(Buckets.backup(real(project)), allowed = true)
      val allowed = System.nanoTime()
      waitFor(5.minutes, s"${real(project)}'s backups clearing") {
        line(real(project)).exists(_.phase == "backing up") &&
        gauge("ankka.backups.failing", real(project)).contains(0.0)
      }
      writing.set(false)
      println(
        s"MEASURED SC-003: cleared ${(System.nanoTime() - allowed) / 1_000_000_000}s after the key was allowed"
      )
  }

  Then(
    "the database of the control plane archives every write as it is made, into a backup bucket of its own"
  ) { () =>
    val cp = installation.controlPlane.getOrElse(fail("no control plane line"))
    assertNotEquals(cp.phase, "failing", cp.toString)
    val bucket = admin.bucket(Buckets.platformBackup("controlplane")).getOrElse(fail("no bucket"))
    assert(bucket.allowedKeys.nonEmpty, "no key reaches the control plane's backup bucket")
  }

  Then("the database of the control plane has a base backup") { () =>
    assert(installation.controlPlane.exists(_.lastBaseBackup.isDefined), installation.toString)
  }

  Then(
    "the status of the installation says when the last base backup of the database of the control plane completed"
  ) { () =>
    assertEquals(installation.backupTarget, "object-store")
    assert(installation.controlPlane.flatMap(_.lastBaseBackup).isDefined, installation.toString)
  }

  Then("the database of {string} is no part of it") { (service: String) =>
    val databases = psql(real("shop"), "select datname from pg_database").linesIterator.toSet
    assert(!databases.contains(real(service)), databases.toString)
    assert(databases.contains(real("base")), databases.toString)
  }

  Then(
    "the status of {string} says that the platform does not back up a database the service declares"
  ) { (service: String) =>
    waitFor(3.minutes, "the supplied database's phrase") {
      statusOf(real(service), real("shop"))
        .flatMap(_.database)
        .contains("supplied; its owner's to back up")
    }
  }

  Then("{string} is ready") { (service: String) =>
    assert(statusOf(real(service), real("shop")).exists(_.lifecycle == ServiceLifecycle.Ready))
  }

  Then("the status of the installation says that nothing is backed up") { () =>
    val status = installationStatus()
    assertEquals(status.backupTarget, "none")
    assert(status.notBackedUp.exists(_.contains("nothing is backed up")), status.toString)
  }

  Then("the status of the project {string} says that nothing is backed up") { (project: String) =>
    val status = projectStatus(real(project))
    assert(!status.backedUp)
    assertEquals(status.target, "none")
    assert(status.detail.exists(_.contains("nothing is backed up")), status.toString)
    assert(
      k8s
        .resources(classOf[com.thinkmorestupidless.ankka.operator.cnpg.BarmanObjectStore])
        .inNamespace(namespace(real(project)))
        .list()
        .getItems
        .isEmpty,
      "an installation with no target archives nothing"
    )
  }

  Then("the object store refuses {string}") { (_: String) =>
    assert(refusals.size == 3, refusals.toString)
    assert(refusals.forall((_, status) => status == 403), refusals.toString)
  }

  // ── Beside the scenarios ──────────────────────────────────────────────────

  test("an installation on Garage says its backups are not encrypted by the platform, and why") {
    installationBacksUp(true)
    val status = installationStatus()
    assert(status.encryption.startsWith("none:"), status.encryption)
    assert(status.encryption.contains("volume"), status.encryption)
    assert(status.sharesFailureDomain)
  }
