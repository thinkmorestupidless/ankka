package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.core.BuildInfo
import com.thinkmorestupidless.ankka.crd.{
  AnkkaSerialization,
  AnkkaService,
  AnkkaServiceSpec,
  AutoscalingSpec,
  EnvEntry
}
import com.thinkmorestupidless.ankka.operator.{
  ClusterImages,
  Operator,
  ServiceReconciler,
  Settings as OperatorSettings
}
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A process-hosted service on a real Kubernetes: the operator running in this JVM against k3s, the
 * Python shopping cart as the developer's image, the sidecar injected beside it, and a plain
 * Postgres in the namespace as the supplied database (no CNPG: what this suite proves is the
 * two-container pod, not provisioning, which the operator's own suites cover).
 *
 * Every assertion that matters takes the real path: requests from the node to the Service's
 * clusterIP, never a port-forward; the app container killed from inside the pod; a probe pod in the
 * namespace trying the loopback ports.
 */
class SidecarClusterSuite extends munit.FunSuite:

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")
  override def munitTimeout: scala.concurrent.duration.Duration = 30.minutes

  private val K3sImage     = "rancher/k3s:v1.35.1-k3s1"
  private val SidecarImage = s"ankka-sidecar:${BuildInfo.imageTag}"
  private val PythonImage =
    sys.props.getOrElse("ankka.python.image", s"sample-shopping-cart-python:${BuildInfo.imageTag}")
  private val Prefix    = "ankka"
  private val Project   = "checkout"
  private val Namespace = s"$Prefix-$Project"
  private val Service   = "cart"

  private var k3s: K3sContainer     = null
  private var k8s: KubernetesClient = null
  private var operator: Operator    = null

  private val settings =
    OperatorSettings.default.copy(resyncInterval = 2.seconds, sidecarImage = SidecarImage)

  private def repositoryRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .take(5)
      .find(p => Files.isDirectory(p.resolve("protocol")))
      .getOrElse(fail("could not find the repository root"))

  override def beforeAll(): Unit =
    if !munitIgnore then
      // The Python image is built here, not by sbt: it is a `docker build`, and the tag is this
      // build's so a stale image from another session is never the one deployed.
      val build = new ProcessBuilder(
        "docker",
        "build",
        "-q",
        "-f",
        "sdks/python/examples/shopping_cart/Dockerfile",
        "-t",
        PythonImage,
        "sdks/python"
      ).directory(repositoryRoot.toFile).redirectErrorStream(true).start()
      val output = new String(build.getInputStream.readAllBytes())
      assertEquals(build.waitFor(), 0, s"docker build failed:\n$output")

      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.start()
      ClusterImages.importInto(k3s, SidecarImage)
      ClusterImages.importInto(k3s, PythonImage)

      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      k8s.load(getClass.getResourceAsStream("/ankka/crd/ankkaservice.yaml")).serverSideApply(): Unit
      waitFor(60.seconds)(
        k8s
          .apiextensions()
          .v1()
          .customResourceDefinitions()
          .withName("ankkaservices.ankka.thinkmorestupidless.com")
          .get() != null
      )
      // The namespace is the control plane's to create; here the suite stands in for it.
      k8s
        .namespaces()
        .resource(
          new io.fabric8.kubernetes.api.model.NamespaceBuilder()
            .withMetadata(new ObjectMetaBuilder().withName(Namespace).build())
            .build()
        )
        .serverSideApply(): Unit
      deployPostgres()

      operator = new Operator(k8s, settings, ServiceReconciler(k8s, settings))
      operator.start()

  override def afterAll(): Unit =
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  // ── helpers ───────────────────────────────────────────────────────────────

  private def waitFor(timeout: FiniteDuration)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(500)
    if !passed then fail(s"condition did not hold within $timeout; pods: ${podSummary()}")

  private def nodeExec(command: String*): (Int, String) =
    val result = k3s.execInContainer(command*)
    (result.getExitCode, result.getStdout + result.getStderr)

  private def kubectl(args: String*): (Int, String) = nodeExec(("kubectl" +: args)*)

  /** A plain Postgres with the platform's DDL, as the service's supplied database. */
  private def deployPostgres(): Unit =
    val ddl =
      Vector("10-journal-postgres.sql", "20-projection-postgres.sql", "30-timers-postgres.sql")
        .map(n => n -> new String(getClass.getResourceAsStream(s"/ankka/ddl/$n").readAllBytes()))
    val configMap = new io.fabric8.kubernetes.api.model.ConfigMapBuilder()
      .withMetadata(
        new ObjectMetaBuilder().withName("postgres-ddl").withNamespace(Namespace).build()
      )
      .withData(ddl.toMap.asJava)
      .build()
    k8s.configMaps().inNamespace(Namespace).resource(configMap).serverSideApply(): Unit
    val manifest = s"""
apiVersion: apps/v1
kind: Deployment
metadata: { name: postgres, namespace: $Namespace }
spec:
  replicas: 1
  selector: { matchLabels: { app: postgres } }
  template:
    metadata: { labels: { app: postgres } }
    spec:
      containers:
        - name: postgres
          image: postgres:17-alpine
          env:
            - { name: POSTGRES_USER, value: ankka }
            - { name: POSTGRES_PASSWORD, value: ankka }
            - { name: POSTGRES_DB, value: ankka }
          ports: [ { containerPort: 5432 } ]
          volumeMounts: [ { name: ddl, mountPath: /docker-entrypoint-initdb.d } ]
          readinessProbe: { exec: { command: [ pg_isready, -U, ankka, -d, ankka ] }, periodSeconds: 2 }
      volumes: [ { name: ddl, configMap: { name: postgres-ddl } } ]
---
apiVersion: v1
kind: Service
metadata: { name: postgres, namespace: $Namespace }
spec:
  selector: { app: postgres }
  ports: [ { port: 5432, targetPort: 5432 } ]
"""
    k8s.load(new java.io.ByteArrayInputStream(manifest.getBytes)).serverSideApply(): Unit
    waitFor(180.seconds) {
      val d = k8s.apps().deployments().inNamespace(Namespace).withName("postgres").get()
      d != null && Option(d.getStatus).flatMap(s => Option(s.getReadyReplicas)).exists(_ > 0)
    }

  private def resources = k8s.resources(classOf[AnkkaService]).inNamespace(Namespace)

  private def spec(instances: Int = 1, restarts: Int = 0): AnkkaServiceSpec =
    AnkkaServiceSpec(
      projectId = Project,
      serviceName = Service,
      generation = 1L,
      image = PythonImage,
      hosting = "process",
      port = Some(9000),
      // A supplied database: the escape hatch, so nothing is provisioned. These reach the
      // sidecar, never the process — asserted below.
      env = List(
        EnvEntry("ANKKA_DB_HOST", Some(s"postgres.$Namespace.svc"), None, None),
        EnvEntry("ANKKA_DB_PORT", Some("5432"), None, None),
        EnvEntry("ANKKA_DB_NAME", Some("ankka"), None, None),
        EnvEntry("ANKKA_DB_USER", Some("ankka"), None, None),
        EnvEntry("ANKKA_DB_PASSWORD", Some("ankka"), None, None)
      ),
      provisionDatabase = false,
      autoscaling = AutoscalingSpec(minInstances = instances, maxInstances = instances),
      restarts = restarts
    )

  private def apply(s: AnkkaServiceSpec): Unit =
    val r = new AnkkaService
    r.setMetadata(new ObjectMetaBuilder().withName(Service).withNamespace(Namespace).build())
    r.setSpec(s)
    resources.resource(r).serverSideApply(): Unit

  private def status = Option(resources.withName(Service).get()).flatMap(r => Option(r.getStatus))

  private def pods =
    k8s
      .pods()
      .inNamespace(Namespace)
      .withLabel("ankka.thinkmorestupidless.com/service", Service)
      .list()
      .getItems
      .asScala
      .toVector

  private def podSummary(): String =
    pods.map(p => s"${p.getMetadata.getName}=${p.getStatus.getPhase}/${readyOf(p)}").mkString(", ")

  private def readyOf(p: io.fabric8.kubernetes.api.model.Pod): Boolean =
    Option(p.getStatus)
      .flatMap(s => Option(s.getConditions))
      .exists(_.asScala.exists(c => c.getType == "Ready" && c.getStatus == "True"))

  private def readyReplicas: Int =
    Option(k8s.apps().deployments().inNamespace(Namespace).withName(Service).get())
      .flatMap(d => Option(d.getStatus))
      .flatMap(s => Option(s.getReadyReplicas))
      .map(_.intValue)
      .getOrElse(0)

  private def nodeHttp(path: String, post: Option[String] = None): (Int, String) =
    val service = k8s.services().inNamespace(Namespace).withName(Service).get()
    val target =
      s"http://${service.getSpec.getClusterIP}:${service.getSpec.getPorts.get(0).getPort}$path"
    val command = post match
      case None => Seq("wget", "-qO-", "-T", "5", target)
      case Some(body) =>
        Seq(
          "wget",
          "-qO-",
          "-T",
          "5",
          "--header",
          "Content-Type: application/json",
          "--post-data",
          body,
          target
        )
    nodeExec(command*)

  // ── the story ─────────────────────────────────────────────────────────────

  test("S2.1 a process-hosted descriptor becomes a two-container pod that is Ready and serves") {
    apply(spec())
    waitFor(300.seconds)(readyReplicas >= 1)
    val pod    = pods.head
    val images = pod.getSpec.getContainers.asScala.map(_.getImage).toVector
    assertEquals(images, Vector(SidecarImage, PythonImage))
    waitFor(60.seconds)(status.exists(_.lifecycle == "Ready"))
    // wget exits 0 on 2xx; a 204 has no body.
    val (added, _) =
      nodeHttp("/carts/c1/items", Some("""{"productId":"p1","name":"Pen","quantity":2}"""))
    assertEquals(added, 0)
    val (code, body) = nodeHttp("/carts/c1")
    assertEquals(code, 0, body)
    assert(body.contains("\"productId\":\"p1\""), body)
  }

  test("the credential and the database reach the sidecar only; the process knows how to find it") {
    val pod = pods.head
    val (_, sidecarEnv) =
      kubectl("exec", "-n", Namespace, pod.getMetadata.getName, "-c", Service, "--", "env")
    val (_, appEnv) =
      kubectl("exec", "-n", Namespace, pod.getMetadata.getName, "-c", s"$Service-app", "--", "env")
    assert(sidecarEnv.contains("ANKKA_DB_HOST="), sidecarEnv)
    assert(!appEnv.contains("ANKKA_DB_"), appEnv)
    assert(
      appEnv.contains("ANKKA_PROCESS_PORT=9010") && appEnv.contains(
        "ANKKA_SIDECAR_ADDRESS=127.0.0.1:9011"
      ),
      appEnv
    )
  }

  private def restartCountOfApp(name: String): Int =
    pods
      .find(_.getMetadata.getName == name)
      .flatMap(p => Option(p.getStatus.getContainerStatuses))
      .map(_.asScala.filter(_.getName == s"$Service-app").map(_.getRestartCount.intValue).sum)
      .getOrElse(-1)

  /**
   * The app container's process, as the node sees it. From inside the container PID 1 ignores
   * SIGSTOP and SIGKILL (a process is protected from signals sent from its own PID namespace unless
   * it installed a handler), so the signal has to come from the node — the same reason
   * `MultiNodeClusterSuite` kills a JVM through crictl.
   */
  private def hostPidOfApp(): Int =
    val (_, id) = nodeExec("crictl", "ps", "-q", "--name", s"$Service-app")
    val (_, pid) =
      nodeExec("crictl", "inspect", "-o", "go-template", "--template", "{{.info.pid}}", id.trim)
    pid.trim.toInt

  private def signal(pid: Int, sig: String): Unit =
    assertEquals(nodeExec("kill", s"-$sig", pid.toString)._1, 0, s"kill -$sig $pid")

  test(
    "S2.5 a frozen process makes the pod un-ready and requests fail; a dead one is restarted with the sidecar kept"
  ) {
    val pod  = pods.head
    val name = pod.getMetadata.getName
    // Frozen: the TCP connection stays up, so only a real round trip can tell — which is what
    // readiness does.
    signal(hostPidOfApp(), "STOP")
    waitFor(30.seconds)(pods.exists(p => p.getMetadata.getName == name && !readyOf(p)))
    val (frozen, body) = nodeHttp("/carts/c1")
    assert(frozen != 0, s"a request to a frozen process should fail: $body")
    signal(hostPidOfApp(), "CONT")
    waitFor(60.seconds)(pods.exists(p => p.getMetadata.getName == name && readyOf(p)))
    // Dead: the container restarts; the pod, and the sidecar in it, stay.
    val restartsBefore = restartCountOfApp(name)
    signal(hostPidOfApp(), "KILL")
    waitFor(120.seconds)(
      restartCountOfApp(name) > restartsBefore &&
        pods.exists(p => p.getMetadata.getName == name && readyOf(p))
    )
    // The container restarts faster than the readiness probe can notice (three failures at five
    // seconds each), so a request can meet the new process still booting; a client retries.
    var answer = nodeHttp("/carts/c1")
    waitFor(60.seconds) {
      answer = nodeHttp("/carts/c1")
      answer._1 == 0
    }
    assert(
      answer._2.contains("\"productId\":\"p1\""),
      "the cart survived the process restarting: " + answer._2
    )
    assertEquals(pods.map(_.getMetadata.getName), Vector(name))
  }

  test("S2.2 scaling 1→3 forms one cluster and leaves the first pod alone") {
    val first = pods.head.getMetadata.getName
    apply(spec(instances = 3))
    waitFor(300.seconds)(readyReplicas == 3)
    assert(
      pods.exists(_.getMetadata.getName == first),
      s"the first pod was replaced: ${podSummary()}"
    )
    val (code, body) = nodeHttp("/carts/c1")
    assertEquals(code, 0, body)
  }

  test("S2.3 a restart replaces pods one at a time with no refused request") {
    val before = pods.map(_.getMetadata.getName).toSet
    apply(spec(instances = 3, restarts = 1))
    var refused  = 0
    var requests = 0
    val deadline = System.nanoTime() + 300.seconds.toNanos
    while pods.exists(p => before.contains(p.getMetadata.getName)) && System.nanoTime() < deadline
    do
      val (code, _) = nodeHttp("/carts/c1")
      requests += 1
      if code != 0 then refused += 1
    waitFor(120.seconds)(readyReplicas == 3 && pods.forall(readyOf))
    assert(
      requests > 5,
      s"the rollout finished before the loop measured anything ($requests requests)"
    )
    assertEquals(refused, 0, s"$refused of $requests requests were refused during the rollout")
  }

  test("SC-008 the protocol ports are unreachable from another pod in the namespace") {
    val podIp = pods.head.getStatus.getPodIP
    val (_, _) = kubectl(
      "run",
      "probe",
      "-n",
      Namespace,
      "--image=busybox:1.36",
      "--restart=Never",
      "--",
      "sleep",
      "600"
    )
    waitFor(120.seconds)(
      kubectl(
        "get",
        "pod",
        "probe",
        "-n",
        Namespace,
        "-o",
        "jsonpath={.status.phase}"
      )._2.trim == "Running"
    )
    val (process, _) =
      kubectl("exec", "-n", Namespace, "probe", "--", "nc", "-z", "-w", "2", podIp, "9010")
    val (sidecar, _) =
      kubectl("exec", "-n", Namespace, "probe", "--", "nc", "-z", "-w", "2", podIp, "9011")
    val (http, _) =
      kubectl("exec", "-n", Namespace, "probe", "--", "nc", "-z", "-w", "2", podIp, "9000")
    assert(process != 0, "the process's port answered from another pod")
    assert(sidecar != 0, "the sidecar's callback port answered from another pod")
    assertEquals(http, 0, "the HTTP port should answer; nc is not the problem")
  }
