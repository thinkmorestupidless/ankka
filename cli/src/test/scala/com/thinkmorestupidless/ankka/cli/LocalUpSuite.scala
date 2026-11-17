package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.local.LocalSeams

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * `ankka local up` against the machine's Docker (feature 066, `-Dankka.local.tests`): a project
 * `shop` of two stand-in services, brought up, asked, brought down. Each case starts from nothing
 * and ends with `local down --volumes`.
 */
class LocalUpSuite extends munit.FunSuite:

  override val munitTimeout: Duration = 10.minutes

  override def munitIgnore: Boolean = LocalSwitch.skip

  private val tag        = s"ankka-local-standin:${java.util.UUID.randomUUID().toString.take(8)}"
  private val billingTag = tag + "-billing"
  private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build()
  private var home: Path = null

  /** A free port above 20000, so a run never meets the ports a developer's services use. */
  private def freePort(): Int =
    Iterator
      .continually(local.LocalState.freePort())
      .find(_ > 20000)
      .get

  private def docker(words: String*): (Int, String) =
    val process = ProcessBuilder(("docker" +: words).asJava).redirectErrorStream(true).start()
    process.getOutputStream.close()
    val out = String(process.getInputStream.readAllBytes(), UTF_8)
    (process.waitFor(), out)

  private def build(labels: String*): Unit = build(labels.toVector, tag)

  private def build(labels: Vector[String], tagged: String): Unit =
    val context     = Paths.get(getClass.getResource("/standin/Dockerfile").toURI).getParent
    val (code, out) = docker(
      (Vector("build", "-q", "-t", tagged) ++ labels.flatMap(l =>
        Vector("--label", l)
      ) :+ context.toString)*
    )
    assertEquals(code, 0, out)

  override def beforeAll(): Unit =
    if !munitIgnore then
      if sys.props.get("ankka.local.tests").contains("on") then LocalSwitch.requireDocker()
      build()
      assertEquals(docker("tag", tag, billingTag)._1, 0)

  override def afterAll(): Unit =
    if !munitIgnore then docker("rmi", "-f", tag, billingTag): Unit

  /** A project directory per case, and the running and cache directories inside it. */
  final case class Shop(root: Path, orders: Int, billing: Int)

  private var shop: Shop = null

  override def beforeEach(context: BeforeEach): Unit =
    home = Files.createTempDirectory("local-up")
    sys.props("ankka.running.dir") = home.resolve("running").toString
    sys.props("ankka.cache.dir") = home.resolve("cache").toString
    shop = project()

  override def afterEach(context: AfterEach): Unit =
    if shop != null then run("local", "down", "--volumes", "--dir", shop.root.toString): Unit
    sys.props.remove("ankka.running.dir")
    sys.props.remove("ankka.cache.dir")
    LocalSeams.reset()

  private def project(billingEnv: String = ""): Shop =
    val orders  = freePort()
    val billing = Iterator.continually(freePort()).find(_ != orders).get
    val root    = LocalFixtures.project(
      Vector(
        LocalFixtures.Service("orders", orders, image = tag),
        LocalFixtures.Service("billing", billing, image = billingTag, env = billingEnv)
      ),
      root = Files.createDirectories(home.resolve("shop"))
    )
    Shop(root, orders, billing)

  private def run(args: String*): (Int, String, String) =
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    val code = Main.run(args, PrintStream(out, true, UTF_8), PrintStream(err, true, UTF_8))
    (code, out.toString(UTF_8), err.toString(UTF_8))

  private def up(args: String*): (Int, String, String) =
    run((Vector("local", "up", "--dir", shop.root.toString) ++ args)*)

  /** An `up` that must succeed, failing with what it said when it does not. */
  private def upOk(): String =
    val (code, out, err) = up()
    assertEquals(code, 0, out + err)
    out

  private def get(port: Int, path: String): (Int, String) =
    val answer = http.send(
      HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path")).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )
    (answer.statusCode, answer.body)

  private def put(port: Int, path: String, body: String): Int =
    http
      .send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
          .PUT(HttpRequest.BodyPublishers.ofString(body))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      .statusCode

  private def compose(words: String*): (Int, String) = docker(
    ("compose" +: "-p" +: "ankka-shop" +: words)*
  )

  /** Each running container's id, by service. */
  private def containers(): Map[String, String] =
    val (_, out) = compose("ps", "--format", "{{.Service}} {{.ID}} {{.State}}")
    out.linesIterator
      .map(_.trim.split(' '))
      .collect { case Array(service, id, "running") =>
        service -> id
      }
      .toMap

  private def psql(database: String, sql: String): String =
    compose(
      "exec",
      "-T",
      "ankka-postgres",
      "psql",
      "-U",
      "ankka",
      "-d",
      database,
      "-tAc",
      sql
    )._2.trim

  test(
    "one command starts every service of a project, each in its own database, and each finds the others"
  ) {
    val (code, out, err) = up()
    assertEquals(code, 0, err)
    assert(
      containers().keySet.contains("orders") && containers().keySet.contains("billing"),
      containers()
    )
    val databases = psql("postgres", "SELECT datname FROM pg_database").linesIterator.toSet
    assert(databases.contains("orders") && databases.contains("billing"), databases)
    assertEquals(psql("orders", "SELECT to_regclass('event_journal')"), "event_journal")
    assertEquals(psql("billing", "SELECT to_regclass('event_journal')"), "event_journal")
    assertEquals(get(shop.orders, "/call/billing/env/ANKKA_SERVICE_NAME"), (200, "billing"))
    assert(out.contains(s"orders") && out.contains(s"http://127.0.0.1:${shop.orders}"), out)
    assert(out.contains(s"billing") && out.contains(s"http://127.0.0.1:${shop.billing}"), out)
  }

  test(
    "a service that does not become ready is reported by name, with what it logged, and the command fails"
  ) {
    shop = project(billingEnv = """{"name":"STANDIN_FAIL","value":"start"}""")
    // Long enough for the database server's first start, which this bound covers too.
    val (code, _, err) = up("--timeout", "1m")
    assertEquals(code, 1)
    assert(err.contains("\"billing\" was not ready within 1m; the end of its log:"), err)
    assert(err.contains("standin billing was told to fail at start"), err)
    assert(containers().contains("orders"), containers())
  }

  test("bringing a project down stops every service and everything started for it") {
    upOk()
    val (code, out, _) = run("local", "down", "--dir", shop.root.toString)
    assertEquals(code, 0)
    assertEquals(out.trim, "shop is down; what it recorded is kept")
    assertEquals(compose("ps", "--all", "-q")._2.trim, "")
  }

  test("bringing up a project that is already up starts only what is not running") {
    upOk()
    val orders = containers()("orders")
    compose("stop", "billing")
    val (code, out, err) = up()
    assertEquals(code, 0, err)
    assertEquals(containers()("orders"), orders)
    assert(containers().contains("billing"))
    assert(out.linesIterator.exists(l => l.contains("orders") && l.trim.endsWith("running")), out)
  }

  test("a service whose environment or image changed is restarted, and only it") {
    upOk()
    val orders = containers()("orders")
    LocalFixtures.write(shop.root, "local.json", """{"values":{"billing":{"CURRENCY":"GBP"}}}""")
    val (_, changed, err) = up()
    assert(
      changed.linesIterator.exists(l =>
        l.contains("billing") && l.contains("restarted (its environment changed)")
      ),
      changed + err
    )
    assertEquals(get(shop.billing, "/env/CURRENCY"), (200, "GBP"))
    build(Vector("rebuilt=1"), billingTag)
    val (_, rebuilt, err2) = up()
    assert(
      rebuilt.linesIterator.exists(l =>
        l.contains("billing") && l.contains("restarted (its image changed)")
      ),
      rebuilt + err2
    )
    assertEquals(containers()("orders"), orders)
  }

  test("a directory that holds no project is refused, saying what a project's directory holds") {
    val empty          = Files.createTempDirectory("nothing")
    val (code, _, err) = run("local", "up", "--dir", empty.toString)
    assertEquals(code, 1)
    assert(err.contains("holds no project: a project's directory holds context-map.json"), err)
    assertEquals(compose("ps", "--all", "-q")._2.trim, "")
  }

  test("what the services recorded is kept when a project is brought down and up again") {
    upOk()
    assertEquals(put(shop.orders, "/records/1", "kept"), 200)
    assertEquals(run("local", "down", "--dir", shop.root.toString)._1, 0)
    upOk()
    assertEquals(get(shop.orders, "/records/1"), (200, "kept"))
  }

  test("what the services recorded is removed when the developer asks for it") {
    upOk()
    assertEquals(put(shop.orders, "/records/1", "gone"), 200)
    assertEquals(run("local", "down", "--volumes", "--dir", shop.root.toString)._1, 0)
    assertEquals(
      docker(
        "volume",
        "ls",
        "-q",
        "--filter",
        "label=com.docker.compose.project=ankka-shop"
      )._2.trim,
      ""
    )
    assert(!Files.exists(shop.root.resolve(".ankka/local")))
    upOk()
    assertEquals(get(shop.orders, "/records/1")._1, 404)
  }

  test("a service is started with the literal environment its descriptor declares") {
    shop = project(billingEnv = """{"name":"CURRENCY","value":"EUR"}""")
    upOk()
    assertEquals(get(shop.billing, "/env/CURRENCY"), (200, "EUR"))
  }

  test(
    "a variable the descriptor reads from a secret takes the value the project's local values give it"
  ) {
    shop = project(billingEnv =
      """{"name":"PAYMENT_KEY","secretKeyRef":{"name":"payments","key":"key"}}"""
    )
    LocalFixtures.write(
      shop.root,
      "local.json",
      """{"values":{"billing":{"PAYMENT_KEY":"test-key"}}}"""
    )
    upOk()
    assertEquals(get(shop.billing, "/env/PAYMENT_KEY"), (200, "test-key"))
  }

  // ── topics (US2) ──────────────────────────────────────────────────────────

  private def kafkaTopics(words: String*): (Int, String) =
    compose(
      (Vector(
        "exec",
        "-T",
        "ankka-kafka",
        "/opt/kafka/bin/kafka-topics.sh",
        "--bootstrap-server",
        "ankka-kafka:9094"
      ) ++ words)*
    )

  private def declare(json: String): Unit =
    LocalFixtures.write(shop.root, "topics.json", json): Unit

  /** When Docker created a container, or a file in one came to be, as seconds since the epoch. */
  private def createdAt(service: String): Long =
    val id = containers()(service)
    java.time.Instant.parse(docker("inspect", "-f", "{{.Created}}", id)._2.trim).getEpochSecond

  test(
    "each topic the project declares is made on the local broker with its partitions before any service starts"
  ) {
    declare("""{"topics":[{"name":"orders-placed","partitions":6}]}""")
    upOk()
    val (_, described) = kafkaTopics("--describe", "--topic", "shop.orders-placed")
    assert(described.contains("PartitionCount: 6"), described)
    // The partition's directory is made when the topic is; the service's container after it.
    val made = compose(
      "exec",
      "-T",
      "ankka-kafka",
      "stat",
      "-c",
      "%Y",
      "/var/lib/kafka/data/shop.orders-placed-0"
    )._2.trim.toLong
    assert(made > 0, "the partition's directory has a time")
    assert(made <= createdAt("orders") && made <= createdAt("billing"), s"made $made")
  }

  test("a topic declared as compacted is made compacted") {
    declare("""{"topics":[{"name":"customers","partitions":1,"compacted":true}]}""")
    upOk()
    val (_, described) = kafkaTopics("--describe", "--topic", "shop.customers")
    assert(described.contains("cleanup.policy=compact"), described)
  }

  test("a topic that already exists with other partitions is reported, and the command fails") {
    upOk()
    kafkaTopics(
      "--create",
      "--topic",
      "shop.orders-placed",
      "--partitions",
      "1",
      "--replication-factor",
      "1"
    )
    run("local", "down", "--dir", shop.root.toString)
    declare("""{"topics":[{"name":"orders-placed","partitions":6}]}""")
    val (code, _, err) = up()
    assertEquals(code, 1)
    assert(
      err.contains(
        "the topic \"shop.orders-placed\" has 1 partition on the local broker and 6 declared"
      ),
      err
    )
    assertEquals(containers().keySet, Set("ankka-postgres", "ankka-kafka"))
  }

  test("the local broker makes no topic that the project does not declare") {
    upOk()
    // A publication to an undeclared topic is refused by the broker, not made by it; that a
    // service's log names the topic is KafkaConnectionSuite's (research R16).
    val (code, said) = compose(
      "exec",
      "-T",
      "ankka-kafka",
      "sh",
      "-c",
      "echo hello | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server ankka-kafka:9094 " +
        "--topic shop.returns --producer-property max.block.ms=5000"
    )
    assert(
      code != 0 || said.contains("UNKNOWN_TOPIC_OR_PARTITION") || said.contains(
        "not present in metadata"
      ),
      said
    )
    assert(!kafkaTopics("--list")._2.linesIterator.contains("shop.returns"))
  }

  test(
    "a topic crossing in the context map that names no declared topic is refused before anything starts"
  ) {
    LocalFixtures.write(
      shop.root,
      "context-map.json",
      """{"project":"shop","services":[{"name":"orders","owns":"orders","boundary":{"criterion":"ownership","why":"w"}},""" +
        """{"name":"billing","owns":"invoices","boundary":{"criterion":"ownership","why":"w"}}],""" +
        """"crossings":[{"from":"orders","to":"billing","topic":"orders-placed"}]}"""
    )
    declare("""{"topics":[]}""")
    val (code, _, err) = up()
    assertEquals(code, 1)
    assert(
      err.contains("names the topic \"orders-placed\", which topics.json does not declare"),
      err
    )
    assertEquals(compose("ps", "--all", "-q")._2.trim, "")
  }

  // ── companions (US4) ──────────────────────────────────────────────────────

  private def identity(extra: String = "", env: String = ""): Int =
    val port = freePort()
    LocalFixtures.write(
      shop.root,
      "local.json",
      s"""{"companions":[{"name":"identity","image":"$tag","ports":[{"port":8080,"host":$port}],
         |"env":{"ANKKA_HTTP_PORT":"8080","ANKKA_SERVICE_NAME":"identity"$env},
         |"ready":{"http":{"port":8080,"path":"/env/ANKKA_SERVICE_NAME"}},"neededBy":["orders"]$extra}]}""".stripMargin
    )
    port

  test("what a project declares beside its services is started before the services") {
    val port = identity()
    upOk()
    assert(containers().contains("identity"), containers())
    assertEquals(get(port, "/env/ANKKA_SERVICE_NAME"), (200, "identity"))
    assert(createdAt("identity") <= createdAt("orders"))
  }

  test("a service is started only when what it needs beside it is ready") {
    identity(env = ""","STANDIN_FAIL":"start"""")
    val (code, _, err) = up("--timeout", "1m")
    assertEquals(code, 1)
    assert(err.contains("\"identity\" was not ready within 1m"), err)
    assert(err.contains("\"orders\" is not started: it needs \"identity\""), err)
    assert(!containers().contains("orders"), containers())
    assert(containers().contains("billing"), containers())
  }

  test(
    "something declared beside the services is given a database in the project's database server when it asks for one"
  ) {
    identity(extra = ""","database":true""")
    upOk()
    val databases = psql("postgres", "SELECT datname FROM pg_database").linesIterator.toSet
    assert(Set("identity", "orders", "billing").subsetOf(databases), databases)
  }

  test("bringing a project down stops what was started beside its services") {
    identity()
    upOk()
    assertEquals(run("local", "down", "--dir", shop.root.toString)._1, 0)
    assert(!containers().contains("identity"), containers())
  }

  test("with the companion's declaration removed the command still starts every service") {
    identity()
    upOk()
    Files.delete(shop.root.resolve("local.json"))
    upOk()
    assert(containers().contains("orders") && containers().contains("billing"), containers())
  }

  // ── one service from its source (US5) ─────────────────────────────────────

  test("a service the developer leaves out is not started, and the command says how to run it") {
    val (code, out, err) = up("--without", "billing")
    assertEquals(code, 0, out + err)
    assert(
      out.contains("left out: billing — run it from its source with: ankka local run billing"),
      out
    )
    assertEquals(containers().keySet, Set("ankka-postgres", "ankka-kafka", "orders"))
  }

  test("only the services the developer names are started, with what they need") {
    val (code, out, err) = up("billing")
    assertEquals(code, 0, out + err)
    assertEquals(containers().keySet, Set("ankka-postgres", "ankka-kafka", "billing"))
    val databases = psql("postgres", "SELECT datname FROM pg_database").linesIterator.toSet
    assert(databases("billing") && !databases("orders"), databases)
  }

  /**
   * `billing` run from its source: the stand-in's one file on the host's JDK, announcing itself in
   * the suite's running directory as a runtime does. That the real runtime finds and is found the
   * same way is `ProjectTemplateSuite`'s, against the template's own services.
   */
  private def fromSource(service: String)(body: => Unit): Unit =
    val running = home.resolve("running")
    LocalFixtures.write(
      shop.root,
      "local.json",
      s"""{"values":{"$service":{"STANDIN_ANNOUNCE":"$running"}}}"""
    )
    val source = Paths.get(getClass.getResource("/standin/Standin.java").toURI)
    val said   = java.util.concurrent.atomic.AtomicReference[(Int, String, String)]()
    val runner = Thread(() =>
      said.set(
        run("local", "run", service, "--dir", shop.root.toString, "--", "java", source.toString)
      )
    )
    runner.start()
    val port  = if service == "billing" then shop.billing else shop.orders
    val until = Deadline.now + 2.minutes
    while scala.util.Try(get(port, "/env/ANKKA_SERVICE_NAME")).toOption.forall(_._1 != 200) do
      assert(
        until.hasTimeLeft() && runner.isAlive,
        s"$service did not start from its source: ${said.get}"
      )
      Thread.sleep(500)
    try body
    finally
      runner.interrupt()
      runner.join(30000)
    assertEquals(said.get._1, 130, "interrupted, it answers 130")

  test("a service run from its source is reached by name by the services the command started") {
    val (code, out, err) = up("--without", "billing")
    assertEquals(code, 0, out + err)
    fromSource("billing") {
      assertEquals(get(shop.orders, "/call/billing/env/ANKKA_SERVICE_NAME"), (200, "billing"))
    }
  }

  test("a service run from its source reaches the services the command started by name") {
    val (code, out, err) = up("--without", "billing")
    assertEquals(code, 0, out + err)
    fromSource("billing") {
      assertEquals(get(shop.billing, "/call/orders/env/ANKKA_SERVICE_NAME"), (200, "orders"))
    }
  }

  test("leaving a service out of a project that is up restarts no other service") {
    upOk()
    val before           = containers()("orders")
    val (code, out, err) = up("--without", "billing")
    assertEquals(code, 0, out + err)
    assertEquals(containers()("orders"), before)
  }

  // ── status, logs and the console (US6) ────────────────────────────────────

  test("the developer is told which services of a project are running and ready") {
    upOk()
    assertEquals(docker("stop", containers()("billing"))._1, 0)
    val (code, out, err) = run("local", "status", "--dir", shop.root.toString)
    assertEquals(code, 0, err)
    val lines = out.linesIterator.map(_.trim.replaceAll("\\s+", " ")).toVector
    assertEquals(
      lines,
      Vector(
        s"orders ready http://127.0.0.1:${shop.orders} started by ankka local up",
        "billing not running"
      )
    )
  }

  test("the developer reads a service's log by the service's name") {
    upOk()
    val (code, out, err) = run("local", "logs", "orders", "--dir", shop.root.toString)
    assertEquals(code, 0, err)
    assert(out.contains("standin orders started"), out)
  }

  test("the local console lists the services a project was brought up with") {
    upOk()
    val console = com.thinkmorestupidless.ankka.cli.console.ConsoleServer.start(
      com.thinkmorestupidless.ankka.cli.console.LocalSource(home.resolve("running")),
      0,
      PrintStream(ByteArrayOutputStream())
    )
    try
      val (status, body) = get(console.port, "/api/services")
      assertEquals(status, 200, body)
      assert(body.contains("\"orders\"") && body.contains("\"billing\""), body)
    finally console.stop()
  }

  // ── the other hostings (research R10) ─────────────────────────────────────

  /**
   * `image` on the machine, or the case is skipped saying why; never skipped in CI with the local
   * tests on, where a skip would pass a job that ran nothing.
   */
  private def requireImage(image: String): Unit =
    val present = docker("image", "inspect", image)._1 == 0
    if !present && sys.props.get("ankka.local.tests").contains("on") && sys.env.contains("CI") then
      fail(s"$image is not on the machine; build it before the local tests")
    assume(present, s"$image is not on the machine; build it with sbt Docker/publishLocal")

  /** A project of the orders stand-in and one other service, `extra` its descriptor's hosting. */
  private def hostedShop(other: String, extra: String, crossings: Vector[String]): Shop =
    val orders = freePort()
    val port   = freePort()
    val root   = LocalFixtures.project(
      Vector(
        LocalFixtures.Service("orders", orders, image = tag),
        LocalFixtures.Service(other, port, image = billingTag, extra = extra)
      ),
      crossings = crossings,
      root = Files.createDirectories(home.resolve(s"shop-$other"))
    )
    Shop(root, orders, port)

  test("a process-hosted service is its sidecar and its process, in one network namespace") {
    requireImage("ankka-sidecar:latest")
    // The stand-in as the process: it speaks no protocol, so the sidecar is never ready, and what
    // is proved is what the command composes, not the runtime's half.
    shop = hostedShop(
      "billing",
      ""","hosting":"process","protocol":"1.0"""",
      Vector("""{"from":"orders","to":"billing","route":"POST /invoices"}""")
    )
    // The bound covers the database server's first start too, and the sidecar is never ready.
    val (_, out, err) = up("--timeout", "90s")
    val ids           = containers()
    assert(ids.contains("billing") && ids.contains("billing-app"), out + err + ids)
    val (_, mode) =
      docker("inspect", "-f", "{{.HostConfig.NetworkMode}}", ids("billing-app"))
    val (_, sidecarId) = docker("inspect", "-f", "{{.Id}}", ids("billing"))
    assertEquals(mode.trim, s"container:${sidecarId.trim}")
    // The sidecar's schema is in its database, made by the command from the sidecar's own files.
    assertEquals(
      psql(
        "billing",
        "select count(*) from information_schema.tables where table_name = 'event_journal'"
      ),
      "1"
    )
  }

  test("a web-hosted service's proxy serves a mount to the service it names") {
    requireImage("ankka-proxy:latest")
    shop = hostedShop(
      "front",
      ""","hosting":"web","processPort":8080,"mounts":[{"path":"/api","service":"orders"}]""",
      Vector("""{"from":"front","to":"orders","route":"GET /env/{name}"}""")
    )
    upOk(): Unit
    assertEquals(get(shop.billing, "/api/env/ANKKA_SERVICE_NAME"), (200, "orders"))
  }
