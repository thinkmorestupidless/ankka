package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.core.BuildInfo
import com.thinkmorestupidless.ankka.crd.{AnkkaSerialization, AnkkaService}
import com.thinkmorestupidless.ankka.operator.{
  ClusterImages,
  GatewayStack,
  InPod,
  KeycloakStack,
  Operator,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.fabric8.kubernetes.api.model.{ConfigMapBuilder, EnvVar, ObjectMetaBuilder}
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * The read record on a real Kubernetes (feature 038, Postgres backend): the control plane deployed
 * into k3s from its shipped manifests with the `secret-reads` component's database and patch, the
 * identity provider for an owner's token, the operator in this JVM, and the shopping cart sample as
 * the service that reads — through its `/secrets` route, called from a probe pod holding the cart's
 * own certificate, since the route admits only the service itself.
 *
 * What no offline suite can show: that a read inside a pod reaches the control plane at the address
 * the Kubernetes overlay carries, over mutual TLS and through both network policies; that the
 * record's database comes up from its component and the control plane makes its table there as the
 * role that owns it; and that a read whose record cannot be written is refused in a real pod.
 *
 * The Secret Manager backend's cases wait on spec 044: Workload Identity does not exist in k3s, and
 * the fake Secret Manager listens on this JVM's loopback, which no pod can reach. They are named
 * below, ignored, until 044's fake provider gives them a cluster to run in.
 *
 * Disable with `-Dankka.cluster.tests=off`, which also skips building the images.
 */
class SecretsClusterSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout: FiniteDuration = 30.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  private val K3sImage          = "rancher/k3s:v1.35.1-k3s1"
  private val BaseDomain        = "secrets.example.test"
  private val ControlPlaneImage = s"ankka-controlplane:${BuildInfo.imageTag}"
  private val SampleImage       = s"sample-shopping-cart:${BuildInfo.imageTag}"
  private val Namespace         = "ankka-controlplane"
  private val Organization      = "acme"
  private val Project           = "shop"
  private val Workloads         = s"ankka-$Project"
  private val Service           = "cart"
  private val Secret            = "acme"
  private val Value             = "sk-acme-1"
  private val Reads             = 3

  private var k3s: K3sContainer     = null
  private var k8s: KubernetesClient = null
  private var operator: Operator    = null
  private var httpsPort: Int        = 0
  private var deployToken: String   = ""

  // The realm's tokens live five minutes and the suite runs for longer, so one is minted again once
  // the last is four minutes old.
  @volatile private var minted: Option[(String, Long)] = None
  private def ownerToken: String =
    minted.filter((_, at) => System.nanoTime() - at < 4.minutes.toNanos) match
      case Some((token, _)) => token
      case None =>
        val token = KeycloakStack.mintToken(
          GatewayStack.exportCa(k8s),
          BaseDomain,
          httpsPort,
          "secrets-owner",
          "secrets-owner-secret"
        )
        minted = Some(token -> System.nanoTime())
        token

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("kustomization")))
      .getOrElse(fail("could not find the repository root"))

  private def read(relative: String): String = Files.readString(repoRoot.resolve(relative))

  private def apply(yaml: String): Unit =
    k8s.load(ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))).serverSideApply(): Unit

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.withExposedPorts(6443, GatewayStack.HttpsNodePort)
      k3s.start()
      httpsPort = k3s.getMappedPort(GatewayStack.HttpsNodePort)
      ClusterImages.importInto(k3s, ControlPlaneImage)
      ClusterImages.importInto(k3s, SampleImage)
      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()

      k8s
        .load(
          java.net.URI
            .create(
              "https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-1.30/releases/cnpg-1.30.0.yaml"
            )
            .toURL
            .openStream()
        )
        .serverSideApply(): Unit
      waitFor(120.seconds, "CloudNativePG's controller") {
        Option(
          k8s
            .apps()
            .deployments()
            .inNamespace("cnpg-system")
            .withName("cnpg-controller-manager")
            .get()
        ).flatMap(d => Option(d.getStatus)).flatMap(s => Option(s.getReadyReplicas)).exists(_ > 0)
      }
      apply(read("kustomization/components/crd/ankkaservice.yaml"))
      apply(read("kustomization/components/crd/ankkaproject.yaml"))
      apply(read("kustomization/components/controlplane/namespace.yaml"))

      // The control plane's schema ConfigMap, as deploy-local.sh makes it: the single-copy DDL and
      // the grants literal.
      val ddl = repoRoot.resolve("modules/runtime/src/main/resources/ankka/ddl")
      val schema = Files
        .list(ddl)
        .iterator()
        .asScala
        .filter(_.toString.endsWith(".sql"))
        .map(f => f.getFileName.toString -> Files.readString(f))
        .toMap + ("99-grants.sql" ->
        "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO ankka; GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO ankka;")
      k8s
        .configMaps()
        .inNamespace(Namespace)
        .resource(
          new ConfigMapBuilder()
            .withMetadata(
              new ObjectMetaBuilder()
                .withName("ankka-controlplane-schema")
                .withNamespace(Namespace)
                .build()
            )
            .withData(schema.asJava)
            .build()
        )
        .serverSideApply(): Unit
      apply(read("kustomization/components/postgres/cluster.yaml"))
      // The record's own database, from its component, as every overlay lists it.
      apply(read("kustomization/components/secret-reads/cluster.yaml"))
      apply(read("kustomization/components/controlplane/controlplane-rbac.yaml"))
      apply(read("kustomization/components/controlplane/service.yaml"))

      // cert-manager and the platform's authorities, Envoy Gateway, and the identity provider an
      // owner's token comes from.
      GatewayStack.install(k3s, k8s, repoRoot, BaseDomain)
      KeycloakStack.install(k3s, k8s, repoRoot, BaseDomain, httpsPort)
      KeycloakStack.createServiceClient(
        k3s,
        "secrets-owner",
        "secrets-owner-secret",
        platformAdmin = true
      )
      apply(read("kustomization/components/controlplane/zero-trust.yaml"))
      k8s
        .apps()
        .deployments()
        .inNamespace(Namespace)
        .resource(controlPlaneDeployment)
        .serverSideApply(): Unit

      // The operator, in this JVM on admin credentials: not what this suite is about.
      val operatorSettings = OperatorSettings.default.copy(resyncInterval = 2.seconds)
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
      operator.start()

  override def afterAll(): Unit =
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  /**
   * The control plane's Deployment as an overlay would make it: the base domain and the HTTPS port
   * filled in, this build's image, one instance (three are `ControlPlaneClusterSuite`'s to prove),
   * and the `secret-reads` component's patch merged in from its own file — so a change to the
   * component is a change to what this suite deploys.
   */
  private def controlPlaneDeployment: Deployment =
    val yaml = read("kustomization/components/controlplane/deployment.yaml")
      .replaceAll("(?<!ANKKA_)BASE_DOMAIN", BaseDomain)
      .replace("""value: "443"""", s"""value: "$httpsPort"""")
      .replace("image: ankka-controlplane:latest", s"image: $ControlPlaneImage")
      .replace("replicas: 3", "replicas: 1")
    val deployment = k8s
      .apps()
      .deployments()
      .load(ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
      .item()
    val patch = k8s
      .apps()
      .deployments()
      .load(
        ByteArrayInputStream(
          read("kustomization/components/secret-reads/controlplane-env.yaml")
            .getBytes(StandardCharsets.UTF_8)
        )
      )
      .item()
    val added: Vector[EnvVar] =
      patch.getSpec.getTemplate.getSpec.getContainers.asScala.head.getEnv.asScala.toVector
    assert(added.nonEmpty, "the secret-reads component's patch adds no variable")
    val container = deployment.getSpec.getTemplate.getSpec.getContainers.asScala
      .find(_.getName == "ankka-controlplane")
      .getOrElse(fail("the control plane's manifest has no ankka-controlplane container"))
    val kept = container.getEnv.asScala.filterNot(e => added.exists(_.getName == e.getName))
    container.setEnv((kept ++ added).asJava)
    deployment

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(1000)
    if !passed then fail(s"$what did not happen within $timeout")

  private def nodeExec(command: String*): (Int, String) =
    val result = k3s.execInContainer(command*)
    (result.getExitCode, result.getStdout + result.getStderr)

  /** Every pod in `namespace`, its containers' states and the end of each container's log. */
  private def diagnose(namespace: String): String =
    val pods = k8s.pods().inNamespace(namespace).list().getItems.asScala
    pods
      .map { pod =>
        val name = pod.getMetadata.getName
        val states = Option(pod.getStatus.getContainerStatuses).toList
          .flatMap(_.asScala)
          .map(c => s"${c.getName} ready=${c.getReady} restarts=${c.getRestartCount} ${c.getState}")
        val logs = pod.getSpec.getContainers.asScala.map { c =>
          val log =
            try
              k8s
                .pods()
                .inNamespace(namespace)
                .withName(name)
                .inContainer(c.getName)
                .tailingLines(60)
                .getLog
            catch case e: Throwable => s"(no log: ${e.getMessage})"
          s"--- $name/${c.getName}\n$log"
        }
        (s"$name phase=${pod.getStatus.getPhase} $states" +: logs).mkString("\n")
      }
      .mkString("\n")

  private def controlPlaneReady: Boolean =
    k8s
      .pods()
      .inNamespace(Namespace)
      .withLabel("app.kubernetes.io/name", "ankka-controlplane")
      .list()
      .getItems
      .asScala
      .exists(p =>
        p.getMetadata.getDeletionTimestamp == null &&
          Option(p.getStatus.getContainerStatuses).exists(_.asScala.exists(_.getReady))
      )

  private def awaitControlPlane(): Unit =
    try waitFor(480.seconds, "a ready control plane")(controlPlaneReady)
    catch case e: Throwable => fail(s"${e.getMessage}\n${diagnose(Namespace)}")

  /**
   * The control plane's API by its Service's name, from inside its own pod with that pod's service
   * certificate (the node's `wget` is BusyBox's, with no PUT), as `token`. Answers the HTTP status
   * and the body; 0 when curl had no answer.
   */
  private def api(
      method: String,
      path: String,
      body: Option[String] = None,
      token: => String = ownerToken
  ): (Int, String) =
    val from = k8s
      .pods()
      .inNamespace(Namespace)
      .withLabel("app.kubernetes.io/name", "ankka-controlplane")
      .list()
      .getItems
      .asScala
      .find(p =>
        p.getMetadata.getDeletionTimestamp == null &&
          Option(p.getStatus.getContainerStatuses).exists(_.asScala.exists(_.getReady))
      )
      .getOrElse(fail(s"no ready control plane pod\n${diagnose(Namespace)}"))
    val dir = "/var/run/secrets/ankka/service"
    val curl = Vector(
      "curl",
      "-sS",
      "-m",
      "15",
      "-w",
      "\n%{http_code}",
      "-X",
      method,
      "-H",
      s"Authorization: Bearer $token",
      "-H",
      "Content-Type: application/json",
      "--cert",
      s"$dir/tls.crt",
      "--key",
      s"$dir/tls.key",
      "--cacert",
      s"$dir/ca.crt"
    ) ++ body.toVector.flatMap(b => Vector("-d", b)) :+
      s"https://ankka-controlplane.$Namespace.svc.cluster.local:9000$path"
    val (_, out) = nodeExec(
      (Vector("kubectl", "exec", "-n", Namespace, from.getMetadata.getName, "--") ++ curl)*
    )
    val lines  = out.linesIterator.toVector
    val status = lines.lastOption.flatMap(_.trim.toIntOption).getOrElse(0)
    (status, lines.dropRight(1).mkString("\n"))

  /** SQL against the record's database, as its superuser, so ownership can be read too. */
  private def records(sql: String): String =
    val (code, out) = nodeExec(
      "kubectl",
      "exec",
      "-n",
      Namespace,
      "ankka-secret-reads-db-1",
      "-c",
      "postgres",
      "--",
      "psql",
      "-U",
      "postgres",
      "-d",
      "secret_reads",
      "-tA",
      "-c",
      sql
    )
    assertEquals(code, 0, out)
    out.trim

  private def count(where: String): Int =
    records(s"select count(*) from secret_reads where $where;").toInt

  private val ofTheCart = s"project = '$Project' and service = '$Service' and name = '$Secret'"

  /** See `InPod.prober`: a pod in the project holding the cart's own service certificate. */
  private lazy val prober: String = InPod.prober(k3s, Workloads, Service)

  /** The cart's `/secrets` route through its Service, as the cart itself. */
  private def cart(method: String, body: Option[String] = None): (Int, String) =
    InPod.curl(
      k3s,
      Workloads,
      prober,
      s"https://$Service.$Workloads.svc.cluster.local:9000/secrets/$Secret",
      method = method,
      body = body,
      maxSeconds = 20
    )

  test("the record's database comes up from its component, and the control plane makes its table") {
    awaitControlPlane()
    // As the role CNPG made the database's owner, so the database needs no schema and no grant.
    waitFor(120.seconds, "the secret_reads table") {
      records("select tableowner from pg_tables where tablename = 'secret_reads';") ==
        "secret_reads"
    }
  }

  test("a read of a service secret inside a deployed service's pod leaves a row in the record") {
    awaitControlPlane()
    assertEquals(
      api("POST", s"/organizations/$Organization", Some("""{"name":"Acme"}"""))._1 / 100,
      2
    )
    assertEquals(
      api(
        "POST",
        s"/projects/$Project",
        Some(s"""{"name":"Shop","organizationId":"$Organization"}""")
      )._1 / 100,
      2
    )
    val (applied, appliedBody) = api(
      "PUT",
      s"/services/$Project/$Service",
      Some(s"""{"name":"$Service","service":{"image":"$SampleImage"}}""")
    )
    assertEquals(applied / 100, 2, appliedBody)
    try
      waitFor(480.seconds, s"$Service to be Ready") {
        api("GET", s"/services/$Project/$Service")._2.contains("\"lifecycle\":\"Ready\"")
      }
    catch case e: Throwable => fail(s"${e.getMessage}\n${diagnose(Workloads)}")

    val (kept, keptBody) = cart("PUT", Some(s"""{"value":"$Value"}"""))
    assertEquals(kept, 204, keptBody)
    for i <- 1 to Reads do
      val (status, body) = cart("GET")
      assertEquals(status, 200, s"read $i: $body")
      assert(!body.contains(Value), s"the route answered the value: $body")

    // Committed before the value was returned, so already there: no wait.
    assertEquals(count(s"$ofTheCart and operation = 'get' and outcome = 'read'"), Reads)
    assertEquals(count(s"$ofTheCart and operation = 'put' and outcome = 'written'"), 1)
    assertEquals(
      records(
        s"select distinct hosting || ' ' || backend || ' ' || component_kind from secret_reads " +
          s"where $ofTheCart;"
      ),
      "embedded postgres endpoint"
    )
    assertEquals(count(s"secret_reads::text like '%$Value%'"), 0, "a record holds the value")
  }

  test(
    "an owner is answered from the read record which services read a secret on the Postgres backend"
  ) {
    val (status, body) = api("GET", s"/projects/$Project/secret-reads?name=$Secret")
    assertEquals(status, 200, body)
    assertEquals("\"operation\":\"get\"".r.findAllIn(body).size, Reads, body)
    assert(body.contains(s"\"service\":\"$Service\""), body)
    assert(!body.contains(Value), body)
  }

  test("a deploy token, a member of the organization, is refused the read record") {
    val (created, createdBody) =
      api("POST", s"/organizations/$Organization/tokens", Some("""{"label":"ci"}"""))
    assertEquals(created / 100, 2, createdBody)
    deployToken = """"secret":"([^"]+)"""".r
      .findFirstMatchIn(createdBody)
      .map(_.group(1))
      .getOrElse(fail(s"no secret in $createdBody"))
    // The token answers elsewhere, so the refusal below is the owner rule, not a bad credential.
    assertEquals(api("GET", s"/services/$Project", token = deployToken)._1, 200)
    assertEquals(
      api("GET", s"/projects/$Project/secret-reads", token = deployToken)._1,
      403
    )
  }

  test("a service may record only its own reads") {
    def record(service: String) =
      s"""{"at":"2026-10-09T12:00:00Z","project":"$Project","service":"$service",""" +
        s""""hosting":"embedded","name":"forged","operation":"get","outcome":"read",""" +
        s""""backend":"postgres"}"""
    def post(service: String) = InPod.curl(
      k3s,
      Workloads,
      prober,
      s"https://ankka-controlplane.$Namespace.svc.cluster.local:9000/secret-reads",
      method = "POST",
      body = Some(record(service))
    )
    // Its own is kept, so the refusal that follows is the rule, not the network or the certificate.
    assertEquals(post(Service)._1 / 100, 2)
    val (refused, body) = post("payments")
    assertEquals(refused, 403, body)
    assertEquals(count(s"project = '$Project' and service = 'payments'"), 0)
  }

  test("a read whose record cannot be written is refused, and no value is returned") {
    val before = count(ofTheCart)
    k8s.apps().deployments().inNamespace(Namespace).withName("ankka-controlplane").scale(0): Unit
    waitFor(180.seconds, "the control plane to be gone") {
      k8s
        .pods()
        .inNamespace(Namespace)
        .withLabel("app.kubernetes.io/name", "ankka-controlplane")
        .list()
        .getItems
        .isEmpty
    }
    try
      val (status, body) = cart("GET")
      assertEquals(status, 503, body)
      assert(!body.contains(Value), body)
    finally
      k8s.apps().deployments().inNamespace(Namespace).withName("ankka-controlplane").scale(1): Unit
      awaitControlPlane()
    // Nothing was recorded while it was away, and a read now is recorded again.
    assertEquals(count(ofTheCart), before)
    waitFor(120.seconds, "a recorded read after the control plane returned") {
      cart("GET")._1 == 200
    }
    assert(count(ofTheCart) > before)
  }

  test("the read record outlives the service") {
    val kept = count(ofTheCart)
    assert(kept >= Reads, s"$kept records")
    assertEquals(api("DELETE", s"/services/$Project/$Service")._1 / 100, 2)
    waitFor(300.seconds, s"$Service's resource to be removed") {
      k8s.resources(classOf[AnkkaService]).inNamespace(Workloads).withName(Service).get() == null
    }
    assertEquals(count(ofTheCart), kept)
    val (status, body) = api("GET", s"/projects/$Project/secret-reads?service=$Service")
    assertEquals(status, 200, body)
    assert(body.contains(s"\"service\":\"$Service\""), body)
  }

  // Spec 044's fake provider is what these need: a cluster where a pod can be granted access to
  // Secret Manager and reach a fake of it (tasks T027 and T047).
  test("a service is not rolled out until its access to its secrets is granted".ignore) {}
  test("a project secret's entry reaches the pod once it is synced to Secret Manager".ignore) {}
