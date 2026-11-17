package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.local.*

/**
 * The pure half of `ankka local up` (feature 066, research R2): what is read, what is refused
 * before anything starts, and the plan each project gives, with no Docker.
 */
class LocalPlanSuite extends munit.FunSuite:

  test("the command's own variables are the platform's, the database's and the broker's") {
    for name <- Vector(
        "ANKKA_DB_NAME",
        "ANKKA_DB_HOST",
        "ANKKA_KAFKA_TOPIC_PREFIX",
        "ANKKA_HTTP_PORT",
        "ANKKA_GRPC_PORT",
        "ANKKA_SECRET_KEY",
        "ANKKA_SERVICE_NAME",
        "ANKKA_OBSERVABILITY_ADDRESS",
        "ANKKA_SIDECAR_ADDRESS",
        "ANKKA_PROCESS_PORT",
        "ANKKA_WASM_MODULE",
        "ANKKA_CLUSTER_MODE",
        "ANKKA_DATABASE",
        "PORT",
        "ANKKA_SERVICES_URL"
      )
    do assert(LocalVariables.isCommandOwned(name), name)
    for name <- Vector("CURRENCY", "ANKKA_AUTH_STAFF_ISSUER", "JAVA_TOOL_OPTIONS", "LOG_LEVEL") do
      assert(!LocalVariables.isCommandOwned(name), name)
  }

  test("a directory that holds no context map is no project") {
    val dir = java.nio.file.Files.createTempDirectory("empty")
    assertEquals(
      LocalProject.read(dir),
      Left(
        Vector(
          s"$dir holds no project: a project's directory holds context-map.json and " +
            "services/<name>/service.json for each service"
        )
      )
    )
  }

  test("a project is read: its map, its descriptors, its topics and its local file") {
    val project =
      LocalProject.read(LocalFixtures.project()).fold(p => fail(p.mkString("; ")), identity)
    assertEquals(project.name, "shop")
    assertEquals(project.services, Vector("orders", "billing"))
    assertEquals(project.descriptor("billing").service.port, 9001)
    assertEquals(project.topics.topics, Vector.empty)
    assertEquals(project.local, LocalFile())
  }

  test("a service the map names with no descriptor is refused, with the map's own words") {
    val root = LocalFixtures.project()
    java.nio.file.Files.delete(root.resolve("services/billing/service.json"))
    val problems = LocalProject.read(root).left.getOrElse(fail("read"))
    assert(
      problems.contains(
        "the context map names \"billing\", and services/billing/service.json does not exist"
      ),
      problems
    )
  }

  test("an invalid descriptor is refused by its path, every problem together") {
    val root = LocalFixtures.project(
      Vector(
        LocalFixtures.Service("orders", 9000, extra = ""","instanceType":"huge""""),
        LocalFixtures.Service("billing", 9001, extra = ""","hosting":"submarine"""")
      )
    )
    val problems = LocalProject.read(root).left.getOrElse(fail("read"))
    assert(problems.exists(_.startsWith("services/orders/service.json: ")), problems)
    assert(problems.exists(_.startsWith("services/billing/service.json: ")), problems)
  }
