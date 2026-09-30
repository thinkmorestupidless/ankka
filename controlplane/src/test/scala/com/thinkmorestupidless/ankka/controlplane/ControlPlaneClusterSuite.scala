package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.fabric8.kubernetes.api.model.{ConfigMapBuilder, ObjectMetaBuilder, Pod}
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import com.thinkmorestupidless.ankka.operator.{
  ClusterImages,
  GatewayStack,
  KeycloakStack,
  Membership,
  Operator,
  ServiceReconciler,
  Settings as OperatorSettings
}
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * The control plane itself at three instances — deployed INTO k3s from the shipped manifests,
 * because "three instances" has no meaning in-process.
 *
 * Driven through its HTTP API from the k3s node rather than through the CLI: the client has to
 * survive the pod it was talking to being replaced (case 5), and a port-forward would not. The CLI
 * is a thin client over exactly these routes, and `EndToEndClusterSuite` already proves the two
 * agree.
 *
 * What it settles (research R12): that the projector sweeps once, that a status seen by three nodes
 * is recorded once, and that nothing else in the control plane assumed it was alone.
 */
class ControlPlaneClusterSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout: FiniteDuration = 20.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  private val K3sImage          = "rancher/k3s:v1.35.1-k3s1"
  private val BaseDomain        = "test.local"
  private val ControlPlaneImage = "ankka-controlplane:latest"
  private val SampleImage       = "sample-shopping-cart:latest"
  // A real token from the deployed identity provider, through the gateway, for a client this
  // suite creates: the deployed control plane verifies against the in-cluster key set and expects
  // the issuer it derives from ANKKA_BASE_DOMAIN and ANKKA_HTTPS_PORT — so this is also the proof
  // that the derivation agrees with what Keycloak writes into a token (research R3).
  private lazy val Token: String =
    KeycloakStack.mintToken(
      GatewayStack.exportCa(k8s),
      BaseDomain,
      k3s.getMappedPort(GatewayStack.HttpsNodePort),
      "e2e-cli",
      "e2e-secret"
    )
  private val Namespace = "ankka-controlplane"
  private val Project   = "checkout"

  // The console (feature 017): deployed beside the control plane, signed in to through the gateway
  // as a person with a password, the way a browser does it.
  private val ConsoleImage     = "ankka-console:latest"
  private val ConsoleNamespace = "ankka-console"
  private val ConsoleUser      = "consoleuser"
  private val ConsolePassword  = "console-password"
  private def consoleAuthority =
    s"console.$BaseDomain:${k3s.getMappedPort(GatewayStack.HttpsNodePort)}"

  private var k3s: K3sContainer     = null
  private var k8s: KubernetesClient = null
  private var operator: Operator    = null

  /** The repository root, from wherever sbt forked us. */
  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("kustomization")))
      .getOrElse(fail("could not find the repository root"))

  private def applyManifest(relative: String): Unit =
    k8s.load(Files.newInputStream(repoRoot.resolve(relative))).serverSideApply(): Unit

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      // The gateway's HTTPS NodePort, mapped out to the host so the real CLI can reach the
      // control plane's external address the way a developer's machine would (feature 005).
      k3s.withExposedPorts(6443, GatewayStack.HttpsNodePort)
      k3s.start()
      ClusterImages.importInto(k3s, ControlPlaneImage)
      ClusterImages.importInto(k3s, SampleImage)

      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()

      // What deploy-local.sh does, in the same order: CNPG, the CRD, the namespace, the schema
      // ConfigMap from the single-copy DDL plus the grants, then the manifests.
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
      waitFor(120.seconds) {
        val d =
          k8s
            .apps()
            .deployments()
            .inNamespace("cnpg-system")
            .withName("cnpg-controller-manager")
            .get()
        d != null && Option(d.getStatus).flatMap(s => Option(s.getReadyReplicas)).exists(_ > 0)
      }
      applyManifest("kustomization/components/crd/ankkaservice.yaml")
      applyManifest("kustomization/components/controlplane/namespace.yaml")

      val ddl = repoRoot.resolve("modules/runtime/src/main/resources/ankka/ddl")
      val data = Files
        .list(ddl)
        .iterator()
        .asScala
        .filter(_.toString.endsWith(".sql"))
        .map { f =>
          f.getFileName.toString -> Files.readString(f)
        }
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

      applyManifest("kustomization/components/postgres/cluster.yaml")
      applyManifest("kustomization/components/controlplane/controlplane-rbac.yaml")
      applyManifest("kustomization/components/controlplane/service.yaml")
      // cert-manager, Envoy Gateway, the platform's Gateway and the local CA — so the control
      // plane's own route can be proven, and the CLI driven over verified TLS. Before the route
      // below, for the same reason CNPG is installed before anything references a Cluster: the
      // CRD has to exist first.
      GatewayStack.install(k3s, k8s, repoRoot, BaseDomain)
      // The identity provider (feature 008), as deploy-local.sh installs it, and a client whose
      // service account is a platform admin — what every request in this suite authenticates as.
      KeycloakStack.install(
        k3s,
        k8s,
        repoRoot,
        BaseDomain,
        k3s.getMappedPort(GatewayStack.HttpsNodePort)
      )
      KeycloakStack.createServiceClient(k3s, "e2e-cli", "e2e-secret", platformAdmin = true)

      // The control plane's own certificates, policies and backend TLS (feature 014), before the
      // Deployment that mounts them.
      applyManifest("kustomization/components/controlplane/zero-trust.yaml")

      // The base domain the overlay would have fanned out, filled in by hand here — and the HTTPS
      // port clients actually reach the gateway on, which the deploy script substitutes the same
      // way: the control plane derives the issuer it expects from both.
      val httpsPort = k3s.getMappedPort(GatewayStack.HttpsNodePort)
      for name <- Vector("deployment.yaml", "httproute.yaml") do
        // The placeholder only — not the `ANKKA_BASE_DOMAIN` variable *name* beside it, which a
        // plain replace mangled into `ANKKA_test.local`, leaving the pod with no base domain and,
        // since feature 008, no issuer to derive: every instance crash-looped at startup.
        val yaml = Files
          .readString(repoRoot.resolve(s"kustomization/components/controlplane/$name"))
          .replaceAll("(?<!ANKKA_)BASE_DOMAIN", BaseDomain)
          .replace("""value: "443"""", s"""value: "$httpsPort"""")
        k8s.load(new java.io.ByteArrayInputStream(yaml.getBytes("UTF-8"))).serverSideApply(): Unit

      // The console (feature 017), from its component's own files, with what the overlay's
      // replacements would fill in: its hostname and its authority, host and port together.
      ClusterImages.importInto(k3s, ConsoleImage)
      for name <- Vector(
          "namespace.yaml",
          "serviceaccount.yaml",
          "secrets.yaml",
          "zero-trust.yaml",
          "service.yaml",
          "deployment.yaml",
          "httproute.yaml"
        )
      do
        val yaml = Files
          .readString(repoRoot.resolve(s"kustomization/components/console/$name"))
          // The placeholders only, not the variable names that contain them (`ANKKA_CONSOLE_AUTHORITY`).
          .replaceAll("(?<!ANKKA_)CONSOLE_AUTHORITY", consoleAuthority)
          .replaceAll("(?<!ANKKA_)BASE_DOMAIN", BaseDomain)
        k8s.load(new java.io.ByteArrayInputStream(yaml.getBytes("UTF-8"))).serverSideApply(): Unit
      KeycloakStack.createUser(k3s, ConsoleUser, ConsolePassword, platformAdmin = true)

      // The operator, in-process on admin credentials: not what this suite is about.
      val operatorSettings = OperatorSettings.default.copy(resyncInterval = 2.seconds)
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
      operator.start()

  override def afterAll(): Unit =
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  private def waitFor(timeout: FiniteDuration)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(500)
    if !passed then fail(s"condition did not hold within $timeout")

  private def pods: Vector[Pod] =
    k8s
      .pods()
      .inNamespace(Namespace)
      .withLabel("app.kubernetes.io/name", "ankka-controlplane")
      .list()
      .getItems
      .asScala
      .toVector

  private def readyPods: Vector[Pod] =
    pods.filter(p =>
      Option(p.getStatus.getContainerStatuses).exists(_.asScala.headOption.exists(_.getReady))
    )

  private def nodeExec(command: String*): (Int, String) =
    val result = k3s.execInContainer(command*)
    (result.getExitCode, result.getStdout + result.getStderr)

  private val MemberPattern = """\{"node":"([^"]+)"[^}]*?"status":"([A-Za-z]+)"""".r

  private def membership(pod: Pod): Set[String] =
    Option(pod.getStatus.getPodIP).fold(Set.empty[String]) { ip =>
      // Management admits only the control plane's own cluster certificate, which its pods hold.
      val (status, body) = com.thinkmorestupidless.ankka.operator.InPod.curl(
        k3s,
        Namespace,
        pod.getMetadata.getName,
        s"https://$ip:7626/cluster/members",
        identity = "cluster",
        verifyHost = false
      )
      if status != 200 then Set.empty
      else
        MemberPattern
          .findAllMatchIn(body)
          .collect { case m if m.group(2) == "Up" => m.group(1) }
          .toSet
    }

  /**
   * The control plane's API, through its Service's clusterIP.
   *
   * Issued from inside one of the control plane's own pods with `curl`, because the k3s node's
   * `wget` is BusyBox's: POST only, no PUT, and PUT is the apply. The pod is only a place to run
   * `curl` from — the request still goes to the Service and lands on whichever instance it picks.
   * Chosen fresh on every call, ready and not terminating, and the *last* by name so that a test
   * deleting `pods.head` never pulls the client out from under itself.
   */
  private def api(method: String, path: String, body: Option[String] = None): (Int, String) =
    // By the Service's name, over mutual TLS with the pod's own service certificate (feature 014).
    val target = s"https://ankka-controlplane.$Namespace.svc.cluster.local:9000$path"
    val dir    = "/var/run/secrets/ankka/service"
    val from = readyPods
      .filter(_.getMetadata.getDeletionTimestamp == null)
      .lastOption
      .getOrElse(pods.last)
    val curl = Vector(
      "curl",
      "-sS",
      "-f",
      "-m",
      "10",
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
    ) ++ body.toVector.flatMap(b => Vector("-d", b)) :+ target
    nodeExec(
      (Vector("kubectl", "exec", "-n", Namespace, from.getMetadata.getName, "--") ++ curl)*
    )

  private val OldestPattern = """"oldest":"[^"]*@([0-9.]+):""".r

  /** The pod hosting the cluster singletons: the oldest member, as the cluster reports it. */
  private def oldestPod: Option[Pod] =
    pods.iterator
      .map(_.getStatus.getPodIP)
      .filter(_ != null)
      .flatMap(ip =>
        pods
          .find(_.getStatus.getPodIP == ip)
          .map(p =>
            com.thinkmorestupidless.ankka.operator.InPod.curl(
              k3s,
              Namespace,
              p.getMetadata.getName,
              s"https://$ip:7626/cluster/members",
              identity = "cluster",
              verifyHost = false
            )
          )
      )
      .collectFirst { case (200, body) => body }
      .flatMap(body => OldestPattern.findFirstMatchIn(body).map(_.group(1)))
      .flatMap(ip => pods.find(_.getStatus.getPodIP == ip))

  private def psql(sql: String): (Int, String) =
    nodeExec(
      "kubectl",
      "exec",
      "-n",
      Namespace,
      "ankka-controlplane-db-1",
      "-c",
      "postgres",
      "--",
      "psql",
      "-U",
      "postgres",
      "-d",
      "ankka",
      "-tA",
      "-c",
      sql
    )

  /**
   * Events in one service's journal — all of them, or only one type.
   *
   * The row is a CBOR `JournalRecord` with ankka's JSON event embedded as bytes, so the event's
   * `"type"` discriminator is greppable in the raw payload without decoding the envelope.
   */
  private def journalEvents(serviceName: String, ofType: Option[String] = None): Int =
    val typeFilter =
      ofType.fold("")(t => s""" and encode(event_payload, 'escape') like '%"type":"$t"%'""")
    val (code, out) = psql(
      s"select count(*) from event_journal where persistence_id = 'service|$Project/$serviceName'$typeFilter;"
    )
    assertEquals(code, 0, out)
    out.trim.toInt

  /**
   * Only one service in this suite has to *run*: svc1, which case 3 watches reach `Ready`. Every
   * other one exists to be counted, re-created or applied under load, and a JVM apiece for those
   * starved the k3s node until the control plane answered in seconds rather than milliseconds
   * (measured: median 5.2s per GET with five sample pods up). `pause` costs nothing, and with
   * `"http": false` it is a legal descriptor that simply never becomes Ready.
   */
  private def descriptor(name: String, real: Boolean = false) =
    if real then s"""{"name":"$name","service":{"image":"$SampleImage"}}"""
    else s"""{"name":"$name","service":{"image":"registry.k8s.io/pause:3.9","http":false}}"""

  test("1. three control-plane instances form one cluster from the shipped manifests") {
    try waitFor(420.seconds)(readyPods.size == 3)
    catch
      case failure: Throwable =>
        // Say what the pods were doing: an image that will not start, a node out of memory and a
        // cluster that will not form look identical from a ready count.
        val report = k8s.pods().inNamespace(Namespace).list().getItems.asScala.map { pod =>
          val status = Option(pod.getStatus)
          val containers = status.toList
            .flatMap(_.getContainerStatuses.asScala)
            .map(c =>
              s"${c.getName} ready=${c.getReady} restarts=${c.getRestartCount} state=${c.getState}"
            )
          val conditions = status.toList
            .flatMap(_.getConditions.asScala)
            .map(c => s"${c.getType}=${c.getStatus}(${c.getReason})")
          s"${pod.getMetadata.getName}: phase=${status.map(_.getPhase)} $containers $conditions"
        }
        val logs = k8s
          .pods()
          .inNamespace(Namespace)
          .list()
          .getItems
          .asScala
          .headOption
          .map(pod =>
            scala.util
              .Try(
                k8s
                  .pods()
                  .inNamespace(Namespace)
                  .withName(pod.getMetadata.getName)
                  .tailingLines(40)
                  .getLog
              )
              .getOrElse("(no log)")
          )
        val node =
          k8s.nodes().list().getItems.asScala.headOption.map(n => s"allocatable=${n.getStatus.getAllocatable} conditions=${n.getStatus.getConditions.asScala.map(c => s"${c.getType}=${c.getStatus}")}")
        fail(
          s"three instances never became ready:\n  ${report.mkString("\n  ")}\n  node: $node\n  log of first pod:\n${logs.getOrElse("")}",
          failure
        )
    val views = pods.map(membership)
    assertEquals(Membership.disjointClusters(views), 1, views.toString)
    views.foreach(v => assertEquals(v.size, 3, v.toString))
    // One cluster, and every instance answers.
    for pod <- pods do
      val (code, out) = com.thinkmorestupidless.ankka.operator.InPod.curl(
        k3s,
        Namespace,
        pod.getMetadata.getName,
        s"https://${pod.getStatus.getPodIP}:9000/organizations/",
        verifyHost = false,
        headers = Seq(s"Authorization: Bearer $Token")
      )
      assertEquals(code, 200, s"${pod.getMetadata.getName}: $out")
  }

  test(
    "1a. the control plane's own ports refuse a pod with no platform identity; readiness answers"
  ) {
    // Feature 014: the control plane is policed like every service it deploys.
    com.thinkmorestupidless.ankka.operator.PkiStack.kubectl(
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
    com.thinkmorestupidless.ankka.operator.PkiStack.kubectl(
      k3s,
      "wait",
      "-n",
      "default",
      "--for=condition=Ready",
      "pod/stranger",
      "--timeout=120s"
    ): Unit
    val ip = readyPods.head.getStatus.getPodIP
    def connects(port: Int) =
      k3s
        .execInContainer(
          "kubectl",
          "exec",
          "-n",
          "default",
          "stranger",
          "--",
          "sh",
          "-c",
          s"echo | nc -w 3 $ip $port"
        )
        .getExitCode == 0
    assert(!connects(17355), "remoting admitted a stranger")
    assert(!connects(7626), "management admitted a stranger")
    assert(!connects(9000), "the API admitted a pod with no platform identity")
    assert(connects(7627), "readiness must admit anyone")
  }

  test("2. commands through the Service reach whichever instance, and land once") {
    assertEquals(api("POST", "/organizations/acme", Some("""{"name":"Acme"}"""))._1, 0)
    assertEquals(
      api(
        "POST",
        s"/projects/$Project",
        Some("""{"name":"Checkout","organizationId":"acme"}""")
      )._1,
      0
    )
    for i <- 1 to 5 do
      val (code, out) =
        api("PUT", s"/services/$Project/svc$i", Some(descriptor(s"svc$i", real = i == 1)))
      assertEquals(code, 0, out)
    // Projected exactly once each: one AnkkaService per service, at generation 1.
    waitFor(120.seconds) {
      (1 to 5).forall { i =>
        val r = k8s
          .resources(classOf[com.thinkmorestupidless.ankka.crd.AnkkaService])
          .inNamespace(s"ankka-$Project")
          .withName(s"svc$i")
          .get()
        r != null && r.getSpec.generation == 1L
      }
    }
    // One apply, one ServiceApplied — however many instances there are: a command through the
    // Service lands on one node's entity, once. (Observations are events too, and the operator
    // has been reporting since the resource appeared, so the count is of applies, not rows.)
    for i <- 1 to 5 do assertEquals(journalEvents(s"svc$i", Some("ServiceApplied")), 1, s"svc$i")
  }

  test(
    "3. a status seen by three instances is recorded once — the journal does not grow in steady state"
  ) {
    // svc1's operator reports arrive at all three nodes' watches. The entity's "identical
    // observation is refused" guard is what makes that cost commands, not events.
    waitFor(300.seconds) {
      val (_, out) = api("GET", s"/services/$Project/svc1")
      out.contains("\"lifecycle\":\"Ready\"")
    }
    val settled = journalEvents("svc1")
    Thread.sleep(45.seconds.toMillis)
    assertEquals(
      journalEvents("svc1"),
      settled,
      "the journal grew with nothing changing — duplicate observations"
    )
    // And every observation that did land was a change: with three watches reporting, a guard
    // that failed shows up as the same observation journaled twice in a row.
    val (code, rows) = psql(
      s"select encode(event_payload, 'escape') from event_journal " +
        s"where persistence_id = 'service|$Project/svc1' order by seq_nr;"
    )
    assertEquals(code, 0, rows)
    val observed = rows.linesIterator.filter(_.contains("\"type\":\"ServiceObserved\"")).toVector
    assert(observed.nonEmpty, "no observations reached the journal")
    observed.sliding(2).foreach {
      case Vector(a, b) => assertNotEquals(a, b, "an identical observation was journaled twice")
      case _            => ()
    }
  }

  test("4. the sweeper runs on one instance, and moves when that instance goes") {
    // Cluster singletons run on the oldest member; kill that pod and another must take over,
    // seen by an out-of-band deletion being repaired within a sweep or two.
    val host = oldestPod.getOrElse(fail("no pod reports the oldest member"))
    k8s.pods().inNamespace(Namespace).withName(host.getMetadata.getName).delete(): Unit
    waitFor(300.seconds)(
      readyPods.size == 3 && !pods.exists(_.getMetadata.getName == host.getMetadata.getName)
    )
    // The singleton moved: the cluster now names a surviving pod as oldest.
    waitFor(60.seconds)(oldestPod.exists(_.getMetadata.getName != host.getMetadata.getName))
    k8s
      .resources(classOf[com.thinkmorestupidless.ankka.crd.AnkkaService])
      .inNamespace(s"ankka-$Project")
      .withName("svc2")
      .delete(): Unit
    waitFor(120.seconds) {
      k8s
        .resources(classOf[com.thinkmorestupidless.ankka.crd.AnkkaService])
        .inNamespace(s"ankka-$Project")
        .withName("svc2")
        .get() != null
    }
  }

  test("5. commands keep being accepted while an instance is replaced") {
    // From a settled cluster: the previous case replaced a pod too, and a replacement still
    // joining and taking shards is not the steady state this case measures.
    waitFor(300.seconds)(
      readyPods.size == 3 && Membership.disjointClusters(pods.map(membership)) == 1
    )
    Thread.sleep(10000)
    val accepted          = AtomicInteger(0); val refused = AtomicInteger(0)
    val failures          = java.util.concurrent.ConcurrentLinkedQueue[String]()
    val latencies         = java.util.concurrent.ConcurrentLinkedQueue[Long]()
    @volatile var running = true
    val load = new Thread(() =>
      var n = 0
      while running do
        n += 1
        val t0              = System.nanoTime()
        val (list, listOut) = api("GET", s"/services/$Project")
        val ms              = (System.nanoTime() - t0) / 1_000_000
        latencies.add(ms): Unit
        if list == 0 then accepted.incrementAndGet(): Unit
        else
          refused.incrementAndGet(); failures.add(s"GET #$n (${ms}ms): $listOut"): Unit
        if n % 5 == 0 then
          val (code, out) =
            api("PUT", s"/services/$Project/under-load-$n", Some(descriptor(s"under-load-$n")))
          if code == 0 then accepted.incrementAndGet(): Unit
          else
            refused.incrementAndGet(); failures.add(s"PUT #$n: $out"): Unit
        Thread.sleep(100)
    )
    load.setDaemon(true); load.start()

    val victim = pods.head
    k8s.pods().inNamespace(Namespace).withName(victim.getMetadata.getName).delete(): Unit
    waitFor(300.seconds)(
      readyPods.size == 3 && !pods.exists(_.getMetadata.getName == victim.getMetadata.getName)
    )
    Thread.sleep(5000)
    running = false; load.join(10000)

    val total = accepted.get + refused.get
    val lat   = latencies.asScala.toVector.sorted
    val timing =
      if lat.isEmpty then "no timings"
      else s"GET latency min/median/max ${lat.head}/${lat(lat.size / 2)}/${lat.last}ms"
    assert(
      total > 20,
      s"only $total commands issued; $timing; refused: ${failures.asScala.mkString(" | ")}"
    )
    val rate = accepted.get.toDouble / total
    assert(
      rate >= 0.99,
      f"$rate%.3f accepted (${refused.get} refused of $total):\n${failures.asScala.mkString("\n")}"
    )
    // Everything applied under load exists.
    val (_, listing) = api("GET", s"/services/$Project")
    for name <- "under-load-\\d+".r.findAllIn(listing).toSet do assert(listing.contains(name))
  }

  // ---- Exposure (feature 005): the control plane's own route, and the CLI over verified TLS ----

  private def routeCondition(kind: String): Option[(Boolean, String)] =
    Option(
      k8s
        .resources(classOf[io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute])
        .inNamespace(Namespace)
        .withName("ankka-controlplane")
        .get()
    ).flatMap(r => Option(r.getStatus))
      .flatMap(s => Option(s.getParents))
      .flatMap(_.asScala.headOption)
      .flatMap(p => Option(p.getConditions))
      .flatMap(_.asScala.find(_.getType == kind))
      .map(c => (c.getStatus == "True", c.getReason))

  test("6. the control plane's route is accepted by the gateway and resolves its backend") {
    waitFor(120.seconds)(
      routeCondition("Accepted").exists(_._1) && routeCondition("ResolvedRefs").exists(_._1)
    )
  }

  /**
   * The real CLI, as a subprocess: `Main.main` on this JVM's classpath, with the JDK's hosts-file
   * override so `api.test.local` resolves to the mapped NodePort's host — an override that would
   * change name resolution for this whole test JVM if set here, which is why it is a subprocess.
   */
  private def cliProcess(config: Path, hosts: Path, args: String*): (Int, String) =
    val java = Paths.get(sys.props("java.home"), "bin", "java").toString
    val command = Vector(
      java,
      s"-Djdk.net.hosts.file=$hosts",
      s"-Dankka.config=$config",
      "-cp",
      sys.props("java.class.path"),
      "com.thinkmorestupidless.ankka.cli.Main"
    ) ++ args
    val process = new ProcessBuilder(command*).redirectErrorStream(true).start()
    val output  = new String(process.getInputStream.readAllBytes(), "UTF-8")
    (process.waitFor(), output)

  test(
    "7. the CLI works through https://api.<base> with the exported root — and refuses without it"
  ) {
    val port   = k3s.getMappedPort(GatewayStack.HttpsNodePort)
    val ca     = GatewayStack.exportCa(k8s)
    val config = Files.createTempFile("ankka-cli-tls", ".json")
    Files.delete(config)
    val hosts = Files.createTempFile("ankka-hosts", ".txt")
    Files.writeString(hosts, s"127.0.0.1 api.$BaseDomain\n")

    assertEquals(
      cliProcess(config, hosts, "config", "set", "url", s"https://api.$BaseDomain:$port")._1,
      0
    )
    assertEquals(cliProcess(config, hosts, "config", "set", "token", Token)._1, 0)
    assertEquals(cliProcess(config, hosts, "config", "set", "ca", ca.toString)._1, 0)

    val (code, out) = cliProcess(config, hosts, "services", "list", "-p", Project)
    assertEquals(code, 0, out)
    assert(out.contains("svc1"), out)

    // No root, no bypass: the only thing the CLI can do is say how to trust one.
    assertEquals(cliProcess(config, hosts, "config", "unset", "ca")._1, 0)
    val (refused, refusedOut) = cliProcess(config, hosts, "services", "list", "-p", Project)
    assertEquals(refused, 1, refusedOut)
    assert(refusedOut.contains("ankka config set ca"), refusedOut)
  }

  test("8. the shipped RBAC, both ways: it can read a log and cannot touch a workload") {
    // The manifest side is covered cheaply by LogsRbacSuite. This is the half that a manifest
    // test cannot do: asking the API server, as the control plane's own ServiceAccount rather
    // than as an admin. CLAUDE.md records why that distinction matters — the suites mostly use
    // admin credentials, so a *missing* verb fails silently in CI and loudly on a real deploy,
    // which has already happened once (`ensureNamespace` needed `patch` and had only `create`).
    val tokenResult =
      k3s.execInContainer(
        "kubectl",
        "create",
        "token",
        "ankka-controlplane",
        "-n",
        Namespace,
        "--duration=10m"
      )
    assertEquals(tokenResult.getExitCode, 0, tokenResult.getStderr)
    val token = tokenResult.getStdout.trim

    val restricted = new KubernetesClientBuilder()
      .withConfig(
        new io.fabric8.kubernetes.client.ConfigBuilder()
          .withMasterUrl(k8s.getConfiguration.getMasterUrl)
          .withTrustCerts(true)
          .withOauthToken(token)
          .build()
      )
      .build()

    try
      // Granted: it can find a pod and read what that pod printed. `ankka services logs` is
      // exactly these two calls, so this is the feature working rather than a proxy for it.
      val pods = restricted.pods().inNamespace(Namespace).list().getItems
      assert(!pods.isEmpty, "the control plane's own token could not list pods")

      val log = restricted
        .pods()
        .inNamespace(Namespace)
        .withName(pods.get(0).getMetadata.getName)
        .tailingLines(5)
        .getLog(true)
      assert(log != null, "the control plane's own token could not read a pod's log")

      // Withheld, and refused by the API server itself rather than by ankka's own code. A
      // read-only widening that quietly became more is the thing this catches.
      for (what, attempt) <- Vector[(String, () => Unit)](
          "delete a pod" -> (() =>
            restricted
              .pods()
              .inNamespace(Namespace)
              .withName(pods.get(0).getMetadata.getName)
              .delete(): Unit
          ),
          "delete a deployment" -> (() =>
            restricted
              .apps()
              .deployments()
              .inNamespace(Namespace)
              .withName("ankka-controlplane")
              .delete(): Unit
          )
        )
      do
        val ex = intercept[io.fabric8.kubernetes.client.KubernetesClientException](attempt())
        assertEquals(
          ex.getCode,
          403,
          s"expected the API server to refuse to $what: ${ex.getMessage}"
        )

      // Feature 013, both directions on one resource. A registry credential is a Secret the control
      // plane must be able to write and must never be able to read — so the interesting assertion
      // is not that `create` works, it is that `get` on the object it just created is refused by the
      // API server. An admin-credentialled test cannot see either half.
      val credential = new io.fabric8.kubernetes.api.model.SecretBuilder()
        .withMetadata(
          new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
            .withName("ankka-registry")
            .withNamespace(Namespace)
            .build()
        )
        .withType("kubernetes.io/dockerconfigjson")
        .withStringData(java.util.Map.of(".dockerconfigjson", """{"auths":{}}"""))
        .build()
      restricted
        .resource(credential)
        .fieldManager("ankka-controlplane")
        .forceConflicts()
        .serverSideApply(): Unit

      for (what, attempt) <- Vector[(String, () => Unit)](
          "read the credential back" -> (() =>
            restricted.secrets().inNamespace(Namespace).withName("ankka-registry").get(): Unit
          ),
          "list credentials" -> (() => restricted.secrets().inNamespace(Namespace).list(): Unit),
          "delete the credential" -> (() =>
            restricted.secrets().inNamespace(Namespace).withName("ankka-registry").delete(): Unit
          )
        )
      do
        val ex = intercept[io.fabric8.kubernetes.client.KubernetesClientException](attempt())
        assertEquals(
          ex.getCode,
          403,
          s"expected the API server to refuse to $what: ${ex.getMessage}"
        )
    finally restricted.close()
  }

  // ── The console (feature 017) ─────────────────────────────────────────────

  /**
   * A browser without scripts, played by `curl` on the host: a cookie jar, the gateway's
   * certificate verified against the exported root, and `--resolve` standing in for DNS — the same
   * route a developer's browser takes, gateway and all.
   */
  private final class Browser:
    private val jar  = Files.createTempFile("ankka-console-cookies", ".txt")
    private val port = k3s.getMappedPort(GatewayStack.HttpsNodePort)
    private val ca   = GatewayStack.exportCa(k8s)
    val origin       = s"https://$consoleAuthority"

    /** The final status, URL and body, having followed redirects unless told not to. */
    def request(
        url: String,
        form: Seq[(String, String)] = Nil,
        follow: Boolean = true
    ): (Int, String, String) =
      val args = Vector(
        "curl",
        "-sS",
        "-m",
        "30",
        "--cacert",
        ca.toString,
        "--resolve",
        s"console.$BaseDomain:$port:127.0.0.1",
        "--resolve",
        s"auth.$BaseDomain:$port:127.0.0.1",
        "-c",
        jar.toString,
        "-b",
        jar.toString,
        "-w",
        "\n%{http_code} %{url_effective}"
      ) ++ (if follow then Vector("-L") else Vector.empty) ++
        (if form.nonEmpty then Vector("-H", s"Origin: $origin") else Vector.empty) ++
        form.flatMap((k, v) => Vector("--data-urlencode", s"$k=$v")) :+ url
      val process = new ProcessBuilder(args*).redirectErrorStream(true).start()
      val output =
        new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      process.waitFor()
      val lastLine                 = output.lastIndexOf('\n')
      val Array(status, effective) = output.substring(lastLine + 1).split(" ", 2)
      (status.toIntOption.getOrElse(0), effective, output.substring(0, math.max(lastLine, 0)))

    def signIn(): Unit =
      val (_, _, form) = request(s"$origin/")
      val action = """action="([^"]+)"""".r
        .findFirstMatchIn(form)
        .map(_.group(1).replace("&amp;", "&"))
        .getOrElse(fail(s"no login form in: ${form.take(300)}\n${consoleLogs()}"))
      val (status, url, page) = request(
        action,
        Seq("username" -> ConsoleUser, "password" -> ConsolePassword, "credentialId" -> "")
      )
      assertEquals(status, 200, page.take(500))
      assertEquals(url, s"$origin/")
      assert(page.contains("Signed in as"), page.take(1000))

  /** The console's own logs, for a failure message: what it said when it answered as it did. */
  private def consoleLogs(): String =
    nodeExec(
      "kubectl",
      "-n",
      ConsoleNamespace,
      "logs",
      "-l",
      "app.kubernetes.io/name=ankka-console",
      "--tail=60",
      "--prefix"
    )._2

  /**
   * The console and a control plane that answers: the console cases can run without case 1 before
   * them.
   */
  private def consoleAndControlPlaneReady: Boolean =
    consoleReady && readyPods.nonEmpty && api("GET", "/organizations")._1 == 0

  private def consoleReady: Boolean =
    Option(k8s.apps().deployments().inNamespace(ConsoleNamespace).withName("ankka-console").get())
      .flatMap(d => Option(d.getStatus))
      .flatMap(s => Option(s.getReadyReplicas))
      .exists(_ >= 2)

  test("9. the console signs a person in through the gateway and acts as them") {
    waitFor(420.seconds)(consoleAndControlPlaneReady)
    val browser = Browser()

    // A stranger is sent to the identity provider's own form, through the gateway.
    val (formStatus, formUrl, form) = browser.request(s"${browser.origin}/")
    assertEquals(formStatus, 200, s"${form.take(300)}\n${consoleLogs()}")
    assert(formUrl.startsWith(s"https://auth.$BaseDomain:"), formUrl)

    // Signed in, and back on the page asked for: the console exchanged the code over the identity
    // provider's in-cluster address and read who this is from the control plane.
    browser.signIn()

    // A form posted as a page with no scripts posts it: an organization, then a project, then a
    // service, each read back from the control plane the console called as this person.
    val (_, orgUrl, org) = browser.request(
      s"${browser.origin}/organizations/new",
      Seq("intent" -> "create", "id" -> "console-org", "name" -> "Console Org")
    )
    assertEquals(orgUrl, s"${browser.origin}/organizations/console-org")
    assert(org.contains("<h1>Console Org</h1>"), org.take(1000))
    assertEquals(api("GET", "/organizations/console-org")._1, 0)

    val (_, projectUrl, _) = browser.request(
      s"${browser.origin}/organizations/console-org/projects/new",
      Seq("intent" -> "create", "id" -> "console-proj", "name" -> "Console Project")
    )
    assertEquals(projectUrl, s"${browser.origin}/projects/console-proj")
    val (_, serviceUrl, _) = browser.request(
      s"${browser.origin}/projects/console-proj/services/apply",
      Seq("intent" -> "apply", "descriptor" -> descriptor("console-svc"))
    )
    assertEquals(serviceUrl, s"${browser.origin}/projects/console-proj/services/console-svc")
    // The listing is a projection; it shows the service within moments, or a minute or two on a
    // k3s node shared with a control plane cluster, Keycloak and Postgres.
    waitFor(120.seconds)(
      browser
        .request(s"${browser.origin}/projects/console-proj")
        ._3
        .contains("data-service=\"console-svc\"")
    )
  }

  test("10. the console is reachable only through the gateway, and holds no grant") {
    waitFor(420.seconds)(consoleAndControlPlaneReady)
    // From a pod in another namespace, the console's serving port is closed by the network.
    val from = readyPods.headOption.getOrElse(fail("no control plane pod to try from"))
    val (code, out) = nodeExec(
      "kubectl",
      "exec",
      "-n",
      Namespace,
      from.getMetadata.getName,
      "--",
      "curl",
      "-sk",
      "-m",
      "5",
      s"https://ankka-console.$ConsoleNamespace.svc:9000/"
    )
    assertNotEquals(code, 0, s"the console answered a pod outside the gateway: $out")

    // The console's own ServiceAccount can do nothing with the API server.
    val tokenResult =
      k3s.execInContainer(
        "kubectl",
        "create",
        "token",
        "ankka-console",
        "-n",
        ConsoleNamespace,
        "--duration=10m"
      )
    assertEquals(tokenResult.getExitCode, 0, tokenResult.getStderr)
    val restricted = new KubernetesClientBuilder()
      .withConfig(
        new io.fabric8.kubernetes.client.ConfigBuilder()
          .withMasterUrl(k8s.getConfiguration.getMasterUrl)
          .withTrustCerts(true)
          .withOauthToken(tokenResult.getStdout.trim)
          .build()
      )
      .build()
    try
      val ex = intercept[io.fabric8.kubernetes.client.KubernetesClientException](
        restricted.pods().inNamespace(ConsoleNamespace).list(): Unit
      )
      assertEquals(ex.getCode, 403, ex.getMessage)
    finally restricted.close()
  }

  test(
    "11. a signed-in session survives the console's instances being replaced, refusing nothing"
  ) {
    waitFor(420.seconds)(consoleAndControlPlaneReady)
    val browser = Browser()
    browser.signIn()

    // One request a second, throughout a rolling restart of both instances, to a page a signed-in
    // console serves from its session alone: the control plane's availability is not this case's.
    val statuses          = java.util.concurrent.ConcurrentLinkedQueue[Int]()
    @volatile var running = true
    val poller = new Thread(() =>
      while running do
        statuses.add(browser.request(s"${browser.origin}/auth/sign-out", follow = false)._1)
        Thread.sleep(1000)
    )
    poller.start()
    try
      val (restart, restartOut) =
        nodeExec(
          "kubectl",
          "-n",
          ConsoleNamespace,
          "rollout",
          "restart",
          "deployment/ankka-console"
        )
      assertEquals(restart, 0, restartOut)
      val (rolled, rolledOut) = nodeExec(
        "kubectl",
        "-n",
        ConsoleNamespace,
        "rollout",
        "status",
        "deployment/ankka-console",
        "--timeout=300s"
      )
      assertEquals(rolled, 0, rolledOut)
      // A quiet node rolls both instances in seconds; keep asking until there are enough answers to
      // judge, a few of them from the instances that replaced the old ones.
      val deadline = System.nanoTime() + 60.seconds.toNanos
      Thread.sleep(3000)
      while statuses.size < 12 && System.nanoTime() < deadline do Thread.sleep(500)
    finally
      running = false
      poller.join()
    val seen = statuses.asScala.toVector
    assert(seen.size >= 10, s"too few requests to say anything: $seen")
    assertEquals(
      seen.filterNot(_ == 200),
      Vector.empty,
      s"requests refused or redirected during the roll: $seen"
    )
  }
