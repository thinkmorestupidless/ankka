package com.thinkmorestupidless.ankka.cli

import java.nio.file.Files

/** What `ankka init` decides before it starts a process. */
class InitSuite extends munit.FunSuite:

  test("the sbt invocation carries the template, the name and this CLI's version") {
    val cmd = Init.command(Init.Request("orders", "file:///t/ankka.g8"), version = "0.2.0")
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
