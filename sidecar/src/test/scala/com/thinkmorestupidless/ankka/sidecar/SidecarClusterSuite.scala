package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.testkit.LogCapturing
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
 * And a wasm-hosted service beside it: the Rust cart's conformance reference built to a module and
 * shipped in a module image, run by the platform's runtime image with the module copied in by an
 * init container — plus two images that must fail, and say why.
 *
 * Every assertion that matters takes the real path: requests from the node to the Service's
 * clusterIP, never a port-forward; the app container killed from inside the pod; a probe pod in the
 * namespace trying the loopback ports.
 */
class SidecarClusterSuite extends munit.FunSuite with LogCapturing:

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")
  override def munitTimeout: scala.concurrent.duration.Duration = 30.minutes

  private val K3sImage     = "rancher/k3s:v1.35.1-k3s1"
  private val SidecarImage = s"ankka-sidecar:${BuildInfo.imageTag}"
  private val PythonImage =
    sys.props.getOrElse("ankka.python.image", s"sample-shopping-cart-python:${BuildInfo.imageTag}")

  /**
   * The Rust reference module's image, built here when cargo is present; its cases skip otherwise.
   */
  private val RustImage     = s"sample-shopping-cart-rust:${BuildInfo.imageTag}"
  private val WrongAbiImage = s"ankka-wasm-wrong-abi:${BuildInfo.imageTag}"
  private val NoCopyImage   = s"ankka-wasm-no-copy:${BuildInfo.imageTag}"
  private val RustService   = "rust-cart"
  private var rustBuilt     = false
  private val Prefix        = "ankka"
  private val Project       = "checkout"
  private val Namespace     = s"$Prefix-$Project"
  private val Service       = "cart"

  /**
   * Feature 022: a Python service with one authenticated route, built on the sample's image, and
   * the issuer whose tokens it accepts. The issuer's keys are served inside the cluster, since the
   * test issuer's own server is on the host's loopback where no pod can reach it.
   */
  private val AuthImage       = s"ankka-auth-probe-python:${BuildInfo.imageTag}"
  private val AuthService     = "accounts"
  private val UnlistedService = "accounts-unlisted"
  private val KeysImage       = "busybox:1.37"
  private lazy val authIssuer =
    com.thinkmorestupidless.ankka.auth.oidc.TestIssuer(
      "https://auth.cluster.test/realms/staff",
      "staff",
      "accounts"
    )

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
      //
      // It copies the SDK as it is on disk, and the generated stubs are gitignored: without them
      // the image builds, and its process dies on `No module named 'ankka._proto'` in every case
      // after, each waiting out its own timeout. Refused here instead, naming the step.
      assert(
        Files.isDirectory(repositoryRoot.resolve("sdks/python/src/ankka/_proto")),
        "the Python SDK's generated stubs are missing: run `uv run python scripts/proto.py` in sdks/python"
      )
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

      buildModuleImages()
      buildAuthImage()

      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.start()
      ClusterImages.importInto(k3s, SidecarImage)
      ClusterImages.importInto(k3s, PythonImage)
      ClusterImages.importInto(k3s, AuthImage)
      // A public image, so pulled rather than assumed: a machine that had never pulled it (a fresh
      // CI runner) failed the import, while every laptop that had passed.
      docker(repositoryRoot, "pull", "-q", KeysImage)
      ClusterImages.importInto(k3s, KeysImage)
      (Vector(WrongAbiImage, NoCopyImage) ++ Option.when(rustBuilt)(RustImage))
        .foreach(ClusterImages.importInto(k3s, _))

      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      k8s.load(getClass.getResourceAsStream("/ankka/crd/ankkaservice.yaml")).serverSideApply(): Unit
      // The installation's authorities: the sidecar's certificates come from them.
      com.thinkmorestupidless.ankka.operator.PkiStack.install(k3s, k8s)
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
      // Two services never share a database; the wasm one has its own.
      deployPostgres("postgres-rust")
      deployPostgres("postgres-accounts")
      deployKeys()

      operator = new Operator(k8s, settings, ServiceReconciler(k8s, settings))
      operator.start()

  override def afterAll(): Unit =
    if k3s != null then authIssuer.stop()
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
    if !passed then
      fail(s"condition did not hold within $timeout; pods: ${podSummary()}\n${podDiagnosis()}")

  /**
   * Why each pod is where it is: every container's state (a waiting reason, a last termination),
   * the conditions that are not met, and the last lines of every container's log. "Running/false"
   * alone left a CI failure with nothing to go on.
   */
  private def podDiagnosis(): String =
    try
      pods
        .map { p =>
          val name = p.getMetadata.getName
          val statuses =
            Option(p.getStatus.getContainerStatuses).map(_.asScala.toSeq).getOrElse(Nil) ++
              Option(p.getStatus.getInitContainerStatuses).map(_.asScala.toSeq).getOrElse(Nil)
          val containers = statuses.map { c =>
            val state = Option(c.getState.getWaiting)
              .map(w => s"waiting ${w.getReason}: ${Option(w.getMessage).getOrElse("")}")
              .orElse(
                Option(c.getState.getTerminated)
                  .map(t => s"terminated ${t.getReason} (${t.getExitCode})")
              )
              .getOrElse("running")
            val last = Option(c.getLastState)
              .flatMap(s => Option(s.getTerminated))
              .map(t => s", last terminated ${t.getReason} (${t.getExitCode})")
              .getOrElse("")
            val log = scala.util
              .Try(
                k8s
                  .pods()
                  .inNamespace(Namespace)
                  .withName(name)
                  .inContainer(c.getName)
                  .tailingLines(30)
                  .getLog
              )
              .getOrElse("(no log)")
            s"  [${c.getName}] ready=${c.getReady} restarts=${c.getRestartCount} $state$last\n$log"
          }
          val unmet = p.getStatus.getConditions.asScala
            .filter(_.getStatus != "True")
            .map(c => s"${c.getType}: ${c.getReason} ${Option(c.getMessage).getOrElse("")}".trim)
          s"--- $name ${unmet.mkString("; ")}\n${containers.mkString("\n")}"
        }
        .mkString("\n")
    catch case e: Exception => s"(could not diagnose the pods: $e)"

  private def nodeExec(command: String*): (Int, String) =
    val result = k3s.execInContainer(command*)
    (result.getExitCode, result.getStdout + result.getStderr)

  private def kubectl(args: String*): (Int, String) = nodeExec(("kubectl" +: args)*)

  private def docker(dir: Path, args: String*): Unit =
    val process = new ProcessBuilder(("docker" +: args)*)
      .directory(dir.toFile)
      .redirectErrorStream(true)
      .start()
    val output = new String(process.getInputStream.readAllBytes())
    assertEquals(process.waitFor(), 0, s"docker ${args.mkString(" ")} failed:\n$output")

  /**
   * The module images: the Rust conformance reference (cargo, then the example's Dockerfile), a
   * module exporting ABI version 2, and an image whose copy fails. The last two are assembled here,
   * from WebAssembly text and a one-line Dockerfile, so they need no toolchain.
   */
  private def buildModuleImages(): Unit =
    val rust = repositoryRoot.resolve("sdks/rust")
    rustBuilt =
      try
        new ProcessBuilder(
          "cargo",
          "build",
          "-p",
          "shopping-cart",
          "--release",
          "--target",
          "wasm32-unknown-unknown",
          "--features",
          "conformance"
        ).directory(rust.toFile).inheritIO().start().waitFor() == 0
      catch case _: java.io.IOException => false
    if rustBuilt then
      docker(rust, "build", "-q", "-f", "examples/shopping-cart/Dockerfile", "-t", RustImage, ".")
    // In CI the runner was given cargo to run these cases, so a module that did not build is a
    // failure: skipped, every wasm case would report green having run nothing.
    else if sys.env.contains("CI") then
      fail("the Rust module did not build (is cargo, with wasm32-unknown-unknown, on PATH?)")
    else
      println("SidecarClusterSuite: the Rust module did not build; the wasm service's cases skip")

    val wrong = Files.createTempDirectory("wrong-abi")
    Files.write(
      wrong.resolve("service.wasm"),
      com.dylibso.chicory.wabt.Wat2Wasm.parse(
        """(module (memory (export "memory") 1)
          |  (func (export "ankka1_alloc") (param i32) (result i32) i32.const 0)
          |  (func (export "ankka1_free") (param i32 i32))
          |  (func (export "ankka1_discover") (param i32 i32) (result i64) i64.const 0)
          |  (func (export "ankka2_discover") (param i32 i32) (result i64) i64.const 0))""".stripMargin
      )
    )
    Files.writeString(
      wrong.resolve("Dockerfile"),
      "FROM busybox:1.37\nCOPY service.wasm /service.wasm\nCMD [\"cp\", \"/service.wasm\", \"/ankka/module/service.wasm\"]\n"
    )
    docker(wrong, "build", "-q", "-t", WrongAbiImage, ".")
    val noCopy = Files.createTempDirectory("no-copy")
    Files.writeString(noCopy.resolve("Dockerfile"), "FROM busybox:1.37\nCMD [\"false\"]\n")
    docker(noCopy, "build", "-q", "-t", NoCopyImage, ".")

  /**
   * The sample's image with one more module: a service whose only endpoint is `AUTHENTICATED` and
   * answers what the principal carried. Assembled here, like the module images, so the sample
   * itself never needs an issuer to start.
   */
  private def buildAuthImage(): Unit =
    val dir = Files.createTempDirectory("auth-probe")
    Files.writeString(
      dir.resolve("auth_probe.py"),
      """import asyncio
        |import json
        |
        |from ankka import Acl, Ankka, Endpoint, get
        |
        |
        |class Me(Endpoint):
        |    prefix = "/me"
        |    acl = Acl.AUTHENTICATED
        |
        |    @get("/")
        |    def me(self) -> str:
        |        p = self.request.principal
        |        assert p is not None
        |        return json.dumps({"subject": p.subject, "issuer": p.issuer, "tier": p.claims.get("tier")})
        |
        |
        |if __name__ == "__main__":
        |    asyncio.run(Ankka.service().register(Me).listen())
        |""".stripMargin
    )
    Files.writeString(
      dir.resolve("Dockerfile"),
      s"FROM $PythonImage\nCOPY auth_probe.py /app/examples/auth_probe.py\n" +
        "CMD [\"python\", \"-m\", \"examples.auth_probe\"]\n"
    )
    docker(dir, "build", "-q", "-t", AuthImage, ".")

  /**
   * The test issuer's published keys, served inside the cluster from a ConfigMap by busybox's web
   * server: what a real identity provider's keys URL is to a service, and the only way a pod can
   * reach keys the suite holds.
   */
  private def deployKeys(): Unit =
    val jwks = scala.io.Source.fromURL(authIssuer.jwksUrl).mkString
    val configMap = new io.fabric8.kubernetes.api.model.ConfigMapBuilder()
      .withMetadata(new ObjectMetaBuilder().withName("jwks").withNamespace(Namespace).build())
      .withData(Map("jwks" -> jwks).asJava)
      .build()
    k8s.configMaps().inNamespace(Namespace).resource(configMap).serverSideApply(): Unit
    val manifest = s"""
apiVersion: apps/v1
kind: Deployment
metadata: { name: jwks, namespace: $Namespace }
spec:
  replicas: 1
  selector: { matchLabels: { app: jwks } }
  template:
    metadata: { labels: { app: jwks } }
    spec:
      containers:
        - name: httpd
          image: $KeysImage
          imagePullPolicy: IfNotPresent
          command: [ httpd, -f, -p, "8080", -h, /www ]
          ports: [ { containerPort: 8080 } ]
          volumeMounts: [ { name: jwks, mountPath: /www } ]
          readinessProbe: { tcpSocket: { port: 8080 }, periodSeconds: 2 }
      volumes: [ { name: jwks, configMap: { name: jwks } } ]
---
apiVersion: v1
kind: Service
metadata: { name: jwks, namespace: $Namespace }
spec:
  selector: { app: jwks }
  ports: [ { port: 8080, targetPort: 8080 } ]
"""
    k8s.load(new java.io.ByteArrayInputStream(manifest.getBytes)).serverSideApply(): Unit
    waitFor(120.seconds) {
      val d = k8s.apps().deployments().inNamespace(Namespace).withName("jwks").get()
      d != null && Option(d.getStatus).flatMap(s => Option(s.getReadyReplicas)).exists(_ > 0)
    }

  /** A plain Postgres with the platform's DDL, as the service's supplied database. */
  private def deployPostgres(name: String = "postgres"): Unit =
    val ddl =
      Vector(
        "10-journal-postgres.sql",
        "20-projection-postgres.sql",
        "30-timers-postgres.sql",
        "40-secrets-postgres.sql"
      ).map(n => n -> new String(getClass.getResourceAsStream(s"/ankka/ddl/$n").readAllBytes()))
    val configMap = new io.fabric8.kubernetes.api.model.ConfigMapBuilder()
      .withMetadata(
        new ObjectMetaBuilder().withName(s"$name-ddl").withNamespace(Namespace).build()
      )
      .withData(ddl.toMap.asJava)
      .build()
    k8s.configMaps().inNamespace(Namespace).resource(configMap).serverSideApply(): Unit
    val manifest = s"""
apiVersion: apps/v1
kind: Deployment
metadata: { name: $name, namespace: $Namespace }
spec:
  replicas: 1
  selector: { matchLabels: { app: $name } }
  template:
    metadata: { labels: { app: $name } }
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
      volumes: [ { name: ddl, configMap: { name: $name-ddl } } ]
---
apiVersion: v1
kind: Service
metadata: { name: $name, namespace: $Namespace }
spec:
  selector: { app: $name }
  ports: [ { port: 5432, targetPort: 5432 } ]
"""
    k8s.load(new java.io.ByteArrayInputStream(manifest.getBytes)).serverSideApply(): Unit
    waitFor(180.seconds) {
      val d = k8s.apps().deployments().inNamespace(Namespace).withName(name).get()
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

  /**
   * From inside a ready pod's sidecar — which holds the service's certificate, as every port is
   * mutual TLS and admits only workloads with a platform identity — to the Service's name, so the
   * request still takes Service → endpoints → pod. 0 for a 2xx, as `wget` answered.
   */
  /** See `InPod.prober`: the one place requests are made from, whatever the service's pods do. */
  private lazy val prober: String =
    com.thinkmorestupidless.ankka.operator.InPod.prober(k3s, Namespace, Service)

  private def nodeHttp(path: String, post: Option[String] = None): (Int, String) =
    val (code, body) = com.thinkmorestupidless.ankka.operator.InPod.curl(
      k3s,
      Namespace,
      prober,
      s"https://$Service.$Namespace.svc.cluster.local:9000$path",
      method = if post.isDefined then "POST" else "GET",
      body = post
    )
    (if code / 100 == 2 then 0 else 1, s"$code $body")

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

  // ── feature 022: a process-hosted service's users' tokens, on a real pod ────

  private def authSpec(listsIssuer: Boolean): AnkkaServiceSpec =
    val database = List(
      EnvEntry("ANKKA_DB_HOST", Some(s"postgres-accounts.$Namespace.svc"), None, None),
      EnvEntry("ANKKA_DB_PORT", Some("5432"), None, None),
      EnvEntry("ANKKA_DB_NAME", Some("ankka"), None, None),
      EnvEntry("ANKKA_DB_USER", Some("ankka"), None, None),
      EnvEntry("ANKKA_DB_PASSWORD", Some("ankka"), None, None)
    )
    val issuers = List(
      EnvEntry("ANKKA_AUTH_ISSUERS", Some("staff"), None, None),
      EnvEntry("ANKKA_AUTH_STAFF_ISSUER", Some(authIssuer.issuer), None, None),
      EnvEntry(
        "ANKKA_AUTH_STAFF_JWKS_URL",
        Some(s"http://jwks.$Namespace.svc.cluster.local:8080/jwks"),
        None,
        None
      ),
      EnvEntry("ANKKA_AUTH_STAFF_AUDIENCE", Some("accounts"), None, None)
    )
    spec().copy(
      serviceName = if listsIssuer then AuthService else UnlistedService,
      image = AuthImage,
      env = database ++ (if listsIssuer then issuers else Nil)
    )

  private def authHttp(token: Option[String]): (Int, String) =
    com.thinkmorestupidless.ankka.operator.InPod.curl(
      k3s,
      Namespace,
      prober,
      s"https://$AuthService.$Namespace.svc.cluster.local:9000/me/",
      headers = token.toSeq.map(t => s"Authorization: Bearer $t")
    )

  test("an authenticated route of a process-hosted service verifies tokens on a real pod") {
    applyAs(AuthService, authSpec(listsIssuer = true))
    // Ready at all is the first proof: a sidecar that did not receive the issuers refuses the
    // route in discovery and never becomes ready.
    waitFor(300.seconds)(readyReplicasOf(AuthService) >= 1)
    val pod             = podsOf(AuthService).head.getMetadata.getName
    val (_, sidecarEnv) = kubectl("exec", "-n", Namespace, pod, "-c", AuthService, "--", "env")
    val (_, appEnv) =
      kubectl("exec", "-n", Namespace, pod, "-c", s"$AuthService-app", "--", "env")
    assert(sidecarEnv.contains("ANKKA_AUTH_ISSUERS=staff"), sidecarEnv)
    assert(!appEnv.contains("ANKKA_AUTH_"), appEnv)

    assertEquals(authHttp(None)._1, 401, "a request with no token is challenged")
    val expired = authIssuer.token("ada", expiresIn = (-5).minutes)
    assertEquals(authHttp(Some(expired))._1, 401, "an expired token is challenged")
    // The keys are fetched on the first verification, from inside the cluster.
    val (code, body) =
      authHttp(Some(authIssuer.token("ada", claims = Map("tier" -> "gold"))))
    assertEquals(code, 200, body)
    assert(body.contains("\"subject\": \"ada\""), body)
    assert(body.contains("\"issuer\": \"staff\""), body)
    assert(body.contains("\"tier\": \"gold\""), body)
  }

  test("a process-hosted service with an authenticated route and no issuer listed does not start") {
    applyAs(UnlistedService, authSpec(listsIssuer = false))
    def logs: String =
      podsOf(UnlistedService).map { p =>
        val name    = p.getMetadata.getName
        val current = kubectl("logs", "-n", Namespace, name, "-c", UnlistedService)._2
        val previous =
          kubectl("logs", "-n", Namespace, name, "-c", UnlistedService, "--previous")._2
        current + previous
      }.mkString
    waitFor(240.seconds)(logs.contains("AUTHENTICATED but no issuer is configured"))
    assert(logs.contains("ANKKA_AUTH_ISSUERS"), logs)
    assertEquals(readyReplicasOf(UnlistedService), 0)
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
      // A platform workload's label, so the network admits it to the HTTP port: what is proved
      // below is that the loopback protocol ports stay closed even to a pod the network admits.
      "--labels=app.kubernetes.io/managed-by=ankka",
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

  // ── a wasm service ─────────────────────────────────────────────────────────

  private def wasmSpec(
      name: String,
      image: String,
      instances: Int = 1,
      restarts: Int = 0,
      deadline: Int = 600
  ): AnkkaServiceSpec =
    AnkkaServiceSpec(
      projectId = Project,
      serviceName = name,
      generation = 1L,
      image = image,
      hosting = "wasm",
      port = Some(9000),
      env = List(
        EnvEntry("ANKKA_DB_HOST", Some(s"postgres-rust.$Namespace.svc"), None, None),
        EnvEntry("ANKKA_DB_PORT", Some("5432"), None, None),
        EnvEntry("ANKKA_DB_NAME", Some("ankka"), None, None),
        EnvEntry("ANKKA_DB_USER", Some("ankka"), None, None),
        EnvEntry("ANKKA_DB_PASSWORD", Some("ankka"), None, None),
        EnvEntry("GREETING", Some("hello from the descriptor"), None, None)
      ),
      provisionDatabase = false,
      autoscaling = AutoscalingSpec(minInstances = instances, maxInstances = instances),
      restarts = restarts,
      progressDeadlineSeconds = deadline
    )

  private def applyAs(name: String, s: AnkkaServiceSpec): Unit =
    val r = new AnkkaService
    r.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(Namespace).build())
    r.setSpec(s)
    resources.resource(r).serverSideApply(): Unit

  private def statusOf(name: String) =
    Option(resources.withName(name).get()).flatMap(r => Option(r.getStatus))

  private def podsOf(name: String) =
    k8s
      .pods()
      .inNamespace(Namespace)
      .withLabel("ankka.thinkmorestupidless.com/service", name)
      .list()
      .getItems
      .asScala
      .toVector

  private def readyReplicasOf(name: String): Int =
    Option(k8s.apps().deployments().inNamespace(Namespace).withName(name).get())
      .flatMap(d => Option(d.getStatus))
      .flatMap(s => Option(s.getReadyReplicas))
      .map(_.intValue)
      .getOrElse(0)

  private def rustHttp(path: String, post: Option[String] = None): (Int, String) =
    val (code, body) = com.thinkmorestupidless.ankka.operator.InPod.curl(
      k3s,
      Namespace,
      prober,
      s"https://$RustService.$Namespace.svc.cluster.local:9000$path",
      method = if post.isDefined then "POST" else "GET",
      body = post
    )
    (code, body)

  private def onlyWithRust(): Unit = assume(rustBuilt, "cargo is not on PATH")

  test("wasm: the descriptor becomes one container with the module copied in, Ready, and serving") {
    onlyWithRust()
    // The process-hosted cases are done: their service leaves the node, which runs every JVM of
    // both services otherwise, and answers in seconds rather than milliseconds when it does.
    resources.withName(Service).delete(): Unit
    waitFor(180.seconds)(pods.isEmpty)
    applyAs(RustService, wasmSpec(RustService, RustImage))
    waitFor(300.seconds)(readyReplicasOf(RustService) >= 1)
    val pod = podsOf(RustService).head.getSpec
    assertEquals(pod.getContainers.asScala.map(_.getImage).toVector, Vector(SidecarImage))
    assert(
      pod.getInitContainers.asScala.map(_.getImage).contains(RustImage),
      pod.getInitContainers.toString
    )
    waitFor(60.seconds)(statusOf(RustService).exists(_.lifecycle == "Ready"))
    val (added, addBody) =
      rustHttp("/carts/r1/items", Some("""{"productId":"p1","name":"Pen","quantity":2}"""))
    assertEquals(added / 100, 2, addBody)
    val (code, body) = rustHttp("/carts/r1")
    assertEquals(code, 200, body)
    assert(body.contains("\"productId\":\"p1\""), body)
  }

  test("wasm: the module reads the descriptor's variables, and none of the platform's") {
    onlyWithRust()
    val (secret, secretBody) = rustHttp("/conformance/config/ANKKA_DB_PASSWORD")
    assertEquals(secret, 404, s"a reserved variable reached the module: $secretBody")
    val (greeting, greetingBody) = rustHttp("/conformance/config/GREETING")
    assertEquals(greeting, 200, greetingBody)
    assert(greetingBody.contains("hello from the descriptor"), greetingBody)
  }

  test("wasm: scaling 1→3 leaves the first pod, and a restart refuses no request") {
    onlyWithRust()
    val first = podsOf(RustService).head.getMetadata.getName
    applyAs(RustService, wasmSpec(RustService, RustImage, instances = 3))
    waitFor(300.seconds)(readyReplicasOf(RustService) == 3)
    assert(podsOf(RustService).exists(_.getMetadata.getName == first), "the first pod was replaced")

    val before = podsOf(RustService).map(_.getMetadata.getName).toSet
    applyAs(RustService, wasmSpec(RustService, RustImage, instances = 3, restarts = 1))
    var refused  = 0
    var requests = 0
    val deadline = System.nanoTime() + 300.seconds.toNanos
    while podsOf(RustService)
        .exists(p => before.contains(p.getMetadata.getName)) && System.nanoTime() < deadline
    do
      if rustHttp("/carts/r1")._1 != 200 then refused += 1
      requests += 1
    waitFor(120.seconds)(readyReplicasOf(RustService) == 3 && podsOf(RustService).forall(readyOf))
    assert(
      requests > 5,
      s"the rollout finished before the loop measured anything ($requests requests)"
    )
    assertEquals(refused, 0, s"$refused of $requests requests were refused during the rollout")
  }

  test("wasm: a module of another ABI version fails the service, naming the versions") {
    applyAs("wrong-abi", wasmSpec("wrong-abi", WrongAbiImage, deadline = 120))
    // Failed may be reported first for a failed readiness probe, before the container has exited
    // often enough to be backing off; the reason that names the versions follows.
    def detail = statusOf("wrong-abi").flatMap(_.detail).getOrElse("")
    waitFor(300.seconds)(
      statusOf("wrong-abi").exists(_.lifecycle == "Failed") && detail.contains("ankka2_discover")
    )
    assert(detail.contains("version 2"), detail)
    resources.withName("wrong-abi").delete(): Unit
  }

  test("wasm: a module image whose copy fails is the service's reported failure") {
    applyAs("no-copy", wasmSpec("no-copy", NoCopyImage, deadline = 120))
    waitFor(300.seconds)(statusOf("no-copy").exists(_.lifecycle == "Failed"))
    val detail = statusOf("no-copy").flatMap(_.detail).getOrElse("")
    assert(detail.contains("InitContainerFailed") || detail.contains("init container"), detail)
    resources.withName("no-copy").delete(): Unit
  }
