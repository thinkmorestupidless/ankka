package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.local.{Engine, LocalSeams}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

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

  private var engine: ScriptedEngine = null
  private var databases: Databases   = null
  private var cache: Path            = null

  override def beforeEach(context: BeforeEach): Unit =
    engine = ScriptedEngine()
    databases = Databases()
    engine.execAnswer = databases.answer
    cache = Files.createTempDirectory("cache")
    for version <- Vector("0.15.0", "0.17.5", "1.0.0") do
      Files.createDirectories(cache.resolve(s"ddl/$version"))
      Files.writeString(
        cache.resolve(s"ddl/$version/10-journal.sql"),
        s"-- the schema of $version",
        UTF_8
      )
    sys.props("ankka.cache.dir") = cache.toString
    LocalSeams.engine = () => engine
    LocalSeams.probe = _ => true
    LocalSeams.isFree = _ => true
    LocalSeams.cliVersion = () => "0.17.0"

  override def afterEach(context: AfterEach): Unit =
    LocalSeams.reset()
    sys.props.remove("ankka.cache.dir"): Unit

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
