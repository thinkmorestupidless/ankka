package nakka.cli

import nakka.controlplane.api.*

/** What the CLI actually prints. */
class OutputSuite extends munit.FunSuite:

  private def status(
      name: String,
      lifecycle: ServiceLifecycle = ServiceLifecycle.Ready,
      ready: Int = 1,
      desired: Int = 1,
      image: String = "cart:1.0",
      generation: Long = 1L,
      detail: Option[String] = None,
      database: Option[String] = None
  ) =
    ServiceStatus(
      name = name,
      projectId = "checkout",
      lifecycle = lifecycle,
      generation = generation,
      image = image,
      readyInstances = ready,
      desiredInstances = desired,
      detail = detail,
      database = database
    )

  test("columns are padded to the widest cell, header included") {
    val rendered = Output.services(
      Vector(
        status("cart", image = "registry.example.com/acme/cart:1.4.2"),
        status("b", image = "b:1")
      ),
      Format.Table
    )
    val lines = rendered.linesIterator.toVector
    assertEquals(lines.size, 3)
    assert(lines.head.startsWith("NAME  STATUS"), lines.head)

    // Every row's IMAGE column starts at the same offset, or it is not a table.
    val imageStarts = lines.map { line =>
      val marker = Vector("IMAGE", "registry.example.com", "b:1")
        .find(line.contains)
        .getOrElse(fail(s"no image cell in '$line'"))
      line.indexOf(marker)
    }
    assertEquals(imageStarts.distinct.size, 1, rendered)
  }

  test("an empty listing says so rather than printing a bare header") {
    assertEquals(Output.services(Vector.empty, Format.Table), "no results")
    assertEquals(Output.projects(Vector.empty, Format.Table), "no results")
    assertEquals(Output.organizations(Vector.empty, Format.Table), "no results")
  }

  test("an empty listing as json is an empty array, so a pipe still parses") {
    assertEquals(Output.services(Vector.empty, Format.Json), "[]")
    assertEquals(Output.projects(Vector.empty, Format.Json), "[]")
    assertEquals(Output.organizations(Vector.empty, Format.Json), "[]")
  }

  test("a lifecycle prints as one word in both formats") {
    val row = status("cart", lifecycle = ServiceLifecycle.PartiallyReady, ready = 1, desired = 3)
    assert(Output.services(Vector(row), Format.Table).contains("PartiallyReady"))
    assert(Output.services(Vector(row), Format.Json).contains("\"PartiallyReady\""))
  }

  test("instances render as ready-over-desired") {
    val rendered = Output.services(Vector(status("cart", ready = 2, desired = 5)), Format.Table)
    assert(rendered.contains("2/5"), rendered)
  }

  test("a single service shows detail, which no column could hold") {
    val rendered = Output.service(
      status(
        "cart",
        lifecycle = ServiceLifecycle.Failed,
        detail = Some("ImagePullBackOff: manifest for cart:9.9 not found")
      ),
      Format.Table
    )
    assert(rendered.contains("detail"), rendered)
    assert(rendered.contains("ImagePullBackOff"), rendered)
    assert(rendered.contains("status"), rendered)
  }

  test("a single service with no detail omits the row entirely") {
    val rendered = Output.service(status("cart"), Format.Table)
    assert(!rendered.contains("detail"), rendered)
  }

  test("a single service shows its database phrase when one has been reported") {
    val rendered = Output.service(status("cart", database = Some("provisioned")), Format.Table)
    assert(rendered.contains("database"), rendered)
    assert(rendered.contains("provisioned"), rendered)
  }

  test("a single service with no database report omits the row entirely") {
    val rendered = Output.service(status("cart"), Format.Table)
    assert(!rendered.contains("database"), rendered)
  }

  test("the token is never printed, in either format") {
    val settings = Settings("http://cp", Some("super-secret-token"), Some("checkout"))

    val rendered = Output.settings(settings, Format.Table)
    assert(!rendered.contains("super-secret-token"), rendered)
    assert(rendered.contains("(set)"), rendered)
    assert(rendered.contains("http://cp"), rendered)

    val json = Output.settings(settings, Format.Json)
    assert(!json.contains("super-secret-token"), json)
    assert(json.contains("(set)"), json)
  }

  test("an unset token and project say so rather than printing nothing") {
    val rendered = Output.settings(Settings(), Format.Table)
    assert(rendered.contains("(unset)"), rendered)
  }
