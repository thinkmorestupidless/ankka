package com.thinkmorestupidless.ankka.cli

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.ServiceDescriptor
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.core.ContextMap

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A project of several services, end to end (feature 062, quickstart §3): `ankka init --project`
 * renders the project template into a temporary directory through the real command, and the
 * project's own build, tests, image build, tags, map check, scripts and workflows are run or read
 * there, in order. Each step that proves something also breaks it once and watches it fail.
 *
 * `build.sbt` publishes ankka locally first when `-Dankka.template.tests` names `project` (or is
 * unset). It needs sbt and Docker; named, it fails rather than skips without them.
 */
class ProjectTemplateSuite extends munit.FunSuite:

  override val munitTimeout: FiniteDuration = 15.minutes

  private def missingTools: Seq[String] = Seq("sbt", "docker").filterNot(TemplateSwitch.onPath)

  override def munitIgnore: Boolean = TemplateSwitch.skip("project", missingTools)

  private var workspace: Path = null
  private def project: Path   = workspace.resolve("shop")

  override def beforeAll(): Unit =
    if !munitIgnore then
      TemplateSwitch.requireTools("project", missingTools)
      System.setProperty("ankka.init.specify", "off")
      workspace = Files.createTempDirectory("ankka-project")

  private def cli(args: String*): (Int, String, String) =
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    val code = Main.run(args, PrintStream(out, true, UTF_8), PrintStream(err, true, UTF_8))
    (code, out.toString(UTF_8), err.toString(UTF_8))

  /** Runs a program in the project, its output kept and returned with its exit code. */
  private def run(command: String*): (Int, String) =
    val log     = Files.createTempFile("project-run", ".log")
    val process = new ProcessBuilder(command*)
      .directory(project.toFile)
      .redirectErrorStream(true)
      .redirectOutput(log.toFile)
      .start()
    val code = process.waitFor()
    // CI's sbt colours its log, so a line that looks like `1.4.2` ends in an escape sequence.
    (code, Files.readString(log).replaceAll("\u001b\\[[0-9;]*[A-Za-z]", ""))

  private def sbt(args: String*): (Int, String) =
    run(("sbt" +: "-batch" +: "-Dsbt.color=false" +: "-Dsbt.log.noformat=true" +: args)*)

  private def read(path: String): String = Files.readString(project.resolve(path))

  /** Edits `path` for the length of `body`, and puts it back whatever happens. */
  private def broken[A](path: String, from: String, to: String)(body: => A): A =
    val file     = project.resolve(path)
    val original = Files.readString(file)
    assert(original.contains(from), s"$path does not hold $from")
    Files.writeString(file, original.replace(from, to))
    try body
    finally Files.writeString(file, original): Unit

  private val ordersCode  = "services/orders/src/main/scala/com/example/shop/orders"
  private val billingCode = "services/billing/src/main/scala/com/example/shop/billing"

  test(
    "1. a project holds its services, a shared module and their context map, each named for itself"
  ) {
    val (code, out, err) =
      cli("init", "shop", "--project", "--services", "orders,billing", "--dir", workspace.toString)
    assertEquals(code, 0, err)
    assert(out.contains("ankka context-map check"), out)
    for path <- Vector(
        "build.sbt",
        "project/Services.scala",
        "shared/src/main/scala/com/example/shop/contracts/OrdersV1.scala",
        "contracts/orders.v1.json",
        "topics.json",
        "tests/src/test/scala/com/example/shop/ItemAddedIsRecordedSuite.scala",
        "tests/src/test/scala/com/example/shop/ContextMapSuite.scala",
        s"$ordersCode/OrdersService.scala",
        s"$billingCode/BillingService.scala",
        ".github/workflows/ci.yml",
        ".github/workflows/service-checks.yml",
        ".github/workflows/deploy.yml",
        ".specify/extensions.yml",
        ".claude/skills/speckit-design/SKILL.md",
        "GLOSSARY.md",
        "features/README.md",
        "specs/.gitkeep"
      )
    do assert(Files.isRegularFile(project.resolve(path)), path)
    for (service, port) <- Vector("orders" -> 9000, "billing" -> 9001) do
      val descriptor = readFromString[ServiceDescriptor](read(s"services/$service/service.json"))
      assertEquals(descriptor.problems, Vector.empty)
      assertEquals(descriptor.name, service)
      assertEquals(descriptor.service.image, s"$service:latest")
      assertEquals(descriptor.service.port, port)
      assert(
        read(s"services/$service/src/main/resources/application.conf")
          .contains(s"""ankka.service.name = "$service"""")
      )
    val map = ContextMap.read(project).fold(p => fail(p.mkString("; ")), identity)
    assertEquals(map.project, "shop")
    assertEquals(map.services.map(_.name), Vector("orders", "billing"))
    assertEquals(map.crossings.flatMap(_.topic), Vector("orders-events"))
    assertEquals(map.crossings.flatMap(_.route), Vector("GET /billing/{id}"))
    assertEquals(read("CONTEXT-MAP.md"), ContextMap.render(map))
  }

  test("2. the tests of every service, and the tests that cross them, pass unchanged") {
    val (code, out) = sbt("test")
    assertEquals(code, 0, out.takeRight(4000))
    for suite <- Vector(
        "com.example.shop.ItemAddedIsRecordedSuite",
        "com.example.shop.ContextMapSuite",
        "com.example.shop.orders.ItemEntitySuite",
        "com.example.shop.billing.ItemIntegrationSuite"
      )
    do assert(out.contains(suite), s"$suite did not run")
  }

  test("3. billing recording the wrong thing fails the test that crosses services") {
    broken(s"$billingCode/application/OrdersReader.scala", "item.count))", "item.count + 1))") {
      val (code, out) = sbt("tests/testOnly *ItemAddedIsRecordedSuite")
      assertNotEquals(code, 0, "the crossing test passed with billing broken")
      assert(out.contains("ItemAddedIsRecordedSuite"), out.takeRight(2000))
    }
  }

  test("3a. a publication the context map does not record fails the map's test") {
    broken(
      s"$ordersCode/application/OrdersPublisher.scala",
      "\"orders-events\"",
      "\"orders-audit\""
    ) {
      val (code, out) = sbt("tests/testOnly *ContextMapSuite")
      assertNotEquals(code, 0, "the map's test passed with an unrecorded publication")
      assert(out.contains("orders-audit"), out.takeRight(3000))
    }
  }

  test("4. one service's image is built alone") {
    def billingImage = run("docker", "images", "-q", "billing")._2.trim
    val before       = billingImage
    val (code, out)  = sbt("orders/Docker/publishLocal")
    assertEquals(code, 0, out.takeRight(3000))
    assertEquals(run("docker", "image", "inspect", "orders:latest")._1, 0)
    assertEquals(billingImage, before, "building orders built a billing image")
  }

  test("5. each service's version follows its own tags") {
    def git(args: String*) =
      val (code, out) =
        run(("git" +: "-c" +: "user.email=t@example.com" +: "-c" +: "user.name=t" +: args)*)
      assertEquals(code, 0, out)
    git("init", "-q")
    git("add", "-A")
    git("commit", "-qm", "feat: start")
    git("tag", "orders/v1.4.2")
    val (atTag, out) = sbt("show orders/version")
    assertEquals(atTag, 0, out.takeRight(2000))
    assert(out.linesIterator.exists(_.trim.endsWith("1.4.2")), out.takeRight(1000))
    Files.writeString(project.resolve("NOTES.md"), "a commit past the tag\n")
    git("add", "-A")
    git("commit", "-qm", "docs: notes")
    git("tag", "billing/v3.1.0")
    val (_, past) = sbt("show orders/version", "show billing/version")
    val versions  =
      past.linesIterator.map(_.replace("[info]", "").trim).filter(_.matches("\\d.*")).toVector
    assert(versions.headOption.exists(_.startsWith("1.4.2+")), versions.toString)
    assertEquals(versions.lift(1), Some("3.1.0"))
  }

  test("6. the context map check passes, and fails naming each of four breaks") {
    def check() = cli("context-map", "check", "--dir", project.toString)
    assertEquals(check()._1, 0, check()._3)

    val billing = project.resolve("services/billing/service.json")
    val kept    = Files.readString(billing)
    Files.delete(billing)
    try
      assert(
        check()._3.contains(
          "the context map names \"billing\", and services/billing/service.json does not exist"
        )
      )
    finally Files.writeString(billing, kept): Unit

    broken(
      "context-map.json",
      "\"services\": [",
      "\"services\": [{\"name\": \"shipping\", \"boundary\": {\"criterion\": \"ownership\"}},"
    ) {
      assert(check()._3.contains("the context map names \"shipping\""), check()._3)
    }

    val schema = project.resolve("contracts/orders.v1.json")
    Files.move(schema, project.resolve("contracts/orders.json"))
    try
      assert(
        check()._3.contains("names the schema contracts/orders.v1.json, which does not exist"),
        check()._3
      )
    finally Files.move(project.resolve("contracts/orders.json"), schema): Unit

    broken("CONTEXT-MAP.md", "## Crossings", "## What crosses") {
      assert(check()._3.contains("CONTEXT-MAP.md is out of step with context-map.json"), check()._3)
    }
    assertEquals(check()._1, 0, check()._3)
  }

  test("7. a python service added inside the project touches only its directory and the map") {
    val (code, _, err) = cli("init", "stock", "--language", "python", "--dir", project.toString)
    assertEquals(code, 0, err)
    val (_, status) = run("git", "status", "--porcelain", "--untracked-files=all")
    val touched     = status.linesIterator.map(_.drop(3)).toVector
    assert(touched.nonEmpty, status)
    assertEquals(
      touched.filterNot(p =>
        p.startsWith("services/stock/") || p == "context-map.json" || p == "CONTEXT-MAP.md"
      ),
      Vector.empty
    )
    assert(read("services/stock/service.json").contains("\"port\": 9002"))
    assertEquals(
      ContextMap.read(project).toOption.get.services.map(_.name),
      Vector("orders", "billing", "stock")
    )
    assertEquals(cli("context-map", "check", "--dir", project.toString)._1, 0)
    run("git", "add", "-A")
    val (committed, out) =
      run(
        "git",
        "-c",
        "user.email=t@example.com",
        "-c",
        "user.name=t",
        "commit",
        "-qm",
        "feat: stock"
      )
    assertEquals(committed, 0, out)
  }

  test("8. a change maps to the services it touched and to whether the crossing tests run") {
    def changed(path: String): String =
      val (_, base) = run("git", "rev-parse", "HEAD")
      val file      = project.resolve(path)
      Files.createDirectories(file.getParent)
      Files.writeString(
        file,
        "x\n",
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND
      )
      run("git", "add", "-A")
      run(
        "git",
        "-c",
        "user.email=t@example.com",
        "-c",
        "user.name=t",
        "commit",
        "-qm",
        s"chore: $path"
      )
      run("scripts/changed-services.sh", base.trim, "HEAD")._2.trim
    assertEquals(
      changed("services/orders/x"),
      """{"services":[{"service":"orders","language":"scala"}],"tests":true}"""
    )
    assertEquals(
      changed("shared/x"),
      """{"services":[{"service":"billing","language":"scala"},{"service":"orders","language":"scala"}],"tests":true}"""
    )
    assertEquals(
      changed("services/stock/x"),
      """{"services":[{"service":"stock","language":"python"}],"tests":false}"""
    )
    assertEquals(changed("README.md"), """{"services":[],"tests":false}""")
  }

  test("9. each service's language is told from its build files") {
    for (service, language) <- Vector(
        "orders"  -> "scala",
        "billing" -> "scala",
        "stock"   -> "python"
      )
    do assertEquals(run("scripts/service-language.sh", s"services/$service")._2.trim, language)
    val (code, out) = run("scripts/service-language.sh", "specs")
    assertEquals(code, 1)
    assert(out.contains("cannot tell the language of specs"), out)
  }

  test(
    "10. adding a service in a directory that holds no project is refused, and nothing is written"
  ) {
    val empty          = Files.createTempDirectory("no-project")
    val (code, _, err) = cli("init", "stock", "--add", "--dir", empty.toString)
    assertEquals(code, 1)
    assert(
      err.contains(
        "holds no project started from the project template (no context-map.json); nothing was written"
      ),
      err
    )
    assertEquals(Files.list(empty).count(), 0L)
  }

  test("11. the workflows release one service by its tag and read secrets only to deploy") {
    val ci     = read(".github/workflows/ci.yml")
    val checks = read(".github/workflows/service-checks.yml")
    val deploy = read(".github/workflows/deploy.yml")
    for (name, text) <- Vector("ci" -> ci, "service-checks" -> checks, "deploy" -> deploy) do
      assertEquals("\\$\\{\\{".r.findAllIn(text).size, "\\}\\}".r.findAllIn(text).size, name)
      assert(!text.contains("{{project}}") && !text.contains("{{first}}"), name)
    assert(deploy.contains("tags: [\"*/v*\"]"), deploy)
    assert(deploy.contains("${GITHUB_REF_NAME%%/v*}") || deploy.contains("${TAG%%/v*}"), deploy)
    assert(deploy.contains("secrets.ANKKA_TOKEN"))
    assert(deploy.contains("thinkmorestupidless/ankka-action@"))
    assert(
      deploy.contains(
        "ankka services deploy \"$SERVICE\" \"$IMAGE\" -f \"services/$SERVICE/service.json\""
      )
    )
    assert(deploy.contains("::error::shop has no service $service"), deploy)
    assert(
      deploy.indexOf("uses: ./.github/workflows/service-checks.yml") < deploy.indexOf("\n  deploy:")
    )
    for language <- Vector("scala", "python", "typescript", "web", "go", "rust", "java", "kotlin")
    do assert(deploy.contains(language) && checks.contains(s"'$language'"), language)
    assert(!ci.contains("secrets."), "ci.yml names a secret")
    assert(
      checks.contains("workflow_call:") && checks.contains("service:") && checks.contains(
        "language:"
      )
    )
  }

  // ── the project on one machine (feature 066) ─────────────────────────────────────────────────

  private val http = java.net.http.HttpClient.newHttpClient()

  private def request(method: String, port: Int, path: String, body: String = ""): (Int, String) =
    val publisher =
      if body.isEmpty then java.net.http.HttpRequest.BodyPublishers.noBody()
      else java.net.http.HttpRequest.BodyPublishers.ofString(body)
    try
      val answer = http.send(
        java.net.http.HttpRequest
          .newBuilder(java.net.URI.create(s"http://127.0.0.1:$port$path"))
          .header("Content-Type", "application/json")
          .method(method, publisher)
          .build(),
        java.net.http.HttpResponse.BodyHandlers.ofString()
      )
      (answer.statusCode, answer.body)
    catch case _: java.io.IOException => (0, "")

  /** `probe` until it holds, for at most `within`; its last answer otherwise. */
  private def eventually(within: FiniteDuration)(probe: => (Int, String))(
      holds: ((Int, String)) => Boolean
  ): (Int, String) =
    val deadline = within.fromNow
    var last     = probe
    while !holds(last) && deadline.hasTimeLeft() do
      Thread.sleep(1000)
      last = probe
    last

  private def local(args: String*): (Int, String, String) =
    cli((Vector("local") ++ args ++ Vector("--dir", project.toString))*)

  private def localOk(args: String*): String =
    val (code, out, err) = local(args*)
    assertEquals(code, 0, out + err)
    out

  private var ports: Map[String, Int] = Map.empty

  private def compose(words: String*): (Int, String) =
    run(("docker" +: "compose" +: "-p" +: "ankka-shop" +: words)*)

  private def containerId(service: String): String =
    compose("ps", "-q", service)._2.trim

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

  private def addItem(id: String): Unit =
    val (code, body) =
      request("POST", ports("orders"), s"/orders/$id", """{"name":"pen","count":2}""")
    assert(code < 300, s"$code $body")

  /**
   * orders' answer to the request that crosses to billing, once billing has recorded `id`: an
   * entity nothing was recorded for answers 200 too, empty, so the wait is for the item itself.
   */
  private def crossing(id: String): (Int, String) =
    eventually(90.seconds)(request("GET", ports("orders"), s"/orders/$id/billing"))(r =>
      r._1 == 200 && r._2.contains("\"pen\"")
    )

  /** `ankka local run <service>` as a process of its own, stopped after `body`. */
  private def fromSource[A](service: String)(body: => A): A =
    val launcher = Path.of(sys.props("java.home"), "bin", "java").toString
    val process  = new ProcessBuilder(
      launcher,
      "-cp",
      sys.props("java.class.path"),
      s"-Dankka.running.dir=${sys.props("ankka.running.dir")}",
      s"-Dankka.cache.dir=${sys.props("ankka.cache.dir")}",
      "com.thinkmorestupidless.ankka.cli.Main",
      "local",
      "run",
      service,
      "--dir",
      project.toString
    ).directory(project.toFile)
      .redirectErrorStream(true)
      .redirectOutput(workspace.resolve(s"run-$service.log").toFile)
      .start()
    try body
    finally
      process.destroy()
      process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS): Unit

  test(
    "12. the project holds topics.json and no compose file or run script, and both images build"
  ) {
    for gone <- Vector("docker-compose.yml", "scripts/run.sh", "scripts/databases.sh") do
      assert(!Files.exists(project.resolve(gone)), gone)
    assert(read("topics.json").contains("\"orders-events\""), read("topics.json"))
    // The python service step 7 added has no image; the project on one machine is the template's two.
    Files
      .walk(project.resolve("services/stock"))
      .iterator()
      .asScala
      .toVector
      .reverse
      .foreach(p => Files.delete(p))
    val map = ContextMap.read(project).fold(p => fail(p.mkString("; ")), identity)
    val two = map.copy(services = map.services.filterNot(_.name == "stock"))
    Files.writeString(project.resolve(ContextMap.FileName), ContextMap.write(two))
    Files.writeString(project.resolve(ContextMap.RenderingName), ContextMap.render(two))
    // Ports no developer's own services use, so the suite runs beside them.
    ports = Vector("orders" -> 9000, "billing" -> 9001).map { (service, port) =>
      val free = Iterator
        .continually(com.thinkmorestupidless.ankka.cli.local.LocalState.freePort())
        .find(_ > 20000)
        .get
      val file = project.resolve(s"services/$service/service.json")
      Files.writeString(
        file,
        Files.readString(file).replace(s"\"port\": $port", s"\"port\": $free")
      )
      service -> free
    }.toMap
    sys.props("ankka.running.dir") = workspace.resolve("running").toString
    sys.props("ankka.cache.dir") = workspace.resolve("cache").toString
    assertEquals(cli("context-map", "check", "--dir", project.toString)._1, 0)
    val (code, out) = sbt("Docker/publishLocal")
    assertEquals(code, 0, out.takeRight(3000))
  }

  test("13. ankka local up alone answers a request that crosses from orders to billing") {
    val out = localOk("up")
    assert(out.contains("shop is up"), out)
    addItem("a")
    val (status, body) = crossing("a")
    assertEquals(status, 200, body)
    assert(body.contains("\"pen\""), body)
    // The trace of that request holds the call to billing.
    val served = com.thinkmorestupidless.ankka.cli.console.ConsoleServer.start(
      com.thinkmorestupidless.ankka.cli.console.LocalSource(workspace.resolve("running")),
      0,
      PrintStream(ByteArrayOutputStream())
    )
    try
      val traces = eventually(30.seconds)(request("GET", served.port, "/api/traces/orders"))(
        _._2.contains("billing")
      )
      assert(traces._2.contains("billing"), traces._2.take(2000))
    finally served.stop()
  }

  test("14. the two services' databases are different, and each holds a journal") {
    val databases = psql("postgres", "select datname from pg_database").linesIterator.toSet
    assert(databases("orders") && databases("billing"), databases)
    for database <- Vector("orders", "billing") do
      assertEquals(
        psql(
          database,
          "select count(*) from information_schema.tables where table_name = 'event_journal'"
        ),
        "1",
        database
      )
    assert(psql("orders", "select count(*) from event_journal").toInt > 0)
  }

  test("15. an item survives down and up, and is gone after down --volumes and up") {
    // An entity nothing was recorded for answers 200 too, empty: what is compared is the item.
    localOk("down")
    localOk("up")
    val kept = request("GET", ports("orders"), "/orders/a")
    assertEquals(kept._1, 200, kept._2)
    assert(kept._2.contains("\"pen\""), kept._2)
    localOk("down", "--volumes")
    localOk("up")
    val gone = request("GET", ports("orders"), "/orders/a")
    assertEquals(gone._1, 200, gone._2)
    assert(!gone._2.contains("\"pen\""), gone._2)
  }

  test("16. one service run from its source answers among the others, theirs unchanged") {
    for (source, other) <- Vector("billing" -> "orders", "orders" -> "billing") do
      localOk("up", "--without", source)
      val before = containerId(other)
      assert(before.nonEmpty, s"$other is not running")
      fromSource(source) {
        val id = s"from-$source"
        eventually(5.minutes)(request("GET", ports(source), s"/$source/"))(_._1 == 200): Unit
        addItem(id)
        assertEquals(crossing(id)._1, 200, s"$source run from its source")
        val status = localOk("status")
        assert(
          status.linesIterator.exists(l => l.contains(source) && l.contains("run from its source")),
          status
        )
        assert(
          status.linesIterator.exists(l =>
            l.contains(other) && l.contains("started by ankka local up")
          ),
          status
        )
        // The console lists the one run from its source as it lists the containers.
        val served = com.thinkmorestupidless.ankka.cli.console.ConsoleServer.start(
          com.thinkmorestupidless.ankka.cli.console.LocalSource(workspace.resolve("running")),
          0,
          PrintStream(ByteArrayOutputStream())
        )
        try
          for service <- Vector("orders", "billing") do
            val (code, body) = request("GET", served.port, s"/api/service/$service")
            assertEquals(code, 200, s"$service: $body")
            assert(body.contains("ItemEntity") || body.contains("item"), s"$service: $body")
            assert(body.contains(s"/$service/{id}"), s"$service: $body")
        finally served.stop()
      }
      assertEquals(containerId(other), before, s"$other was restarted")
      localOk("up")
  }

  test("17. without its context map or its topics the project is refused, and comes up with both") {
    localOk("down")
    def without(file: String)(check: String => Unit): Unit =
      val path  = project.resolve(file)
      val moved = workspace.resolve(file + ".aside")
      Files.move(path, moved)
      try
        val (code, _, err) = local("up")
        assertEquals(code, 1, err)
        check(err)
      finally Files.move(moved, path): Unit
    without("context-map.json")(err => assert(err.contains("holds no project"), err))
    without("topics.json")(err =>
      assert(err.contains("has topic crossings and no topics.json"), err)
    )
    assert(localOk("up").contains("shop is up"))
  }

  override def afterAll(): Unit =
    if workspace != null && sys.props.contains("ankka.running.dir") then
      local("down", "--volumes"): Unit
      sys.props.remove("ankka.running.dir")
      sys.props.remove("ankka.cache.dir"): Unit
    if workspace != null then
      Files
        .walk(workspace)
        .iterator()
        .asScala
        .toVector
        .reverse
        .foreach(p => Files.deleteIfExists(p): Unit)
