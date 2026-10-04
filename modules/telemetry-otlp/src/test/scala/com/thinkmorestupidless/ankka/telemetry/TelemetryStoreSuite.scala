package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{Observability, TraceContext}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.typesafe.config.ConfigFactory
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.{BindMode, GenericContainer, Network}
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Paths}
import java.time.Duration as JDuration
import scala.concurrent.duration.*

/** testcontainers' self-typed container needs a concrete subclass for Scala 3 to infer. */
private final class Container(image: DockerImageName) extends GenericContainer[Container](image)

/**
 * A local platform's telemetry store, as its component ships it: the store's own image, and the log
 * agent's own configuration over a pod's log file — the scenarios of
 * `features/observability/telemetry-store.feature`. The images are read from the component's
 * manifests, so this suite and the manifests cannot name different ones. Pulls about a gigabyte the
 * first time.
 */
class TelemetryStoreSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 10.minutes

  private val component = Paths.get("../../kustomization/components/telemetry-store")

  /** The image a manifest of the component names. */
  private def imageIn(file: String): DockerImageName =
    val manifest = Files.readString(component.resolve(file))
    DockerImageName.parse("""image:\s*(\S+)""".r.findFirstMatchIn(manifest).get.group(1))

  /** The agent's configuration, exactly as the component's ConfigMap holds it. */
  private def agentConfiguration: String =
    val manifest = Files.readString(component.resolve("log-agent-config.yaml"))
    val body     = manifest.substring(manifest.indexOf("config.yaml: |") + "config.yaml: |".length)
    body.linesIterator.dropWhile(_.isBlank).map(_.stripPrefix("    ")).mkString("\n")

  private val network = Network.newNetwork()
  private val store = Container(imageIn("lgtm.yaml"))
    .withNetwork(network)
    .withNetworkAliases("lgtm")
    .withExposedPorts(3000, 3100, 3200, 4318, 9090)
    .waitingFor(
      Wait
        .forLogMessage(
          ".*The OpenTelemetry collector and the Grafana LGTM stack are up and running.*",
          1
        )
        .withStartupTimeout(JDuration.ofMinutes(5))
    )

  private var testKit: AnkkaTestKit = null
  private val http                  = HttpClient.newHttpClient()

  private def url(port: Int, path: String) =
    s"http://${store.getHost}:${store.getMappedPort(port)}$path"

  private def get(port: Int, path: String): (Int, String) =
    val response = http.send(
      HttpRequest
        .newBuilder(URI.create(url(port, path)))
        .header("Accept", "application/json")
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    (response.statusCode, response.body)

  override def beforeAll(): Unit =
    val started = System.nanoTime()
    store.start()
    println(s"telemetry store ready in ${(System.nanoTime() - started) / 1_000_000_000L}s")
    testKit = AnkkaTestKit.start(
      Seq(CartEntity.descriptor),
      settings = ConfigFactory.parseString(
        s"""ankka.telemetry.endpoint = "${url(4318, "")}"
           |ankka.telemetry.interval = 200ms
           |ankka.telemetry.metric-interval = 1s
           |ankka.telemetry.service-name = orders
           |ankka.telemetry.project = shop""".stripMargin
      )
    )

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()
    store.stop()
    network.close()

  private def eventually[A](what: String, within: FiniteDuration = 90.seconds)(
      check: => Option[A]
  ): A =
    val deadline = System.nanoTime() + within.toNanos
    var found    = check
    while found.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(500)
      found = check
    found.getOrElse(fail(s"$what did not happen within $within"))

  private def addItem(cart: String) =
    testKit.componentClient.forKeyValueEntity(EntityId(cart)).call(CartEntity.addItem).invoke("tea")

  /** The trace of the newest `cart add-item` span this service recorded. */
  private def lastTrace: TraceContext =
    val observability = Observability(testKit.service.system)
    val span = observability.recorder
      .snapshot()
      .find(s => observability.names.nameOf(s.handlerRef).contains("add-item"))
      .get
    TraceContext(span.traceIdHigh, span.traceId, span.spanId)

  test("a developer reads the trace of a request in the telemetry store") {
    addItem("s-1"): Unit
    val trace = lastTrace
    val body = eventually(s"trace ${trace.traceIdHex} in Tempo") {
      val (status, body) = get(3200, s"/api/traces/${trace.traceIdHex}")
      Option.when(status == 200)(body)
    }
    assert(body.contains("orders"), body.take(500))
    assert(body.contains("add-item"), body.take(500))
  }

  test("a developer reads the metrics of a service in the telemetry store") {
    (1 to 3).foreach(i => addItem(s"m-$i"): Unit)
    val query = java.net.URLEncoder.encode(
      """{__name__=~"ankka_invocations.*",ankka_handler="add-item",ankka_outcome="ok"}""",
      StandardCharsets.UTF_8
    )
    val body = eventually("the invocations counter in the metrics store") {
      val (status, body) = get(9090, s"/api/v1/query?query=$query")
      Option.when(
        status == 200 && body.contains("\"value\"") && """"(\d+)"\]""".r
          .findAllMatchIn(body)
          .exists(_.group(1).toInt >= 3)
      )(body)
    }
    println(
      s"the counter as the store names it: ${"\"__name__\":\"([^\"]+)\"".r.findFirstMatchIn(body).map(_.group(1))}"
    )
    assert(body.contains("\"job\":\"shop/orders\"") || body.contains("orders"), body.take(500))
  }

  test("a developer reads the logs of a service in the telemetry store, joined to their trace") {
    addItem("l-1"): Unit
    val trace = lastTrace
    // A node's pod log directory, holding one ankka service's file and one that is not ankka's.
    val pods = Files.createTempDirectory("pods")
    def write(dir: String, container: String, lines: String*): Unit =
      val at = pods.resolve(dir).resolve(container)
      Files.createDirectories(at)
      // Stamped now: the store is asked for the last hour.
      val now = java.time.Instant.now().toString
      Files.writeString(at.resolve("0.log"), lines.map(l => s"$now stdout F $l\n").mkString): Unit
    write(
      "ankka-shop_orders-6c9d7b8f5-x2k4q_0a1b2c3d",
      "orders",
      s"12:00:00.000 INFO  c.t.a.cart - an item was added trace_id=${trace.traceIdHex} span_id=${trace.spanIdHex}",
      "12:00:00.001 INFO  c.t.a.Ankka - ankka orders service started"
    )
    write(
      "kube-system_coredns-xyz_4e5f6a7b",
      "coredns",
      "[INFO] plugin/reload: Running configuration"
    )
    // Readable to whatever the container runs as, whatever this machine's umask made them.
    Files
      .walk(pods)
      .forEach(p =>
        Files.setPosixFilePermissions(
          p,
          PosixFilePermissions.fromString(if Files.isDirectory(p) then "rwxr-xr-x" else "rw-r--r--")
        ): Unit
      )
    val configFile = Files.createTempFile("log-agent", ".yaml")
    Files.writeString(configFile, agentConfiguration): Unit
    Files.setPosixFilePermissions(configFile, PosixFilePermissions.fromString("rw-r--r--")): Unit
    val agent = Container(imageIn("log-agent.yaml"))
      .withNetwork(network)
      .withEnv("STORE_ENDPOINT", "http://lgtm:4318")
      .withFileSystemBind(pods.toString, "/var/log/pods", BindMode.READ_ONLY)
      .withFileSystemBind(configFile.toString, "/etc/otel/config.yaml", BindMode.READ_ONLY)
      .withCommand("--config=/etc/otel/config.yaml")
      .withCreateContainerCmdModifier(cmd => cmd.withUser("0"): Unit)
    agent.start()
    try
      def lines(selector: String): String =
        val query = java.net.URLEncoder.encode(selector, StandardCharsets.UTF_8)
        val since = (System.currentTimeMillis() - 3_600_000L) * 1_000_000L
        get(3100, s"/loki/api/v1/query_range?query=$query&start=$since&limit=100")._2
      val ours = eventually("the service's lines in Loki", 120.seconds) {
        val body = lines("""{service_name="orders"}""")
        Option.when(body.contains("an item was added") && body.contains("service started"))(body)
      }
      assert(ours.contains(trace.traceIdHex), "the handler's line carries its trace id")
      assert(ours.contains("shop"), "and names the project")
      val theirs = eventually("the other pod's line in Loki", 60.seconds) {
        val body = lines("""{service_name="coredns"}""")
        Option.when(body.contains("plugin/reload"))(body)
      }
      assert(!theirs.contains("trace_id\":\"0"), theirs.take(500))
      // The trace the line names is in the store, under the same id.
      val (status, _) = get(3200, s"/api/traces/${trace.traceIdHex}")
      assertEquals(status, 200)
      // And the store's own page links a line's trace_id to that trace: the image's Grafana
      // provisions Loki with a link on exactly the label the agent writes.
      val loki = http.send(
        HttpRequest
          .newBuilder(URI.create(url(3000, "/api/datasources/uid/loki")))
          .header(
            "Authorization",
            "Basic " + java.util.Base64.getEncoder.encodeToString("admin:admin".getBytes)
          )
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assertEquals(loki.statusCode, 200, loki.body)
      assert(
        """"matcherRegex":"trace_id"""".r.findFirstIn(loki.body).isDefined &&
          loki.body.contains(""""datasourceUid":"tempo""""),
        loki.body
      )
    finally agent.stop()
  }
