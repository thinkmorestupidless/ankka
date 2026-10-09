package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import com.thinkmorestupidless.ankka.operator.{
  ClusterImages,
  GarageStore,
  GatewayStack,
  InPod,
  KeycloakStack,
  ObjectStoreStack,
  Operator,
  PlatformBucket,
  SecretWriter,
  ServiceReconciler,
  StorageCredential,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.runtime.erasure.ChannelWire
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.typesafe.config.ConfigFactory
import io.fabric8.kubernetes.api.model.{ConfigMapBuilder, ObjectMetaBuilder, Pod}
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.{ConnectionContext, Http}
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.nio.file.{Files, Path, Paths}
import java.util.Base64
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Erasure on an installation (feature 042), what only a cluster can show: the control plane and the
 * keyring from their shipped manifests under zero trust, the keyring at two instances on its own
 * CloudNativePG database, the platform bucket on the installation's Garage, and the shopping cart
 * sample at two instances, its customer's details personal. The operator runs in this JVM, as in
 * `ControlPlaneClusterSuite`.
 *
 * The features' scenarios run offline (`ErasingFeatures`, `RestoresFeatures`, `ObjectsFeatures`,
 * `OtherProjectsFeatures`, `AskingFeatures`), against services written for them; the sample has
 * none of their players, views or handlers. What this suite adds is the installation: every
 * instance holding a channel over mutual TLS, a member's request applied through the control plane
 * and read as erased by both instances within a minute, a rolling restart in the middle of one, the
 * control plane's own identity refused a key, and the keyring replaying the log from both its
 * copies. Restores on a cluster wait on database backups, and another project's reads on rendered
 * grants.
 *
 * Disable with `-Dankka.cluster.tests=off`.
 */
class ErasureClusterFeatures extends munit.FunSuite with LogCapturing:

  override val munitTimeout: FiniteDuration = 20.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  private val K3sImage          = "rancher/k3s:v1.35.1-k3s1"
  private val BaseDomain        = "erasure.local"
  private val Tag               = com.thinkmorestupidless.ankka.core.BuildInfo.imageTag
  private val ControlPlaneImage = s"ankka-controlplane:$Tag"
  private val KeyringImage      = s"ankka-keyring:$Tag"
  private val SampleImage       = s"sample-shopping-cart:$Tag"
  private val Namespace         = "ankka-controlplane"
  private val KeyringNamespace  = "ankka-keyring"
  private val KeyringUrl        = "https://ankka-keyring.ankka-keyring.svc:9020"
  private val Project           = "checkout"
  private val Service           = "cart"
  private def ServiceNamespace  = s"ankka-$Project"

  private var k3s: K3sContainer                 = null
  private var k8s: KubernetesClient             = null
  private var operator: Operator                = null
  private var store: ObjectStoreStack.Installed = null
  private var system: ActorSystem               = null

  @volatile private var minted: Option[(String, Long)] = None
  private def Token: String =
    minted.filter((_, at) => System.nanoTime() - at < 4.minutes.toNanos) match
      case Some((token, _)) => token
      case None =>
        val token = KeycloakStack.mintToken(
          GatewayStack.exportCa(k8s),
          BaseDomain,
          k3s.getMappedPort(GatewayStack.HttpsNodePort),
          "e2e-cli",
          "e2e-secret"
        )
        minted = Some(token -> System.nanoTime())
        token

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("kustomization")))
      .getOrElse(fail("could not find the repository root"))

  private def applyYaml(yaml: String): Unit =
    k8s.load(java.io.ByteArrayInputStream(yaml.getBytes("UTF-8"))).serverSideApply(): Unit

  private def applyManifest(relative: String, edit: String => String = identity): Unit =
    applyYaml(edit(Files.readString(repoRoot.resolve(relative))))

  // ── the installation ───────────────────────────────────────────────────────

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.withExposedPorts(6443, GatewayStack.HttpsNodePort)
      k3s.start()
      Vector(ControlPlaneImage, KeyringImage, SampleImage).foreach(ClusterImages.importInto(k3s, _))
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
      waitFor(180.seconds, "CloudNativePG's controller") {
        Option(
          k8s
            .apps()
            .deployments()
            .inNamespace("cnpg-system")
            .withName("cnpg-controller-manager")
            .get()
        ).flatMap(d => Option(d.getStatus)).flatMap(s => Option(s.getReadyReplicas)).exists(_ > 0)
      }
      applyManifest("kustomization/components/crd/ankkaservice.yaml")
      applyManifest("kustomization/components/controlplane/namespace.yaml")
      schemaConfigMap()
      applyManifest("kustomization/components/postgres/cluster.yaml")
      applyManifest("kustomization/components/controlplane/controlplane-rbac.yaml")
      applyManifest("kustomization/components/controlplane/service.yaml")
      GatewayStack.install(k3s, k8s, repoRoot, BaseDomain)
      KeycloakStack.install(
        k3s,
        k8s,
        repoRoot,
        BaseDomain,
        k3s.getMappedPort(GatewayStack.HttpsNodePort)
      )
      KeycloakStack.createServiceClient(k3s, "e2e-cli", "e2e-secret", platformAdmin = true)
      applyManifest("kustomization/components/controlplane/zero-trust.yaml")

      // The installation's object store, and in it the platform's bucket and both readers'
      // credentials, as the operator makes them at start — before the Deployments that read them.
      store = ObjectStoreStack.install(k3s, k8s, repoRoot)
      applyManifest("kustomization/components/keyring/namespace.yaml")
      PlatformBucket.ensure(
        GarageStore(store.settings.adminUrl, store.settings.adminToken),
        StorageCredential(
          GarageStore(store.settings.adminUrl, store.settings.adminToken),
          SecretWriter.fabric8(k8s)
        ),
        store.settings,
        ns => k8s.namespaces().withName(ns).get() != null
      )

      // The keyring's component, file by file, as kustomize would apply it; its image by this
      // build's tag. Its patches are applied by hand below: the operator runs in this JVM.
      for name <- Vector(
          "rbac.yaml",
          "secrets.yaml",
          "cluster.yaml",
          "service.yaml",
          "zero-trust.yaml"
        )
      do applyManifest(s"kustomization/components/keyring/$name")
      applyManifest(
        "kustomization/components/keyring/deployment.yaml",
        _.replace("image: ankka-keyring:latest", s"image: $KeyringImage")
      )

      // The control plane, with what the overlay's replacements and the keyring's patch write.
      val httpsPort = k3s.getMappedPort(GatewayStack.HttpsNodePort)
      applyManifest(
        "kustomization/components/controlplane/deployment.yaml",
        yaml =>
          withKeyringUrl(
            yaml
              .replaceAll("(?<!ANKKA_)BASE_DOMAIN", BaseDomain)
              .replace("""value: "443"""", s"""value: "$httpsPort"""")
              .replace("image: ankka-controlplane:latest", s"image: $ControlPlaneImage")
          )
      )
      applyManifest(
        "kustomization/components/controlplane/httproute.yaml",
        _.replaceAll("(?<!ANKKA_)BASE_DOMAIN", BaseDomain)
      )

      val settings = OperatorSettings.default.copy(
        resyncInterval = 2.seconds,
        keyringUrl = Some(KeyringUrl)
      )
      operator = new Operator(k8s, settings, ServiceReconciler(k8s, settings))
      operator.start()

      explained(KeyringNamespace, "ankka-keyring")(
        waitFor(600.seconds, "the keyring's two instances") {
          readyReplicas(KeyringNamespace, "ankka-keyring") == 2
        }
      )
      explained(Namespace, "ankka-controlplane")(
        waitFor(600.seconds, "the control plane") {
          readyReplicas(Namespace, "ankka-controlplane") >= 1 && api(
            "GET",
            "/organizations"
          )._1 == 0
        }
      )
      system = ActorSystem(
        "erasure-cluster-suite",
        ConfigFactory.parseString("pekko.actor.provider = local").withFallback(ConfigFactory.load())
      )

  override def afterAll(): Unit =
    if system != null then system.terminate(): Unit
    if operator != null then operator.close()
    if store != null then store.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  /** The control plane's schema ConfigMap, from the single-copy DDL and the grants. */
  private def schemaConfigMap(): Unit =
    val ddl = repoRoot.resolve("modules/runtime/src/main/resources/ankka/ddl")
    val data = Files
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
          .withData(data.asJava)
          .build()
      )
      .serverSideApply(): Unit

  /**
   * The keyring component's patch on the control plane: `ANKKA_KEYRING_URL` first in the control
   * plane container's own `env`, never the init container's, which comes first in the file.
   */
  private def withKeyringUrl(yaml: String): String =
    val container = yaml.indexOf("        - name: ankka-controlplane\n")
    assert(container > 0, "the control plane's container is not where the patch expects it")
    val env = yaml.indexOf("          env:\n", container)
    assert(env > 0, "the control plane's container has no env")
    val at = env + "          env:\n".length
    yaml.substring(0, at) +
      s"""            - name: ANKKA_KEYRING_URL\n              value: "$KeyringUrl"\n""" +
      yaml.substring(at)

  // ── helpers ────────────────────────────────────────────────────────────────

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(1000)
    if !passed then fail(s"$what did not happen within $timeout")

  /**
   * Runs `wait`, and when it fails says what the workload's pods were doing: their states and the
   * last lines each logged. A ready count alone cannot tell an image that will not start from a
   * cluster that will not form or a readiness check that never passes.
   */
  private def explained(namespace: String, name: String)(wait: => Unit): Unit =
    try wait
    catch
      case failure: Throwable =>
        val report = podsOf(namespace, name).map { pod =>
          val states = Option(pod.getStatus).toVector
            .flatMap(s => Option(s.getContainerStatuses).toVector.flatMap(_.asScala))
            .map(c =>
              s"${c.getName} ready=${c.getReady} restarts=${c.getRestartCount} ${c.getState}"
            )
          val log =
            try
              k8s
                .pods()
                .inNamespace(namespace)
                .withName(pod.getMetadata.getName)
                .inContainer(name)
                .tailingLines(40)
                .getLog
            catch case e: Throwable => s"(no log: ${e.getMessage})"
          s"${pod.getMetadata.getName}\n  ${states.mkString("\n  ")}\n$log"
        }
        throw AssertionError(s"${failure.getMessage}\n${report.mkString("\n---\n")}", failure)

  private def readyReplicas(namespace: String, name: String): Int =
    Option(k8s.apps().deployments().inNamespace(namespace).withName(name).get())
      .flatMap(d => Option(d.getStatus))
      .flatMap(s => Option(s.getReadyReplicas))
      .fold(0)(_.intValue)

  private def podsOf(namespace: String, name: String): Vector[Pod] =
    k8s
      .pods()
      .inNamespace(namespace)
      .withLabel("app.kubernetes.io/name", name)
      .list()
      .getItems
      .asScala
      .toVector
      .filter(_.getMetadata.getDeletionTimestamp == null)

  private def ready(pod: Pod): Boolean =
    Option(pod.getStatus)
      .flatMap(s => Option(s.getContainerStatuses))
      .exists(cs => cs.asScala.nonEmpty && cs.asScala.forall(_.getReady))

  /** The control plane's API, from inside one of its pods, over mutual TLS, as a platform admin. */
  private def api(method: String, path: String, body: Option[String] = None): (Int, String) =
    val from = podsOf(Namespace, "ankka-controlplane")
      .filter(ready)
      .lastOption
      .getOrElse(fail("no ready control plane pod"))
    val dir = "/var/run/secrets/ankka/service"
    val curl = Vector(
      "curl",
      "-sS",
      "-f",
      "-m",
      "20",
      "-X",
      method,
      "-H",
      s"Authorization: Bearer $Token",
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
    val r = k3s.execInContainer(
      (Vector("kubectl", "exec", "-n", Namespace, from.getMetadata.getName, "--") ++ curl)*
    )
    (r.getExitCode, r.getStdout + r.getStderr)

  private def servicePods: Vector[Pod] = podsOf(ServiceNamespace, Service).filter(ready)

  /** The sample, from inside `pod`, on its own HTTP port with its own certificate. */
  private def sample(
      pod: Pod,
      method: String,
      path: String,
      body: Option[String] = None
  ): (Int, String) =
    InPod.curl(
      k3s,
      ServiceNamespace,
      pod.getMetadata.getName,
      s"https://127.0.0.1:9000$path",
      method = method,
      body = body,
      verifyHost = false,
      container = Some(Service)
    )

  private def customer(cart: String, name: String): Unit =
    val pod = servicePods.headOption.getOrElse(fail("the sample has no ready pod"))
    val (status, body) = sample(
      pod,
      "PUT",
      s"/carts/$cart/customer",
      Some(s"""{"name":"$name","email":"${cart}@example.com"}""")
    )
    assertEquals(status, 200, body)

  private def readsErasedEverywhere(carts: Seq[String]): Boolean =
    val pods = servicePods
    pods.size == 2 && pods.forall(pod =>
      carts.forall(cart =>
        sample(pod, "GET", s"/carts/$cart/customer")._2.contains("\"name\":\"erased\"")
      )
    )

  private val IdPattern = """"id":"(e-[0-9a-f]+)"""".r

  /** Asks the control plane to erase `subject`, as a member: the request's id. */
  private def erase(subject: String): String =
    val (code, out) =
      api("POST", s"/projects/$Project/erasures", Some(s"""{"subject":"$subject"}"""))
    assertEquals(code, 0, out)
    IdPattern.findFirstMatchIn(out).map(_.group(1)).getOrElse(fail(s"no request id in $out"))

  private def awaitApplied(id: String, within: FiniteDuration = 180.seconds): Unit =
    waitFor(within, s"erasure $id applied") {
      val (_, out) = api("GET", s"/projects/$Project/erasures/$id")
      out.contains("\"state\":\"applied\"") || out.contains("\"state\":\"final\"")
    }

  private def keyringStatus(): String =
    val pod = servicePods.headOption.getOrElse(fail("the sample has no ready pod"))
    InPod
      .curl(
        k3s,
        ServiceNamespace,
        pod.getMetadata.getName,
        s"$KeyringUrl/status",
        container = Some(Service)
      )
      ._2

  /** The certificate files of a Secret the platform issued, written where `RotatingTls` reads. */
  private def identityOf(namespace: String, secret: String): RotatingTls =
    val data = k8s.secrets().inNamespace(namespace).withName(secret).get().getData.asScala
    val dir  = Files.createTempDirectory("erasure-identity")
    for name <- Vector("tls.crt", "tls.key", "ca.crt") do
      Files.write(dir.resolve(name), Base64.getDecoder.decode(data(name))): Unit
    RotatingTls(dir, 1.minute)

  /**
   * A channel opened as `identity` through a port-forward to the keyring, saying `hello` for
   * `project`: every frame the keyring sent back until it closed or `within` passed. The keyring's
   * own certificate is checked by its `ankka://` identity; the host is not, since a forward reaches
   * it at a loopback address no certificate names.
   */
  private def channelAs(
      identity: RotatingTls,
      project: String,
      within: FiniteDuration
  ): Vector[String] =
    val forward =
      k8s.services().inNamespace(KeyringNamespace).withName("ankka-keyring").portForward(9020)
    try
      val tls = identity.contextRequiring("ankka://platform/keyring")
      val context = ConnectionContext.httpsClient((host, port) =>
        val engine = tls.createSSLEngine(host, port)
        engine.setUseClientMode(true)
        engine
      )
      val hello =
        ChannelWire.write(ChannelWire.Out.Hello(project, "probe", "probe-1", Vector.empty, None))
      given ActorSystem = system
      val flow = Flow.fromSinkAndSourceMat(
        Sink.seq[Message],
        Source.single(TextMessage(hello)).concat(Source.maybe[Message])
      )(Keep.left)
      val (_, received) = Http().singleWebSocketRequest(
        WebSocketRequest(s"wss://127.0.0.1:${forward.getLocalPort}/channel"),
        flow,
        context
      )
      val frames =
        try Await.result(received, within)
        catch case _: java.util.concurrent.TimeoutException => Seq.empty
      frames.toVector.collect { case t: TextMessage.Strict => t.text }
    finally forward.close()

  // ── the cases ──────────────────────────────────────────────────────────────

  test(
    "1. every instance of a service holds a channel to the keyring's two instances, over mutual TLS"
  ) {
    assertEquals(api("POST", "/organizations/acme", Some("""{"name":"Acme"}"""))._1, 0)
    assertEquals(
      api(
        "POST",
        s"/projects/$Project",
        Some("""{"name":"Checkout","organizationId":"acme"}""")
      )._1,
      0
    )
    val (code, out) = api(
      "PUT",
      s"/services/$Project/$Service",
      Some(s"""{"name":"$Service","service":{"image":"$SampleImage","minInstances":2}}""")
    )
    assertEquals(code, 0, out)
    waitFor(600.seconds, "the sample's two instances") {
      servicePods.size == 2 && api("GET", s"/services/$Project/$Service")._2
        .contains("\"lifecycle\":\"Ready\"")
    }
    val env = servicePods.head.getSpec.getContainers.asScala
      .find(_.getName == Service)
      .toVector
      .flatMap(_.getEnv.asScala)
    assertEquals(env.filter(_.getName == "ANKKA_KEYRING_URL").map(_.getValue), Vector(KeyringUrl))
    // Each keyring instance counts the channels it holds; together, one per service instance.
    waitFor(120.seconds, "a channel per instance") {
      val counts = """"channels":(\d+)""".r.findAllMatchIn(keyringStatus()).map(_.group(1).toInt)
      counts.nonEmpty
    }
    val total = podsOf(KeyringNamespace, "ankka-keyring").filter(ready).map { pod =>
      val (_, body) = InPod.curl(
        k3s,
        KeyringNamespace,
        pod.getMetadata.getName,
        "https://127.0.0.1:9020/status",
        verifyHost = false,
        container = Some("ankka-keyring")
      )
      """"channels":(\d+)""".r.findFirstMatchIn(body).fold(0)(_.group(1).toInt)
    }
    assertEquals(total.sum, 2, s"channels per keyring instance: $total")
  }

  test(
    "2. a member's erasure is applied through the control plane, and read as erased by both instances within a minute"
  ) {
    val carts = (1 to 6).map(i => s"c$i")
    carts.foreach(c => customer(c, s"Customer $c"))
    servicePods.foreach(pod =>
      carts.foreach(c =>
        assert(sample(pod, "GET", s"/carts/$c/customer")._2.contains(s"Customer $c"), c)
      )
    )
    val asked = System.nanoTime()
    val ids   = carts.map(c => erase(s"customer/$c"))
    waitFor(60.seconds, "both instances reading every subject as erased")(
      readsErasedEverywhere(carts)
    )
    val took = (System.nanoTime() - asked).nanos.toSeconds
    ids.foreach(awaitApplied(_))
    println(s"erasure read everywhere after ${took}s")
  }

  test("3. a new personal field is refused for an erased data subject") {
    val pod = servicePods.head
    val (status, body) = sample(
      pod,
      "PUT",
      "/carts/c1/customer",
      Some("""{"name":"Back Again","email":"again@example.com"}""")
    )
    assert(status >= 400, s"$status $body")
  }

  test(
    "4. an erasure asked for during a rolling restart is applied, and read as erased by the new instances"
  ) {
    customer("r1", "Rolling Customer")
    val before = servicePods.map(_.getMetadata.getName).toSet
    assertEquals(api("POST", s"/services/$Project/$Service/restart")._1, 0)
    val id = erase("customer/r1")
    waitFor(600.seconds, "the restart rolled both instances") {
      val now = servicePods.map(_.getMetadata.getName).toSet
      now.size == 2 && now.intersect(before).isEmpty
    }
    awaitApplied(id, 300.seconds)
    waitFor(60.seconds, "the new instances reading the subject as erased")(
      readsErasedEverywhere(Seq("r1"))
    )
  }

  test("5. the control plane's own identity is refused a key; a service of the project is not") {
    val controlPlane = identityOf(Namespace, "ankka-controlplane-service-tls")
    val refused      = channelAs(controlPlane, Project, 20.seconds)
    assert(refused.exists(_.contains("not-admitted")), s"the control plane was answered: $refused")
    val service  = identityOf(ServiceNamespace, s"$Service-service-tls")
    val answered = channelAs(service, Project, 10.seconds)
    assert(
      answered.nonEmpty && !answered.exists(_.contains("not-admitted")),
      s"a service was: $answered"
    )
  }

  test("6. a keyring restarted replays the log from both copies, neither behind") {
    val before = podsOf(KeyringNamespace, "ankka-keyring").map(_.getMetadata.getName).toSet
    k8s
      .apps()
      .deployments()
      .inNamespace(KeyringNamespace)
      .withName("ankka-keyring")
      .rolling()
      .restart(): Unit
    waitFor(600.seconds, "the keyring rolled") {
      val now =
        podsOf(KeyringNamespace, "ankka-keyring").filter(ready).map(_.getMetadata.getName).toSet
      now.size == 2 && now.intersect(before).isEmpty
    }
    val logs = podsOf(KeyringNamespace, "ankka-keyring").map(pod =>
      k8s
        .pods()
        .inNamespace(KeyringNamespace)
        .withName(pod.getMetadata.getName)
        .inContainer("ankka-keyring")
        .getLog
    )
    val replays = logs.flatMap(_.linesIterator.filter(_.contains("keyring replay:")))
    assert(replays.nonEmpty, "no keyring logged its replay")
    replays.foreach { line =>
      assert(line.contains("copies=2"), line)
      assert(line.contains("behind=neither"), line)
      val replayed =
        """replayed=(\d+)""".r.findFirstMatchIn(line).map(_.group(1).toInt).getOrElse(0)
      assert(replayed >= 7, s"the log holds at least the seven erasures applied: $line")
    }
    // And what was erased stays erased.
    waitFor(120.seconds, "the sample still reading every subject as erased") {
      readsErasedEverywhere((1 to 6).map(i => s"c$i") :+ "r1")
    }
  }
