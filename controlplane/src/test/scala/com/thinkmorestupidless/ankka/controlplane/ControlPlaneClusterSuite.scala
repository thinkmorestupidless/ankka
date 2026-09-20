package com.thinkmorestupidless.ankka.controlplane

import io.fabric8.kubernetes.api.model.{ConfigMapBuilder, ObjectMetaBuilder, Pod}
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import com.thinkmorestupidless.ankka.operator.{
  ClusterImages,
  GatewayStack,
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
class ControlPlaneClusterSuite extends munit.FunSuite:

  override val munitTimeout: FiniteDuration = 20.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  private val K3sImage          = "rancher/k3s:v1.35.1-k3s1"
  private val BaseDomain        = "test.local"
  private val ControlPlaneImage = "ankka-controlplane:latest"
  private val SampleImage       = "sample-shopping-cart:latest"
  private val Token             = "dev-local-token" // what token-secret.yaml ships
  private val Namespace         = "ankka-controlplane"
  private val Project           = "checkout"

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
      applyManifest("kustomization/components/controlplane/token-secret.yaml")
      applyManifest("kustomization/components/controlplane/service.yaml")
      // cert-manager, Envoy Gateway, the platform's Gateway and the local CA — so the control
      // plane's own route can be proven, and the CLI driven over verified TLS. Before the route
      // below, for the same reason CNPG is installed before anything references a Cluster: the
      // CRD has to exist first.
      GatewayStack.install(k3s, k8s, repoRoot, BaseDomain)

      // The base domain the overlay would have fanned out, filled in by hand here.
      for name <- Vector("deployment.yaml", "httproute.yaml") do
        val yaml = Files
          .readString(repoRoot.resolve(s"kustomization/components/controlplane/$name"))
          .replace("BASE_DOMAIN", BaseDomain)
        k8s.load(new java.io.ByteArrayInputStream(yaml.getBytes("UTF-8"))).serverSideApply(): Unit

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
      val (code, body) = nodeExec("wget", "-qO-", "-T", "3", s"http://$ip:7626/cluster/members")
      if code != 0 then Set.empty
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
    val service = k8s.services().inNamespace(Namespace).withName("ankka-controlplane").get()
    val target  = s"http://${service.getSpec.getClusterIP}:9000$path"
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
      "Content-Type: application/json"
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
      .map(ip => nodeExec("wget", "-qO-", "-T", "3", s"http://$ip:7626/cluster/members"))
      .collectFirst { case (0, body) => body }
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
    waitFor(420.seconds)(readyPods.size == 3)
    val views = pods.map(membership)
    assertEquals(Membership.disjointClusters(views), 1, views.toString)
    views.foreach(v => assertEquals(v.size, 3, v.toString))
    // One cluster, and every instance answers.
    for pod <- pods do
      val (code, out) = nodeExec(
        "wget",
        "-qO-",
        "-T",
        "5",
        "--header",
        s"Authorization: Bearer $Token",
        s"http://${pod.getStatus.getPodIP}:9000/organizations/"
      )
      assertEquals(code, 0, s"${pod.getMetadata.getName}: $out")
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
    finally restricted.close()
  }
