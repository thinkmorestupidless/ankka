package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.controlplane.api.*

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
      database: Option[String] = None,
      hostname: Option[String] = None,
      exposed: Boolean = false
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
      database = database,
      hostname = hostname,
      exposed = exposed
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

  test("the listing has a HOSTNAME column: the URL when exposed, a dash when not") {
    val rendered = Output.services(
      Vector(
        status("cart", hostname = Some("https://cart-checkout.example.test"), exposed = true),
        status("quiet")
      ),
      Format.Table
    )
    val lines = rendered.linesIterator.toVector
    assert(lines.head.contains("HOSTNAME"), lines.head)
    assert(lines(1).contains("https://cart-checkout.example.test"), lines(1))
    assert(lines(2).trim.endsWith("-"), lines(2))
  }

  test("a single service shows its hostname when exposed, and 'not exposed' otherwise") {
    val exposed =
      Output.service(
        status("cart", hostname = Some("https://cart-checkout.example.test"), exposed = true),
        Format.Table
      )
    assert(exposed.contains("hostname"), exposed)
    assert(exposed.contains("https://cart-checkout.example.test"), exposed)

    val private_ = Output.service(status("cart"), Format.Table)
    assert(private_.contains("hostname"), private_)
    assert(private_.contains("not exposed"), private_)
  }

  test("exposed with no hostname — no base domain on the control plane — says so plainly") {
    val rendered = Output.service(status("cart", exposed = true), Format.Table)
    assert(rendered.contains("exposed, but the control plane has no base domain"), rendered)
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

  // ── deploy tokens (feature 013) ───────────────────────────────────────────

  private val minted = DeployTokenCreated(
    id = "3f9a1c2e7b4d8f01",
    label = "github-deploy",
    secret = "ankka_3f9a1c2e7b4d8f01_" + ("9" * 64),
    subject = "token:3f9a1c2e7b4d8f01",
    expiresAt = Some(java.time.Instant.parse("2026-12-24T10:00:00Z"))
  )

  test("a created token prints its secret once, and says so") {
    val rendered = Output.deployTokenCreated(minted, Format.Table)
    assert(rendered.contains(minted.secret), rendered)
    assert(rendered.contains("only time the secret is shown"), rendered)
    assert(rendered.contains("ANKKA_TOKEN"), rendered)
    assert(rendered.contains("2026-12-24"), rendered)
    // Once: a reader who scrolls back must not find a second copy to rely on.
    assertEquals(rendered.sliding(minted.secret.length).count(_ == minted.secret), 1)
  }

  test("a token with no expiry says it never expires rather than printing nothing") {
    val rendered = Output.deployTokenCreated(minted.copy(expiresAt = None), Format.Table)
    assert(rendered.contains("never expires"), rendered)
  }

  test("a listing shows the label and the dates, and never the secret") {
    val rows = Vector(
      DeployTokenSummary(
        id = "3f9a1c2e7b4d8f01",
        label = "github-deploy",
        subject = "token:3f9a1c2e7b4d8f01",
        createdBy = Some("alice@example.test"),
        createdAt = Some(java.time.Instant.parse("2026-09-25T10:00:00Z")),
        expiresAt = Some(java.time.Instant.parse("2026-12-24T10:00:00Z")),
        lastUsed = Some(java.time.LocalDate.parse("2026-09-26"))
      ),
      DeployTokenSummary(
        id = "aaaabbbbccccdddd",
        label = "forever",
        subject = "token:aaaabbbbccccdddd"
      )
    )

    val rendered = Output.deployTokens(rows, Format.Table)
    assert(rendered.contains("github-deploy"), rendered)
    assert(rendered.contains("alice@example.test"), rendered)
    assert(rendered.contains("2026-09-26"), rendered)
    // "never" is a fact about the token; "-" is a value nobody has recorded yet.
    assert(rendered.contains("never"), rendered)
    assert(!rendered.contains("ankka_"), rendered)

    val json = Output.deployTokens(rows, Format.Json)
    assert(!json.contains("ankka_"), json)
    assert(!json.contains("secret"), json)
  }

  test("an empty listing says so") {
    assertEquals(Output.deployTokens(Vector.empty, Format.Table), "no deploy tokens")
  }
