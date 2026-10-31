package com.thinkmorestupidless.ankka.cli

import scala.jdk.CollectionConverters.*
import java.nio.file.Files

/** What `ankka init` decides before it starts a process. */
class InitSuite extends munit.FunSuite:

  // A project started here must never run a developer's own `specify` in a temporary directory.
  override def beforeAll(): Unit = System.setProperty("ankka.init.specify", "off"): Unit

  test("the sbt invocation carries the template, the name and this CLI's version") {
    val cmd = Init.command(Init.Request("orders", Some("file:///t/ankka.g8")), version = "0.2.0")
    assertEquals(
      cmd,
      Vector(
        "sbt",
        "--allow-empty",
        "-batch",
        "new",
        "file:///t/ankka.g8",
        "--name=orders",
        "--ankka_version=0.2.0"
      )
    )
  }

  test("`new` is an argument of its own, never inside a command string") {
    // sbt's launcher runs `new` outside any build only when it sees it on its own; inside one string,
    // the sbt 2 launcher sends it through its thin client, whose own commands reach giter8.
    val cmd = Init.command(Init.Request("orders", pkg = Some("acme.orders")), version = "1.0.0")
    assert(cmd.contains("new"), cmd.toString)
    assert(cmd.forall(arg => !arg.contains(' ')), s"an argument holds a space: $cmd")
  }

  test("a package is passed through when given, and only then") {
    assertEquals(
      Init.newArguments(Init.Request("orders", pkg = Some("acme.orders")), "1.0.0").last,
      "--package=acme.orders"
    )
    assert(!Init.newArguments(Init.Request("orders"), "1.0.0").exists(_.startsWith("--package")))
  }

  test("the default template is the published one") {
    assertEquals(
      Init.newArguments(Init.Request("orders"), "1.0.0")(1),
      "thinkmorestupidless/ankka.g8"
    )
  }

  test("an invalid name is refused with the service-name rule, before anything runs") {
    val problems =
      Init.problems(Init.Request("My Orders", directory = Files.createTempDirectory("init")))
    assert(problems.exists(_.contains("service name 'My Orders' is invalid")), problems)
  }

  test("a non-empty target directory is refused, naming it") {
    val dir = Files.createTempDirectory("init")
    Files.createDirectories(dir.resolve("orders"))
    Files.writeString(dir.resolve("orders").resolve("x"), "occupied")
    val problems = Init.problems(Init.Request("orders", directory = dir))
    assert(problems.exists(_.contains("already exists and is not empty")), problems)
    assertEquals(Init.problems(Init.Request("fresh", directory = dir)), Vector.empty)
  }

  test("sbt on PATH is a real check, not an assumption") {
    assert(!Init.sbtOnPath(Map("PATH" -> Files.createTempDirectory("nopath").toString)))
  }

  test("a language is scala, java, kotlin, python, typescript, rust, go or web, and nothing else") {
    assertEquals(Language.parse("Python"), Right(Language.Python))
    assertEquals(Language.parse("ts"), Right(Language.TypeScript))
    assertEquals(Language.parse("scala"), Right(Language.Scala))
    assertEquals(Language.parse("Rust"), Right(Language.Rust))
    assertEquals(Language.parse("rs"), Right(Language.Rust))
    assertEquals(Language.parse("Go"), Right(Language.Go))
    assertEquals(Language.parse("golang"), Right(Language.Go))
    assertEquals(Language.parse("web"), Right(Language.Web))
    assertEquals(Language.parse("Java"), Right(Language.Java))
    assertEquals(Language.parse("kotlin"), Right(Language.Kotlin))
    assertEquals(Language.parse("kt"), Right(Language.Kotlin))
    assert(
      Language
        .parse("cobol")
        .left
        .exists(_.contains("one of scala, java, kotlin, python, typescript, rust, go, web"))
    )
  }

  test(
    "a java or kotlin package defaults from the name and is refused when javac could not use it"
  ) {
    val dir = Files.createTempDirectory("init")
    def request(language: Language, pkg: Option[String] = None, name: String = "order-service") =
      Init.Request(name, pkg = pkg, directory = dir, language = language)
    for language <- Seq(Language.Java, Language.Kotlin) do
      assertEquals(Scaffold.jvmPackage(request(language)), "com.example.orderservice")
      assertEquals(Init.problems(request(language)), Vector.empty)
      assertEquals(Init.problems(request(language, Some("org.acme.shop"))), Vector.empty)
      assert(
        Init.problems(request(language, Some("org.1acme"))).exists(_.contains("is invalid")),
        language
      )
      assert(
        Init.problems(request(language, Some("org..acme"))).exists(_.contains("is invalid")),
        language
      )
      assert(
        Init.problems(request(language, Some("org.class.x"))).exists(_.contains("'class'")),
        language
      )
      assert(
        Init
          .problems(request(language).copy(template = Some("x/y.g8")))
          .exists(_.contains("--template")),
        language
      )
    // `fun` is Kotlin's keyword and not Java's.
    assertEquals(Init.problems(request(Language.Java, Some("acme.fun"))), Vector.empty)
    assert(Init.problems(request(Language.Kotlin, Some("acme.fun"))).exists(_.contains("'fun'")))
  }

  test("a java project lands in its package, with an executable wrapper and no token left") {
    val dir     = Files.createTempDirectory("init-java")
    val request =
      Init.Request("shop", pkg = Some("org.acme.shop"), directory = dir, language = Language.Java)
    assertEquals(Init.problems(request), Vector.empty)
    val target = Scaffold.render(request, "9.9.9")
    val main   = target.resolve("src/main/java/org/acme/shop/Main.java")
    assert(Files.exists(main), main.toString)
    assert(Files.readString(main).startsWith("package org.acme.shop;"))
    assert(Files.readString(target.resolve("pom.xml")).contains("<groupId>org.acme.shop</groupId>"))
    assert(Files.isExecutable(target.resolve("mvnw")), "mvnw is not executable")
    assert(!Files.isExecutable(target.resolve("pom.xml")), "pom.xml is executable")
    assert(Scaffold.entries(Language.Java).contains(Scaffold.Entry("mvnw", Scaffold.Mode.Exec)))
    val leftovers = Files
      .walk(target)
      .iterator()
      .asScala
      .filter(Files.isRegularFile(_))
      .filter { f =>
        val token = """\{\{[a-z_]+\}\}""".r
        token.findFirstIn(f.toString).nonEmpty || token.findFirstIn(Files.readString(f)).nonEmpty
      }
      .toVector
    assertEquals(leftovers, Vector.empty)
    // An embedded service: a descriptor with no hosting and no protocol, and no sidecar to start.
    val descriptor = com.github.plokhotnyuk.jsoniter_scala.core.readFromString(
      Files.readString(target.resolve("service.json"))
    )(using com.thinkmorestupidless.ankka.controlplane.api.Wire.descriptorCodec)
    assertEquals(descriptor.problems, Vector.empty)
    assertEquals(descriptor.service.runtime, Some("9.9.9"))
    assert(!descriptor.service.isProcessHosted)
    assert(!Files.readString(target.resolve("docker-compose.yml")).contains("sidecar"))
  }

  test("a web project's descriptor is valid, web-hosted, and mounts its backend") {
    val dir     = Files.createTempDirectory("init-web")
    val request = Init.Request("shop-web", directory = dir, language = Language.Web)
    assertEquals(Init.problems(request), Vector.empty)
    val target     = Scaffold.render(request, "9.9.9")
    val descriptor = com.github.plokhotnyuk.jsoniter_scala.core.readFromString(
      Files.readString(target.resolve("service.json"))
    )(using com.thinkmorestupidless.ankka.controlplane.api.Wire.descriptorCodec)
    assertEquals(descriptor.problems, Vector.empty)
    assertEquals(descriptor.name, "shop-web")
    assert(descriptor.service.isWebHosted)
    assertEquals(
      descriptor.service.mounts.map(m => m.path -> m.service),
      Vector("/api" -> "backend")
    )
    // Every template's files, and none of a service's: a web-hosted service starts no sidecar.
    assert(Files.exists(target.resolve(".mcp.json")))
    assert(!Files.exists(target.resolve("docker-compose.yml")))
    assert(Files.exists(target.resolve(".claude/skills/ankka/SKILL.md")))
  }

  test("--package and --template are refused for a web project") {
    val dir      = Files.createTempDirectory("init-web-options")
    val problems = Init.problems(
      Init.Request(
        "w",
        template = Some("x/y.g8"),
        pkg = Some("a.b"),
        directory = dir,
        language = Language.Web
      )
    )
    assert(problems.exists(_.contains("--template applies to scala only")), problems)
    assert(
      problems.exists(_.contains("--package applies to scala, java, kotlin and python only")),
      problems
    )
  }

  test("a rust crate keeps the name's hyphens, and is refused when cargo could not name it") {
    val dir                   = Files.createTempDirectory("init")
    def request(name: String) = Init.Request(name, directory = dir, language = Language.Rust)
    assertEquals(Scaffold.module(request("order-service")), "order-service")
    assertEquals(Init.problems(request("order-service")), Vector.empty)
    assertEquals(Scaffold.crateProblems("1cart").size, 1)
    assert(Init.problems(request("cart").copy(pkg = Some("x"))).exists(_.contains("--package")))
  }

  test("a python package is derived from the name, and refused when Python could not import it") {
    val dir                                               = Files.createTempDirectory("init")
    def request(name: String, pkg: Option[String] = None) =
      Init.Request(name, pkg = pkg, directory = dir, language = Language.Python)
    assertEquals(Scaffold.module(request("order-service")), "order_service")
    assertEquals(Init.problems(request("order-service")), Vector.empty)
    assert(Init.problems(request("import")).exists(_.contains("is a Python keyword")))
    assertEquals(Init.problems(request("import", Some("imports"))), Vector.empty)
    assert(Init.problems(request("orders", Some("Orders.App"))).exists(_.contains("is invalid")))
  }

  test("options that belong to another language are refused rather than ignored") {
    val dir    = Files.createTempDirectory("init")
    val python =
      Init.Request("orders", template = Some("x/y.g8"), directory = dir, language = Language.Python)
    assert(Init.problems(python).exists(_.contains("--template applies to scala only")))
    val ts =
      Init.Request("orders", pkg = Some("acme"), directory = dir, language = Language.TypeScript)
    assert(
      Init
        .problems(ts)
        .exists(_.contains("--package applies to scala, java, kotlin and python only"))
    )
  }

  test("every template of a service ignores the directory store a laptop keeps objects in") {
    for language <- Seq(Language.Python, Language.TypeScript, Language.Rust, Language.Go) do
      val request =
        Init.Request("probe", directory = Files.createTempDirectory("init"), language = language)
      val ignored = Files.readAllLines(Scaffold.render(request, "9.9.9").resolve(".gitignore"))
      assert(ignored.contains(".ankka/"), s"$language: $ignored")
    // The Scala template is ankka.g8, rendered by sbt new rather than by the CLI.
    val g8 = Files.readAllLines(java.nio.file.Paths.get("../ankka.g8/src/main/g8/.gitignore"))
    assert(g8.contains(".ankka/"), g8.toString)
  }

  test("each template renders every token, keeps GitHub's expressions, and carries its dotfiles") {
    for language <- Seq(Language.Python, Language.TypeScript, Language.Go, Language.Web) do
      val dir     = Files.createTempDirectory("init")
      val request = Init.Request("probe", directory = dir, language = language)
      val target  = Scaffold.render(request, "9.9.9")
      val listed  = Scaffold.files(language)
      assert(listed.contains(".gitignore"), listed.toString)
      assert(listed.contains(".github/workflows/deploy.yml"), listed.toString)
      assert(listed.contains(".github/pull_request_template.md"), listed.toString)
      assert(listed.contains(".githooks/commit-msg"), listed.toString)
      assert(listed.exists(_.startsWith(".claude/skills/")), listed.toString)
      // The hook is a shell script rendered out of a jar: it must come out executable, or git skips it.
      assert(
        Files.isExecutable(target.resolve(".githooks/commit-msg")),
        "commit-msg not executable"
      )
      val deploy = Files.readString(target.resolve(".github/workflows/deploy.yml"))
      assert(deploy.contains("${{ secrets.ANKKA_TOKEN }}"), deploy)
      assert(!deploy.contains("{{name}}"), deploy)
      val descriptor = Files.readString(target.resolve("service.json"))
      assert(descriptor.contains("\"name\": \"probe\""), descriptor)
      val hosting = if language == Language.Web then "web" else "process"
      assert(descriptor.contains(s"\"hosting\": \"$hosting\""), descriptor)
  }

  // ── Projects of several services (feature 062) ────────────────────────────────────────────────

  private def cli(args: String*): (Int, String, String) =
    val out  = java.io.ByteArrayOutputStream()
    val err  = java.io.ByteArrayOutputStream()
    val utf8 = java.nio.charset.StandardCharsets.UTF_8
    val code =
      Main.run(args, java.io.PrintStream(out, true, utf8), java.io.PrintStream(err, true, utf8))
    (code, out.toString(utf8), err.toString(utf8))

  private def started(services: String = "orders,billing"): java.nio.file.Path =
    val dir            = Files.createTempDirectory("project-init")
    val (code, _, err) =
      cli("init", "shop", "--project", "--services", services, "--dir", dir.toString)
    assertEquals(code, 0, err)
    dir.resolve("shop")

  private def project(language: Language = Language.Scala, template: Option[String] = None) =
    Init.Request(
      "shop",
      template,
      directory = Files.createTempDirectory("project-init"),
      language = language
    )

  test("--project refuses each shape of --services the contract names, with its words") {
    def problems(services: Option[String], add: Boolean = false) =
      Projects.startProblems(project(), services, add)
    assertEquals(
      problems(None),
      Vector("--project needs --services with at least two service names")
    )
    assertEquals(
      problems(Some("orders")),
      Vector("--project needs --services with at least two service names")
    )
    assertEquals(problems(Some("orders,orders")), Vector("the services must be distinct"))
    assertEquals(
      problems(Some("orders,shared")),
      Vector("\"shared\" and \"tests\" are the project's own directories")
    )
    assert(problems(Some("orders,Billing")).exists(_.contains("service name 'Billing' is invalid")))
    assertEquals(
      problems(Some("orders,billing"), add = true),
      Vector("--add adds a service to a project; --project starts one")
    )
    assertEquals(problems(Some("orders,billing")), Vector.empty)
  }

  test("--project refuses another language, a template and an invalid project id") {
    assertEquals(
      Projects.startProblems(project(Language.Python), Some("orders,billing"), add = false),
      Vector(
        "--project starts Scala services; add a service in another language inside the project " +
          "with ankka init <service> --language <lang>"
      )
    )
    assertEquals(
      Projects.startProblems(
        project(template = Some("file:///t.g8")),
        Some("orders,billing"),
        add = false
      ),
      Vector("--template applies to a single Scala service")
    )
    val local = Init.Request("local", directory = Files.createTempDirectory("project-init"))
    assert(
      Projects
        .startProblems(local, Some("orders,billing"), add = false)
        .exists(_.contains("reserved"))
    )
  }

  test(
    "a project holds its services, each with its own descriptor and port, and its rendered map"
  ) {
    val root = started("orders,billing,stock")
    for (service, port) <- Vector("orders" -> 9000, "billing" -> 9001, "stock" -> 9002) do
      val descriptor = Files.readString(root.resolve(s"services/$service/service.json"))
      assert(descriptor.contains(s"\"name\": \"$service\""), descriptor)
      assert(descriptor.contains(s"\"port\": $port"), descriptor)
    val map = com.thinkmorestupidless.ankka.core.ContextMap.read(root).toOption.get
    assertEquals(map.services.map(_.name), Vector("orders", "billing", "stock"))
    assertEquals(com.thinkmorestupidless.ankka.core.ContextMap.check(root, map), Vector.empty)
    assert(Files.isExecutable(root.resolve("scripts/run.sh")))
    assert(
      Files.isRegularFile(
        root.resolve("shared/src/main/scala/com/example/shop/contracts/OrdersV1.scala")
      )
    )
  }

  test("a project's files carry no token left unfilled") {
    val root = started()
    val left = Files
      .walk(root)
      .iterator()
      .asScala
      .filter(Files.isRegularFile(_))
      .filterNot(_.toString.contains("/.claude/"))
      .filterNot(_.toString.contains("/.specify/extensions/"))
      .flatMap(f => "\\{\\{[a-zA-Z_]+\\}\\}".r.findAllIn(Files.readString(f)).map(t => s"$f: $t"))
      .filterNot(_.contains("${{"))
      .toVector
    assertEquals(left, Vector.empty)
  }

  test("a directory with a context map is a project, and the next port fills the first gap") {
    val root = started()
    assert(Projects.isProject(root))
    assert(!Projects.isProject(Files.createTempDirectory("not-a-project")))
    assertEquals(Projects.nextPort(root), 9002)
    Files.delete(root.resolve("services/orders/service.json"))
    assertEquals(Projects.nextPort(root), 9000)
  }

  test(
    "a service added inside a project gets the next port and an entry in the map, and nothing else"
  ) {
    val root   = started()
    val before = Files.walk(root).iterator().asScala.map(root.relativize(_).toString).toSet
    val (code, out, err) = cli("init", "stock", "--dir", root.toString)
    assertEquals(code, 0, err)
    assert(out.contains("complete the context map's entry for \"stock\""), out)
    val after   = Files.walk(root).iterator().asScala.map(root.relativize(_).toString).toSet
    val outside = (after -- before).filterNot(_.startsWith("services/stock"))
    assertEquals(outside, Set.empty[String])
    assert(Files.readString(root.resolve("services/stock/service.json")).contains("\"port\": 9002"))
    assert(Files.readString(root.resolve("context-map.json")).contains("\"stock\""))
    assert(Files.readString(root.resolve("CONTEXT-MAP.md")).contains("| stock |"))
  }

  test(
    "a python service inside a project keeps a compose file of its own, on the project's database and broker"
  ) {
    val root           = started()
    val (code, _, err) = cli("init", "stock", "--language", "python", "--dir", root.toString)
    assertEquals(code, 0, err)
    val service = root.resolve("services/stock")
    val compose = Files.readString(service.resolve("docker-compose.yml"))
    assert(!compose.contains("postgres:"), compose)
    assert(compose.contains("ANKKA_DB_HOST: host.docker.internal"), compose)
    assert(compose.contains("ANKKA_DB_NAME: stock"), compose)
    assert(compose.contains("ANKKA_KAFKA_BOOTSTRAP_SERVERS: host.docker.internal:9094"), compose)
    assert(compose.contains("\"9002:9002\""), compose)
    for owned <- Vector(".github", ".githooks", ".claude", ".mcp.json") do
      assert(!Files.exists(service.resolve(owned)), owned)
    assert(!Files.readString(service.resolve("README.md")).contains("## Deploy"))
  }

  test(
    "adding a service the project already has, or one named for its own directories, is refused"
  ) {
    val root = started()
    for name <- Vector("orders", "shared", "tests") do
      val (code, _, err) = cli("init", name, "--dir", root.toString)
      assertEquals(code, 1, name)
      assert(err.contains(s"shop already has a service \"$name\""), err)
  }

  test("--add in a directory that holds no project refuses and writes nothing") {
    val dir            = Files.createTempDirectory("no-project")
    val (code, _, err) = cli("init", "stock", "--add", "--dir", dir.toString)
    assertEquals(code, 1)
    assert(
      err.contains(
        s"$dir holds no project started from the project template (no context-map.json); nothing was written"
      ),
      err
    )
    assertEquals(Files.list(dir).count(), 0L)
  }

  test("specify runs when it is on PATH and is not switched off, and its failure is reported") {
    val target = Files.createTempDirectory("specify")
    val bin    = Files.createTempDirectory("bin")
    val fake   = bin.resolve("specify")
    Files.writeString(fake, "#!/bin/sh\n")
    fake.toFile.setExecutable(true): Unit
    val path              = Map("PATH" -> bin.toString)
    var ran               = Vector.empty[Vector[String]]
    def runner(code: Int) = (command: Vector[String], _: java.nio.file.Path) =>
      ran = ran :+ command
      code
    assertEquals(Projects.specify(target, path, Map.empty, runner(0)), Projects.Specify.Ran)
    assertEquals(ran, Vector(Projects.SpecifyCommand))
    assertEquals(Projects.specify(target, path, Map.empty, runner(3)), Projects.Specify.Failed(3))
    assertEquals(
      Projects.specify(target, path, Map("ankka.init.specify" -> "off"), runner(0)),
      Projects.Specify.Skipped
    )
    assertEquals(
      Projects.specify(target, path + ("ANKKA_INIT_SPECIFY" -> "off"), Map.empty, runner(0)),
      Projects.Specify.Skipped
    )
    assertEquals(
      Projects.specify(target, Map("PATH" -> ""), Map.empty, runner(0)),
      Projects.Specify.Skipped
    )
    assertEquals(ran.size, 2)
  }

  test("when specify did not run, the next steps name it first") {
    val steps = Projects.startSteps(
      java.nio.file.Path.of("/x/shop"),
      "shop",
      "orders",
      Projects.Specify.Skipped
    )
    assert(
      steps.contains(
        "  cd shop\n  specify init --here --ai claude --ai-skills\n  docker compose up -d"
      ),
      steps
    )
    val ran =
      Projects.startSteps(java.nio.file.Path.of("/x/shop"), "shop", "orders", Projects.Specify.Ran)
    assert(!ran.contains("specify"), ran)
    assert(ran.contains("ankka context-map check"), ran)
  }
