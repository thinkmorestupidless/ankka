package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  Fabric8AnkkaServiceClient,
  ServiceProjector
}
import com.thinkmorestupidless.ankka.crd.{AnkkaSerialization, AnkkaService}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.{
  ClusterImages,
  Membership,
  Operator,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * A real ankka service at several instances: one cluster, rolled under load, and losing a node
 * three different ways.
 *
 * The invariant every case is ultimately about: the instances of one service never constitute more
 * than one cluster. It is decided the way research R6 decided it — read `/cluster/members` from
 * every pod and count DISJOINT member sets ([[Membership.disjointClusters]]).
 *
 * Failure is done the real way. `kubectl delete --force` still sends SIGTERM and is a graceful
 * leave; a crash here is `SIGKILL` sent from the k3s node, and a partition is `iptables` on it.
 *
 * Slow, deliberately: repeated simultaneous cold starts, because one clean formation is no evidence
 * for a race — twenty in the nightly run (SC-002), five by default (`-Dankka.coldstarts=N`).
 * Disable with `-Dankka.cluster.tests=off`.
 */
class MultiNodeClusterSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout: FiniteDuration = 50.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  private val K3sImage    = "rancher/k3s:v1.35.1-k3s1"
  private val SampleImage = "sample-shopping-cart:latest"
  // A real issuer is not needed to prove anything here: an in-process one mints tokens the
  // verifier accepts, and KeycloakRealmSuite is where real ones are read.
  private lazy val identity = TestIdentity()
  private lazy val Token =
    identity.token("tester", Some("tester@example.test"), expiresIn = 2.hours)
  private val Prefix    = "ankka"
  private val Project   = "checkout"
  private val Namespace = s"$Prefix-$Project"
  private val Service   = "cart"

  /**
   * SC-002 asks for at least twenty, which take thirteen minutes; five by default, and the nightly
   * `cluster` workflow passes `-Dankka.coldstarts=20`.
   */
  private val ColdStarts = sys.props.get("ankka.coldstarts").flatMap(_.toIntOption).getOrElse(5)

  private var k3s: K3sContainer     = null
  private var k8s: KubernetesClient = null
  private var operator: Operator    = null
  private var testKit: AnkkaTestKit = null
  private var url: String           = ""
  private var config: Path          = null

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.start()
      ClusterImages.importInto(k3s, SampleImage)

      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      k8s.load(getClass.getResourceAsStream("/ankka/crd/ankkaservice.yaml")).serverSideApply(): Unit
      // The installation's authorities: every pod's certificates come from them.
      com.thinkmorestupidless.ankka.operator.PkiStack.install(k3s, k8s)
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

      val operatorSettings = OperatorSettings.default.copy(resyncInterval = 2.seconds)
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
      operator.start()

      val deployConfig = DeployConfig.default
        .copy(namespacePrefix = Prefix, sweepInterval = 2.seconds, progressDeadline = 170.seconds)
      val projector = ServiceProjector.withClient(
        deployConfig,
        new Fabric8AnkkaServiceClient(k8s, Prefix, resyncMillis = 2000L)
      )
      val server =
        HttpServer.at("127.0.0.1", 0)(ControlPlane.endpoints(identity.acl())*)
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

      config = Files.createTempFile("ankka-multi", ".json")
      Files.delete(config)
      sys.props("ankka.config") = config.toString

  override def afterAll(): Unit =
    if testKit != null then identity.stop()
    sys.props.remove("ankka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if testKit != null then testKit.stop()
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  // ---- plumbing ------------------------------------------------------------------------------

  private def ankka(args: String*): (Int, String) =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code = Main.run(
      args ++ Seq("--url", url, "--token", Token),
      PrintStream(out, true, StandardCharsets.UTF_8),
      PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8))

  /** Retries `probe` until `ok` holds, failing with the last value it answered. */
  private def waitForValue[A](timeout: FiniteDuration, what: String)(probe: => A)(
      ok: A => Boolean
  ): Unit =
    val deadline  = System.nanoTime() + timeout.toNanos
    var last: Any = "never answered"
    var passed    = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try
          val value = probe
          last = value
          ok(value)
        catch
          case e: Throwable =>
            last = e.toString
            false
      if !passed then Thread.sleep(500)
    if !passed then fail(s"$what did not hold within $timeout; last: $last")

  private def waitFor(timeout: FiniteDuration)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(500)
    if !passed then fail(s"condition did not hold within $timeout")

  private def applyCart(instances: Int): Unit =
    val file = Files.createTempFile("cart", ".json")
    Files.writeString(
      file,
      s"""{"name":"$Service","service":{"image":"$SampleImage","resources":{"autoscaling":{"minInstances":$instances}}}}"""
    )
    val (code, out) = ankka("services", "apply", "-f", file.toString, "-p", Project)
    Files.deleteIfExists(file): Unit
    assertEquals(code, 0, s"apply failed: $out")

  private def status =
    Option(k8s.resources(classOf[AnkkaService]).inNamespace(Namespace).withName(Service).get())
      .flatMap(r => Option(r.getStatus))

  private def pods: Vector[Pod] =
    k8s
      .pods()
      .inNamespace(Namespace)
      .withLabel("app.kubernetes.io/name", Service)
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

  /** See `InPod.prober`: the one place requests are made from, whatever the service's pods do. */
  private lazy val prober: String =
    com.thinkmorestupidless.ankka.operator.InPod.prober(k3s, Namespace, Service)

  /**
   * Through the Service's name — never a port-forward, which would bypass the Service — from the
   * prober, presenting the service's certificate. Answers 0 for a 2xx, as `wget` did, so callers
   * read the same.
   */
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

  /** One pod, by its IP — which its certificate does not name, hence no host check. */
  private def podHttp(pod: Pod, path: String): (Int, String) =
    val (code, body) = com.thinkmorestupidless.ankka.operator.InPod.curl(
      k3s,
      Namespace,
      pod.getMetadata.getName,
      s"https://${pod.getStatus.getPodIP}:9000$path",
      verifyHost = false
    )
    (if code / 100 == 2 then 0 else 1, s"$code $body")

  private val MemberPattern = """\{"node":"([^"]+)"[^}]*?"status":"([A-Za-z]+)"""".r

  /** Up members as one pod sees them; empty if not yet joined or not answering. */
  private def membership(pod: Pod): Set[String] =
    Option(pod.getStatus.getPodIP).fold(Set.empty[String]) { ip =>
      // Management admits only the service's own certificate, which the pod holds.
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

  private def disjointClusters: Int = Membership.disjointClusters(pods.map(membership))

  private def addItem(cart: String, product: String): Unit =
    val (code, out) = nodeHttp(
      s"/carts/$cart/items",
      post = Some(s"""{"productId":"$product","name":"Widget","quantity":1}""")
    )
    assertEquals(code, 0, out)

  private def psql(sql: String): (Int, String) =
    nodeExec(
      "kubectl",
      "exec",
      "-n",
      Namespace,
      "ankka-db-1",
      "-c",
      "postgres",
      "--",
      "psql",
      "-U",
      "postgres",
      "-d",
      Service,
      "-tA",
      "-c",
      sql
    )

  private def initRestarts: Int =
    pods
      .flatMap(p =>
        Option(p.getStatus.getInitContainerStatuses)
          .map(_.asScala.map(_.getRestartCount.intValue).sum)
      )
      .sum

  /**
   * A crash: SIGKILL to the JVM from the node. Not `kubectl delete`, which is a graceful leave.
   *
   * The pod is re-read for the container id, and the whole thing retried once: a pod object held
   * from before the previous case can name a container that has since been replaced, and the `kill`
   * exec itself once came back 137 on a node under load — with the outputs kept, so the next such
   * failure says what it was.
   */
  private def sigkill(pod: Pod): Unit =
    def attempt(): Either[String, Unit] =
      val fresh = k8s.pods().inNamespace(Namespace).withName(pod.getMetadata.getName).get()
      if fresh == null then return Left(s"pod ${pod.getMetadata.getName} no longer exists")
      val cid =
        fresh.getStatus.getContainerStatuses.get(0).getContainerID.stripPrefix("containerd://")
      val (c1, pid) =
        nodeExec("crictl", "inspect", "-o", "go-template", "--template", "{{.info.pid}}", cid)
      if c1 != 0 then Left(s"crictl inspect $cid: exit $c1: $pid")
      else
        val (c2, out) = nodeExec("kill", "-9", pid.trim)
        if c2 != 0 then Left(s"kill -9 ${pid.trim}: exit $c2: $out") else Right(())
    attempt() match
      case Right(()) => ()
      case Left(first) =>
        Thread.sleep(2000)
        attempt() match
          case Right(()) =>
            println(s"sigkill: first attempt failed and the retry succeeded: $first")
          case Left(second) => fail(s"sigkill failed twice: $first; then $second")

  private def partition(pod: Pod, on: Boolean): Unit =
    val ip = pod.getStatus.getPodIP
    val op = if on then "-I" else "-D"
    for rule <- Vector(Vector("-s", ip), Vector("-d", ip)) do
      val (code, out) = nodeExec(
        (Vector("iptables", op, "FORWARD") ++ (if on then Vector("1")
                                               else Vector.empty) ++ rule ++ Vector(
          "-p",
          "tcp",
          "--dport",
          "17355",
          "-j",
          "DROP"
        ))*
      )
      assertEquals(code, 0, s"iptables: $out")

  /** Continuous reads in the background; returns (successes, failures, stale) when stopped. */
  private final class Load(cart: String, expect: String):
    private val ok                = AtomicInteger(0); private val failed = AtomicInteger(0);
    private val stale             = AtomicInteger(0)
    val failures                  = java.util.concurrent.ConcurrentLinkedQueue[String]()
    @volatile private var running = true
    private val thread = new Thread(() =>
      while running do
        val t0           = System.nanoTime()
        val (code, body) = nodeHttp(s"/carts/$cart")
        if code != 0 then
          // What failed matters: a refused connection is the platform's; a `docker exec` that
          // could not start is the test node's.
          failures.add(
            s"exit $code after ${(System.nanoTime() - t0) / 1_000_000}ms: ${body.trim.take(200)}"
          )
          failed.incrementAndGet(): Unit
        else if !body.contains(expect) then stale.incrementAndGet(): Unit
        else ok.incrementAndGet(): Unit
        Thread.sleep(200)
    )
    thread.setDaemon(true); thread.start()
    def stop(): (Int, Int, Int) = {
      running = false; thread.join(10000); (ok.get, failed.get, stale.get)
    }

  // ---- US1 / US3 -------------------------------------------------------------------------------

  test("1. three instances, applied through the CLI, form one cluster and report 3/3 Ready") {
    assertEquals(ankka("organizations", "create", "acme", "--name", "Acme")._1, 0)
    assertEquals(ankka("projects", "create", Project, "--name", "Checkout", "-O", "acme")._1, 0)
    applyCart(3)

    val deadline = System.nanoTime() + 300.seconds.toNanos
    var ready    = false
    while !ready && System.nanoTime() < deadline do
      status.foreach { s =>
        assert(s.lifecycle != "Failed", s"Failed on the way up: ${s.detail}")
        ready = s.lifecycle == "Ready" && s.readyInstances == 3
      }
      if !ready then Thread.sleep(500)
    assert(ready, s"never reached 3/3 Ready; last: $status")

    val views = pods.map(membership)
    assertEquals(views.size, 3)
    assertEquals(Membership.disjointClusters(views), 1, views.toString)
    views.foreach(v => assertEquals(v.size, 3, v.toString))

    // The cluster formed over TLS: management above answered only to the service's certificate,
    // and remoting says which transport it bound.
    for pod <- pods do
      val (code, log) = nodeExec("kubectl", "logs", "-n", Namespace, pod.getMetadata.getName)
      assertEquals(code, 0, log)
      assert(
        "(?i)artery tls-?tcp".r.findFirstIn(log).isDefined,
        s"${pod.getMetadata.getName} did not start TLS remoting"
      )
    // Readiness answers the node, which holds no certificate; the HTTP port does not.
    for pod <- pods do
      val ip = pod.getStatus.getPodIP
      assertEquals(nodeExec("wget", "-qO-", s"http://$ip:7627/ready")._1, 0, "readiness")
      assertNotEquals(
        nodeExec("wget", "-qO-", "-T", "5", s"http://$ip:9000/carts/c1")._1,
        0,
        "the HTTP port answered plain HTTP"
      )
  }

  test("2. one entity, wherever the request lands — and one writer in the journal") {
    addItem("c1", "p1")
    for pod <- pods do
      val (code, body) = podHttp(pod, "/carts/c1")
      assertEquals(code, 0, s"${pod.getMetadata.getName}: $body")
      assert(body.contains("Widget"), s"${pod.getMetadata.getName}: $body")
    // An unbroken sequence per persistence id: count(*) == max(seq_nr) means no gaps, no
    // duplicates — one writer. Two clusters over one journal would break it on the first clash.
    val (code, out) =
      psql("select count(*) = max(seq_nr) from event_journal group by persistence_id;")
    assertEquals(code, 0, out)
    assert(out.linesIterator.forall(l => l.trim.isEmpty || l.trim == "t"), out)
  }

  test("3. a rolling update under load: one cluster throughout, ≥99% of requests answered") {
    val before = pods.map(_.getMetadata.getName).toSet
    val load   = Load("c1", "Widget")
    assertEquals(ankka("services", "restart", Service, "-p", Project)._1, 0)

    var mostClusters = 0
    var replaced     = false
    val deadline     = System.nanoTime() + 300.seconds.toNanos
    while !replaced && System.nanoTime() < deadline do
      mostClusters = mostClusters.max(disjointClusters)
      val now = pods.map(_.getMetadata.getName).toSet
      replaced = now.size == 3 && now.intersect(before).isEmpty && readyPods.size == 3
      if !replaced then Thread.sleep(1000)
    Thread.sleep(5000)
    val (ok, failed, stale) = load.stop()

    assert(replaced, "the rollout did not complete")
    assertEquals(mostClusters, 1, "more than one cluster during the rollout")
    assertEquals(stale, 0, "a response without the item")
    assert(ok + failed > 0)
    val successRate = ok.toDouble / (ok + failed)
    assert(
      successRate >= 0.99,
      f"success rate $successRate%.3f ($ok ok, $failed failed): ${load.failures.asScala.mkString(" | ")}"
    )
  }

  test("4. the same at one instance: never zero ready pods") {
    applyCart(1)
    waitFor(300.seconds)(
      pods.size == 1 && status.exists(s => s.lifecycle == "Ready" && s.readyInstances == 1)
    )
    val before = pods.map(_.getMetadata.getName).toSet
    val load   = Load("c1", "Widget")
    assertEquals(ankka("services", "restart", Service, "-p", Project)._1, 0)

    var fewestReady  = Int.MaxValue
    var mostClusters = 0
    var replaced     = false
    val deadline     = System.nanoTime() + 300.seconds.toNanos
    while !replaced && System.nanoTime() < deadline do
      fewestReady = fewestReady.min(readyPods.size)
      mostClusters = mostClusters.max(disjointClusters)
      val now = pods.map(_.getMetadata.getName).toSet
      replaced = now.size == 1 && now.intersect(before).isEmpty && readyPods.size == 1
      if !replaced then Thread.sleep(500)
    Thread.sleep(3000)
    val (ok, failed, _) = load.stop()

    assert(replaced, "the rollout did not complete")
    assertEquals(mostClusters, 1)
    assert(fewestReady >= 1, s"dropped to $fewestReady ready pods — feature 003's outage is back")
    assert(
      ok.toDouble / (ok + failed) >= 0.99,
      s"$ok ok, $failed failed: ${load.failures.asScala.mkString(" | ")}"
    )
  }

  test("5. twenty simultaneous cold starts: one cluster every time, no init-container restarts") {
    // SC-002 and research R11 together. Through the platform — pause, then resume — because the
    // operator reconciles the Deployment's replica count continuously: scaling it to zero by hand
    // is undone within a resync, which is exactly what it is for.
    applyCart(3)
    waitFor(300.seconds)(readyPods.size == 3 && status.exists(_.readyInstances == 3))
    val times = for round <- 1 to ColdStarts yield
      assertEquals(ankka("services", "pause", Service, "-p", Project)._1, 0)
      waitFor(180.seconds)(pods.isEmpty)
      val t0 = System.nanoTime()
      assertEquals(ankka("services", "resume", Service, "-p", Project)._1, 0)
      waitFor(300.seconds)(readyPods.size == 3)
      val elapsed  = (System.nanoTime() - t0) / 1_000_000_000L
      val clusters = disjointClusters
      assertEquals(clusters, 1, s"round $round: $clusters clusters")
      assertEquals(
        initRestarts,
        0,
        s"round $round: an init container restarted — the CREATE TABLE race"
      )
      elapsed
    println(
      s"cold starts: ${times.size} rounds, one cluster each, formation ${times.min}..${times.max}s"
    )
  }

  // ---- US4 -------------------------------------------------------------------------------------

  test("6. graceful: a deleted pod leaves cleanly and is replaced") {
    waitFor(300.seconds)(readyPods.size == 3 && disjointClusters == 1)
    val victim = pods.head
    val peer   = pods.last
    val victimAddress = membership(peer)
      .find(_.contains(victim.getStatus.getPodIP))
      .getOrElse(fail("victim not a member"))
    k8s.pods().inNamespace(Namespace).withName(victim.getMetadata.getName).delete(): Unit
    waitFor(30.seconds)(!membership(peer).contains(victimAddress))
    waitFor(120.seconds)(readyPods.size == 3 && disjointClusters == 1)
  }

  test("7. crash: a SIGKILLed node's entities answer again, with their state") {
    waitFor(300.seconds)(readyPods.size == 3 && disjointClusters == 1)
    for i <- 2 to 11 do addItem(s"c$i", "p1") // spread over three nodes; some lived on the victim
    val victim         = pods.head
    val restartsBefore = victim.getStatus.getContainerStatuses.get(0).getRestartCount.intValue
    sigkill(victim)
    waitForValue(60.seconds, "every cart answers with its state")(
      (2 to 11).map(i => i -> nodeHttp(s"/carts/c$i")._2).filterNot(_._2.contains("Widget"))
    )(_.isEmpty)
    waitFor(120.seconds) {
      pods
        .find(_.getMetadata.getName == victim.getMetadata.getName)
        .exists(
          _.getStatus.getContainerStatuses.get(0).getRestartCount.intValue > restartsBefore
        ) &&
      readyPods.size == 3 && disjointClusters == 1
    }
  }

  test("8. partition: exactly one side survives, and heals to three") {
    waitFor(300.seconds)(readyPods.size == 3 && disjointClusters == 1)
    val victim         = pods.head
    val peer           = pods.last
    val restartsBefore = victim.getStatus.getContainerStatuses.get(0).getRestartCount.intValue
    partition(victim, on = true)
    try
      // The majority downs it, and keeps serving — asked directly, because the Service keeps
      // routing to the isolated pod until its readiness probe (membership) fails: a request
      // through the Service in that window lands on the minority one time in three.
      waitFor(90.seconds)(!membership(peer).exists(_.contains(victim.getStatus.getPodIP)))
      val majority = pods.filterNot(_.getMetadata.getName == victim.getMetadata.getName)
      assertEquals(majority.size, 2)
      for pod <- majority do
        waitForValue(60.seconds, s"${pod.getMetadata.getName} serves c1")(
          podHttp(pod, "/carts/c1")
        )(
          _._2.contains("Widget")
        )
      // ...and once readiness catches up the Service routes only to them.
      waitForValue(90.seconds, "the Service routes only to the majority")(
        Iterator.fill(6)(nodeHttp("/carts/c1")._2).toVector
      )(_.forall(_.contains("Widget")))
      // The minority downed itself and exited (exit-jvm), so its container restarted.
      waitFor(90.seconds) {
        pods
          .find(_.getMetadata.getName == victim.getMetadata.getName)
          .exists(_.getStatus.getContainerStatuses.get(0).getRestartCount.intValue > restartsBefore)
      }
    finally partition(victim, on = false)
    waitFor(180.seconds)(
      readyPods.size == 3 && disjointClusters == 1 && pods.map(membership).forall(_.size == 3)
    )
  }
