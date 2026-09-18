package nakka.controlplane

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import nakka.cli.Main
import nakka.controlplane.api.ControlPlaneAcl
import nakka.controlplane.deploy.{DeployConfig, Fabric8NakkaServiceClient, ServiceProjector}
import nakka.crd.{NakkaSerialization, NakkaService}
import nakka.operator.cnpg.{PostgresCluster, PostgresDatabase, PostgresDatabaseRole}
import nakka.http.HttpServer
import nakka.operator.{Operator, ServiceReconciler, Settings as OperatorSettings}
import nakka.runtime.ProjectionRuntime
import nakka.testkit.NakkaTestKit
import org.slf4j.LoggerFactory
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Base64
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * Everything, at once: the CLI, the control plane, Postgres, the operator and a real cluster.
 *
 * This is the only test that can catch the two halves disagreeing about the custom resource — the
 * same argument the build already makes for `controlplane` taking `cli % Test`, applied to the
 * second wire format this feature introduces.
 *
 * Disable with `-Dnakka.cluster.tests=off`.
 */
class EndToEndClusterSuite extends munit.FunSuite:

  override val munitTimeout: FiniteDuration = 8.minutes

  override def munitIgnore: Boolean = sys.props.get("nakka.cluster.tests").contains("off")

  private val Image     = "rancher/k3s:v1.31.2-k3s1"
  private val Token     = "e2e-test-token"
  private val Prefix    = "nakka"
  private val Project   = "checkout"
  private val Namespace = s"$Prefix-$Project"
  private val Service   = "cart"

  /**
   * Images that stay running without a command.
   *
   * `busybox` exits immediately — its default command needs a TTY — so a Deployment built from it
   * crash-loops and never reports Ready. `pause` is the smallest thing that just sits there, which
   * is all a reconciliation test needs from a workload.
   */
  private val FirstImage  = "registry.k8s.io/pause:3.9"
  private val SecondImage = "registry.k8s.io/pause:3.10"

  private var k3s: K3sContainer     = null
  private var k8s: KubernetesClient = null
  private var operator: Operator    = null
  private var testKit: NakkaTestKit = null
  private var url: String           = ""
  private var config: Path          = null

  // Captures every log event in this JVM for the life of the suite, so T053 can assert a
  // generated database password never appears in the operator's own logs — the only way to
  // check that with the operator running in-process rather than as a separate container whose
  // stdout could be grepped.
  private val logAppender = new ListAppender[ILoggingEvent]()

  override def beforeAll(): Unit =
    if !munitIgnore then
      val root =
        LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
      logAppender.start()
      root.addAppender(logAppender)

      k3s = new K3sContainer(DockerImageName.parse(Image))
      k3s.start()

      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(NakkaSerialization())
        .build()

      k8s.load(getClass.getResourceAsStream("/nakka/crd/nakkaservice.yaml")).serverSideApply(): Unit

      // CloudNativePG too — the control plane always projects provisionDatabase = true until
      // the escape hatch exists, so every service applied in this suite goes through real
      // provisioning, not just the ones that specifically test it.
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
      waitFor(90.seconds) {
        val d = k8s
          .apps()
          .deployments()
          .inNamespace("cnpg-system")
          .withName("cnpg-controller-manager")
          .get()
        d != null && Option(d.getStatus).flatMap(s => Option(s.getReadyReplicas)).exists(_ > 0)
      }

      val operatorSettings = OperatorSettings.default.copy(resyncInterval = 2.seconds)
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
      operator.start()

      val deployConfig = DeployConfig.default
        .copy(namespacePrefix = Prefix, sweepInterval = 2.seconds, progressDeadline = 60.seconds)
      val projector = ServiceProjector.withClient(
        deployConfig,
        new Fabric8NakkaServiceClient(k8s, Prefix, resyncMillis = 2000L)
      )

      val server = HttpServer.at("127.0.0.1", 0)(
        ControlPlane.endpoints(ControlPlaneAcl.bearer(Token))*
      )
      testKit = NakkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

      config = Files.createTempFile("nakka-e2e", ".json")
      Files.delete(config)
      sys.props("nakka.config") = config.toString

  override def afterAll(): Unit =
    sys.props.remove("nakka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if testKit != null then testKit.stop()
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()
    val root =
      LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
    root.detachAppender(logAppender): Unit

  private def cli(args: String*): (Int, String) =
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    val outs = PrintStream(out, true, StandardCharsets.UTF_8)
    val errs = PrintStream(err, true, StandardCharsets.UTF_8)
    val code = Main.run(args, outs, errs)
    val text = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8)
    (code, text)

  private def nakka(args: String*): (Int, String) =
    cli((args ++ Seq("--url", url, "--token", Token))*)

  private def waitFor(timeout: FiniteDuration)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(250)
    if !passed then fail(s"condition did not hold within $timeout")

  private def deployment = Option(
    k8s.apps().deployments().inNamespace(Namespace).withName(Service).get()
  )

  private def resource = Option(
    k8s.resources(classOf[NakkaService]).inNamespace(Namespace).withName(Service).get()
  )

  private def descriptorJson(image: String): Path =
    val file = Files.createTempFile("cart", ".json")
    Files.writeString(
      file,
      // "http": false because `pause` opens no port. The default now means "serves HTTP on 9000,
      // and is not Ready until it does", which is right for a nakka service and would leave this
      // one un-Ready forever. Not a workaround: this suite's subject is the two halves agreeing
      // about the resource, the workload is irrelevant to it, and this is the honest descriptor
      // for a workload that serves nothing. SampleDeploymentClusterSuite covers one that does.
      s"""{"name":"$Service","service":{"image":"$image","http":false}}"""
    )
    file

  private def applyCart(image: String): Unit =
    val file        = descriptorJson(image)
    val (code, out) = nakka("services", "apply", "-f", file.toString, "-p", Project)
    Files.deleteIfExists(file): Unit
    assertEquals(code, 0, s"apply failed: $out")

  private def listed: String = nakka("services", "list", "-p", Project)._2

  private val SuppliedService = "external"

  private def applySupplied(): Unit =
    val file = Files.createTempFile("external", ".json")
    Files.writeString(
      file,
      s"""{"name":"$SuppliedService","service":{"image":"$FirstImage","http":false,""" +
        """"env":[{"name":"NAKKA_DB_HOST","value":"external-db.example.com"}]}}"""
    )
    val (code, out) = nakka("services", "apply", "-f", file.toString, "-p", Project)
    Files.deleteIfExists(file): Unit
    assertEquals(code, 0, s"apply failed: $out")

  test("1. an organization and a project are created through the CLI") {
    assertEquals(nakka("organizations", "create", "acme", "--name", "Acme")._1, 0)
    assertEquals(nakka("projects", "create", Project, "--name", "Checkout", "-O", "acme")._1, 0)
  }

  test("2. a project id that cannot become a namespace is refused") {
    // The validation this feature added: the id becomes part of a namespace name, so it
    // has to be refused at creation rather than when its first service fails to deploy.
    val (code, out) = nakka("projects", "create", "Not_Valid", "--name", "Bad", "-O", "acme")
    assertNotEquals(code, 0)
    assert(out.toLowerCase.contains("project id"), out)
  }

  test("3. applying a descriptor with no database configuration provisions one and reaches Ready") {
    // The headline, twice over: this is what "the deployment is not happening" stopped being
    // true at (feature 001), and what "an operator has to provision a database by hand first"
    // stops being true at (this feature). The descriptor above names only an image.
    applyCart(FirstImage)

    waitFor(120.seconds)(resource.isDefined)
    waitFor(180.seconds)(deployment.isDefined)
    waitFor(180.seconds)(listed.contains("Ready"))

    assertEquals(
      deployment.get.getSpec.getTemplate.getSpec.getContainers.get(0).getImage,
      FirstImage
    )

    // The database chain is real, not just "the pod is Ready because nothing needed it":
    // a project cluster, and this service's own role and database within it.
    assert(
      Option(
        k8s.resources(classOf[PostgresCluster]).inNamespace(Namespace).withName("nakka-db").get()
      ).isDefined,
      "expected the project's shared Postgres capacity to exist"
    )
    assertEquals(
      resource.flatMap(r => Option(r.getStatus)).flatMap(_.database).map(_.phase),
      Some("Provisioned")
    )
  }

  test("4. the reported status is confirmed, not a guess") {
    val (_, single) = nakka("services", "get", Service, "-p", Project)
    assert(!single.contains("unconfirmed"), single)
  }

  test("5. changing the image rolls the workload") {
    applyCart(SecondImage)
    waitFor(180.seconds)(
      deployment.exists(_.getSpec.getTemplate.getSpec.getContainers.get(0).getImage == SecondImage)
    )
    waitFor(180.seconds)(listed.contains("Ready"))
  }

  test("6. pausing stops the workload and resuming brings it back") {
    assertEquals(nakka("services", "pause", Service, "-p", Project)._1, 0)
    waitFor(120.seconds)(deployment.exists(_.getSpec.getReplicas.intValue == 0))
    waitFor(120.seconds)(listed.contains("Paused"))

    assertEquals(nakka("services", "resume", Service, "-p", Project)._1, 0)
    waitFor(180.seconds)(deployment.exists(_.getSpec.getReplicas.intValue == 1))
    waitFor(180.seconds)(listed.contains("Ready"))
  }

  test("7. restarting replaces the instance without changing the descriptor") {
    val before = resource.get.getSpec.generation
    assertEquals(nakka("services", "restart", Service, "-p", Project)._1, 0)

    waitFor(120.seconds)(resource.exists(_.getSpec.generation > before))
    waitFor(120.seconds)(
      deployment.exists(
        _.getSpec.getTemplate.getMetadata.getAnnotations
          .get(
            "nakka.thinkmorestupidless.com/generation"
          ) == resource.get.getSpec.generation.toString
      )
    )
    assertEquals(
      deployment.get.getSpec.getTemplate.getSpec.getContainers.get(0).getImage,
      SecondImage,
      "a restart must not change the descriptor"
    )
  }

  test("8. a workload deleted out of band is restored without an operator command") {
    k8s.apps().deployments().inNamespace(Namespace).withName(Service).delete(): Unit
    waitFor(120.seconds)(deployment.isDefined)
    waitFor(180.seconds)(listed.contains("Ready"))
  }

  test("9. a descriptor supplying its own NAKKA_DB_* env var is the escape hatch") {
    applySupplied()
    waitFor(120.seconds)(
      Option(
        k8s.resources(classOf[NakkaService]).inNamespace(Namespace).withName(SuppliedService).get()
      ).isDefined
    )
    waitFor(180.seconds)(
      Option(
        k8s.apps().deployments().inNamespace(Namespace).withName(SuppliedService).get()
      ).isDefined
    )
    waitFor(180.seconds)(nakka("services", "list", "-p", Project)._2.contains("Ready"))

    assert(
      Option(
        k8s
          .resources(classOf[PostgresDatabase])
          .inNamespace(Namespace)
          .withName(SuppliedService)
          .get()
      ).isEmpty,
      "no Database may be provisioned for a service bringing its own"
    )
    assert(
      Option(
        k8s
          .resources(classOf[PostgresDatabaseRole])
          .inNamespace(Namespace)
          .withName(SuppliedService)
          .get()
      ).isEmpty,
      "no DatabaseRole may be provisioned for a service bringing its own"
    )
    assert(
      Option(k8s.secrets().inNamespace(Namespace).withName(s"$SuppliedService-db").get()).isEmpty,
      "no credential secret may be generated for a service bringing its own database"
    )

    val (_, single) = nakka("services", "get", SuppliedService, "-p", Project)
    assert(single.contains("supplied"), single)
  }

  test(
    "10. the generated password for a provisioned service never appears in status, spec or logs"
  ) {
    val secret   = k8s.secrets().inNamespace(Namespace).withName(s"$Service-db").get()
    val password = new String(Base64.getDecoder.decode(secret.getData.get("password")))

    val statusJson = resource.flatMap(r => Option(r.getStatus)).map(_.toString).getOrElse("")
    assert(!statusJson.contains(password), "the NakkaService status must never carry the password")

    val deploymentSpec = deployment.map(_.getSpec.toString).getOrElse("")
    assert(
      !deploymentSpec.contains(password),
      "the Deployment spec references the secret by name, never the password's literal value"
    )

    val logged = logAppender.list.asScala.exists(_.getFormattedMessage.contains(password))
    assert(!logged, "the generated password must never appear in any log line this JVM produced")
  }

  test(
    "11. deleting a service leaves its workload behind nothing, but its database untouched (FR-024)"
  ) {
    assertEquals(nakka("services", "delete", Service, "-p", Project)._1, 0)
    waitFor(120.seconds)(resource.isEmpty)
    // The Deployment goes with the resource, by owner reference, not by a sweep.
    waitFor(180.seconds)(deployment.isEmpty)

    // No owner reference ties the project's Cluster or this service's Database to the
    // NakkaService that requested them (a deliberate CnpgRendering design choice) — deleting
    // the last service that used them must not touch either.
    assert(
      Option(
        k8s.resources(classOf[PostgresCluster]).inNamespace(Namespace).withName("nakka-db").get()
      ).isDefined,
      "the project's shared Postgres capacity must survive deleting its only service"
    )
    assert(
      Option(
        k8s.resources(classOf[PostgresDatabase]).inNamespace(Namespace).withName(Service).get()
      ).isDefined,
      "the deleted service's own Database must survive — nothing in this platform destroys one"
    )
  }
