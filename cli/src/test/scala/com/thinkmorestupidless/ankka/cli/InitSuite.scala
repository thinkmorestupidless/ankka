package com.thinkmorestupidless.ankka.cli

import java.nio.file.Files

/** What `ankka init` decides before it starts a process. */
class InitSuite extends munit.FunSuite:

  test("the sbt invocation carries the template, the name and this CLI's version") {
    val cmd = Init.command(Init.Request("orders", Some("file:///t/ankka.g8")), version = "0.2.0")
    assertEquals(cmd.take(3), Vector("sbt", "--allow-empty", "-batch"))
    assertEquals(cmd(3), "new file:///t/ankka.g8 --name=orders --ankka_version=0.2.0")
  }

  test("a package is passed through when given, and only then") {
    assert(
      Init
        .newCommand(Init.Request("orders", pkg = Some("acme.orders")), "1.0.0")
        .endsWith("--package=acme.orders")
    )
    assert(!Init.newCommand(Init.Request("orders"), "1.0.0").contains("--package"))
  }

  test("the default template is the published one") {
    assert(
      Init.newCommand(Init.Request("orders"), "1.0.0").contains("thinkmorestupidless/ankka.g8")
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

  test("a language is scala, python, typescript or rust, with short forms, and nothing else") {
    assertEquals(Language.parse("Python"), Right(Language.Python))
    assertEquals(Language.parse("ts"), Right(Language.TypeScript))
    assertEquals(Language.parse("scala"), Right(Language.Scala))
    assertEquals(Language.parse("Rust"), Right(Language.Rust))
    assertEquals(Language.parse("rs"), Right(Language.Rust))
    assert(Language.parse("go").left.exists(_.contains("one of scala, python, typescript, rust")))
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
    val dir = Files.createTempDirectory("init")
    def request(name: String, pkg: Option[String] = None) =
      Init.Request(name, pkg = pkg, directory = dir, language = Language.Python)
    assertEquals(Scaffold.module(request("order-service")), "order_service")
    assertEquals(Init.problems(request("order-service")), Vector.empty)
    assert(Init.problems(request("import")).exists(_.contains("is a Python keyword")))
    assertEquals(Init.problems(request("import", Some("imports"))), Vector.empty)
    assert(Init.problems(request("orders", Some("Orders.App"))).exists(_.contains("is invalid")))
  }

  test("options that belong to another language are refused rather than ignored") {
    val dir = Files.createTempDirectory("init")
    val python =
      Init.Request("orders", template = Some("x/y.g8"), directory = dir, language = Language.Python)
    assert(Init.problems(python).exists(_.contains("--template applies to scala only")))
    val ts =
      Init.Request("orders", pkg = Some("acme"), directory = dir, language = Language.TypeScript)
    assert(Init.problems(ts).exists(_.contains("--package applies to scala and python only")))
  }

  test("each template renders every token, keeps GitHub's expressions, and carries its dotfiles") {
    for language <- Seq(Language.Python, Language.TypeScript) do
      val dir     = Files.createTempDirectory("init")
      val request = Init.Request("probe", directory = dir, language = language)
      val target  = Scaffold.render(request, "9.9.9")
      val listed  = Scaffold.files(language)
      assert(listed.contains(".gitignore"), listed.toString)
      assert(listed.contains(".github/workflows/deploy.yml"), listed.toString)
      assert(listed.exists(_.startsWith(".claude/skills/")), listed.toString)
      val deploy = Files.readString(target.resolve(".github/workflows/deploy.yml"))
      assert(deploy.contains("${{ secrets.ANKKA_TOKEN }}"), deploy)
      assert(!deploy.contains("{{name}}"), deploy)
      val descriptor = Files.readString(target.resolve("service.json"))
      assert(descriptor.contains("\"name\": \"probe\""), descriptor)
      assert(descriptor.contains("\"hosting\": \"process\""), descriptor)
  }
