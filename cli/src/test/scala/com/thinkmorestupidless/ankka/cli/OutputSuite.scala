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

  test("a single service shows its broker and the topics it uses that are not declared") {
    val rendered = Output.service(
      status("ledger", database = Some("provisioned")).copy(
        broker = Some("provisioned"),
        undeclaredTopics = Some(Vector("entries", "notices"))
      ),
      Format.Table
    )
    val lines = rendered.linesIterator.toVector
    val at    = lines.indexWhere(_.startsWith("broker"))
    assert(at > lines.indexWhere(_.startsWith("database")), rendered)
    assert(lines(at).endsWith("provisioned"), rendered)
    assert(
      lines(at + 1).startsWith("undeclared topics") && lines(at + 1).endsWith("entries"),
      rendered
    )
    assert(lines(at + 2).trim == "notices", rendered)
  }

  test("a service with nothing reported of a broker, or every topic declared, shows neither line") {
    val rendered = Output.service(status("wallet"), Format.Table)
    assert(!rendered.contains("broker") && !rendered.contains("topics"), rendered)
    val declared =
      Output.service(status("wallet").copy(undeclaredTopics = Some(Vector.empty)), Format.Table)
    assert(!declared.contains("topics"), declared)
  }

  test("a project's topics list each with its partitions and phase") {
    val rendered = Output.projectTopics(
      Vector(
        ProjectTopic("transactions", 12, Some("provisioned")),
        ProjectTopic("entries", 3, Some("failed"), Some("the installation has no broker"))
      ),
      Format.Table
    )
    val lines = rendered.linesIterator.toVector
    assert(lines.head.startsWith("TOPIC"), rendered)
    assert(lines(1).contains("transactions") && lines(1).contains("12"), rendered)
    assert(lines(2).contains("the installation has no broker"), rendered)
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

  test(
    "a web-hosted service prints its process port, callers and mounts after the lines every service has"
  ) {
    val web =
      status("web", database = Some("none"), hostname = Some("https://web-checkout.example.test"))
        .copy(
          hosting = "web",
          processPort = Some(3000),
          callers = Vector("orders", "billing/invoices", "*"),
          mounts = Vector(
            MountStatus("/api/cart", "cart", "ok"),
            MountStatus("/api/orders", "orders", "no service"),
            MountStatus("/admin", "admin")
          )
        )
    val lines  = Output.service(web, Format.Table).split("\n").toVector
    val labels = lines.map(_.takeWhile(_ != ' ')).filter(_.nonEmpty)
    assertEquals(
      labels,
      Vector(
        "name",
        "project",
        "status",
        "instances",
        "generation",
        "image",
        "hosting",
        "hostname",
        "database",
        "process",
        "callers",
        "mounts"
      )
    )
    assertEquals(
      lines.dropWhile(!_.startsWith("hosting")),
      Vector(
        "hosting     web",
        "hostname    https://web-checkout.example.test",
        "database    none",
        "process     port 3000",
        "callers     the internet, orders, billing/invoices, every service in checkout",
        "mounts      /api/cart    → cart",
        "            /api/orders  → orders    (no service)",
        "            /admin       → admin"
      )
    )
  }

  test("a web-hosted service with no callers and no mounts still says it admits the internet") {
    val rendered =
      Output.service(status("web").copy(hosting = "web", processPort = Some(8080)), Format.Table)
    assert(rendered.contains("callers     the internet"), rendered)
    assert(!rendered.contains("mounts"), rendered)
  }

  test("an embedded service prints none of a web-hosted service's lines") {
    val rendered = Output.service(status("cart", database = Some("provisioned")), Format.Table)
    for label <- Vector("process", "callers", "mounts") do
      assert(!rendered.linesIterator.exists(_.startsWith(label)), rendered)
    assertEquals(rendered.linesIterator.toVector.last, "database    provisioned")
  }

  test("a service with a bucket says so after its database, and its address when it has one") {
    val rendered = Output.service(
      status("reports", database = Some("provisioned")).copy(
        objectStorage = Some("recovered existing bucket"),
        bucket = Some("shop.reports"),
        bucketAddress = Some("https://storage.example.com/shop.reports")
      ),
      Format.Table
    )
    val lines = rendered.linesIterator.toVector
    val at    = lines.indexWhere(_.startsWith("database"))
    assertEquals(
      lines.slice(at, at + 4),
      Vector(
        "database        provisioned",
        "object storage  recovered existing bucket",
        "bucket          shop.reports",
        "bucket address  https://storage.example.com/shop.reports"
      )
    )
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

  test("an organization's quota is one column, a dash per limit not set (feature 015)") {
    val capped = OrganizationSummary(
      "acme",
      "Acme",
      projects = 2,
      role = Some(Role.Owner),
      quota = Some(Quota(services = Some(3))),
      usage = Usage(2, 1, 4)
    )
    val free     = OrganizationSummary("ops", "Ops", projects = 0)
    val rendered = Output.organizations(Vector(capped, free), Format.Table)
    val lines    = rendered.linesIterator.toVector
    assert(lines.head.contains("SERVICES"), rendered)
    assert(lines.head.contains("INSTANCES"), rendered)
    assert(lines.head.contains("QUOTA"), rendered)
    assert(lines(1).contains("-/3/-"), rendered)
    assert(lines(1).contains(" 4 "), rendered)
    assertEquals(lines(2).split("\\s+").count(_ == "-"), 2, rendered)
    assert(Output.organization(capped, Format.Json).contains("\"quota\":{\"services\":3}"))
    assertEquals(Output.quota(Quota(Some(1), None, Some(3))), "projects 1, instances 3")
  }

  test("the topology of a service shows its socket routes beside its routes") {
    val topology = ServiceTopology(
      service = "notices",
      running = 2,
      contributing = 2,
      partial = false,
      instances = Vector.empty,
      window = TopologyWindow(300, "2026-10-04T00:00:00Z", 0),
      nodes = Vector(
        TopologyNode(
          "endpoint:/notices",
          "Endpoint",
          0,
          platform = false,
          Vector(
            TopologyHandler("GET /notices", "route", Some(false)),
            TopologyHandler("SOCKET /notices/stream", "route", Some(true))
          )
        )
      ),
      declared = Vector.empty,
      calls = Vector.empty,
      differences = Vector.empty
    )
    val printed = Output.topology(topology, Format.Table)
    assert(printed.contains("GET /notices, SOCKET /notices/stream"), printed)
  }

  // ── rollbacks (feature 033) ────────────────────────────────────────────────

  private val digest = "3f9a1c0be2d4" + "0" * 52

  test("a rollback prints the generation it chose, then the status as services get prints it") {
    val result = RolledBack(1, status("cart", generation = 3, image = "cart:1"))
    assertEquals(
      Output.rolledBack(result, Format.Table),
      "rolled back to generation 1\n" + Output.service(result.status, Format.Table)
    )
    assert(Output.rolledBack(result, Format.Json).startsWith("{\"rolledBackTo\":1,"))
  }

  test(
    "the history shows an image and a short digest where one was recorded, and a dash where not"
  ) {
    val entries = Vector(
      HistoryEntry(
        "rolled-back",
        3,
        image = Some("cart:1"),
        digest = Some(digest),
        rolledBackTo = Some(1)
      ),
      HistoryEntry("restarted", 2),
      HistoryEntry("applied", 1, image = Some("cart:1"), digest = Some(digest))
    )
    val lines = Output.history(entries, Format.Table).linesIterator.toVector
    assert(lines.head.matches("WHEN +KIND +GEN +IMAGE +DIGEST +BY"), lines.head)
    assert(lines(1).matches("- +rolled-back to 1 +3 +cart:1 +3f9a1c0be2d4 +-"), lines(1))
    assert(lines(2).matches("- +restarted +2 +- +- +-"), lines(2))
    assert(!lines.mkString.contains(digest), "the table shows twelve characters, not all 64")
    val json = Output.history(entries, Format.Json)
    assert(json.contains(digest) && json.contains("\"rolledBackTo\":1"), json)
    assert(json.contains("\"kind\":\"rolled-back\""), json)
  }
