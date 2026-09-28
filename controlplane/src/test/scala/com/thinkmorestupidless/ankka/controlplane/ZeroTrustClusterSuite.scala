package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.crd.{
  AnkkaSerialization,
  AnkkaService,
  AnkkaServiceSpec,
  AutoscalingSpec
}
import com.thinkmorestupidless.ankka.operator.{
  ClusterImages,
  GatewayStack,
  InPod,
  Membership,
  Operator,
  PkiStack,
  ServiceReconciler,
  Settings as OperatorSettings,
  Transition
}
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Zero trust in a real cluster: every guarantee the platform makes about who may connect to what,
 * measured against the real sample on k3s, whose network enforces policy (kube-router), through the
 * real gateway, on CloudNativePG.
 *
 * Three services, all the shopping cart image: `carts` in `checkout` (three instances, exposed),
 * `orders` in `checkout`, and `orders` in `billing` — the same name in another project, which is
 * what makes "a named service means this project's" observable.
 */
class ZeroTrustClusterSuite extends munit.FunSuite:

  override def munitIgnore: Boolean   = sys.props.get("ankka.cluster.tests").contains("off")
  override val munitTimeout: Duration = 30.minutes

  private val K3sImage    = "rancher/k3s:v1.35.1-k3s1"
  private val SampleImage = "sample-shopping-cart:latest"
  private val Prefix      = "ankka"
  private val BaseDomain  = "test.local"
  private val Checkout    = s"$Prefix-checkout"
  private val Billing     = s"$Prefix-billing"

  private var k3s: K3sContainer     = null
  private var k8s: KubernetesClient = null
  private var operator: Operator    = null
  private var ca: Path              = null
  private var httpsPort: Int        = 0

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.withExposedPorts(6443, GatewayStack.HttpsNodePort, GatewayStack.HttpNodePort)
      k3s.start()
      ClusterImages.importInto(k3s, SampleImage)
      httpsPort = k3s.getMappedPort(GatewayStack.HttpsNodePort)

      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      k8s.load(getClass.getResourceAsStream("/ankka/crd/ankkaservice.yaml")).serverSideApply(): Unit
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
      waitFor(180.seconds, "CNPG is ready") {
        readyReplicas("cnpg-system", "cnpg-controller-manager") > 0
      }
      // Installs the authorities too.
      GatewayStack.install(k3s, k8s, PkiStack.repoRoot, BaseDomain)
      ca = GatewayStack.exportCa(k8s)

      val settings = OperatorSettings.default.copy(
        resyncInterval = 2.seconds,
        baseDomain = Some(BaseDomain)
      )
      operator = new Operator(k8s, settings, ServiceReconciler(k8s, settings))
      operator.start()

      write(Checkout, spec("checkout", "carts", instances = 3, exposed = true))
      write(Checkout, spec("checkout", "orders"))
      write(Billing, spec("billing", "orders"))
      for (ns, name, n) <- Vector(
          (Checkout, "carts", 3),
          (Checkout, "orders", 1),
          (Billing, "orders", 1)
        )
      do
        waitFor(420.seconds, s"$ns/$name Ready $n/$n") {
          status(ns, name).exists(s => s.lifecycle == "Ready" && s.readyInstances == n)
        }

  override def afterAll(): Unit =
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  // ── helpers ──────────────────────────────────────────────────────────────────────────────

  private def spec(project: String, name: String, instances: Int = 1, exposed: Boolean = false) =
    AnkkaServiceSpec(
      projectId = project,
      serviceName = name,
      generation = 1L,
      image = SampleImage,
      progressDeadlineSeconds = 300,
      port = Some(9000),
      exposed = exposed,
      autoscaling = AutoscalingSpec(minInstances = instances)
    )

  private def write(namespace: String, s: AnkkaServiceSpec): Unit =
    k8s
      .namespaces()
      .resource(
        new io.fabric8.kubernetes.api.model.NamespaceBuilder()
          .withMetadata(
            new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
              .withName(namespace)
              .withLabels(java.util.Map.of("app.kubernetes.io/managed-by", "ankka"))
              .build()
          )
          .build()
      )
      .serverSideApply(): Unit
    k8s
      .resources(classOf[AnkkaService])
      .inNamespace(namespace)
      .resource(AnkkaService(namespace, s.serviceName, s))
      .fieldManager("ankka-test")
      .forceConflicts()
      .serverSideApply(): Unit

  private def status(namespace: String, name: String) =
    Option(k8s.resources(classOf[AnkkaService]).inNamespace(namespace).withName(name).get())
      .flatMap(r => Option(r.getStatus))

  private def pods(namespace: String, name: String): Vector[Pod] =
    k8s
      .pods()
      .inNamespace(namespace)
      .withLabel("app.kubernetes.io/name", name)
      .list()
      .getItems
      .asScala
      .toVector
      .filter(p => Option(p.getMetadata.getDeletionTimestamp).isEmpty)

  private def aPod(namespace: String, name: String): String =
    pods(namespace, name).headOption
      .getOrElse(fail(s"no pod for $namespace/$name"))
      .getMetadata
      .getName

  private def readyReplicas(namespace: String, name: String): Int =
    Option(k8s.apps().deployments().inNamespace(namespace).withName(name).get())
      .flatMap(d => Option(d.getStatus))
      .flatMap(s => Option(s.getReadyReplicas))
      .map(_.intValue)
      .getOrElse(0)

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = timeout.fromNow
    var passed   = false
    while !passed && deadline.hasTimeLeft() do
      passed =
        try check
        catch case _: Exception => false
      if !passed then Thread.sleep(1000)
    if !passed then fail(s"$what did not happen within $timeout")

  private def membership(namespace: String, pod: Pod): Set[String] =
    val (code, body) = InPod.curl(
      k3s,
      namespace,
      pod.getMetadata.getName,
      s"https://${pod.getStatus.getPodIP}:7626/cluster/members",
      identity = "cluster",
      verifyHost = false
    )
    if code != 200 then Set.empty
    else
      """\{"node":"([^"]+)"[^}]*?"status":"([A-Za-z]+)"""".r
        .findAllMatchIn(body)
        .collect { case m if m.group(2) == "Up" => m.group(1) }
        .toSet

  /** A busybox pod in a namespace ankka does not manage: no labels, no certificate, no identity. */
  private lazy val stranger: String =
    PkiStack.kubectl(
      k3s,
      "run",
      "stranger",
      "-n",
      "default",
      "--image=busybox:1.36",
      "--restart=Never",
      "--command",
      "--",
      "sleep",
      "3600"
    ): Unit
    PkiStack.kubectl(
      k3s,
      "wait",
      "-n",
      "default",
      "--for=condition=Ready",
      "pod/stranger",
      "--timeout=120s"
    ): Unit
    "stranger"

  /** Whether a TCP connection from the stranger to `ip:port` opens at all. */
  private def strangerConnects(ip: String, port: Int): Boolean =
    k3s
      .execInContainer(
        "kubectl",
        "exec",
        "-n",
        "default",
        stranger,
        "--",
        "sh",
        "-c",
        s"echo | nc -w 3 $ip $port"
      )
      .getExitCode == 0

  /**
   * `curl` on the host, through the gateway, verifying against the installation's exported root.
   */
  private def throughGateway(hostname: String, path: String): (Int, String) =
    val args = Vector(
      "curl",
      "-sS",
      "--cacert",
      ca.toString,
      "--resolve",
      s"$hostname:$httpsPort:127.0.0.1",
      "-m",
      "10",
      "-w",
      "\n%{http_code}",
      s"https://$hostname:$httpsPort$path"
    )
    val process = new ProcessBuilder(args*).redirectErrorStream(true).start()
    val output  = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    process.waitFor()
    val lines = output.linesIterator.toVector
    (lines.lastOption.flatMap(_.trim.toIntOption).getOrElse(0), lines.dropRight(1).mkString("\n"))

  // ── 1. cluster traffic ───────────────────────────────────────────────────────────────────

  test("1. three instances form one cluster over mutual TLS, and every pod agrees") {
    val views = pods(Checkout, "carts").map(membership(Checkout, _))
    assertEquals(views.size, 3)
    assertEquals(Membership.disjointClusters(views), 1, views.toString)
    views.foreach(v => assertEquals(v.size, 3, v.toString))
  }

  test(
    "2. from outside the service's pods, the cluster ports refuse even to connect; readiness answers"
  ) {
    val ip = pods(Checkout, "carts").head.getStatus.getPodIP
    assert(!strangerConnects(ip, 17355), "remoting admitted a stranger")
    assert(!strangerConnects(ip, 7626), "management admitted a stranger")
    assert(!strangerConnects(ip, 9000), "the HTTP port admitted a pod with no platform identity")
    assert(strangerConnects(ip, 7627), "the probe port must admit anyone")
  }

  test(
    "3. a certificate for another service, from inside the service's own pod, fails the handshake"
  ) {
    // The network admits a carts pod; the certificate is the orders service's cluster certificate,
    // from the same authority. Management must refuse it: a peer is a node of *this* service.
    val secret = k8s.secrets().inNamespace(Checkout).withName("orders-cluster-tls").get()
    val carts  = pods(Checkout, "carts").head
    for key <- Vector("tls.crt", "tls.key", "ca.crt") do
      val result = k3s.execInContainer(
        "kubectl",
        "exec",
        "-n",
        Checkout,
        carts.getMetadata.getName,
        "--",
        "sh",
        "-c",
        s"mkdir -p /tmp/foreign && echo '${secret.getData.get(key)}' | base64 -d > /tmp/foreign/$key"
      )
      assertEquals(result.getExitCode, 0, result.getStderr)
    val attempt = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      Checkout,
      carts.getMetadata.getName,
      "--",
      "curl",
      "-sS",
      "-m",
      "10",
      "--insecure",
      "--cert",
      "/tmp/foreign/tls.crt",
      "--key",
      "/tmp/foreign/tls.key",
      s"https://${carts.getStatus.getPodIP}:7626/cluster/members"
    )
    assertNotEquals(attempt.getExitCode, 0, attempt.getStdout)
    assertEquals(pods(Checkout, "carts").map(membership(Checkout, _)).map(_.size).toSet, Set(3))
  }

  // ── 2. callers ───────────────────────────────────────────────────────────────────────────

  private def callCarts(fromNamespace: String, path: String): (Int, String) =
    InPod.curl(
      k3s,
      fromNamespace,
      aPod(fromNamespace, "orders"),
      s"https://carts.$Checkout.svc.cluster.local:9000$path"
    )

  test("4. a request from a service carries its caller, and the callee's ACL decides") {
    assertEquals(
      callCarts(Checkout, "/callers/whoami"),
      (200, "the orders service in project checkout")
    )
    assertEquals(callCarts(Checkout, "/callers/only-orders")._1, 200)
    // Same name, another project: a named service means this project's.
    assertEquals(
      callCarts(Billing, "/callers/whoami"),
      (200, "the orders service in project billing")
    )
    assertEquals(callCarts(Billing, "/callers/only-orders")._1, 403)
    assertEquals(callCarts(Checkout, "/callers/only-self")._1, 403)
  }

  test("5. a request through the gateway reads as the internet") {
    var last = (0, "")
    val served =
      try
        waitFor(180.seconds, "the route serves") {
          last = throughGateway(s"carts-checkout.$BaseDomain", "/callers/whoami")
          last._1 == 200
        }
        true
      catch case _: munit.FailException => false
    if !served then
      def get(args: String*) = k3s.execInContainer(("kubectl" +: args)*)
      val route = get("get", "httproute", "-n", Checkout, "carts", "-o", "jsonpath={.status}")
      val policy =
        get("get", "backendtlspolicy", "-n", Checkout, "carts", "-o", "jsonpath={.status}")
      val bundle = get("get", "configmap", "-n", Checkout, "ankka-service-ca", "-o", "name")
      val envoy = get(
        "logs",
        "-n",
        "envoy-gateway-system",
        "-l",
        "gateway.envoyproxy.io/owning-gateway-name=ankka",
        "--all-containers",
        "--tail=30"
      )
      val controller =
        get("logs", "-n", "envoy-gateway-system", "deploy/envoy-gateway", "--tail=30")
      fail(
        s"""the route never served; last answer $last
           |route: ${route.getStdout}${route.getStderr}
           |backend TLS policy: ${policy.getStdout}${policy.getStderr}
           |service CA bundle: ${bundle.getStdout}${bundle.getStderr}
           |envoy: ${envoy.getStdout.takeRight(4000)}
           |controller: ${controller.getStdout.takeRight(4000)}""".stripMargin
      )
    assertEquals(
      throughGateway(s"carts-checkout.$BaseDomain", "/callers/whoami"),
      (200, "the internet, through the gateway")
    )
  }

  test("6. a service calls another as itself, through the service client") {
    val (code, body) = InPod.curl(
      k3s,
      Checkout,
      aPod(Checkout, "orders"),
      s"https://orders.$Checkout.svc.cluster.local:9000/callers/call/carts"
    )
    assertEquals((code, body), (200, "the orders service in project checkout"))
  }

  // ── 3. the database ──────────────────────────────────────────────────────────────────────

  test("7. every database session is TLS, authenticated by certificate, and no password exists") {
    val result = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      Checkout,
      "ankka-db-1",
      "-c",
      "postgres",
      "--",
      "psql",
      "-U",
      "postgres",
      "-tA",
      "-c",
      "select a.usename, s.ssl, s.client_dn from pg_stat_ssl s join pg_stat_activity a using (pid) " +
        "where a.usename in ('carts', 'orders') order by 1"
    )
    assertEquals(result.getExitCode, 0, result.getStderr)
    val rows = result.getStdout.linesIterator.filter(_.nonEmpty).toVector
    assert(rows.nonEmpty, "no sessions for the services' roles")
    rows.foreach { row =>
      val Array(user, ssl, dn) = row.split('|')
      assertEquals(ssl, "t", row)
      assertEquals(dn, s"/CN=$user", row)
    }
    for name <- Vector("carts", "orders") do
      val keys =
        k8s.secrets().inNamespace(Checkout).withName(s"$name-db").get().getData.keySet.asScala
      assert(!keys.exists(_.toLowerCase.contains("password")), keys.toString)
  }

  test("8. another project's workload cannot reach this project's database at all") {
    val db = k8s.services().inNamespace(Checkout).withName("ankka-db-rw").get().getSpec.getClusterIP
    val attempt = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      Billing,
      aPod(Billing, "orders"),
      "--",
      "sh",
      "-c",
      s"timeout 5 bash -c 'echo > /dev/tcp/$db/5432'"
    )
    assertNotEquals(
      attempt.getExitCode,
      0,
      "billing's pod opened a connection to checkout's database"
    )
  }

  // ── 4. rotation ──────────────────────────────────────────────────────────────────────────

  test(
    "9. renewing the certificates under load fails no request, restarts nothing, changes no membership"
  ) {
    val before = pods(Checkout, "carts").map(p => p.getMetadata.getName -> restarts(p)).toMap
    val orders = aPod(Checkout, "orders")
    // A request a second for two minutes, from inside the orders pod, recorded there.
    val url = s"https://carts.$Checkout.svc.cluster.local:9000/callers/whoami"
    val dir = "/var/run/secrets/ankka/service"
    k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      Checkout,
      orders,
      "--",
      "sh",
      "-c",
      s"nohup sh -c 'for i in $$(seq 120); do curl -s -o /dev/null -m 5 -w \"%{http_code}\\n\" " +
        s"--cert $dir/tls.crt --key $dir/tls.key --cacert $dir/ca.crt $url >> /tmp/codes; sleep 1; done' " +
        ">/dev/null 2>&1 &"
    ): Unit
    val renewed = Vector("carts-cluster", "carts-service", "carts-database")
    val serials = renewed.map(c => c -> secretSerial(c)).toMap
    for certificate <- renewed do
      PkiStack.kubectl(
        k3s,
        "patch",
        "certificate",
        certificate,
        "-n",
        Checkout,
        "--subresource=status",
        "--type=merge",
        "-p",
        """{"status":{"conditions":[{"type":"Issuing","status":"True","reason":"ManuallyTriggered","message":"suite","lastTransitionTime":"2026-01-01T00:00:00Z"}]}}"""
      ): Unit
    for certificate <- renewed do
      waitFor(120.seconds, s"$certificate is reissued")(
        secretSerial(certificate) != serials(certificate)
      )
    // The mounted files follow the Secret on the kubelet's schedule.
    val carts = aPod(Checkout, "carts")
    waitFor(150.seconds, "the pod's mounted cluster certificate is the new one") {
      val mounted = k3s
        .execInContainer(
          "kubectl",
          "exec",
          "-n",
          Checkout,
          carts,
          "--",
          "cat",
          "/var/run/secrets/ankka/cluster/tls.crt"
        )
        .getStdout
      mounted.nonEmpty && mounted == pem(
        k8s
          .secrets()
          .inNamespace(Checkout)
          .withName("carts-cluster-tls")
          .get()
          .getData
          .get("tls.crt")
      )
    }
    Thread.sleep(125000) // the load runs its full two minutes
    val codes = k3s
      .execInContainer("kubectl", "exec", "-n", Checkout, orders, "--", "cat", "/tmp/codes")
      .getStdout
      .linesIterator
      .filter(_.nonEmpty)
      .toVector
    assert(codes.size >= 100, s"the load did not run: ${codes.size} requests")
    assertEquals(codes.filterNot(_ == "200"), Vector.empty, "a request failed during the renewal")
    assertEquals(
      pods(Checkout, "carts").map(p => p.getMetadata.getName -> restarts(p)).toMap,
      before
    )
    assertEquals(pods(Checkout, "carts").map(membership(Checkout, _)).map(_.size).toSet, Set(3))
    // And the database, whose client certificate was renewed too, still answers.
    assertEquals(callCarts(Checkout, "/carts/after-renewal")._1, 200)
  }

  private def restarts(p: Pod): Int =
    Option(p.getStatus.getContainerStatuses)
      .map(_.asScala.map(_.getRestartCount.intValue).sum)
      .getOrElse(0)

  private def secretSerial(certificate: String): String =
    Option(k8s.secrets().inNamespace(Checkout).withName(s"$certificate-tls").get())
      .map(_.getData.get("tls.crt"))
      .getOrElse("")

  private def pem(b64: String): String =
    new String(java.util.Base64.getDecoder.decode(b64), StandardCharsets.US_ASCII)

  // ── 5. the transition ────────────────────────────────────────────────────────────────────

  test(
    "10. a Deployment that predates mutual TLS is replaced once, not rolled, and keeps its data"
  ) {
    // Written before: a cart through billing's orders, which is about to transition.
    val add = InPod.curl(
      k3s,
      Billing,
      aPod(Billing, "orders"),
      s"https://orders.$Billing.svc.cluster.local:9000/carts/kept/items",
      method = "POST",
      body = Some("""{"productId":"p1","name":"Kept","quantity":1}""")
    )
    assert(add._1 / 100 == 2, add.toString)
    // What an older operator rendered: a template without the transport label.
    PkiStack.kubectl(
      k3s,
      "patch",
      "deployment",
      "orders",
      "-n",
      Billing,
      "--type=json",
      "-p",
      """[{"op":"remove","path":"/spec/template/metadata/labels/ankka.thinkmorestupidless.com~1transport"}]"""
    ): Unit
    waitFor(120.seconds, "the transition is reported") {
      status(Billing, "orders").flatMap(_.detail).contains(Transition.Detail)
    }
    waitFor(300.seconds, "the service is Ready again") {
      status(Billing, "orders").exists(s => s.lifecycle == "Ready" && s.readyInstances == 1)
    }
    val labels = k8s
      .apps()
      .deployments()
      .inNamespace(Billing)
      .withName("orders")
      .get()
      .getSpec
      .getTemplate
      .getMetadata
      .getLabels
      .asScala
    assertEquals(labels.get("ankka.thinkmorestupidless.com/transport"), Some("tls"))
    val (code, body) = InPod.curl(
      k3s,
      Billing,
      aPod(Billing, "orders"),
      s"https://orders.$Billing.svc.cluster.local:9000/carts/kept"
    )
    assertEquals(code, 200, body)
    assert(body.contains("Kept"), body)
  }

  test(
    "11. a runtime that predates mutual TLS opens no probe port, and says so in the kubelet's words"
  ) {
    write(
      Billing,
      spec("billing", "ancient").copy(
        image = "registry.k8s.io/pause:3.9",
        port = None,
        progressDeadlineSeconds = 60
      )
    )
    waitFor(240.seconds, "the ancient image is Failed with the probe's failure") {
      status(Billing, "ancient").exists(s =>
        s.lifecycle == "Failed" && s.detail.exists(_.contains("7627"))
      )
    }
  }
