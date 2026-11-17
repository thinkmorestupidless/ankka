package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.local.{Engine, LocalPlan, LocalSeams, LocalState}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * `ankka local up` and `down` through `Main.run` (feature 066), over an engine that runs nothing:
 * what is asked of Docker and in which order, what is refused before anything starts, and what the
 * command says.
 */
class LocalCommandSuite extends munit.FunSuite:

  /** A database server that remembers databases, their comments and what was applied to each. */
  final class Databases:
    @volatile var made: Set[String]                 = Set.empty
    @volatile var comments: Map[String, String]     = Map.empty
    @volatile var applied: Vector[(String, String)] = Vector.empty
    private val Named                               = """.*datname = '([^']*)'.*""".r
    private val Create                              = """CREATE DATABASE "([^"]*)"""".r
    private val Comment = """COMMENT ON DATABASE "([^"]*)" IS '([^']*)'""".r

    def answer(
        @scala.annotation.unused service: String,
        words: Vector[String],
        stdin: Option[String]
    ): Engine.Result =
      val database = words.dropWhile(_ != "-d").drop(1).headOption.getOrElse("")
      val sql      = words.dropWhile(_ != "-c").drop(1).headOption.getOrElse("")
      synchronized {
        stdin match
          case Some(script) =>
            applied :+= database -> script
            Engine.Result(0, "", "")
          case None =>
            sql match
              case s if s.startsWith("SELECT 1") =>
                val Named(name) = s: @unchecked
                Engine.Result(0, if made(name) then "1\n" else "", "")
              case s if s.startsWith("SELECT shobj_description") =>
                val Named(name) = s: @unchecked
                Engine.Result(0, comments.getOrElse(name, "") + "\n", "")
              case Create(name) =>
                made += name
                Engine.Result(0, "CREATE DATABASE", "")
              case Comment(name, text) =>
                comments += name -> text
                Engine.Result(0, "COMMENT", "")
              case other => Engine.Result(1, "", s"unexpected: $other")
      }

  /** The broker's topic tool: what `--describe` prints, and every creation asked of it. */
  final class Broker:
    @volatile var described: String                  = ""
    @volatile var created: Vector[String]            = Vector.empty
    def answer(words: Vector[String]): Engine.Result = synchronized {
      if words.contains("--describe") then Engine.Result(0, described, "")
      else
        created :+= words.mkString(" ")
        Engine.Result(0, "", "")
    }

  private var engine: ScriptedEngine = null
  private var databases: Databases   = null
  private var broker: Broker         = null
  private var cache: Path            = null
  private var running: Path          = null

  override def beforeEach(context: BeforeEach): Unit =
    engine = ScriptedEngine()
    databases = Databases()
    broker = Broker()
    engine.execAnswer = (service, words, stdin) =>
      if service == LocalPlan.Kafka then broker.answer(words)
      else databases.answer(service, words, stdin)
    cache = Files.createTempDirectory("cache")
    for version <- Vector("0.15.0", "0.17.5", "1.0.0") do
      Files.createDirectories(cache.resolve(s"ddl/$version"))
      Files.writeString(
        cache.resolve(s"ddl/$version/10-journal.sql"),
        s"-- the schema of $version",
        UTF_8
      )
    sys.props("ankka.cache.dir") = cache.toString
    // The console's entries go into a directory of the suite's, never the developer's own.
    running = Files.createTempDirectory("running")
    sys.props("ankka.running.dir") = running.toString
    LocalSeams.engine = () => engine
    LocalSeams.probe = _ => true
    LocalSeams.isFree = _ => true
    LocalSeams.cliVersion = () => "0.17.0"

  override def afterEach(context: AfterEach): Unit =
    LocalSeams.reset()
    sys.props.remove("ankka.cache.dir"): Unit
    sys.props.remove("ankka.running.dir"): Unit

  private def run(args: String*): (Int, String, String) =
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    val code = Main.run(args, PrintStream(out, true, UTF_8), PrintStream(err, true, UTF_8))
    (code, out.toString(UTF_8), err.toString(UTF_8))

  private def up(root: Path, args: String*): (Int, String, String) =
    run((Vector("local", "up", "--dir", root.toString) ++ args)*)

  /** The calls of the last run, without the gateway question a Linux machine adds. */
  private def calls: Vector[String] = engine.calls.filterNot(_ == "gateway")

  private def ups: Vector[String] = calls.filter(_.startsWith("up "))

  test(
    "the engine is asked in order: images, the server and broker, databases, then the services"
  ) {
    val root           = LocalFixtures.project()
    val (code, _, err) = up(root)
    assertEquals(code, 0, err)
    val c = calls
    assertEquals(c.head, "available")
    val firstUp   = c.indexWhere(_.startsWith("up "))
    val lastImage = c.lastIndexWhere(_.startsWith("image "))
    val firstExec = c.indexWhere(_.startsWith("exec "))
    val lastExec  = c.lastIndexWhere(_.startsWith("exec "))
    assert(lastImage < firstUp, c)
    assertEquals(c(firstUp), "up ankka-shop ankka-postgres ankka-kafka")
    assert(firstUp < firstExec, c)
    assertEquals(ups.last, "up ankka-shop orders billing --remove-orphans")
    assert(lastExec < c.indexOf(ups.last), c)
    assertEquals(databases.made, Set("orders", "billing"))
    assert(Files.isRegularFile(root.resolve(".ankka/local/compose.json")))
    assert(Files.isRegularFile(root.resolve(".ankka/local/state.json")))
  }

  private val Topics =
    """{"topics":[{"name":"orders-placed","partitions":6},{"name":"customers","partitions":3,"compacted":true}]}"""

  test("the declared topics are made after the broker is healthy and before any service") {
    val root = LocalFixtures.project()
    LocalFixtures.write(root, "topics.json", Topics)
    val (code, _, err) = up(root)
    assertEquals(code, 0, err)
    val c        = calls
    val describe =
      c.indexWhere(l => l.startsWith("exec ankka-shop ankka-kafka") && l.contains("--describe"))
    val create =
      c.indexWhere(l => l.startsWith("exec ankka-shop ankka-kafka") && l.contains("--create"))
    assert(c.indexOf("up ankka-shop ankka-postgres ankka-kafka") < describe, c)
    assert(describe < create && create < c.indexOf(ups.last), c)
    assertEquals(broker.created.size, 1)
    assert(
      broker.created.head.contains("--topic shop.orders-placed --partitions 6"),
      broker.created
    )
    assert(
      broker.created.head.contains(
        "--topic shop.customers --partitions 3 --replication-factor 1 --config cleanup.policy=compact"
      ),
      broker.created
    )
  }

  test("a topic that exists otherwise than declared starts no service, and says so") {
    val root = LocalFixtures.project()
    LocalFixtures.write(root, "topics.json", Topics)
    broker.described =
      "Topic: shop.orders-placed\tTopicId: x\tPartitionCount: 1\tReplicationFactor: 1\tConfigs: \n"
    val (code, _, err) = up(root)
    assertEquals(code, 1)
    assert(
      err.contains(
        "the topic \"shop.orders-placed\" has 1 partition on the local broker and 6 declared; ankka local down --volumes removes it"
      ),
      err
    )
    assert(err.contains("the database server and the broker of shop are left running"), err)
    assertEquals(ups, Vector("up ankka-shop ankka-postgres ankka-kafka"))
    assertEquals(broker.created, Vector.empty)
  }

  test("nothing is started when a static problem exists") {
    val root           = LocalFixtures.project()
    val (code, _, err) = up(root, "payments")
    assertEquals(code, 1)
    assert(err.contains("\"payments\" is not a service of shop"), err)
    assertEquals(ups, Vector.empty)
    assert(!Files.exists(root.resolve(".ankka/local/compose.json")))
  }

  test("the printed table names both services and their addresses, and says each started") {
    val (code, out, err) = up(LocalFixtures.project())
    assertEquals(code, 0, err)
    val lines = out.linesIterator.toVector
    assertEquals(lines.head, "shop is up")
    assertEquals(
      lines(1).split("\\s+").filter(_.nonEmpty).toVector,
      Vector("orders", "http://127.0.0.1:9000", "started")
    )
    assertEquals(
      lines(2).split("\\s+").filter(_.nonEmpty).toVector,
      Vector("billing", "http://127.0.0.1:9001", "started")
    )
  }

  test("a second run with nothing changed says both are running") {
    val root = LocalFixtures.project()
    up(root)
    val (code, out, _) = up(root)
    assertEquals(code, 0)
    assert(out.linesIterator.drop(1).forall(_.trim.endsWith("running")), out)
  }

  test("a container replaced with another image is restarted for its image, the other running") {
    val root = LocalFixtures.project()
    up(root)
    engine.replace = Map("billing" -> true)
    val (_, out, _) = up(root)
    val lines       = out.linesIterator.toVector
    assert(
      lines.exists(l => l.contains("billing") && l.contains("restarted (its image changed)")),
      out
    )
    assert(lines.exists(l => l.contains("orders") && l.trim.endsWith("running")), out)
  }

  test("a container replaced with the same image is restarted for its environment") {
    val root = LocalFixtures.project()
    up(root)
    LocalFixtures.write(
      root,
      "services/billing/service.json",
      LocalFixtures
        .Service("billing", 9001, env = """{"name":"CURRENCY","value":"GBP"}""")
        .descriptor
    )
    engine.replace = Map("billing" -> false)
    val (_, out, _) = up(root)
    assert(
      out.linesIterator.exists(l =>
        l.contains("billing") && l.contains("restarted (its environment changed)")
      ),
      out
    )
  }

  test("an image that cannot be pulled names the service and the image, and starts nothing") {
    engine.present = _ != "billing:1"
    engine.pullable = _ != "billing:1"
    val (code, _, err) = up(LocalFixtures.project())
    assertEquals(code, 1)
    assertEquals(
      err.trim,
      "\"billing\" names the image billing:1, which is not on this machine and could not be pulled"
    )
    assertEquals(ups, Vector.empty)
  }

  test("Docker absent is refused before any file is read") {
    engine.availability = Left("docker is not running on this machine")
    val (code, _, err) = up(Files.createTempDirectory("nothing"))
    assertEquals(code, 1)
    assertEquals(err.trim, "docker is not running on this machine")
    assertEquals(calls, Vector("available"))
  }

  test("down with nothing up says so, and succeeds") {
    val (code, out, _) = run("local", "down", "--dir", LocalFixtures.project().toString)
    assertEquals(code, 0)
    assertEquals(out.trim, "nothing of shop is running")
  }

  test("down stops the project, keeping what it recorded unless asked") {
    val root = LocalFixtures.project()
    up(root)
    val (code, out, _) = run("local", "down", "--dir", root.toString)
    assertEquals(code, 0)
    assertEquals(out.trim, "shop is down; what it recorded is kept")
    assert(Files.exists(root.resolve(".ankka/local/state.json")))
    up(root)
    val (_, removed, _) = run("local", "down", "--volumes", "--dir", root.toString)
    assertEquals(removed.trim, "shop is down; what it recorded is removed")
    assert(calls.contains("down ankka-shop -v"))
    assert(!Files.exists(root.resolve(".ankka/local")))
  }

  private def onRuntime(orders: String, billing: String): Path =
    LocalFixtures.project(
      Vector(
        LocalFixtures.Service("orders", 9000, extra = s""","runtime":"$orders""""),
        LocalFixtures.Service("billing", 9001, extra = s""","runtime":"$billing"""")
      )
    )

  private def schemaComment(version: String): String =
    val schema =
      local.Schemas.files(Some(version), "0.17.0", engine, cache = cache).fold(fail(_), identity)
    local.Schemas.comment(schema)

  test(
    "a database holding an older schema is refused, naming both versions, and nothing is dropped"
  ) {
    val root = onRuntime("0.15.0", "0.15.0")
    databases.made = Set("orders", "billing")
    databases.comments =
      Map("orders" -> schemaComment("0.17.5"), "billing" -> schemaComment("0.15.0"))
    val (code, _, err) = up(root)
    assertEquals(code, 1)
    assert(
      err.contains(
        "the database of \"orders\" holds the schema of ankka 0.17.5 and its descriptor names 0.15.0; " +
          "remove it with: docker compose -p ankka-shop exec ankka-postgres dropdb -U ankka orders"
      ),
      err
    )
    assert(err.contains("the database server and the broker of shop are left running"), err)
    assertEquals(ups, Vector("up ankka-shop ankka-postgres ankka-kafka"))
    assert(!calls.exists(_.contains("dropdb")))
    assertEquals(databases.applied, Vector.empty)
  }

  test("a database of another major version is refused") {
    val root = onRuntime("1.0.0", "1.0.0")
    databases.made = Set("orders", "billing")
    databases.comments =
      Map("orders" -> schemaComment("0.17.5"), "billing" -> schemaComment("1.0.0"))
    val (code, _, err) = up(root)
    assertEquals(code, 1)
    assert(
      err.contains(
        "the database of \"orders\" holds the schema of ankka 0.17.5 and its descriptor names 1.0.0"
      ),
      err
    )
  }

  test("a newer runtime of the same major is applied, and the move is said") {
    val root = onRuntime("0.17.5", "0.17.5")
    databases.made = Set("orders", "billing")
    databases.comments =
      Map("orders" -> schemaComment("0.15.0"), "billing" -> schemaComment("0.17.5"))
    val (code, _, err) = up(root)
    assertEquals(code, 0, err)
    assert(err.contains("the schema of \"orders\" moved from ankka 0.15.0 to 0.17.5"), err)
    assertEquals(databases.applied.map(_._1), Vector("orders"))
  }

  test("two services on different runtimes are each given their own version's files") {
    val (code, _, err) = up(onRuntime("0.15.0", "0.17.5"))
    assertEquals(code, 0, err)
    val applied = databases.applied.toMap
    assertEquals(applied("orders"), "-- the schema of 0.15.0")
    assertEquals(applied("billing"), "-- the schema of 0.17.5")
  }

  test(
    "a service added to the context map is given a database and started, nothing else restarted"
  ) {
    val root = LocalFixtures.project()
    up(root)
    val withPayments = LocalFixtures.project(
      Vector(
        LocalFixtures.Service("orders", 9000),
        LocalFixtures.Service("billing", 9001),
        LocalFixtures.Service("payments", 9002)
      ),
      crossings = Vector(
        """{"from":"orders","to":"billing","route":"POST /invoices"}""",
        """{"from":"orders","to":"payments","route":"POST /payments"}"""
      ),
      root = root
    )
    val (code, out, err) = up(withPayments)
    assertEquals(code, 0, err)
    assert(databases.made.contains("payments"))
    val lines = out.linesIterator.toVector
    assert(lines.exists(l => l.contains("payments") && l.trim.endsWith("started")), out)
    assert(
      lines
        .filter(l => l.contains("orders") || l.contains("billing"))
        .forall(_.trim.endsWith("running")),
      out
    )
  }

  test("a service removed from the context map is stopped and its database kept") {
    val root = LocalFixtures.project(
      Vector(
        LocalFixtures.Service("orders", 9000),
        LocalFixtures.Service("billing", 9001),
        LocalFixtures.Service("payments", 9002)
      ),
      crossings = Vector(
        """{"from":"orders","to":"billing","route":"POST /invoices"}""",
        """{"from":"orders","to":"payments","route":"POST /payments"}"""
      )
    )
    up(root)
    Files.delete(root.resolve("services/payments/service.json"))
    Files.delete(root.resolve("services/payments"))
    LocalFixtures.project(root = root)
    val (code, _, err) = up(root)
    assertEquals(code, 0, err)
    assert(
      err.contains(
        "\"payments\" is no longer in the context map; it is stopped, and its database is kept"
      ),
      err
    )
    assert(!engine.running("ankka-shop").contains("payments"))
    assert(databases.made.contains("payments"))
  }

  test("the project's local values are never printed") {
    val root = LocalFixtures.project(
      Vector(
        LocalFixtures.Service(
          "orders",
          9000,
          env = """{"name":"PAYMENT_KEY","secretKeyRef":{"name":"payments","key":"key"}}"""
        ),
        LocalFixtures.Service("billing", 9001)
      )
    )
    LocalFixtures.write(root, "local.json", """{"values":{"orders":{"PAYMENT_KEY":"test-key"}}}""")
    def said(r: (Int, String, String)): String = r._2 + r._3
    val printed                                = Vector.newBuilder[String]
    printed += said(up(root))
    // `local status` joins these once it exists (T035).
    LocalSeams.probe = _ => false
    printed += said(up(root, "--timeout", "10ms"))
    LocalSeams.probe = _ => true
    printed += said(up(root, "payments"))
    printed.result().foreach(text => assert(!text.contains("test-key"), text))
    // The value did reach the container, so the test is not passing on a value never used.
    assert(Files.readString(root.resolve(".ankka/local/compose.json"), UTF_8).contains("test-key"))
  }

  test(
    "a missing local value, a taken port and an undeclared topic are refused together, and nothing starts"
  ) {
    val root = LocalFixtures.project(
      Vector(
        LocalFixtures.Service(
          "orders",
          9000,
          env = """{"name":"PAYMENT_KEY","secretKeyRef":{"name":"payments","key":"key"}}"""
        ),
        LocalFixtures.Service("billing", 9001)
      ),
      crossings = Vector("""{"from":"orders","to":"billing","topic":"orders-placed"}""")
    )
    LocalFixtures.write(root, "topics.json", """{"topics":[]}""")
    LocalSeams.isFree = _ != 9001
    val (code, _, err) = up(root)
    assertEquals(code, 1)
    assert(
      err.contains("\"orders\" reads PAYMENT_KEY from a secret and local.json gives it no value"),
      err
    )
    assert(err.contains("\"billing\" names port 9001, which is in use on this machine"), err)
    assert(
      err.contains(
        "the crossing from \"orders\" to \"billing\" names the topic \"orders-placed\", which topics.json does not declare"
      ),
      err
    )
    assertEquals(ups, Vector.empty)
  }

  private def withCompanion(root: Path): Path =
    LocalFixtures.write(
      root,
      "local.json",
      """{"companions":[{"name":"identity","image":"keycloak","ports":[{"port":8080,"host":8081}],
        |"ready":{"http":{"port":8080,"path":"/"}},"database":true,"neededBy":["orders"]}]}""".stripMargin
    )
    root

  test("a second up with a companion running finds the companion's port its own") {
    val root = withCompanion(LocalFixtures.project())
    assertEquals(up(root)._1, 0)
    // The running companion holds its host port, as it does on a machine.
    LocalSeams.isFree = _ != 8081
    val (code, _, err) = up(root)
    assertEquals(code, 0, err)
  }

  test("companions are made after the databases and before the services, and listed") {
    val root             = withCompanion(LocalFixtures.project())
    val (code, out, err) = up(root)
    assertEquals(code, 0, err)
    assertEquals(
      ups,
      Vector(
        "up ankka-shop ankka-postgres ankka-kafka",
        "up ankka-shop identity",
        "up ankka-shop orders billing --remove-orphans"
      )
    )
    assertEquals(databases.made, Set("orders", "billing", "identity"))
    assert(
      !databases.applied.exists(_._1 == "identity"),
      "a companion's database is given no schema"
    )
    assert(
      out.linesIterator.exists(l => l.contains("identity") && l.contains("127.0.0.1:8081")),
      out
    )
  }

  test(
    "a companion that is not ready is named with its log, and holds back only the services that need it"
  ) {
    val root = withCompanion(LocalFixtures.project())
    LocalSeams.probe = {
      case LocalPlan.ReadyCheck.Http(8081, _) => false
      case _                                  => true
    }
    val (code, _, err) = up(root, "--timeout", "10ms")
    assertEquals(code, 1)
    assert(err.contains("\"identity\" was not ready within 10ms; the end of its log:"), err)
    assert(err.contains("identity logged"), err)
    assert(
      err.contains("\"orders\" is not started: it needs \"identity\", which is not ready"),
      err
    )
    assertEquals(ups.last, "up ankka-shop billing --remove-orphans")
  }

  // ── one service from its source (US5) ─────────────────────────────────────

  test("a service the developer leaves out is not started, and the command says how to run it") {
    val root             = LocalFixtures.project()
    val (code, out, err) = up(root, "--without", "orders")
    assertEquals(code, 0, err)
    assertEquals(ups.last, "up ankka-shop billing --remove-orphans")
    assert(
      out.linesIterator.contains(
        "left out: orders — run it from its source with: ankka local run orders"
      ),
      out
    )
  }

  test("a service run from its source announces itself where the command looks") {
    val before = sys.props.get("ankka.running.dir")
    try
      sys.props("ankka.running.dir") = "/x/running"
      assertEquals(
        com.thinkmorestupidless.ankka.cli.local.LocalRun.runningDirectory(Some("-Xmx1g")),
        Some("-Xmx1g -Dankka.running.dir=/x/running")
      )
      sys.props.remove("ankka.running.dir")
      assertEquals(com.thinkmorestupidless.ankka.cli.local.LocalRun.runningDirectory(None), None)
    finally before.foreach(sys.props("ankka.running.dir") = _)
  }

  test("a service left out of a project that is up is stopped, its data and the others kept") {
    val root = LocalFixtures.project()
    assertEquals(up(root)._1, 0)
    val orders           = engine.running("ankka-shop")("orders").id
    val (code, out, err) = up(root, "--without", "billing")
    assertEquals(code, 0, err)
    assert(calls.contains("stop ankka-shop billing"), calls)
    assert(!calls.exists(_.startsWith("down")), calls)
    assertEquals(engine.running("ankka-shop").get("billing"), None)
    assertEquals(engine.running("ankka-shop")("orders").id, orders)
    assert(out.contains("left out: billing"), out)
  }

  test("only the services the developer names are started, with what they need") {
    val root           = LocalFixtures.project()
    val (code, _, err) = up(root, "billing")
    assertEquals(code, 0, err)
    assertEquals(ups.head, "up ankka-shop ankka-postgres ankka-kafka")
    assertEquals(ups.last, "up ankka-shop billing --remove-orphans")
    assertEquals(databases.made, Set("billing"))
  }

  test("the console is given an entry for each service once it is ready, and down removes them") {
    val root           = LocalFixtures.project()
    val (code, _, err) = up(root)
    assertEquals(code, 0, err)
    def entries =
      Option(running.toFile.list()).toVector.flatten.filter(_.endsWith(".json")).sorted
    assertEquals(entries, Vector("shop.billing.json", "shop.orders.json"))
    assert(Files.readString(running.resolve("shop.orders.json")).contains("\"owner\""))
    assertEquals(run("local", "down", "--dir", root.toString)._1, 0)
    assertEquals(entries, Vector.empty)
  }

  private def localRun(root: Path, args: String*): (Int, String, String) =
    run((Vector("local", "run") ++ args.take(1) ++ Vector("--dir", root.toString) ++ args.drop(1))*)

  /** What a command run from its source was given, as `env` printed it. */
  private def envOf(file: Path): Map[String, String] =
    Files
      .readAllLines(file, UTF_8)
      .asScala
      .toVector
      .flatMap(line =>
        line.split("=", 2) match
          case Array(k, v) => Some(k -> v)
          case _           => None
      )
      .toMap

  test(
    "a service run from its source is given its own database, port, name, prefix and local values"
  ) {
    val root = LocalFixtures.project()
    LocalFixtures.write(
      root,
      "local.json",
      """{"values":{"billing":{"CURRENCY":"GBP"},"orders":{"OTHER":"x"}}}"""
    )
    val file             = root.resolve("env.txt")
    val (code, out, err) = localRun(root, "billing", "--", "sh", "-c", s"env > $file")
    assertEquals(code, 0, out + err)
    val env = envOf(file)
    assertEquals(env.get("ANKKA_SERVICE_NAME"), Some("billing"))
    assertEquals(env.get("ANKKA_HTTP_PORT"), Some("9001"))
    assertEquals(env.get("ANKKA_DB_HOST"), Some("localhost"))
    assertEquals(env.get("ANKKA_DB_NAME"), Some("billing"))
    assert(env.get("ANKKA_DB_PORT").exists(_.toIntOption.isDefined), env)
    assert(env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.startsWith("localhost:")), env)
    assertEquals(env.get("ANKKA_KAFKA_TOPIC_PREFIX"), Some("shop."))
    assertEquals(env.get("CURRENCY"), Some("GBP"))
    assert(!env.contains("OTHER"), "another service's values are not given")
    assert(!env.contains("ANKKA_OBSERVABILITY_ADDRESS"), env)
    // What it needs is made, and no service container is brought up.
    assertEquals(databases.made, Set("billing"))
    assert(!ups.exists(u => u.contains("orders") || u.contains(" billing")), ups)
  }

  test("a service that is a container is refused with the contract's words") {
    val root = LocalFixtures.project()
    assertEquals(up(root)._1, 0)
    val (code, _, err) = localRun(root, "orders", "--", "true")
    assertEquals(code, 1)
    assertEquals(
      err.trim,
      "\"orders\" is running from its image; leave it out with: ankka local up --without orders"
    )
  }

  test("a service whose command cannot be told is refused") {
    val root           = LocalFixtures.project()
    val (code, _, err) = localRun(root, "orders")
    assertEquals(code, 1)
    assertEquals(
      err.trim,
      "cannot tell how to run \"orders\" from its source; give the command after --, or name it " +
        "under \"run\" in local.json"
    )
    assert(calls.forall(c => c == "available" || c.startsWith("ps ")), calls)
  }

  test("the command named under run in local.json is the one run, with the service's exit code") {
    val root = LocalFixtures.project()
    LocalFixtures.write(root, "local.json", """{"run":{"billing":["sh","-c","exit 7"]}}""")
    assertEquals(localRun(root, "billing")._1, 7)
  }

  // ── one service from its source, each hosting (research R12) ──────────────

  private def hostedBilling(extra: String, port: Int = 9001): Path =
    LocalFixtures.project(
      Vector(
        LocalFixtures.Service("orders", 9000),
        LocalFixtures.Service("billing", port, extra = extra)
      )
    )

  private def composed(root: Path): String =
    Files.readString(root.resolve(".ankka/local/compose.json"), UTF_8)

  test("a process service left out is stopped whole: its sidecar and its program") {
    val root = hostedBilling(""","hosting":"process","protocol":"1.0"""")
    assertEquals(up(root)._1, 0)
    val (code, _, err) = up(root, "--without", "billing")
    assertEquals(code, 0, err)
    assert(calls.contains("stop ankka-shop billing billing-app"), calls)
  }

  test("after a process service ran from its source, up brings it up from its image") {
    val root             = hostedBilling(""","hosting":"process","protocol":"1.0"""")
    val (ran, out, said) = localRun(root, "billing", "--", "true")
    assertEquals(ran, 0, out + said)
    assert(engine.running("ankka-shop").contains("billing-sidecar"))
    val (code, _, err) = up(root)
    assertEquals(code, 0, err)
    assert(calls.contains("stop ankka-shop billing-sidecar"), calls)
    assert(engine.running("ankka-shop").contains("billing"))
  }

  test("a process run from its source is given a sidecar that dials it on the host") {
    val root             = hostedBilling(""","hosting":"process","protocol":"1.0"""")
    val file             = root.resolve("env.txt")
    val (code, out, err) = localRun(root, "billing", "--", "sh", "-c", s"env > $file")
    assertEquals(code, 0, out + err)
    val state   = LocalState.read(root)
    val process = state.processPorts("billing")
    val back    = state.processPorts(LocalPlan.sidecarKey("billing"))
    // The sidecar is a container of the command's; the process is the developer's, on the host.
    assertEquals(ups.last, "up ankka-shop billing-sidecar --remove-orphans")
    val compose = composed(root)
    assert(compose.contains(s"host.docker.internal:$process"), compose)
    assert(compose.contains("ANKKA_SIDECAR_BIND"), compose)
    assert(compose.contains(s"127.0.0.1:$back:${LocalPlan.SidecarPort}"), compose)
    val env = envOf(file)
    assertEquals(env.get("ANKKA_PROCESS_PORT"), Some(process.toString))
    assertEquals(env.get("ANKKA_SIDECAR_ADDRESS"), Some(s"127.0.0.1:$back"))
    // What is the runtime's goes to the sidecar, not to the process; the broker's to both.
    assert(!env.contains("ANKKA_DB_NAME"), env)
    assert(env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.startsWith("localhost:")), env)
  }

  test("a module run from its source is built, then loaded by its runtime read-only") {
    val root               = hostedBilling(""","hosting":"wasm","protocol":"1.0"""")
    val module             = "target/wasm32-unknown-unknown/release/billing.wasm"
    val (refused, _, said) = localRun(root, "billing", "--", "true")
    assertEquals(refused, 1)
    assertEquals(
      said.trim,
      s"\"billing\" has no module at services/billing/$module; its command did not build one"
    )
    assert(!ups.exists(_.contains("billing")), ups)
    val build            = s"mkdir -p ${module.split('/').init.mkString("/")} && touch $module"
    val (code, out, err) = localRun(root, "billing", "--", "sh", "-c", build)
    assertEquals(code, 0, out + err)
    assertEquals(ups.last, "up ankka-shop billing --remove-orphans")
    val built = root.resolve(s"services/billing/$module").toAbsolutePath
    assert(composed(root).contains(s"$built:${LocalPlan.ModuleFile}:ro"), composed(root))
    assert(calls.contains("logs ankka-shop billing --follow"), calls)
  }

  test("logs shows both containers of a service that is two") {
    val root = hostedBilling(""","hosting":"process","protocol":"1.0"""")
    assertEquals(up(root)._1, 0)
    assertEquals(ups.last, "up ankka-shop orders billing billing-app --remove-orphans")
    val (code, _, err) = run("local", "logs", "billing", "--dir", root.toString)
    assertEquals(code, 0, err)
    assert(calls.contains("logs ankka-shop billing"), calls)
    assert(calls.contains("logs ankka-shop billing-app"), calls)
  }

  test("a web-hosted program run from its source runs behind the proxy's engine on the machine") {
    val port = locally {
      val socket = java.net.ServerSocket(0)
      try socket.getLocalPort
      finally socket.close()
    }
    val root = hostedBilling(
      s""","hosting":"web","processPort":${port + 1},"mounts":[{"path":"/api","service":"orders"}]""",
      port = port
    )
    val (code, out, err) = localRun(root, "billing", "--", "sh", "-c", "exit 3")
    assertEquals(code, 3, out + err)
    assert(!ups.exists(_.contains("billing")), ups)
  }

  // ── a project made outside the template (SC-008) ──────────────────────────

  /** Every path under `dir` with its bytes, as one digest: what a run must leave as it found. */
  private def digest(dir: Path): String =
    val sha = java.security.MessageDigest.getInstance("SHA-256")
    Files
      .walk(dir)
      .iterator()
      .asScala
      .filter(Files.isRegularFile(_))
      .map(f => dir.relativize(f).toString -> f)
      .toVector
      .sortBy(_._1)
      .foreach { (name, file) =>
        sha.update(name.getBytes(UTF_8))
        sha.update(Files.readAllBytes(file))
      }
    java.util.HexFormat.of().formatHex(sha.digest())

  test(
    "a project of twelve services made outside the template comes up once it gains its three files"
  ) {
    val root  = Files.createTempDirectory("outside")
    val names = Vector(
      "orders",
      "billing",
      "stock",
      "pricing",
      "search",
      "accounts",
      "shipping",
      "returns",
      "reviews",
      "loyalty",
      "notices",
      "shopfront"
    )
    val process = Set("stock", "pricing", "search", "reviews")
    val secret  = Set("billing", "accounts", "loyalty")
    for (name, i) <- names.zipWithIndex do
      val hosting =
        if process(name) then ""","hosting":"process","protocol":"1.0""""
        else if name == "shopfront" then
          ""","hosting":"web","mounts":[{"path":"/api","service":"orders"}]"""
        else ""
      val env =
        if secret(name) then """{"name":"API_KEY","secretKeyRef":{"name":"keys","key":"api"}}"""
        else ""
      LocalFixtures.write(
        root,
        s"services/$name/service.json",
        LocalFixtures.Service(name, 9000 + i, extra = hosting, env = env).descriptor
      )
      LocalFixtures.write(root, s"services/$name/src/Main.txt", s"$name's own code")
    val before = digest(root.resolve("services"))

    // Descriptors alone are not a project.
    val (refused, _, said) = up(root)
    assertEquals(refused, 1)
    assert(said.contains("holds no project"), said)
    assertEquals(ups, Vector.empty)

    // The three files the repository gains, and nothing else.
    LocalFixtures.write(root, "contracts/order.v1.json", """{"type":"object"}""")
    LocalFixtures.write(root, "contracts/stock.v1.json", """{"type":"object"}""")
    LocalFixtures.project(
      Vector.empty,
      crossings = Vector(
        """{"from":"orders","to":"billing","topic":"orders-placed","contract":"order.v1","schema":"contracts/order.v1.json"}""",
        """{"from":"stock","to":"pricing","topic":"stock-levels","contract":"stock.v1","schema":"contracts/stock.v1.json"}"""
      ),
      root = root,
      names = names
    ): Unit
    LocalFixtures.write(
      root,
      "topics.json",
      """{"topics":[
        |{"name":"orders-placed","partitions":3,"contract":"order.v1","schema":"contracts/order.v1.json"},
        |{"name":"stock-levels","partitions":1,"contract":"stock.v1","schema":"contracts/stock.v1.json"}]}""".stripMargin
    )
    LocalFixtures.write(
      root,
      "local.json",
      """{"values":{
        |"billing":{"API_KEY":"test"},"accounts":{"API_KEY":"test","ISSUER":"{{companion.identity.url}}/realms/shop"},
        |"loyalty":{"API_KEY":"test"},"orders":{"ISSUER":"{{companion.identity.url}}/realms/shop"}},
        |"companions":[{"name":"identity","image":"keycloak","ports":[{"port":8080}],
        |"ready":{"http":{"port":8080,"path":"/"}},"database":true,"neededBy":["orders","accounts"]}]}""".stripMargin
    )

    val (code, out, err) = up(root)
    assertEquals(code, 0, out + err)
    assert(ups.contains("up ankka-shop identity"), ups)
    val started = ups.last.split(' ').toSet
    for name <- names do assert(started(name), s"$name was not started: ${ups.last}")
    assertEquals(digest(root.resolve("services")), before, "a service's files were touched")
  }

  // ── status and logs (US6) ─────────────────────────────────────────────────

  test("status says of each service whether it is ready, where it answers and who started it") {
    val root = LocalFixtures.project()
    assertEquals(up(root)._1, 0)
    engine.change("ankka-shop", "billing")(_.copy(state = "exited"))
    val (code, out, err) = run("local", "status", "--dir", root.toString)
    assertEquals(code, 0, err)
    val lines = out.linesIterator.map(_.trim.replaceAll("\\s+", " ")).toVector
    assertEquals(
      lines,
      Vector(
        "orders ready http://127.0.0.1:9000 started by ankka local up",
        "billing not running"
      )
    )
  }

  test("a database's schema moves forward within a major, and to any development build") {
    import com.thinkmorestupidless.ankka.cli.local.Databases.movable
    assert(movable("0.16.2", "0.16.3"))
    assert(movable("0.15.0", "0.16.0"))
    assert(!movable("0.16.3", "0.16.2"))
    assert(movable("0.16.3", "0.16.3+44-412d4c1b-SNAPSHOT"))
    assert(movable("0.16.3+44-412d4c1b-SNAPSHOT", "0.16.3+51-5aba576c-SNAPSHOT"))
  }

  test("a state file that does not read is refused, not replaced, and nothing starts") {
    val root = LocalFixtures.project()
    assertEquals(up(root)._1, 0)
    val file = LocalState.file(root)
    val keys = LocalState.read(root).secretKeys
    Files.writeString(file, "{not json", UTF_8)
    val started        = ups.size
    val (code, _, err) = up(root)
    assertEquals(code, 1)
    assert(err.startsWith(".ankka/local/state.json is not readable: "), err)
    assert(err.contains("ankka local down --volumes removes it with what was recorded"), err)
    assertEquals(Files.readString(file, UTF_8), "{not json")
    assert(keys.nonEmpty)
    assertEquals(ups.size, started, calls)
  }

  test(
    "a port the command chose once, held by something else since, is refused before anything starts"
  ) {
    val root = LocalFixtures.project()
    assertEquals(up(root)._1, 0)
    val postgres = LocalState.read(root).postgresPort.get
    assertEquals(run("local", "down", "--dir", root.toString)._1, 0)
    LocalSeams.isFree = _ != postgres
    val started        = ups.size
    val (code, _, err) = up(root)
    assertEquals(code, 1)
    assertEquals(
      err.trim,
      s"\"ankka-postgres\" names port $postgres, which is in use on this machine"
    )
    assertEquals(ups.size, started, calls)
  }

  test("status asks a web-hosted service's port, as up does") {
    val root = hostedBilling(
      ""","hosting":"web","processPort":3000,"mounts":[{"path":"/api","service":"orders"}]"""
    )
    assertEquals(up(root)._1, 0)
    val (code, out, err) = run("local", "status", "--dir", root.toString)
    assertEquals(code, 0, err)
    val billing =
      out.linesIterator.map(_.trim.replaceAll("\\s+", " ")).find(_.startsWith("billing"))
    assertEquals(billing, Some("billing ready http://127.0.0.1:9001 started by ankka local up"))
  }

  test("logs --follow of a module service follows its runtime, not the run that gave the module") {
    val root = hostedBilling(""","hosting":"wasm","protocol":"1.0"""")
    assertEquals(up(root)._1, 0)
    val (code, _, err) = run("local", "logs", "billing", "--follow", "--dir", root.toString)
    assertEquals(code, 0, err)
    assertEquals(
      calls.filter(_.startsWith("logs")),
      Vector("logs ankka-shop billing-module", "logs ankka-shop billing --follow")
    )
  }

  test("status of a directory that holds no project is refused") {
    val (code, _, err) =
      run("local", "status", "--dir", Files.createTempDirectory("empty").toString)
    assertEquals(code, 1)
    assert(err.contains("holds no project"), err)
  }

  test("logs shows a service's log by its name, and refuses what is not the command's") {
    val root = LocalFixtures.project()
    assertEquals(
      run("local", "logs", "orders", "--dir", root.toString)._3.trim,
      "\"orders\" was not started by ankka local up"
    )
    assertEquals(up(root)._1, 0)
    val (code, out, err) = run("local", "logs", "orders", "--tail", "5", "--dir", root.toString)
    assertEquals(code, 0, err)
    assertEquals(out.trim, "orders logged")
    assert(calls.contains("logs ankka-shop orders"), calls)
    val (unknown, _, said) = run("local", "logs", "payments", "--dir", root.toString)
    assertEquals(unknown, 1)
    assertEquals(said.trim, "\"payments\" is not a service or companion of shop")
  }
