package com.thinkmorestupidless.ankka.core

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * `ContextMap` against the contract's table (feature 062): each problem a map can have, produced
 * alone by a project that has only that defect, in the contract's words; a sound project with none;
 * the rendering byte for byte; a field the platform does not know refused by name.
 */
class ContextMapSuite extends munit.FunSuite:

  private val Sample = """{
    "project": "shop",
    "services": [
      {"name": "orders", "owns": "orders and their lines",
       "boundary": {"criterion": "ownership", "why": "the orders team releases on its own cadence"}},
      {"name": "billing", "owns": "invoices and payments",
       "boundary": {"criterion": "ownership", "why": "finance owns invoicing"}}
    ],
    "crossings": [
      {"from": "orders", "to": "billing", "topic": "orders-placed",
       "contract": "order.v1", "schema": "contracts/order.v1.json"},
      {"from": "orders", "to": "billing", "route": "POST /invoices"}
    ]
  }"""

  private val Rendering =
    """# shop
      |
      |*Rendered from `context-map.json` by `ankka context-map render`; edit the file, not this page.*
      |
      |## Services
      |
      || Service | Owns | Boundary | Why |
      ||---|---|---|---|
      || orders | orders and their lines | ownership | the orders team releases on its own cadence |
      || billing | invoices and payments | ownership | finance owns invoicing |
      |
      |## Crossings
      |
      || From | To | What passes | Contract or route |
      ||---|---|---|---|
      || orders | billing | the declared topic `orders-placed` | `order.v1` (`contracts/order.v1.json`) |
      || orders | billing | a call | `POST /invoices` |
      |""".stripMargin

  private def sample: ContextMap =
    ContextMap.parse(Sample).fold(p => fail(p.mkString("; ")), identity)

  private def write(root: Path, path: String, text: String): Unit =
    val file = root.resolve(path)
    Files.createDirectories(file.getParent)
    Files.writeString(file, text, UTF_8): Unit

  /** A project the map is true of: its two service directories, the schema and the rendering. */
  private def project(map: ContextMap): Path =
    val root = Files.createTempDirectory("context-map")
    write(
      root,
      "services/orders/service.json",
      """{"name":"orders","service":{"image":"orders:1"}}"""
    )
    write(
      root,
      "services/billing/service.json",
      """{"name":"billing","service":{"image":"billing:1"}}"""
    )
    write(root, "contracts/order.v1.json", """{"type":"object"}""")
    write(root, ContextMap.FileName, ContextMap.write(map))
    write(root, ContextMap.RenderingName, ContextMap.render(map))
    root

  /** The problems of `map` over a project otherwise true of the sample, rendering refreshed. */
  private def problems(map: ContextMap, tweak: Path => Unit = _ => ()): Vector[String] =
    val root = project(map)
    tweak(root)
    ContextMap.check(root, map)

  private def crossing(c: ContextMap.Crossing): ContextMap = sample.copy(crossings = Vector(c))

  test("a map true of its project has no problem") {
    assertEquals(problems(sample), Vector.empty)
  }

  test("the rendering is the contract's, byte for byte") {
    assertEquals(ContextMap.render(sample), Rendering)
  }

  test("what the map writes reads back as the same map") {
    assertEquals(ContextMap.parse(ContextMap.write(sample)), Right(sample))
  }

  test("no file") {
    val root = Files.createTempDirectory("context-map")
    assertEquals(
      ContextMap.read(root),
      Left(
        Vector(s"$root holds no project started from the project template (no context-map.json)")
      )
    )
  }

  test("a field the platform does not know is refused by name") {
    val text = Sample.replace("\"project\": \"shop\",", "\"project\": \"shop\", \"owner\": \"me\",")
    assertEquals(
      ContextMap.parse(text),
      Left(Vector("the context map names a field the platform does not know: \"owner\""))
    )
  }

  test("a file that is not JSON says so") {
    val Left(Vector(line)) = ContextMap.parse("{ not json"): @unchecked
    assert(line.startsWith("context-map.json: "), line)
  }

  test("an invalid project id") {
    val found = problems(sample.copy(project = "Shop"))
    assertEquals(found.size, 1, found)
    assert(
      found.head.startsWith("the context map's project \"Shop\" is not a valid project name: ")
    )
  }

  test("a service with no directory") {
    val map = sample.copy(services =
      sample.services :+ ContextMap.Service("shipping", "", ContextMap.Boundary("ownership"))
    )
    assertEquals(
      problems(map),
      Vector(
        "the context map names \"shipping\", and services/shipping/service.json does not exist"
      )
    )
  }

  test("a directory the map does not name") {
    val map = sample.copy(services = sample.services.take(1), crossings = Vector.empty)
    assertEquals(
      problems(map),
      Vector(
        "shop has a service \"billing\" (services/billing/service.json) that the context map does not name"
      )
    )
  }

  test("a descriptor whose name differs from its directory") {
    val found = problems(
      sample,
      root =>
        write(
          root,
          "services/billing/service.json",
          """{"name":"invoicing","service":{"image":"i:1"}}"""
        )
    )
    assertEquals(
      found,
      Vector("services/billing/service.json is named \"invoicing\", not \"billing\"")
    )
  }

  test("a boundary drawn by no criterion the map knows") {
    val map = sample.copy(services =
      sample.services.map(s => s.copy(boundary = s.boundary.copy(criterion = "taste"))).take(1) ++
        sample.services.drop(1)
    )
    assertEquals(
      problems(map),
      Vector(
        "the boundary of \"orders\" is drawn by \"taste\", which is not ownership, scaling, consistency"
      )
    )
  }

  test("a crossing naming a service the project does not have") {
    assertEquals(
      problems(crossing(ContextMap.Crossing("orders", "shipping", route = Some("GET /x")))),
      Vector(
        "the crossing from \"orders\" to \"shipping\" names \"shipping\", which is not a service of shop; " +
          "another project's service is written \"<project>/<service>\""
      )
    )
  }

  test("a crossing to another project's service is not this project's to resolve") {
    assertEquals(
      problems(
        crossing(ContextMap.Crossing("orders", "payments/ledger", route = Some("POST /entries")))
      ),
      Vector.empty
    )
  }

  test("a crossing of neither shape") {
    assertEquals(
      problems(crossing(ContextMap.Crossing("orders", "billing", topic = Some("orders-placed")))),
      Vector(
        "the crossing from \"orders\" to \"billing\" is neither a declared topic with a contract and a schema nor a route"
      )
    )
  }

  test("a crossing of both shapes") {
    val both = sample.crossings.head.copy(route = Some("POST /invoices"))
    assertEquals(
      problems(crossing(both)),
      Vector(
        "the crossing from \"orders\" to \"billing\" is both a topic and a route; a crossing is one or the other"
      )
    )
  }

  test("a schema that does not exist") {
    val found = problems(sample, root => Files.delete(root.resolve("contracts/order.v1.json")))
    assertEquals(
      found,
      Vector(
        "the crossing \"orders-placed\" names the schema contracts/order.v1.json, which does not exist"
      )
    )
  }

  test("a schema that is not JSON") {
    val found = problems(sample, root => write(root, "contracts/order.v1.json", "{ not json"))
    assertEquals(found.size, 1, found)
    assert(
      found.head.startsWith("the schema contracts/order.v1.json is not a JSON document: "),
      found.head
    )
  }

  test("an invalid contract name") {
    val bad = sample.crossings.head.copy(contract = Some("Order V1"))
    assertEquals(
      problems(crossing(bad)),
      Vector(s"the contract \"Order V1\" is not a valid contract name: ${Contract.NameRule}")
    )
  }

  test("an invalid topic name") {
    val bad = sample.crossings.head.copy(topic = Some("Orders_Placed"))
    assertEquals(
      problems(crossing(bad)),
      Vector(s"the topic \"Orders_Placed\" is not a valid topic name: ${Names.TopicNameRule}")
    )
  }

  test("an invalid route") {
    assertEquals(
      problems(crossing(ContextMap.Crossing("orders", "billing", route = Some("invoices")))),
      Vector("the route \"invoices\" is not \"<METHOD> <path>\"")
    )
  }

  test("two equal crossings") {
    val map = sample.copy(crossings = sample.crossings :+ sample.crossings.head)
    assertEquals(
      problems(map),
      Vector("the crossing from \"orders\" to \"billing\" by \"orders-placed\" is recorded twice")
    )
  }

  test("a rendering out of step") {
    val found = problems(sample, root => write(root, ContextMap.RenderingName, "# shop\n"))
    assertEquals(
      found,
      Vector("CONTEXT-MAP.md is out of step with context-map.json; run ankka context-map render")
    )
  }

  test("no rendering") {
    val found = problems(sample, root => Files.delete(root.resolve(ContextMap.RenderingName)))
    assertEquals(found, Vector("CONTEXT-MAP.md does not exist; run ankka context-map render"))
  }

  test("the problems come in the contract's order") {
    val map = sample.copy(
      project = "Shop",
      services =
        sample.services :+ ContextMap.Service("shipping", "", ContextMap.Boundary("ownership"))
    )
    val found = problems(map, root => Files.delete(root.resolve(ContextMap.RenderingName)))
    assertEquals(found.size, 3, found)
    assert(found(0).startsWith("the context map's project"))
    assert(found(1).startsWith("the context map names \"shipping\""))
    assert(found(2).startsWith("CONTEXT-MAP.md does not exist"))
  }

  test("a service's topic crossings, published and read") {
    val crossings = ContextMap.topicCrossings(sample, "orders")
    assertEquals(crossings.published.flatMap(_.topic), Vector("orders-placed"))
    assertEquals(crossings.read, Vector.empty)
    assertEquals(
      ContextMap.topicCrossings(sample, "billing").read.flatMap(_.topic),
      Vector("orders-placed")
    )
  }
