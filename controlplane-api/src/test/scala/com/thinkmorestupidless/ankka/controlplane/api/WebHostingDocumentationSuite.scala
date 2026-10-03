package com.thinkmorestupidless.ankka.controlplane.api

import java.nio.file.{Files, Path}

/**
 * `features/documentation/web-hosting.feature`: the statements the published documentation must
 * make about web hosting, held to the pages. Where the statement is the platform's own words, the
 * words come from the platform: every refusal a web descriptor can be given is produced by the real
 * rules and found on the descriptor reference.
 */
class WebHostingDocumentationSuite extends munit.FunSuite:

  private lazy val docs: Path =
    var dir = Path.of("").toAbsolutePath
    while !Files.isDirectory(dir.resolve("docs/reference")) do dir = dir.getParent
    dir.resolve("docs")

  private def page(path: String): String = Files.readString(docs.resolve(path))

  private def says(path: String, statements: String*): Unit =
    val text = page(path).replaceAll("\\s+", " ")
    for statement <- statements do
      assert(text.contains(statement), s"$path does not say: $statement")

  test("the documentation takes a developer from nothing written to a deployed interface") {
    // The same steps WebTemplateSuite and the cluster suite run.
    says(
      "get-started/first-interface.md",
      "ankka init shop-web --language web",
      "npm test",
      "ankka local web --service backend=",
      "docker build -t shop-web:latest .",
      "ankka services apply -f service.json",
      "ankka services expose shop-web"
    )
  }

  /** A web descriptor broken in one way: each gives one or more of the rules' messages. */
  private val web = ServiceSpec("web:1", hosting = ServiceSpec.Web)
  private val broken: Vector[ServiceSpec] = Vector(
    ServiceSpec("web:1", hosting = "lambda"),
    web.copy(http = false),
    web.copy(protocol = Some("1.0")),
    web.copy(runtime = Some("0.9.0")),
    web.copy(env = Vector(EnvVar("PORT", Some("1")), EnvVar("ANKKA_SERVICES_URL", Some("x")))),
    web.copy(env = Vector(EnvVar("ANKKA_DB_HOST", Some("db")))),
    web.copy(env =
      Vector(EnvVar("KEY", secretKeyRef = Some(SecretKeyRef("web-service-tls", "k"))))
    ),
    web.copy(processPort = Some(70000)),
    web.copy(processPort = Some(9000)),
    web.copy(processPort = Some(7627)),
    web.copy(port = 7630),
    web.copy(mounts = Vector(Mount("api", "cart"))),
    web.copy(mounts = Vector(Mount("/", "cart"))),
    web.copy(mounts = Vector(Mount("/api/", "cart"))),
    web.copy(mounts = Vector(Mount("/api", "cart"), Mount("/api", "orders"))),
    web.copy(mounts = Vector(Mount("/api", "cart"), Mount("/api/orders", "orders"))),
    web.copy(mounts = Vector(Mount("/api", "Cart Service"))),
    web.copy(callers = Vector("Order Service")),
    web.copy(callers = Vector("orders", "orders")),
    ServiceSpec("cart:1", mounts = Vector(Mount("/api", "cart")))
  )

  test("the documentation of the descriptor describes a web-hosted service") {
    says(
      "reference/service-descriptor.md",
      "## Web hosting",
      "`mounts`",
      "`callers`",
      "`processPort`"
    )
    // Every message the rules give a web descriptor, matched against the page's templates, where a
    // `<placeholder>` stands for any value.
    val templates = """`([^`\n]+)`""".r
      .findAllMatchIn(page("reference/service-descriptor.md"))
      .map(_.group(1))
      .filter(_.contains(" "))
      .map(t => java.util.regex.Pattern.quote(t).replaceAll("<[^>]+>", "\\\\E.+\\\\Q").r)
      .toVector
    val messages = broken.flatMap(spec => ServiceDescriptor("web", spec).problems).distinct
    assert(messages.size >= broken.size, messages.mkString("\n"))
    for message <- messages do
      assert(
        templates.exists(_.matches(message)),
        s"the descriptor reference does not give the refusal: $message"
      )
  }

  test("the documentation says what the process is told and how it calls services") {
    says(
      "reference/web-hosting.md",
      "`PORT`",
      "`ANKKA_SERVICES_URL`",
      "`X-Ankka-Caller`",
      "`X-Forwarded-Proto`",
      "`X-Forwarded-Host`",
      "`X-Forwarded-Port`",
      "`Host`",
      "## Calling another service",
      "calling address",
      "$ANKKA_SERVICES_URL/cart/carts/c1",
      "$ANKKA_SERVICES_URL/invoices.billing/issue"
    )
  }

  test("the documentation says that a mounted service has to admit the internet") {
    says(
      "deploy/web-hosting.md",
      "The mounted service is told the caller `Gateway`",
      "it serves the request only if its access rule admits the internet",
      "Only a call the process makes is told to come from the web-hosted service"
    )
  }

  test("the documentation says what web hosting does not do") {
    says(
      "reference/limitations.md",
      "Web hosting keeps no image but the one its descriptor names",
      "A request may reach any instance of a web-hosted service",
      "No upgraded connections",
      "No traces, metrics or topology for a web-hosted service",
      "A mounted service on an older runtime refuses"
    )
  }
