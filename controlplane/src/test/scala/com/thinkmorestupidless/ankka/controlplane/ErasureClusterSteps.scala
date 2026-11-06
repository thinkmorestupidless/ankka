package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{ErasureRequestView, ErasureWire}
import com.thinkmorestupidless.ankka.controlplane.erasure.{
  ErasureLogBucket,
  ErasureLogEndpoint,
  ErasurePlane,
  ErasureSweep,
  KeyringAdmin
}
import com.thinkmorestupidless.ankka.controlplane.tenancy.ErasureSettings
import com.thinkmorestupidless.ankka.core.keyring.{ErasureDestroyed, ErasureOrder, ErasureStatus}
import com.thinkmorestupidless.ankka.crd.Buckets
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.{
  ClusterImages,
  KeyringSettings as OperatorKeyring,
  PkiStack,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.RuntimeExtension
import com.thinkmorestupidless.ankka.runtime.storage.{S3Bucket, S3Target, SigV4}
import org.testcontainers.images.builder.Transferable

import java.net.URI
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * What both erasure suites on k3s share (feature 042): the installation of the backup suites with
 * the keyring installed from its own component, two instances over its own CNPG database archived
 * into its platform bucket, and the operator told that erasure is on, so it makes the keyring's
 * secret key, the erasure log's bucket and its two keys, and gives every personal service the
 * keyring's address.
 *
 * The control plane runs in this JVM, as in every backup suite, so two things the keyring and the
 * control plane do to each other cross the node's boundary:
 *
 *   - the control plane reaches the keyring's door through a port-forward to a running instance,
 *     with a certificate for `ankka://platform/controlplane` from the service authority (the
 *     keyring's own certificate names `localhost` here, for the client's hostname check, and
 *     nothing else of it changes);
 *   - the keyring reads the control plane's copy of the erasure log at the address it is given in
 *     the cluster, `https://ankka-controlplane.ankka-controlplane.svc:9000`: a Service with no
 *     selector, whose one endpoint is this JVM on the node's gateway address, serving
 *     `ErasureLogEndpoint` over mutual TLS with that certificate.
 *
 * The erasure log's second copy is the platform bucket `platform.erasure-log` in the cluster's
 * Garage, written by this control plane through the store's port-forward with the key the operator
 * issued it (`ankka-erasure-log` in `ankka-controlplane`), and read by the keyring with its own.
 *
 * The services are the cart sample's image running as players (`CART_PLAYERS`): `players`, with an
 * entity and a view whose email is looked up by token, and a publisher where there is a broker;
 * `engagement`, which reads its own project's topic; and `cardholders`, which reads another
 * project's.
 */
abstract class ErasureClusterSteps(feature: String) extends BackupClusterSteps(feature):

  override val munitTimeout: FiniteDuration = 25.minutes

  protected val KeyringNamespace      = OperatorKeyring.Namespace
  protected val ControlPlaneNamespace = "ankka-controlplane"
  protected val KeyringImage          = s"ankka-keyring:$Tag"
  protected val KeyringAddress        = "https://keyring.ankka-keyring.svc:8443"

  /** The keyring's environment beyond its component's, for a suite that admits machines. */
  protected def keyringEnvironment: Vector[(String, String)] = Vector.empty

  /** Whether the keyring's route for machines is applied: only where the Gateway is installed. */
  protected def keyringRoute: Boolean = false

  /** Whether to wait for the keyring database's first base backup before any scenario. */
  protected def keyringBackedUp: Boolean = false

  override protected def operatorSettings: OperatorSettings =
    super.operatorSettings.copy(erasure = Some(OperatorKeyring(KeyringAddress)))

  override protected def erasureSettings: ErasureSettings =
    ErasureSettings(enabled = true, keyringInstances = 2)

  // ── the control plane's identity, for both directions ───────────────────────

  /** The files of a certificate for `ankka://platform/controlplane`, made in `installMore`. */
  protected var controlPlaneTls: Path = null

  protected def node(args: String*): String = PkiStack.kubectl(k3s, args*)

  protected def applyYaml(yaml: String, name: String): Unit =
    k3s.copyFileToContainer(
      Transferable.of(yaml.getBytes(StandardCharsets.UTF_8)),
      s"/tmp/erasure-$name.yaml"
    )
    node("apply", "--server-side", "--force-conflicts", "-f", s"/tmp/erasure-$name.yaml"): Unit

  private def issueControlPlaneCertificate(): Unit =
    applyYaml(
      s"""apiVersion: v1
         |kind: Namespace
         |metadata:
         |  name: $ControlPlaneNamespace
         |  labels: { app.kubernetes.io/managed-by: ankka }
         |---
         |apiVersion: cert-manager.io/v1
         |kind: Certificate
         |metadata:
         |  name: suite-controlplane
         |  namespace: $ControlPlaneNamespace
         |spec:
         |  secretName: suite-controlplane-tls
         |  uris: [ankka://platform/controlplane]
         |  dnsNames:
         |    - ankka-controlplane.$ControlPlaneNamespace.svc
         |    - ankka-controlplane.$ControlPlaneNamespace.svc.cluster.local
         |  usages: [server auth, client auth]
         |  duration: 24h
         |  privateKey: { algorithm: RSA, size: 2048, encoding: PKCS8 }
         |  issuerRef: { name: ankka-service, kind: ClusterIssuer, group: cert-manager.io }
         |""".stripMargin,
      "controlplane-certificate"
    )
    node(
      "wait",
      "-n",
      ControlPlaneNamespace,
      "--for=condition=Ready",
      "certificate/suite-controlplane",
      "--timeout=180s"
    ): Unit
    controlPlaneTls = Files.createTempDirectory("ankka-erasure-controlplane")
    val data = k8s
      .secrets()
      .inNamespace(ControlPlaneNamespace)
      .withName("suite-controlplane-tls")
      .get()
      .getData
    for file <- Seq("tls.crt", "tls.key", "ca.crt") do
      Files.write(
        controlPlaneTls.resolve(file),
        java.util.Base64.getDecoder.decode(data.get(file))
      ): Unit

  /** What a subclass installs beyond the keyring's needs, before the operator starts. */
  protected def installErasureMore(): Unit = ()

  override protected def installMore(): Unit =
    ClusterImages.importInto(k3s, KeyringImage)
    issueControlPlaneCertificate()
    installErasureMore()

  // ── the control plane's half ─────────────────────────────────────────────────

  /**
   * The keyring's door, through a port-forward to a ready instance made for each call: an instance
   * may be replaced while the suite runs, and a forward to a pod that left answers nothing.
   */
  protected object ForwardedKeyring extends KeyringAdmin:
    private given ExecutionContext                                     = ExecutionContext.global
    private def through[A](call: KeyringAdmin => Future[A]): Future[A] =
      try
        val pod     = readyKeyringPods().headOption.getOrElse(fail("no keyring instance is ready"))
        val forward = k8s.pods().inNamespace(KeyringNamespace).withName(pod).portForward(8443)
        try
          call(KeyringAdmin.Door(s"https://localhost:${forward.getLocalPort}", controlPlaneTls))
            .andThen(_ => forward.close())
        catch
          case e: Throwable =>
            forward.close()
            throw e
      catch case e: Throwable => Future.failed(e)
    def destroy(order: ErasureOrder): Future[ErasureDestroyed] = through(_.destroy(order))
    def resend(seq: Long): Future[Unit]                        = through(_.resend(seq))
    def status(seq: Long): Future[ErasureStatus]               = through(_.status(seq))

  /** The erasure log's bucket, with the key the operator issued the control plane. */
  private lazy val logBucket: S3Bucket =
    var data: java.util.Map[String, String] = null
    waitFor(5.minutes, "the operator issuing the control plane's key on the erasure log") {
      data = Option(
        k8s.secrets().inNamespace(ControlPlaneNamespace).withName("ankka-erasure-log").get()
      ).map(_.getData).orNull
      data != null
    }
    def entry(key: String) = new String(java.util.Base64.getDecoder.decode(data.get(key)))
    S3Bucket(
      S3Target(
        URI.create(s"http://127.0.0.1:${s3Forward.getLocalPort}"),
        "garage",
        Buckets.ErasureLog,
        Some(SigV4.Credentials(entry("ANKKA_S3_ACCESS_KEY"), entry("ANKKA_S3_SECRET_KEY"))),
        None
      ),
      None,
      com.thinkmorestupidless.ankka.sdk.BucketExposure.Signed,
      None,
      30.seconds,
      7.days,
      HttpClient.newHttpClient()
    )

  protected lazy val plane: ErasurePlane =
    ErasurePlane(ForwardedKeyring, Some(ErasureLogBucket.over(logBucket)))

  override protected def erasurePlane: Option[ErasurePlane] = Some(plane)

  /** The control plane's copy of the erasure log, served where the keyring reads it. */
  protected lazy val bridge: HttpServer =
    HttpServer.at("0.0.0.0", 0)(_ => ErasureLogEndpoint(() => Some(plane))).withTls(controlPlaneTls)

  override protected def moreExtensions: Seq[RuntimeExtension] =
    Seq(ErasureSweep(plane, () => 30.days), bridge)

  // ── the keyring ──────────────────────────────────────────────────────────────

  override protected def controlPlaneStarted(): Unit =
    reachControlPlaneFromCluster()
    installKeyring()

  /** The selectorless Service the keyring reads the control plane's copy of the log through. */
  private def reachControlPlaneFromCluster(): Unit =
    val port = bridge.boundPort.getOrElse(fail("the log's server did not bind"))
    val host = k3s.getContainerInfo.getNetworkSettings.getNetworks.asScala.values.headOption
      .map(_.getGateway)
      .orNull
    assert(host != null && host.nonEmpty, "the node has no gateway address")
    applyYaml(
      s"""apiVersion: v1
         |kind: Service
         |metadata: { name: ankka-controlplane, namespace: $ControlPlaneNamespace }
         |spec:
         |  ports: [{ name: http, port: 9000, targetPort: $port, protocol: TCP }]
         |---
         |apiVersion: discovery.k8s.io/v1
         |kind: EndpointSlice
         |metadata:
         |  name: ankka-controlplane-suite
         |  namespace: $ControlPlaneNamespace
         |  labels: { kubernetes.io/service-name: ankka-controlplane }
         |addressType: IPv4
         |endpoints: [{ addresses: ["$host"] }]
         |ports: [{ name: http, port: $port, protocol: TCP }]
         |""".stripMargin,
      "controlplane-bridge"
    )

  /** The component's files as an installation applies them, but for what this node needs. */
  private def installKeyring(): Unit =
    val component          = repoRoot.resolve("kustomization/components/keyring")
    def read(file: String) = Files.readString(component.resolve(file))
    val certificate        = read("certificate.yaml").replace(
      "    - keyring.ankka-keyring.svc.cluster.local\n",
      "    - keyring.ankka-keyring.svc.cluster.local\n    - localhost\n"
    )
    assert(certificate.contains("- localhost"), "the keyring's certificate names no localhost")
    val extra = keyringEnvironment
      .map((k, v) => s"""            - { name: $k, value: "$v" }""")
      .mkString("\n")
    val deployment = read("deployment.yaml")
      .replace("image: ankka-keyring:latest", s"image: $KeyringImage")
      .replace(
        "            - name: ANKKA_KEYRING_KMS_KEY\n              value: \"\"\n",
        "            - name: ANKKA_KEYRING_KMS_KEY\n              value: \"\"\n" +
          (if extra.isEmpty then "" else extra + "\n")
      )
    assert(deployment.contains(KeyringImage), "the keyring's image was not replaced")
    keyringEnvironment.foreach((k, _) =>
      assert(deployment.contains(s"name: $k,"), s"$k was not added to the keyring")
    )
    val files = Vector(
      read("namespace.yaml"),
      read("rbac.yaml"),
      certificate,
      read("database.yaml"),
      deployment,
      read("service.yaml"),
      read("zero-trust.yaml")
    ) ++ Option.when(keyringRoute)(httpRoute).toVector
    applyYaml(files.mkString("\n---\n"), "keyring")
    try
      waitFor(10.minutes, "two keyring instances ready") {
        readyKeyringPods().size == 2
      }
    catch
      case e: munit.FailException =>
        // The keyring logs plain lines, which the installation's dump filters out: say why here.
        fail(s"${e.getMessage}\n${keyringLogs()}")
    if keyringBackedUp then
      waitFor(10.minutes, "the keyring database's first base backup") {
        PkiStack
          .jsonPath(
            k3s,
            "backup",
            "-n",
            KeyringNamespace,
            "{.items[*].status.phase}"
          )
          .split(' ')
          .contains("completed")
      }

  /** The keyring's route for machines, as an overlay fills it. */
  protected def httpRoute: String =
    Files
      .readString(repoRoot.resolve("kustomization/components/keyring/httproute.yaml"))
      .replaceAll("(?<!ANKKA_)BASE_DOMAIN", routeDomain)

  protected def routeDomain: String = "example.test"

  protected def keyringPods(): Vector[io.fabric8.kubernetes.api.model.Pod] =
    k8s
      .pods()
      .inNamespace(KeyringNamespace)
      .withLabel("app.kubernetes.io/name", "ankka-keyring")
      .list()
      .getItems
      .asScala
      .toVector
      .filter(_.getMetadata.getDeletionTimestamp == null)

  /** The last lines of each keyring instance, the previous container's first: why it stopped. */
  protected def keyringLogs(): String =
    keyringPods()
      .map(_.getMetadata.getName)
      .take(2)
      .map { pod =>
        def logs(previous: Boolean) =
          val args =
            Vector("kubectl", "logs", "-n", KeyringNamespace, pod, "-c", "ankka-keyring") ++
              Option.when(previous)("--previous") ++ Vector("--tail", "60")
          val r = PkiStack.exec(k3s, 30.seconds, args*)
          r.getStdout + r.getStderr
        s"== $pod (previous)\n${logs(true)}\n== $pod\n${logs(false)}"
      }
      .mkString("\n")

  /** The last lines of a service's instances, its platform container's. */
  protected def servicePodLogs(project: String, service: String): String =
    pods(project, service)
      .map(_.getMetadata.getName)
      .take(2)
      .map { pod =>
        val r = PkiStack.exec(
          k3s,
          30.seconds,
          "sh",
          "-c",
          s"kubectl logs -n ${namespace(project)} $pod --all-containers --tail 80 | " +
            "grep -vE ' DEBUG ' | tail -60"
        )
        s"== $pod\n${r.getStdout}${r.getStderr}"
      }
      .mkString("\n")

  protected def readyKeyringPods(): Vector[String] =
    keyringPods()
      .filter(p =>
        Option(p.getStatus.getConditions).toVector
          .flatMap(_.asScala)
          .exists(c => c.getType == "Ready" && c.getStatus == "True")
      )
      .map(_.getMetadata.getName)

  /** `psql` as postgres in one of the keyring's database clusters' primary. */
  protected def keyringSql(sql: String, cluster: String = "keyring-db"): String =
    val pod = k8s
      .pods()
      .inNamespace(KeyringNamespace)
      .withLabel("cnpg.io/cluster", cluster)
      .withLabel("cnpg.io/instanceRole", "primary")
      .list()
      .getItems
      .asScala
      .headOption
      .getOrElse(fail(s"$cluster has no primary"))
      .getMetadata
      .getName
    val r = PkiStack.exec(
      k3s,
      1.minute,
      "kubectl",
      "exec",
      "-n",
      KeyringNamespace,
      pod,
      "-c",
      "postgres",
      "--",
      "psql",
      "-U",
      "postgres",
      "-d",
      "keyring",
      "-tA",
      "-v",
      "ON_ERROR_STOP=1",
      "-c",
      sql
    )
    assertEquals(r.getExitCode, 0, r.getStderr)
    r.getStdout.trim

  /** A keyring instance's replay report, from its plain probe port, read from the node. */
  protected def keyringStatus(pod: String): String =
    val ip = k8s.pods().inNamespace(KeyringNamespace).withName(pod).get().getStatus.getPodIP
    PkiStack.exec(k3s, 30.seconds, "wget", "-qO-", s"http://$ip:7627/status").getStdout

  // ── the services ─────────────────────────────────────────────────────────────

  /**
   * A players service's descriptor: the cart's image in the role named, declaring personal fields.
   */
  protected def playersDescriptor(name: String, role: String, from: Option[String] = None): String =
    val env = Vector(s"""{"name":"CART_PLAYERS","value":"$role"}""") ++
      from.map(p => s"""{"name":"CART_PLAYERS_FROM","value":"$p"}""")
    s"""{"name":"$name","service":{"image":"$SampleImage","personal":true,"env":[${env
        .mkString(",")}]}}"""

  /** What a service of the players image answers at `path`, from inside its own pod. */
  protected def players(
      project: String,
      service: String,
      path: String,
      body: Option[String] = None
  ): (Int, String) =
    cart(project, service, if body.isDefined then "POST" else "GET", path, body)

  protected def register(project: String, service: String, id: String, name: String): Unit =
    val (code, body) = players(
      project,
      service,
      s"/players/$id",
      Some(s"""{"email":"${id}@example.com","name":"$name","currency":"EUR"}""")
    )
    assertEquals(code, 200, body)

  /** A field of a players read: `"name":"..."`. */
  protected def field(body: String, name: String): Option[String] =
    s""""$name":"([^"]*)"""".r.findFirstMatchIn(body).map(_.group(1))

  // ── erasure requests ─────────────────────────────────────────────────────────

  protected def requestOf(project: String, id: String): ErasureRequestView =
    readFromString(
      ok(ankka("projects", "erasures", "get", id, "-p", project, "-o", "json")).out
    )(using ErasureWire.erasureRequestViewCodec)

  /** Files an erasure through the command line and waits for it to be applied. */
  protected def erase(project: String, subject: String): ErasureRequestView =
    val asked = readFromString(
      ok(ankka("projects", "erasures", "file", subject, "-p", project, "-o", "json")).out
    )(using ErasureWire.erasureRequestViewCodec)
    var applied: ErasureRequestView = null
    waitFor(5.minutes, s"the erasure of $subject in $project applied") {
      applied = requestOf(project, asked.id)
      applied.state == "applied" && applied.destroyedAt.isDefined
    }
    applied

  protected def now(): Instant = Instant.now()
