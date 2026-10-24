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
  private val PythonImage  =
    sys.props.getOrElse("ankka.python.image", s"sample-shopping-cart-python:${BuildInfo.imageTag}")

  /**
   * The Rust reference module's image, built here when cargo is present; its cases skip otherwise.
   */
  private val RustImage     = s"sample-shopping-cart-rust:${BuildInfo.imageTag}"
  private val WrongAbiImage = s"ankka-wasm-wrong-abi:${BuildInfo.imageTag}"
  private val NoCopyImage   = s"ankka-wasm-no-copy:${BuildInfo.imageTag}"
  private val RustService   = "rust-cart"
  private var rustBuilt     = false

  /**
   * The Go shopping cart, built against the SDK released at `GoSdkVersion` from a file proxy (see
   * `buildGoImage`), so the version its build records is one a developer's would.
   */
  private val GoImage      = s"sample-shopping-cart-go:${BuildInfo.imageTag}"
  private val GoService    = "go-cart"
  private val GoSdkVersion = "1.4.0"
  private val Prefix       = "ankka"
  private val Project      = "checkout"
  private val Namespace    = s"$Prefix-$Project"
  private val Service      = "cart"

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

  private var k3s: K3sContainer                                                        = null
  private var k8s: KubernetesClient                                                    = null
  private var operator: Operator                                                       = null
  private var store: com.thinkmorestupidless.ankka.operator.ObjectStoreStack.Installed = null

  private val settings =
    OperatorSettings.default.copy(
      resyncInterval = 2.seconds,
      sidecarImage = SidecarImage,
      // Every service here exports to the platform's collector, with no telemetry in its code.
      otlpEndpoint = Some(com.thinkmorestupidless.ankka.operator.CollectorStack.endpoint)
    )

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
      buildGoImage()

      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.start()
      ClusterImages.importInto(k3s, SidecarImage)
      ClusterImages.importInto(k3s, PythonImage)
      ClusterImages.importInto(k3s, AuthImage)
      ClusterImages.importInto(k3s, GoImage)
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
      com.thinkmorestupidless.ankka.operator.CollectorStack.install(k3s, k8s)
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

      // The installation's object store (feature 034), for the hostings' object storage cases.
      store =
        com.thinkmorestupidless.ankka.operator.ObjectStoreStack.install(k3s, k8s, repositoryRoot)
      val withStore = settings.copy(objectStore = Some(store.settings))
      operator = new Operator(k8s, withStore, ServiceReconciler(k8s, withStore))
      operator.start()

  // The authentication cases' services are used by no other case, and one of them never starts on
  // purpose: left running, it restarted a JVM every few minutes through every case after it, on a
  // node whose CPU the rollout and wasm cases need. Gone after each case, whichever it was.
  override def afterEach(context: AfterEach): Unit =
    if !munitIgnore && k8s != null then
      Vector(AuthService, UnlistedService).foreach(name =>
        scala.util.Try(resources.withName(name).delete()): Unit
      )
    super.afterEach(context)

  override def afterAll(): Unit =
    if k3s != null then authIssuer.stop()
    if operator != null then operator.close()
    if store != null then store.close()
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
      // Every pod in the namespace, not only the cart's: a case waiting on another service (the
      // wasm cases' `rust-cart`) printed nothing at all.
      k8s
        .pods()
        .inNamespace(Namespace)
        .list()
        .getItems
        .asScala
        .toSeq
        .map { p =>
          val name     = p.getMetadata.getName
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
   * The Go example's image, built entirely inside Docker (no Go on the host). The context holds the
   * SDK's sources as `scripts/image.sh` stages them, the example, and `go-released/`'s Dockerfile
   * and helper, which publish the SDK into a file proxy at `GoSdkVersion` and build the example
   * against that release rather than through its `replace`: a replaced dependency records no
   * version, so the image `image.sh` builds always says 0.0.0, and the scenario is about the
   * version it was built with.
   */
  private def buildGoImage(): Unit =
    val sdk                              = repositoryRoot.resolve("sdks/go")
    val example                          = sdk.resolve("examples/shopping-cart")
    val context                          = Files.createTempDirectory("go-cart")
    def copy(from: Path, to: Path): Unit =
      Files.createDirectories(to.getParent)
      Files.copy(from, to): Unit
    def files(dir: Path, deep: Boolean): Vector[Path] =
      val stream = if deep then Files.walk(dir) else Files.list(dir)
      try stream.iterator.asScala.filter(Files.isRegularFile(_)).toVector
      finally stream.close()
    def source(p: Path) =
      val name = p.getFileName.toString
      name.endsWith(".go") && !name.endsWith("_test.go")
    Vector("go.mod", "go.sum").foreach(n => copy(sdk.resolve(n), context.resolve(s"sdk/$n")))
    (files(sdk, deep = false) ++ files(sdk.resolve("internal"), deep = true))
      .filter(source)
      .foreach(p => copy(p, context.resolve("sdk").resolve(sdk.relativize(p).toString)))
    files(example, deep = true)
      .filterNot(p => p.toString.endsWith(".test") || p.toString.endsWith(".out"))
      .foreach(p => copy(p, context.resolve("example").resolve(example.relativize(p).toString)))
    val released = repositoryRoot.resolve("sidecar/src/test/resources/go-released")
    files(released, deep = true)
      .foreach(p => copy(p, context.resolve(released.relativize(p).toString)))
    docker(
      context,
      "build",
      "-q",
      "--build-arg",
      s"SDK_VERSION=$GoSdkVersion",
      "-t",
      GoImage,
      "."
    )

  /**
   * The test issuer's published keys, served inside the cluster from a ConfigMap by busybox's web
   * server: what a real identity provider's keys URL is to a service, and the only way a pod can
   * reach keys the suite holds.
   */
  private def deployKeys(): Unit =
    val jwks      = scala.io.Source.fromURL(authIssuer.jwksUrl).mkString
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
        "40-secrets-postgres.sql",
        "50-recovery-postgres.sql"
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

  /** Every pod in the namespace, whatever service it is, with why each container is not running. */
  private def podSummary(): String =
    k8s
      .pods()
      .inNamespace(Namespace)
      .list()
      .getItems
      .asScala
      .toVector
      .map { p =>
        val waiting = Option(p.getStatus.getContainerStatuses).toVector
          .flatMap(_.asScala)
          .flatMap(c =>
            Option(c.getState.getWaiting)
              .map(w => s"${c.getName}:${w.getReason}")
              .orElse(
                Option(c.getState.getTerminated).map(t => s"${c.getName}:exited ${t.getExitCode}")
              )
          )
        s"${p.getMetadata.getName}=${p.getStatus.getPhase}/${readyOf(p)}${waiting.mkString("[", ",", "]")}"
      }
      .mkString(", ")

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

  /**
   * The rollout cases' limit: longer than `ankka.ask-timeout` (10s), so a request the platform
   * gives up on shows its own answer rather than curl's cut-off at the same moment.
   */
  private val DiagnoseSeconds = 15

  private def nodeHttp(
      path: String,
      post: Option[String] = None,
      diagnose: Boolean = false
  ): (Int, String) =
    val (code, body) = com.thinkmorestupidless.ankka.operator.InPod.curl(
      k3s,
      Namespace,
      prober,
      s"https://$Service.$Namespace.svc.cluster.local:9000$path",
      method = if post.isDefined then "POST" else "GET",
      body = post,
      maxSeconds = if diagnose then DiagnoseSeconds else 10,
      timings = diagnose
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

  test(
    "a service exports whatever language it is written in, with no change to its code (Python)"
  ) {
    // S2.1 made requests of the Python cart; the sidecar beside it recorded and exported them.
    com.thinkmorestupidless.ankka.operator.CollectorStack
      .waitFor(k3s, s"a span of the Python $Service") { seen =>
        seen.exists(_.service == Service)
      }: Unit
    // The address is the sidecar's, never the process's.
    val containers        = pods.head.getSpec.getContainers.asScala
    def has(name: String) =
      containers
        .find(_.getName == name)
        .exists(_.getEnv.asScala.exists(_.getName == "ANKKA_OTLP_ENDPOINT"))
    assert(has(Service), "the sidecar has no collector")
    assert(!has(s"$Service-app"), "the process was given the collector's address")
  }

  test("the credential and the database reach the sidecar only; the process knows how to find it") {
    val pod             = pods.head
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
    spec().copy(
      serviceName = if listsIssuer then AuthService else UnlistedService,
      image = AuthImage,
      env = database ++ (if listsIssuer then issuerEnv else Nil)
    )

  /**
   * The issuer a service with an authenticated route names, keyed by the suite's test issuer and
   * served in the cluster by `deployKeys`. A runtime refuses to host an authenticated endpoint with
   * no issuer, so every service whose code declares one needs these: the accounts service and the
   * Rust module, whose conformance build has a `PrivateEndpoint`.
   */
  private lazy val issuerEnv = List(
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
    val (_, appEnv)     =
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
        val name     = p.getMetadata.getName
        val current  = kubectl("logs", "-n", Namespace, name, "-c", UnlistedService)._2
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
    val (_, id)  = nodeExec("crictl", "ps", "-q", "--name", s"$Service-app")
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

  /**
   * Follows the logs of every pod of `service`, each container, from now until the suite ends, into
   * files on the node: the pods a rollout replaces are deleted with their logs, and a refused
   * request's cause may be on one of them. Started before the rollout; a pod that appears during it
   * is picked up within a second.
   */
  private def followLogs(service: String): Unit =
    val dir    = s"/tmp/rollout-logs/$service"
    val script =
      s"""mkdir -p $dir
         |while true; do
         |  for p in $$(kubectl -n $Namespace get pods -o name | grep -E '^pod/$service-[a-z0-9]+-[a-z0-9]+$$'); do
         |    f=$dir/$${p#pod/}.log
         |    [ -f "$$f" ] || (kubectl -n $Namespace logs -f --timestamps --prefix --all-containers "$$p" > "$$f" 2>&1 &)
         |  done
         |  sleep 1
         |done""".stripMargin
    // Detached, with nothing inherited: an exec returns only once every holder of its output closes.
    nodeExec("sh", "-c", s"( $script ) > /dev/null 2>&1 < /dev/null &"): Unit

  /**
   * Where a refused request's ten seconds went: every trace in which a span of `service` took nine
   * seconds or more, span by span; every span of the service that began inside that window,
   * whatever its trace; one healthy trace to compare with; and what every pod of the service logged
   * over the window, the pods the rollout deleted included. `before` names the pods the rollout
   * replaced.
   */
  private def rolloutReport(service: String, before: Set[String]): String =
    import com.thinkmorestupidless.ankka.operator.{CollectorLog, CollectorStack}
    def slow(seen: Vector[CollectorLog.Span]) =
      seen.filter(s => s.service == service && s.durationMillis >= 9000)
    val deadline = 30.seconds.fromNow
    var seen     = CollectorStack.spans(k3s)
    while slow(seen).isEmpty && deadline.hasTimeLeft() do
      Thread.sleep(2000)
      seen = CollectorStack.spans(k3s)
    val mine                                 = seen.filter(_.service == service)
    def age(instance: String)                = if before.contains(instance) then "old" else "new"
    def line(s: CollectorLog.Span, t0: Long) =
      val a = s.attributes
      s"  +${(s.startNanos - t0) / 1000000}ms ${s.durationMillis}ms ${s.instance}(${age(s.instance)}) " +
        s"${s.kind} ${s.name} id=${s.spanId} parent=${s.parentId} trace=${s.traceId.take(8)} " +
        s"${a.getOrElse("ankka.component", "")}/${a.getOrElse("ankka.handler", "")} " +
        s"outcome=${a.getOrElse("ankka.outcome", "")}"
    val slowSpans = slow(seen).sortBy(_.startNanos).take(3)
    if slowSpans.isEmpty then return "\nno span of the service took nine seconds or more"
    val healthy = mine
      .groupBy(_.traceId)
      .values
      .find(t => t.size > 1 && t.forall(_.durationMillis < 1000))
      .map(t => t.sortBy(_.startNanos))
    val healthyText = healthy.fold("\nno healthy trace with more than one span") { t =>
      t.map(line(_, t.head.startNanos)).mkString("\na healthy trace:\n", "\n", "")
    }
    val windows = slowSpans.map { slowSpan =>
      val t0     = slowSpan.startNanos
      val tEnd   = t0 + slowSpan.durationMillis * 1000000L
      val traced = seen.filter(_.traceId == slowSpan.traceId).sortBy(_.startNanos)
      val during = mine
        .filter(s =>
          s.traceId != slowSpan.traceId && s.startNanos >= t0 - 1000000000L && s.startNanos <= tEnd
        )
        .sortBy(_.startNanos)
      val logs = logsBetween(service, t0 - 2000000000L, tEnd + 2000000000L)
      traced.map(line(_, t0)).mkString(s"\ntrace ${slowSpan.traceId}:\n", "\n", "") +
        s"\nspans of $service in other traces from 1s before it until it ended (${during.size}):\n" +
        during.take(60).map(line(_, t0)).mkString("\n") +
        s"\nlogs of $service from 2s before it until 2s after it ended:\n" + logs
    }
    windows.mkString + healthyText

  /** Lines that `followLogs` kept for `service` stamped between the two instants, at most 300. */
  private def logsBetween(service: String, fromNanos: Long, toNanos: Long): String =
    val (_, out) = nodeExec("sh", "-c", s"cat /tmp/rollout-logs/$service/*.log 2>/dev/null")
    val Stamp    = """.*?(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z).*""".r
    def nanosOf(line: String): Option[Long] = line match
      case Stamp(t) =>
        scala.util
          .Try(java.time.Instant.parse(t))
          .toOption
          .map(i => i.getEpochSecond * 1000000000L + i.getNano)
      case _ => None
    val kept = out.linesIterator
      .flatMap(l => nanosOf(l).map(_ -> l))
      .filter((t, _) => t >= fromNanos && t <= toNanos)
      .toVector
      .sortBy(_._1)
      .map(_._2)
    if kept.isEmpty then "  (none)"
    else
      (if kept.size > 300 then kept.take(300) :+ s"  … ${kept.size - 300} more" else kept)
        .mkString("\n")

  test("S2.3 a restart replaces pods one at a time with no refused request") {
    val before = pods.map(_.getMetadata.getName).toSet
    followLogs(Service)
    apply(spec(instances = 3, restarts = 1))
    var refused  = Vector.empty[String]
    var requests = 0
    val deadline = System.nanoTime() + 300.seconds.toNanos
    while pods.exists(p => before.contains(p.getMetadata.getName)) && System.nanoTime() < deadline
    do
      val (code, body) = nodeHttp("/carts/c1", diagnose = true)
      requests += 1
      if code != 0 then refused :+= body
    waitFor(120.seconds)(readyReplicas == 3 && pods.forall(readyOf))
    assert(
      requests > 5,
      s"the rollout finished before the loop measured anything ($requests requests)"
    )
    assert(
      refused.isEmpty,
      s"${refused.size} of $requests requests were refused during the rollout: ${refused
          .mkString(" | ")}${if refused.isEmpty then "" else rolloutReport(Service, before)}"
    )
  }

  test("SC-008 the protocol ports are unreachable from another pod in the namespace") {
    val podIp  = pods.head.getStatus.getPodIP
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
        EnvEntry("GREETING", Some("hello from the descriptor"), None, None),
        // Has the reference register what the suite drives to see it call another service.
        EnvEntry("ANKKA_CONFORMANCE_CALLS", Some("on"), None, None)
      ) ++ issuerEnv,
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

  private def rustHttp(
      path: String,
      post: Option[String] = None,
      diagnose: Boolean = false
  ): (Int, String) =
    val (code, body) = com.thinkmorestupidless.ankka.operator.InPod.curl(
      k3s,
      Namespace,
      prober,
      s"https://$RustService.$Namespace.svc.cluster.local:9000$path",
      method = if post.isDefined then "POST" else "GET",
      body = post,
      maxSeconds = if diagnose then DiagnoseSeconds else 10,
      timings = diagnose
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

  test("a service exports whatever language it is written in, with no change to its code (Rust)") {
    onlyWithRust()
    com.thinkmorestupidless.ankka.operator.CollectorStack
      .waitFor(k3s, s"a span of the Rust $RustService") { seen =>
        seen.exists(_.service == RustService)
      }: Unit
  }

  test("wasm: the module reads the descriptor's variables, and none of the platform's") {
    onlyWithRust()
    val (secret, secretBody) = rustHttp("/conformance/config/ANKKA_DB_PASSWORD")
    assertEquals(secret, 404, s"a reserved variable reached the module: $secretBody")
    val (collector, collectorBody) = rustHttp("/conformance/config/ANKKA_OTLP_ENDPOINT")
    assertEquals(collector, 404, s"the collector's address reached the module: $collectorBody")
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
    followLogs(RustService)
    applyAs(RustService, wasmSpec(RustService, RustImage, instances = 3, restarts = 1))
    var refused  = Vector.empty[String]
    var requests = 0
    val deadline = System.nanoTime() + 300.seconds.toNanos
    while podsOf(RustService)
        .exists(p => before.contains(p.getMetadata.getName)) && System.nanoTime() < deadline
    do
      val (code, body) = rustHttp("/carts/r1", diagnose = true)
      if code != 200 then refused :+= s"$code $body"
      requests += 1
    waitFor(120.seconds)(readyReplicasOf(RustService) == 3 && podsOf(RustService).forall(readyOf))
    assert(
      requests > 5,
      s"the rollout finished before the loop measured anything ($requests requests)"
    )
    assert(
      refused.isEmpty,
      s"${refused.size} of $requests requests were refused during the rollout: ${refused
          .mkString(" | ")}${if refused.isEmpty then "" else rolloutReport(RustService, before)}"
    )
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

  // ── a process calls another service as itself (feature 025) ─────────────────

  private val SampleImage    = s"sample-shopping-cart:${BuildInfo.imageTag}"
  private val CartsService   = "carts"
  private val OrdersService  = "orders"
  private var callersStarted = false

  /** Each its own database: two services never share one. */
  private def database(name: String): List[EnvEntry] = List(
    EnvEntry("ANKKA_DB_HOST", Some(s"$name.$Namespace.svc"), None, None),
    EnvEntry("ANKKA_DB_PORT", Some("5432"), None, None),
    EnvEntry("ANKKA_DB_NAME", Some("ankka"), None, None),
    EnvEntry("ANKKA_DB_USER", Some("ankka"), None, None),
    EnvEntry("ANKKA_DB_PASSWORD", Some("ankka"), None, None)
  )

  /**
   * The Scala sample as `carts`, whose route `/callers/orders-alone` admits the service `orders` by
   * name and nobody else, and the Python sample a second time as `orders`, whose `/calling` route
   * calls another service as itself. Started once, by the first case that needs them.
   */
  private def startCallers(): Unit =
    if !callersStarted then
      ClusterImages.importInto(k3s, SampleImage)
      deployPostgres("postgres-carts")
      deployPostgres("postgres-orders")
      applyAs(
        CartsService,
        AnkkaServiceSpec(
          projectId = Project,
          serviceName = CartsService,
          generation = 1L,
          image = SampleImage,
          port = Some(9000),
          env = database("postgres-carts"),
          provisionDatabase = false,
          progressDeadlineSeconds = 300
        )
      )
      applyAs(
        OrdersService,
        spec().copy(serviceName = OrdersService, env = database("postgres-orders"))
      )
      waitFor(300.seconds)(
        readyReplicasOf(CartsService) >= 1 && readyReplicasOf(OrdersService) >= 1
      )
      callersStarted = true

  test(
    "a service in every language is admitted by name by a route that admits only it (Python, on a cluster)"
  ) {
    startCallers()
    // From the suite's prober, which holds the certificate of the service `cart`: the route admits
    // `orders` alone, so `cart` is refused, and the admission below is by name.
    val (refusedCode, refusedBody) = com.thinkmorestupidless.ankka.operator.InPod.curl(
      k3s,
      Namespace,
      prober,
      s"https://$CartsService.$Namespace.svc.cluster.local:9000/callers/orders-alone"
    )
    assertEquals(refusedCode, 403, refusedBody)
    // The Python service's own `/calling` route admits only itself, so it is asked by a prober
    // holding its certificate; the prober goes back to `cart`'s for the cases after this one.
    try
      val asOrders =
        com.thinkmorestupidless.ankka.operator.InPod.prober(k3s, Namespace, OrdersService)
      val (code, body) = com.thinkmorestupidless.ankka.operator.InPod.curl(
        k3s,
        Namespace,
        asOrders,
        s"https://$OrdersService.$Namespace.svc.cluster.local:9000" +
          "/calling/call/carts?path=/callers/orders-alone"
      )
      assertEquals((code, body), (200, s"admitted: $Project/$OrdersService"))
    finally com.thinkmorestupidless.ankka.operator.InPod.prober(k3s, Namespace, Service): Unit
  }

  test("a module's consumer is admitted by name by a route that admits only its service") {
    onlyWithRust()
    startCallers()
    val route = "/callers/rust-cart-alone"
    // From the suite's prober, which holds the certificate of the service `cart`: the route admits
    // the module's service alone, so `cart` is refused, and the admission below is by name.
    val (refusedCode, refusedBody) = com.thinkmorestupidless.ankka.operator.InPod.curl(
      k3s,
      Namespace,
      prober,
      s"https://$CartsService.$Namespace.svc.cluster.local:9000$route"
    )
    assertEquals(refusedCode, 403, refusedBody)
    // Asked of the module's entity: its consumer reads the event, calls `carts` through the
    // module's `request` import, and sends the entity what it was answered. The runtime made the
    // call with the module's service's certificate, which is all `carts` read.
    val (asked, askBody) = rustHttp(
      "/service-calls/asks/admitted-1/ask",
      Some(s"""{"service":"$CartsService","path":"$route"}""")
    )
    assertEquals(asked / 100, 2, askBody)
    def state = rustHttp("/service-calls/asks/admitted-1")._2
    waitFor(120.seconds)(state.contains(""""answers":[{"""))
    assert(
      state.contains(s"""{"status":200,"body":"admitted: $Project/$RustService"}"""),
      state
    )
  }

  test("the process of a service that calls other services holds no certificate") {
    startCallers()
    val pod        = podsOf(OrdersService).head.getMetadata.getName
    val (_, found) = kubectl(
      "exec",
      "-n",
      Namespace,
      pod,
      "-c",
      s"$OrdersService-app",
      "--",
      "sh",
      "-c",
      "find / -xdev \\( -name tls.key -o -name tls.crt -o -name ca.crt \\) 2>/dev/null; true"
    )
    assertEquals(found.trim, "", s"the process can read a certificate: $found")
    val (_, sidecarFound) = kubectl(
      "exec",
      "-n",
      Namespace,
      pod,
      "-c",
      OrdersService,
      "--",
      "sh",
      "-c",
      "ls /var/run/secrets/ankka/service"
    )
    // And the sidecar beside it does hold one, so the search above could have found something.
    assert(sidecarFound.contains("tls.key"), sidecarFound)
    val (_, appEnv) =
      kubectl("exec", "-n", Namespace, pod, "-c", s"$OrdersService-app", "--", "env")
    assert(!appEnv.contains("/var/run/secrets/ankka"), appEnv)
  }

  // ── a broker the descriptor names (features/broker/supplied.feature) ─────────────────────────
  //
  // The Python sample registers its graph consumers, which publish to `cart-graph`, only when it is
  // told of a broker. A service of its own, with a database of its own: two services sharing one
  // would delete each other's timers.

  private val GraphService = "graph-cart"

  private lazy val plainKafka: String =
    deployPostgres("graph-postgres")
    com.thinkmorestupidless.ankka.operator.PlainKafka.install(k3s, Namespace)

  private def graphSpec: AnkkaServiceSpec =
    spec().copy(
      serviceName = GraphService,
      env = List(
        EnvEntry("ANKKA_DB_HOST", Some(s"graph-postgres.$Namespace.svc"), None, None),
        EnvEntry("ANKKA_DB_PORT", Some("5432"), None, None),
        EnvEntry("ANKKA_DB_NAME", Some("ankka"), None, None),
        EnvEntry("ANKKA_DB_USER", Some("ankka"), None, None),
        EnvEntry("ANKKA_DB_PASSWORD", Some("ankka"), None, None),
        EnvEntry("ANKKA_KAFKA_BOOTSTRAP_SERVERS", Some(plainKafka), None, None)
      )
    )

  /** What a failure needs: the service's reported status, its pods, and both containers' logs. */
  private def graphDiagnosis(): String =
    val pods = kubectl("get", "pods", "-n", Namespace, "-o", "wide")._2
    val logs = podsOf(GraphService).headOption.toVector.flatMap { p =>
      Vector(GraphService, s"$GraphService-app").map { c =>
        s"── $c ──\n" + kubectl(
          "logs",
          "-n",
          Namespace,
          p.getMetadata.getName,
          "-c",
          c,
          "--tail",
          "40"
        )._2
      }
    }
    s"status: ${statusOf(GraphService)}\n$pods\n${logs.mkString("\n")}"

  test("a service hosted as a process is ready with the broker its descriptor names") {
    applyAs(GraphService, graphSpec)
    val deadline = 300.seconds.fromNow
    while !podsOf(GraphService).exists(readyOf) && deadline.hasTimeLeft() do Thread.sleep(2000)
    assert(podsOf(GraphService).exists(readyOf), graphDiagnosis())
    waitFor(60.seconds)(statusOf(GraphService).exists(_.lifecycle == "Ready"))
    // Both programs were told of the broker: the sidecar connects to it, and the process registered
    // the consumers that need one, which a sidecar with no broker would have refused to start.
    val pod = podsOf(GraphService).find(readyOf).get.getMetadata.getName
    for container <- Vector(GraphService, s"$GraphService-app") do
      val (_, env) = kubectl("exec", "-n", Namespace, pod, "-c", container, "--", "env")
      assert(env.contains(s"ANKKA_KAFKA_BOOTSTRAP_SERVERS=$plainKafka"), s"$container: $env")
  }

  test(
    "a consumer of a service hosted as a process publishes to the broker its descriptor names"
  ) {
    // A prober holding this service's own certificate, so the case runs alone too.
    val graphProber =
      com.thinkmorestupidless.ankka.operator.InPod.prober(k3s, Namespace, GraphService)
    val (code, body) = com.thinkmorestupidless.ankka.operator.InPod.curl(
      k3s,
      Namespace,
      graphProber,
      s"https://$GraphService.$Namespace.svc.cluster.local:9000/carts/graph-c1/items",
      method = "POST",
      body = Some("""{"productId":"graph-p1","name":"Pen","quantity":2}""")
    )
    assertEquals(code / 100, 2, body)
    var published = Vector.empty[String]
    waitFor(120.seconds) {
      published = com.thinkmorestupidless.ankka.operator.PlainKafka
        .read(k3s, Namespace, "cart-graph", max = 10, wait = 10.seconds)
      published.exists(_.contains("graph-c1"))
    }
    assert(published.exists(_.contains("graph-c1")), published.mkString("\n"))
    resources.withName(GraphService).delete(): Unit
  }

  // ── features/object-storage/hostings.feature ─────────────────────────────

  test("the variables of a bucket are given to the process and not to the platform's own program") {
    val name = "reports"
    applyAs(name, spec().copy(serviceName = name, provisionObjectStorage = true))
    waitFor(300.seconds)(readyReplicasOf(name) >= 1)
    val pod             = podsOf(name).head.getMetadata.getName
    val (_, sidecarEnv) = kubectl("exec", "-n", Namespace, pod, "-c", name, "--", "env")
    val (_, appEnv)     = kubectl("exec", "-n", Namespace, pod, "-c", s"$name-app", "--", "env")
    for variable <- Vector(
        "ANKKA_S3_ENDPOINT",
        "ANKKA_S3_REGION",
        "ANKKA_S3_BUCKET",
        "ANKKA_S3_ACCESS_KEY",
        "ANKKA_S3_SECRET_KEY"
      )
    do assert(appEnv.linesIterator.exists(_.startsWith(s"$variable=")), s"$variable: $appEnv")
    assert(appEnv.contains(s"ANKKA_S3_BUCKET=$Project.$name"), appEnv)
    assert(!sidecarEnv.contains("ANKKA_S3_"), sidecarEnv)
    resources.withName(name).delete(): Unit
  }

  test("a module that asks for a variable of its bucket is told its value") {
    onlyWithRust()
    applyAs(
      RustService,
      wasmSpec(RustService, RustImage, instances = 1, restarts = 2)
        .copy(provisionObjectStorage = true)
    )
    // The pod template changes, so the instances roll; the new ones answer the bucket's name. Asked
    // from inside the module's own pod, with its own certificate, so the case needs no other service.
    var lastAnswer = ""
    try
      waitFor(300.seconds) {
        podsOf(RustService).exists { pod =>
          val (code, body) = com.thinkmorestupidless.ankka.operator.InPod.curl(
            k3s,
            Namespace,
            pod.getMetadata.getName,
            s"https://$RustService.$Namespace.svc.cluster.local:9000/conformance/config/ANKKA_S3_BUCKET",
            container = Some(RustService)
          )
          lastAnswer = s"${pod.getMetadata.getName}: $code $body"
          code == 200 && body.contains(s"$Project.$RustService")
        }
      }
    catch
      case e: Throwable =>
        val (_, described) = kubectl("get", "pods", "-n", Namespace, "-o", "wide")
        val status         = statusOf(RustService).map(_.toString).getOrElse("no status")
        fail(s"${e.getMessage}\nlast answer: $lastAnswer\nstatus: $status\n$described")
  }

  // ── a service written in Go (spec 059): features/polyglot/deploying.feature's Go rows ─────────
  //
  // Last, so the services before it have left the node: the Go cart's sidecars are JVMs too, and a
  // node running every case's JVMs answers in seconds. Its own database, and its own prober, holding
  // its own certificate, so the cases run alone as well as after the others.

  private var goStarted    = false
  private var goProberHeld = false

  /**
   * The prober, holding the Go service's certificate; made again after a case lent it elsewhere.
   */
  private def goProber(): String =
    if !goProberHeld then
      com.thinkmorestupidless.ankka.operator.InPod.prober(k3s, Namespace, GoService): Unit
      goProberHeld = true
    "prober"

  private def goSpec(
      instances: Int = 1,
      restarts: Int = 0,
      paused: Boolean = false
  ): AnkkaServiceSpec =
    spec(instances, restarts).copy(
      serviceName = GoService,
      image = GoImage,
      env = database("postgres-go"),
      paused = paused
    )

  private def startGo(): Unit =
    if !goStarted then
      Vector(Service, RustService, OrdersService).foreach(name =>
        scala.util.Try(resources.withName(name).delete()): Unit
      )
      deployPostgres("postgres-go")
      applyAs(GoService, goSpec())
      waitFor(300.seconds)(readyReplicasOf(GoService) >= 1)
      // The cart every later case reads, so each holds alone too.
      var added = (0, "")
      waitFor(60.seconds) {
        added = goHttp("/carts/g1/items", Some("""{"productId":"p1","name":"Pen","quantity":2}"""))
        added._1 / 100 == 2
      }
      goStarted = true

  private def goHttp(
      path: String,
      post: Option[String] = None,
      diagnose: Boolean = false
  ): (Int, String) =
    com.thinkmorestupidless.ankka.operator.InPod.curl(
      k3s,
      Namespace,
      goProber(),
      s"https://$GoService.$Namespace.svc.cluster.local:9000$path",
      method = if post.isDefined then "POST" else "GET",
      body = post,
      maxSeconds = if diagnose then DiagnoseSeconds else 10,
      timings = diagnose
    )

  private def goPod(name: String) = podsOf(GoService).find(_.getMetadata.getName == name)

  private def containerStatus(pod: String, container: String) =
    goPod(pod)
      .flatMap(p => Option(p.getStatus.getContainerStatuses))
      .flatMap(_.asScala.find(_.getName == container))

  /**
   * A container's process as the node sees it, in one pod: `--name` is a regular expression, so it
   * is anchored, and the pod's label picks one instance of several.
   */
  private def hostPidIn(pod: String, container: String): Int =
    val (_, ids) = nodeExec(
      "crictl",
      "ps",
      "-q",
      "--name",
      s"^$container$$",
      "--label",
      s"io.kubernetes.pod.name=$pod"
    )
    val running = ids.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
    assertEquals(running.size, 1, s"containers $container in $pod: $ids")
    val (_, pid) = nodeExec(
      "crictl",
      "inspect",
      "-o",
      "go-template",
      "--template",
      "{{.info.pid}}",
      running.head
    )
    pid.trim.toInt

  /** A process's environment, read from the node: the Go image has no shell and no `env`. */
  private def environmentOf(pod: String, container: String): Vector[String] =
    val (code, out) = nodeExec("cat", s"/proc/${hostPidIn(pod, container)}/environ")
    assertEquals(code, 0, out)
    out.split('\u0000').toVector.filter(_.nonEmpty)

  /**
   * The fields of the line the Go process logged with `message` (`ankka: serving`), as slog's text
   * form writes them: `key=value`, a value quoted when it needs to be.
   */
  private def goLogFields(pod: String, message: String): Map[String, String] =
    val (_, log) = kubectl("logs", "-n", Namespace, pod, "-c", s"$GoService-app")
    val line     = log.linesIterator
      .find(_.contains(s" $message "))
      .getOrElse(fail(s"the process never logged '$message':\n$log"))
    """(\w+)=("(?:[^"\\]|\\.)*"|\S+)""".r
      .findAllMatchIn(line)
      .map(m => m.group(1) -> m.group(2).stripPrefix("\"").stripSuffix("\""))
      .toMap

  test(
    "a service hosted as a process runs beside the platform's own program and is ready only when both are (Go)"
  ) {
    startGo()
    val pod        = podsOf(GoService).head
    val name       = pod.getMetadata.getName
    val containers = pod.getSpec.getContainers.asScala.toVector
    assertEquals(containers.map(_.getName), Vector(GoService, s"$GoService-app"))
    assertEquals(containers.map(_.getImage), Vector(SidecarImage, GoImage))
    waitFor(60.seconds)(statusOf(GoService).exists(_.lifecycle == "Ready"))
    val (added, addBody) =
      goHttp("/carts/g1/items", Some("""{"productId":"p1","name":"Pen","quantity":2}"""))
    assertEquals(added / 100, 2, addBody)
    val (code, body) = goHttp("/carts/g1")
    assertEquals(code, 200, body)
    assert(body.contains("\"productId\":\"p1\""), body)
    // Ready only while both are: with the platform's own program running, a frozen process, whose
    // connection stays up, takes the instance out of readiness, and its requests fail, until it
    // answers again.
    val sidecarId = containerStatus(name, GoService).map(_.getContainerID)
    signal(hostPidIn(name, s"$GoService-app"), "STOP")
    try
      waitFor(30.seconds)(goPod(name).exists(!readyOf(_)))
      val (frozen, frozenBody) = goHttp("/carts/g1")
      assert(frozen / 100 != 2, s"a request to a frozen process was answered: $frozenBody")
      assertEquals(containerStatus(name, GoService).map(_.getContainerID), sidecarId)
    finally signal(hostPidIn(name, s"$GoService-app"), "CONT")
    waitFor(60.seconds)(goPod(name).exists(readyOf))
  }

  test("a deployed service says which SDK its code was built with (Go)") {
    startGo()
    val pod    = podsOf(GoService).find(readyOf).get.getMetadata.getName
    val fields = goLogFields(pod, "ankka: serving")
    assertEquals(
      (fields.get("sdk"), fields.get("version")),
      (Some("ankka-go"), Some(GoSdkVersion)),
      fields.toString
    )
    // The scenario's request goes through the gateway, which this suite does not install; the
    // request here takes the Service's address, as every case of the suite does.
    val (code, body) = goHttp("/carts/g1")
    assertEquals(code, 200, body)
  }

  test("a deployed service runs the platform's own program at the installation's version (Go)") {
    startGo()
    val pod = podsOf(GoService).find(readyOf).get
    // The installation's own: the image the operator was configured with, which the descriptor
    // cannot name.
    val sidecar = pod.getSpec.getContainers.asScala.find(_.getName == GoService).get
    assertEquals(sidecar.getImage, settings.sidecarImage)
    // At its version: what the sidecar told the process in discovery is this build's.
    val fields = goLogFields(pod.getMetadata.getName, "ankka: discovery")
    assertEquals(fields.get("runtime"), Some(BuildInfo.version), fields.toString)
  }

  test(
    "the trace of a deployed service attributes each handler's time to the component that did the work (Go)"
  ) {
    startGo()
    // A request that adds an item to the cart, through its route; the sidecar exports the spans.
    val (added, addBody) =
      goHttp("/carts/g1/items", Some("""{"productId":"p2","name":"Ink","quantity":1}"""))
    assertEquals(added / 100, 2, addBody)
    val cart                                                                 = "shopping-cart"
    def handled(s: com.thinkmorestupidless.ankka.operator.CollectorLog.Span) =
      s.service == GoService &&
        s.attributes.get("ankka.component").contains(cart) &&
        s.attributes.get("ankka.handler").contains("add-item")
    val seen = com.thinkmorestupidless.ankka.operator.CollectorStack
      .waitFor(k3s, s"a span of $cart add-item from $GoService")(_.exists(handled))
    val span = seen.find(handled).get
    assertEquals(span.attributes.get("ankka.outcome"), Some("ok"), span.toString)
    assert(
      span.startNanos > 0 && span.durationMillis >= 0,
      s"the handler's time is not stamped: $span"
    )
    // In the request's trace, under the route that called it, not a root of its own.
    assert(
      seen.exists(s => s.traceId == span.traceId && s.spanId == span.parentId),
      s"the handler's span has no parent in its trace: $span\n${seen.filter(_.traceId == span.traceId)}"
    )
  }

  test("the process of a deployed service is not given the secret key (Go)") {
    startGo()
    val pod                        = podsOf(GoService).find(readyOf).get.getMetadata.getName
    def keyIn(env: Vector[String]) = env.exists(_.startsWith("ANKKA_SECRET_KEY="))
    // The sidecar beside it holds one, so the read below could have found it.
    assert(keyIn(environmentOf(pod, GoService)), "the platform's own program has no secret key")
    val process = environmentOf(pod, s"$GoService-app")
    assert(process.exists(_.startsWith("ANKKA_SIDECAR_ADDRESS=")), process.mkString("\n"))
    assert(!keyIn(process), process.mkString("\n"))
  }

  test(
    "a service in every language is admitted by name by a route that admits only it (Go, on a cluster)"
  ) {
    startGo()
    startCallers()
    val route = "/callers/go-cart-alone"
    // Asked by a prober holding the certificate of `carts` itself: the route admits the Go service
    // alone, so even its own service is refused, and the admission below is by name.
    com.thinkmorestupidless.ankka.operator.InPod.prober(k3s, Namespace, CartsService): Unit
    goProberHeld = false
    val (refusedCode, refusedBody) = com.thinkmorestupidless.ankka.operator.InPod.curl(
      k3s,
      Namespace,
      "prober",
      s"https://$CartsService.$Namespace.svc.cluster.local:9000$route"
    )
    assertEquals(refusedCode, 403, refusedBody)
    // The Go cart's `/calling` admits only itself; its handler calls `carts` as `go-cart`.
    assertEquals(
      goHttp(s"/calling/call/$CartsService?path=$route"),
      (200, s"admitted: $Project/$GoService")
    )
    // The services this case needed leave the node before the rollout cases.
    Vector(CartsService, OrdersService).foreach(name =>
      scala.util.Try(resources.withName(name).delete()): Unit
    )
  }

  test(
    "a service written in another language scales into one cluster without replacing an instance (Go)"
  ) {
    startGo()
    val first = podsOf(GoService).head.getMetadata.getName
    applyAs(GoService, goSpec(instances = 3))
    waitFor(300.seconds)(readyReplicasOf(GoService) == 3)
    assert(
      podsOf(GoService).exists(p => p.getMetadata.getName == first && readyOf(p)),
      s"the first pod was replaced: ${podSummary()}"
    )
    val (code, body) = goHttp("/carts/g1")
    assertEquals(code, 200, body)
    assert(body.contains("\"productId\":\"p1\""), body)
  }

  test(
    "a service written in another language is restarted one instance at a time and refuses no request (Go)"
  ) {
    startGo()
    applyAs(GoService, goSpec(instances = 3))
    waitFor(300.seconds)(readyReplicasOf(GoService) == 3)
    val before = podsOf(GoService).map(_.getMetadata.getName).toSet
    followLogs(GoService)
    applyAs(GoService, goSpec(instances = 3, restarts = 1))
    var refused  = Vector.empty[String]
    var requests = 0
    // Pods not being deleted, at most, at any one look: three and the one being started.
    var most     = 0
    val deadline = System.nanoTime() + 300.seconds.toNanos
    while podsOf(GoService).exists(p => before.contains(p.getMetadata.getName)) &&
      System.nanoTime() < deadline
    do
      val (code, body) = goHttp("/carts/g1", diagnose = true)
      requests += 1
      if code != 200 then refused :+= s"$code $body"
      most = most.max(podsOf(GoService).count(_.getMetadata.getDeletionTimestamp == null))
    waitFor(120.seconds)(readyReplicasOf(GoService) == 3 && podsOf(GoService).forall(readyOf))
    val after = podsOf(GoService).map(_.getMetadata.getName).toSet
    assert(after.intersect(before).isEmpty, s"an instance was not replaced: $before then $after")
    assert(most <= 4, s"$most instances were running at once; they are replaced one at a time")
    assert(
      requests > 5,
      s"the rollout finished before the loop measured anything ($requests requests)"
    )
    assert(
      refused.isEmpty,
      s"${refused.size} of $requests requests were refused during the rollout: ${refused
          .mkString(" | ")}${if refused.isEmpty then "" else rolloutReport(GoService, before)}"
    )
  }

  test("a process that stops is started again while its instance stays in the cluster (Go)") {
    startGo()
    applyAs(GoService, goSpec(instances = 2, restarts = 1))
    waitFor(300.seconds)(
      readyReplicasOf(GoService) == 2 && podsOf(GoService).size == 2 &&
        podsOf(GoService).forall(readyOf)
    )
    val names           = podsOf(GoService).map(_.getMetadata.getName).toSet
    val name            = names.head
    def app             = containerStatus(name, s"$GoService-app")
    def side            = containerStatus(name, GoService)
    val appRestarts     = app.map(_.getRestartCount.intValue).getOrElse(-1)
    val sidecarId       = side.map(_.getContainerID).getOrElse("")
    val sidecarRestarts = side.map(_.getRestartCount.intValue).getOrElse(-1)
    assert(sidecarId.nonEmpty, s"no sidecar container in $name")
    signal(hostPidIn(name, s"$GoService-app"), "KILL")
    waitFor(120.seconds)(
      app.exists(_.getRestartCount.intValue > appRestarts) && goPod(name).exists(readyOf)
    )
    // The platform's own program is the same container, never restarted, in the same pod: it did
    // not leave the cluster.
    assertEquals(side.map(_.getContainerID), Some(sidecarId))
    assertEquals(side.map(_.getRestartCount.intValue), Some(sidecarRestarts))
    assertEquals(podsOf(GoService).map(_.getMetadata.getName).toSet, names)
    // A client retries: the readiness probe cannot see a restart this quick.
    var answer = goHttp("/carts/g1")
    waitFor(60.seconds) {
      answer = goHttp("/carts/g1")
      answer._1 == 200
    }
    assert(answer._2.contains("\"productId\":\"p1\""), answer._2)
  }

  test(
    "a service written in another language is exposed, paused and resumed like any other service (Go, pauses)"
  ) {
    startGo()
    applyAs(GoService, goSpec(instances = 1, restarts = 1))
    waitFor(300.seconds)(readyReplicasOf(GoService) == 1 && podsOf(GoService).size == 1)
    applyAs(GoService, goSpec(instances = 1, restarts = 1, paused = true))
    waitFor(180.seconds)(
      statusOf(GoService).exists(_.lifecycle == "Paused") && podsOf(GoService).isEmpty
    )
  }

  test(
    "a service written in another language is exposed, paused and resumed like any other service (Go, resumes)"
  ) {
    startGo()
    // Paused by the case before; alone, it pauses first.
    if !statusOf(GoService).exists(_.lifecycle == "Paused") then
      applyAs(GoService, goSpec(instances = 1, restarts = 1, paused = true))
      waitFor(180.seconds)(podsOf(GoService).isEmpty)
    applyAs(GoService, goSpec(instances = 1, restarts = 1))
    waitFor(300.seconds)(
      statusOf(GoService).exists(s => s.lifecycle == "Ready" && s.readyInstances == 1) &&
        podsOf(GoService).size == 1
    )
    val (code, body) = goHttp("/carts/g1")
    assertEquals(code, 200, body)
    assert(body.contains("\"productId\":\"p1\""), body)
  }
