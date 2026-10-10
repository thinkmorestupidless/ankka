package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.InstallationStatus
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.crd.Buckets
import com.thinkmorestupidless.ankka.operator.{GarageStore, ObjectStoreStack, PkiStack}
import org.testcontainers.images.builder.Transferable
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  DeleteObjectRequest,
  GetObjectRequest,
  ListObjectsV2Request,
  PutObjectRequest
}

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

/**
 * `features/object-storage/durability.feature` on k3s (feature 041): Garage on three nodes, each
 * object on all three; a second, one-node Garage as the secondary store, which `garage-copy` copies
 * every bucket to; and the installation's status from the copy's own report.
 *
 * One k3s node runs the three: anti-affinity is left out here
 * (`ObjectStoreStack.installReplicated`), which `RemoteOverlaySuite` holds the component itself to.
 * What a node holds is read from its volume by an ephemeral container mounting it, since Garage's
 * image has no shell.
 *
 * Disable with `-Dankka.cluster.tests=off`, which also skips building the sample's image.
 */
class DurabilityClusterFeatures
    extends BackupClusterSteps("../features/object-storage/durability.feature"):

  override protected def installObjectStore(): ObjectStoreStack.Installed =
    ObjectStoreStack.installReplicated(k3s, k8s, repoRoot)

  @volatile private var copyRequired = false
  override protected def controlPlaneBackups =
    super.controlPlaneBackups.copy(copyRequired = copyRequired)

  override protected def serviceOf(logical: String): String = logical

  private val Object   = "march.pdf"
  private val Contents = Array.fill[Byte](3 * 1024 * 1024)(7)
  private var secondaryForward: io.fabric8.kubernetes.client.LocalPortForward = null
  private var secondaryKey: (String, String)                                  = ("", "")
  private var lastCopyDeleted: Option[Long]                                   = None

  override def afterAll(): Unit =
    if secondaryForward != null then secondaryForward.close()
    super.afterAll()

  // ── helpers ───────────────────────────────────────────────────────────────

  private def s3(endpoint: String, key: (String, String)) =
    S3Client
      .builder()
      .endpointOverride(URI.create(endpoint))
      .region(Region.of("garage"))
      .forcePathStyle(true)
      .requestChecksumCalculation(
        software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED
      )
      .responseChecksumValidation(
        software.amazon.awssdk.core.checksums.ResponseChecksumValidation.WHEN_REQUIRED
      )
      .credentialsProvider(
        StaticCredentialsProvider.create(AwsBasicCredentials.create(key._1, key._2))
      )
      .build()

  /** The service's own storage credential, as its Secret holds it. */
  private def credentialOf(project: String, service: String): (String, String) =
    val data = k8s
      .secrets()
      .inNamespace(namespace(project))
      .withName(Buckets.secret(service))
      .get()
      .getData
      .asScala
    def decode(k: String) =
      new String(java.util.Base64.getDecoder.decode(data(k)), StandardCharsets.UTF_8)
    (decode("ANKKA_S3_ACCESS_KEY"), decode("ANKKA_S3_SECRET_KEY"))

  private def primary(project: String, service: String) =
    s3(s"http://127.0.0.1:${s3Forward.getLocalPort}", credentialOf(project, service))

  private def secondary = s3(s"http://127.0.0.1:${secondaryForward.getLocalPort}", secondaryKey)

  private def bucketOf(project: String, service: String) = Buckets.name(project, service)

  private def keep(project: String, service: String): Unit =
    val client = primary(project, service)
    try
      client.putObject(
        PutObjectRequest.builder().bucket(bucketOf(project, service)).key(Object).build(),
        RequestBody.fromBytes(Contents)
      ): Unit
    finally client.close()

  private def readBack(project: String, service: String): Array[Byte] =
    val client = primary(project, service)
    try
      client
        .getObjectAsBytes(
          GetObjectRequest.builder().bucket(bucketOf(project, service)).key(Object).build()
        )
        .asByteArray()
    finally client.close()

  private def secondaryHolds(bucket: String, key: String = ""): Vector[String] =
    val client = secondary
    try
      client
        .listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(key).build())
        .contents()
        .asScala
        .toVector
        .map(_.key())
    catch case _: software.amazon.awssdk.services.s3.model.NoSuchBucketException => Vector.empty
    finally client.close()

  /**
   * The bytes under a node's data directory, read by an ephemeral container mounting its volume.
   */
  private def dataBytes(pod: String): Long =
    val name = s"du-${System.nanoTime()}"
    val patch =
      s"""{"spec":{"ephemeralContainers":[{"name":"$name","image":"busybox:1.36","command":["sleep","300"],""" +
        s""""volumeMounts":[{"name":"data","mountPath":"/data","readOnly":true}]}]}}"""
    k3s.copyFileToContainer(
      Transferable.of(patch.getBytes(StandardCharsets.UTF_8)),
      s"/tmp/$name.json"
    )
    PkiStack.kubectl(
      k3s,
      "patch",
      "pod",
      pod,
      "-n",
      ObjectStoreStack.Namespace,
      "--subresource",
      "ephemeralcontainers",
      "--type",
      "strategic",
      "--patch-file",
      s"/tmp/$name.json"
    ): Unit
    var bytes = -1L
    waitFor(2.minutes, s"$pod's volume read") {
      val r = k3s.execInContainer(
        "kubectl",
        "exec",
        "-n",
        ObjectStoreStack.Namespace,
        pod,
        "-c",
        name,
        "--",
        "du",
        "-sk",
        "/data/data"
      )
      bytes = r.getStdout.trim.takeWhile(_.isDigit).toLongOption.map(_ * 1024).getOrElse(-1L)
      r.getExitCode == 0 && bytes >= 0
    }
    bytes

  /** A second Garage, one node, beside the first: the secondary store a copy is made to. */
  private lazy val secondaryStore: Unit =
    val component = repoRoot.resolve("kustomization/components/garage")
    val rename = (text: String) =>
      text
        .replace("name: garage-config", "name: garage-secondary-config")
        .replace("name: { name: garage-config }", "name: { name: garage-secondary-config }")
        .replace(
          "configMap: { name: garage-config }",
          "configMap: { name: garage-secondary-config }"
        )
        .replace("name: garage\n", "name: garage-secondary\n")
        .replace("app.kubernetes.io/name: garage", "app.kubernetes.io/name: garage-secondary")
        .replace("serviceName: garage", "serviceName: garage-secondary")
    val manifest = Vector("config.yaml", "statefulset.yaml", "service.yaml")
      .map(f => rename(Files.readString(component.resolve(f))))
      .mkString("\n---\n")
    k3s.copyFileToContainer(
      Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)),
      "/tmp/garage-secondary.yaml"
    )
    PkiStack.kubectl(k3s, "apply", "--server-side", "-f", "/tmp/garage-secondary.yaml"): Unit
    waitFor(3.minutes, "the secondary store") {
      PkiStack.jsonPath(
        k3s,
        "-n",
        ObjectStoreStack.Namespace,
        "statefulset",
        "garage-secondary",
        "{.status.readyReplicas}"
      ) == "1"
    }
    val admin = k8s
      .services()
      .inNamespace(ObjectStoreStack.Namespace)
      .withName("garage-secondary")
      .portForward(3903)
    val adminUrl = s"http://127.0.0.1:${admin.getLocalPort}"
    val key      = GarageStore(adminUrl, ObjectStoreStack.AdminToken).createKey("secondary")
    // The copy makes each bucket it copies: the key may create buckets on the secondary.
    val allowed = java.net.http.HttpClient
      .newHttpClient()
      .send(
        java.net.http.HttpRequest
          .newBuilder(URI.create(s"$adminUrl/v2/UpdateKey?id=${key.accessKeyId}"))
          .header("Authorization", s"Bearer ${ObjectStoreStack.AdminToken}")
          .header("Content-Type", "application/json")
          .POST(
            java.net.http.HttpRequest.BodyPublishers.ofString("""{"allow":{"createBucket":true}}""")
          )
          .build(),
        java.net.http.HttpResponse.BodyHandlers.ofString()
      )
    assertEquals(allowed.statusCode(), 200, allowed.body())
    secondaryKey = (key.accessKeyId, key.secretAccessKey)
    admin.close()
    secondaryForward = k8s
      .services()
      .inNamespace(ObjectStoreStack.Namespace)
      .withName("garage-secondary")
      .portForward(3900)
    // The copy, as the component ships it, its secondary's credential this store's key.
    val copy = repoRoot.resolve("kustomization/components/garage-copy")
    val secret = Files
      .readString(copy.resolve("secondary.yaml"))
      .replace("ACCESS_KEY_ID: \"SET\"", s"ACCESS_KEY_ID: \"${key.accessKeyId}\"")
      .replace("SECRET_ACCESS_KEY: \"SET\"", s"SECRET_ACCESS_KEY: \"${key.secretAccessKey}\"")
    val all = (secret +: Vector("status.yaml", "rbac.yaml", "cronjob.yaml").map(f =>
      Files.readString(copy.resolve(f))
    ))
      .mkString("\n---\n")
    k3s.copyFileToContainer(
      Transferable.of(all.getBytes(StandardCharsets.UTF_8)),
      "/tmp/garage-copy.yaml"
    )
    PkiStack.kubectl(k3s, "apply", "--server-side", "-f", "/tmp/garage-copy.yaml"): Unit

  private def copyStatus: Map[String, String] =
    Option(
      k8s.configMaps().inNamespace(ObjectStoreStack.Namespace).withName("garage-copy-status").get()
    )
      .flatMap(c => Option(c.getData))
      .map(_.asScala.toMap)
      .getOrElse(Map.empty)

  /** Runs the copy now, as the CronJob would, and waits for it to say it completed. */
  private def copyNow(): Unit =
    val before = copyStatus.get("lastCompleted")
    val job    = s"garage-copy-${System.nanoTime() % 100000}"
    PkiStack.kubectl(
      k3s,
      "-n",
      ObjectStoreStack.Namespace,
      "create",
      "job",
      job,
      "--from=cronjob/garage-copy"
    ): Unit
    waitFor(5.minutes, "the copy completing") {
      val s = copyStatus
      s.get("failure").exists(_.nonEmpty) || (s.get("lastCompleted").exists(_.nonEmpty) && s.get(
        "lastCompleted"
      ) != before)
    }
    if copyStatus.get("failure").exists(_.nonEmpty) then
      val log = k3s.execInContainer(
        "kubectl",
        "logs",
        "-n",
        ObjectStoreStack.Namespace,
        s"job/$job",
        "-c",
        "copy"
      )
      fail(s"the copy failed: ${copyStatus.get("failure")}\n${log.getStdout}${log.getStderr}")
    lastCopyDeleted = copyStatus.get("deletedObjects").flatMap(_.toLongOption)

  private def installationNow: InstallationStatus =
    readFromString[InstallationStatus](ok(ankka("status", "-o", "json")).out)

  private def withData(data: Map[String, String]) =
    new java.util.function.UnaryOperator[io.fabric8.kubernetes.api.model.ConfigMap]:
      def apply(c: io.fabric8.kubernetes.api.model.ConfigMap) =
        c.setData(data.asJava)
        c

  private def garagePods = Vector("garage-0", "garage-1", "garage-2")

  // ── Given ─────────────────────────────────────────────────────────────────

  Given("an installation whose Garage runs on {int} machines") { (n: Int) =>
    assertEquals(
      PkiStack.jsonPath(
        k3s,
        "-n",
        ObjectStoreStack.Namespace,
        "statefulset",
        "garage",
        "{.status.readyReplicas}"
      ),
      n.toString
    )
  }

  Given("a deployed service {string} with a bucket") { (service: String) =>
    val p = projectOf("shop")
    if !isReady(p, service) then
      deploy(
        p,
        service,
        s"""{"name":"$service","service":{"image":"$SampleImage","provisionObjectStorage":true}}"""
      )
  }

  /** `service` deployed in this scenario's project with a bucket, holding the object. */
  private def keptBy(service: String): Unit =
    val p = projectOf("shop")
    if !isReady(p, service) then
      deploy(
        p,
        service,
        s"""{"name":"$service","service":{"image":"$SampleImage","provisionObjectStorage":true}}"""
      )
    keep(p, service)

  Given("a deployed service {string} that has kept the object {string} in its bucket") {
    (service: String, _: String) => keptBy(service)
  }

  Given("an installation on Garage that names a secondary store")(() => secondaryStore)

  Given("an installation on Garage that names no secondary store") { () =>
    // As if no copy ever completed: the status the copy writes, emptied.
    k8s
      .configMaps()
      .inNamespace(ObjectStoreStack.Namespace)
      .withName("garage-copy-status")
      .edit(withData(Map.empty)): Unit
  }

  Given("a project {string} with a base backup in its backup bucket") { (project: String) =>
    awaitBackedUp(projectOf(project))
  }

  Given("the secondary store holds {string} from the bucket of {string}") {
    (_: String, service: String) =>
      // Each scenario has a project of its own: the object is kept in this one before it is copied.
      keptBy(service)
      copyNow()
      assert(secondaryHolds(bucketOf(projectOf("shop"), service), Object).nonEmpty)
  }

  Given("{string} has since deleted the object {string} from its bucket") {
    (service: String, _: String) =>
      val client = primary(projectOf("shop"), service)
      try
        client.deleteObject(
          DeleteObjectRequest
            .builder()
            .bucket(bucketOf(projectOf("shop"), service))
            .key(Object)
            .build()
        ): Unit
      finally client.close()
  }

  Given("an installation on Garage that requires a copy outside its failure domain") { () =>
    secondaryStore
    copyRequired = true
  }

  Given(
    "a project {string} that has taken a base backup since the last copy to the secondary store"
  ) { (project: String) =>
    val p = projectOf(project)
    ensureProject(p)
    if ok(ankka("services", "list", "-p", p, "-o", "json")).out.trim == "[]" then
      deploy(p, serviceOf("base"), descriptor(serviceOf("base")))
    // The last copy, from long before any base backup.
    k8s
      .configMaps()
      .inNamespace(ObjectStoreStack.Namespace)
      .withName("garage-copy-status")
      .edit(withData(Map("lastCompleted" -> "2020-01-01T00:00:00Z", "failure" -> ""))): Unit
    waitFor(10.minutes, s"$p's first base backup")(line(p).exists(_.lastBaseBackup.isDefined))
  }

  Given("a local platform")(() => ())

  // ── When ──────────────────────────────────────────────────────────────────

  When("{string} keeps the object {string} in its bucket") { (service: String, _: String) =>
    keep(projectOf("shop"), service)
  }

  When("one of the {int} machines and its volume are lost") { (_: Int) =>
    k8s
      .persistentVolumeClaims()
      .inNamespace(ObjectStoreStack.Namespace)
      .withName("data-garage-1")
      .delete(): Unit
    k8s
      .pods()
      .inNamespace(ObjectStoreStack.Namespace)
      .withName("garage-1")
      .withGracePeriod(0)
      .delete(): Unit
  }

  When("the next copy to the secondary store completes")(() => copyNow())

  When("the status of the installation is read")(() => installationNow: Unit)

  When("the status of the project {string} is read") { (project: String) =>
    projectStatus(projectOf(project)): Unit
  }

  When("what it installs is read")(() => ())

  // ── Then ──────────────────────────────────────────────────────────────────

  Then("each of the {int} machines holds {string}") { (_: Int, _: String) =>
    // Three megabytes, kept three times: every node's data grows by at least the object.
    for pod <- garagePods do
      assert(dataBytes(pod) >= Contents.length, s"$pod holds less than the object")
  }

  Then("{string} reads the object {string} back from its bucket") { (service: String, _: String) =>
    waitFor(2.minutes, "the object read back") {
      scala.util.Try(readBack(projectOf("shop"), service)).toOption.exists(_.sameElements(Contents))
    }
  }

  Then("the secondary store holds {string} in the bucket of {string}") {
    (_: String, service: String) =>
      assert(secondaryHolds(bucketOf(projectOf("shop"), service), Object).nonEmpty)
  }

  Then("the secondary store holds the base backup of {string} in the backup bucket of {string}") {
    (_: String, project: String) =>
      val held = secondaryHolds(Buckets.backup(projectOf(project)))
      assert(held.exists(_.contains("/base/")), held.toString)
  }

  Then("the status of the installation says when the last copy completed") { () =>
    val status = installationNow
    assert(status.secondaryStore.flatMap(_.lastCompleted).isDefined, status.toString)
    assert(!status.sharesFailureDomain, status.toString)
  }

  Then("it says that the backups of the installation share the failure domain of the cluster") { () =>
    assert(installationNow.sharesFailureDomain)
  }

  Then("it installs Garage on one machine") { () =>
    val local =
      Seq("kubectl", "kustomize", repoRoot.resolve("kustomization/overlays/local").toString).!!
    val garage = local
      .split("(?m)^---$")
      .find(d => d.contains("kind: StatefulSet") && d.contains("name: garage\n"))
      .get
    assert(garage.contains("replicas: 1") && garage.contains("--single-node"))
  }

  Then("it names no secondary store") { () =>
    val local =
      Seq("kubectl", "kustomize", repoRoot.resolve("kustomization/overlays/local").toString).!!
    assert(!local.contains("name: garage-copy"))
  }

  Then("the secondary store no longer holds {string}") { (_: String) =>
    assertEquals(secondaryHolds(bucketOf(projectOf("shop"), "reports"), Object), Vector.empty)
  }

  Then("the copy reports that it deleted {int} object") { (n: Int) =>
    assertEquals(lastCopyDeleted, Some(n.toLong))
  }

  Then("it does not say that {string} is backed up") { (project: String) =>
    assert(!projectStatus(projectOf(project)).backedUp)
  }

  Then("it says that the latest base backup of {string} has no copy outside the failure domain") {
    (project: String) =>
      val detail = projectStatus(projectOf(project)).detail.getOrElse("")
      assert(detail.contains("no copy outside the failure domain"), detail)
  }
