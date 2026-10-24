package com.thinkmorestupidless.ankka.cli

import scala.jdk.CollectionConverters.*
import java.nio.file.Files

/** What `ankka init` decides before it starts a process. */
class InitSuite extends munit.FunSuite:

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
