package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.local.{DockerEngine, Engine}

/** What `DockerEngine` reads of Docker's answers (feature 066), with no Docker. */
class EngineSuite extends munit.FunSuite:

  test("compose's ps is read whether it answers one array or one object a line") {
    val lines =
      """{"Service":"orders","State":"running","Health":"healthy","ID":"abc","Name":"x"}
        |{"Service":"billing","State":"exited","Health":"","ID":"def"}
        |""".stripMargin
    assertEquals(DockerEngine.parsePs(lines).map(_.Service), Vector("orders", "billing"))
    val array = """[{"Service":"orders","State":"running","ID":"abc"}]"""
    assertEquals(
      DockerEngine.parsePs(array).map(r => (r.Service, r.State)),
      Vector("orders" -> "running")
    )
    assertEquals(DockerEngine.parsePs(""), Vector.empty)
  }

  test("an executable that is not there answers, it does not throw") {
    val engine = DockerEngine("/nonexistent/docker")
    assertEquals(engine.available, Left("docker is not running on this machine"))
    assertEquals(engine.ps("ankka-shop"), Vector.empty)
    assert(!engine.imagePresent("x"))
  }

  test("the scripted engine keeps a project's containers as up and down are called") {
    val engine = ScriptedEngine()
    engine.up(
      java.nio.file.Path.of("c.json"),
      "ankka-shop",
      Vector("orders"),
      removeOrphans = false
    )
    val first = engine.ps("ankka-shop").map(_.id)
    engine.up(
      java.nio.file.Path.of("c.json"),
      "ankka-shop",
      Vector("orders"),
      removeOrphans = false
    )
    assertEquals(engine.ps("ankka-shop").map(_.id), first)
    engine.down("ankka-shop", volumes = false)
    assertEquals(engine.ps("ankka-shop"), Vector.empty[Engine.Container])
    assertEquals(
      engine.calls,
      Vector(
        "up ankka-shop orders",
        "ps ankka-shop",
        "up ankka-shop orders",
        "ps ankka-shop",
        "down ankka-shop",
        "ps ankka-shop"
      )
    )
  }
