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

  private def choices(taken: Set[Int] = Set.empty): LocalState.Choices =
    var next = 40000
    var key  = 0
    LocalState.Choices(
      isFree = p => !taken(p),
      freePort = () => { next += 1; next },
      newKey = () => { key += 1; s"key-$key" }
    )

  test("a port once chosen is never changed, and what is new is chosen") {
    val first = LocalState.completed(
      LocalState(),
      LocalState.Wanted(Vector("orders")),
      choices()
    )
    assertEquals(first.postgresPort, Some(5432))
    assertEquals(first.kafkaPort, Some(9092))
    val second = LocalState.completed(
      first,
      LocalState.Wanted(Vector("orders", "billing")),
      choices(taken = Set(5432, 9092))
    )
    assertEquals(second.postgresPort, first.postgresPort)
    assertEquals(second.observability("orders"), first.observability("orders"))
    assert(second.observability.contains("billing"))
    assertEquals(second.secretKeys("orders"), first.secretKeys("orders"))
  }

  test("a taken 5432 is replaced by a free port, and no two choices are the same") {
    val state = LocalState.completed(
      LocalState(),
      LocalState.Wanted(Vector("orders", "billing"), companionPorts = Map("mail" -> Vector(1025))),
      choices(taken = Set(5432))
    )
    assertNotEquals(state.postgresPort, Some(5432))
    assertEquals(state.ports.size, 5)
  }

  test("each service has a secret key of its own") {
    val state =
      LocalState.completed(LocalState(), LocalState.Wanted(Vector("orders", "billing")), choices())
    assertNotEquals(state.secretKeys("orders"), state.secretKeys("billing"))
    assertEquals(java.util.Base64.getDecoder.decode(LocalState.randomKey()).length, 32)
  }

  test("the state reads back as it was written") {
    val root  = java.nio.file.Files.createTempDirectory("state")
    val state = LocalState.completed(LocalState(), LocalState.Wanted(Vector("orders")), choices())
    LocalState.write(root, state)
    assertEquals(LocalState.read(root), state)
  }
