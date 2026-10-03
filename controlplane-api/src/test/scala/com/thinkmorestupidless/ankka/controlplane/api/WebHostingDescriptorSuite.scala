package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

/**
 * A web-hosted service's descriptor (feature 021): what it may say, and every refusal, in the words
 * the CLI and the control plane both print (contracts/descriptor-and-rendering.md, "Refusals").
 */
class WebHostingDescriptorSuite extends munit.FunSuite:

  private val web = ServiceSpec(image = "shop-web:1", hosting = ServiceSpec.Web)

  private def problems(spec: ServiceSpec, name: String = "web"): Vector[String] =
    ServiceDescriptor(name, spec).problems

  private def refused(spec: ServiceSpec, message: String)(using munit.Location): Unit =
    val found = problems(spec)
    assert(found.contains(message), s"expected «$message» among $found")

  private def secret(name: String, secret: String) =
    EnvVar(name, secretKeyRef = Some(SecretKeyRef(secret, "tls.key")))

  test("a web-hosted service with nothing else said is valid, on the platform's process port") {
    assertEquals(problems(web), Vector.empty)
    assert(web.isWebHosted)
    assertEquals(web.resolvedProcessPort, Some(ServiceSpec.DefaultProcessPort))
    assertEquals(web.resolvedPort, Some(9000))
  }

  test("the complete example of the data model is valid and survives the wire") {
    val spec = web.copy(
      processPort = Some(3000),
      mounts = Vector(Mount("/api/cart", "cart"), Mount("/api/orders", "orders")),
      callers = Vector("orders", "billing/invoices"),
      env =
        Vector(EnvVar("SESSION_KEY", secretKeyRef = Some(SecretKeyRef("web-secrets", "session"))))
    )
    assertEquals(problems(spec), Vector.empty)
    val descriptor = ServiceDescriptor("web", spec)
    assertEquals(readFromString[ServiceDescriptor](writeToString(descriptor)), descriptor)
  }

  test("an unknown hosting is refused, naming all four") {
    refused(
      web.copy(hosting = "static"),
      "hosting must be \"embedded\", \"process\", \"wasm\" or \"web\", not \"static\""
    )
  }

  test("a web-hosted service serves HTTP") {
    refused(
      web.copy(http = false),
      "a web-hosted service's proxy serves HTTP; remove \"http\": false"
    )
  }

  test("a web-hosted service declares no protocol and no runtime") {
    refused(
      web.copy(protocol = Some("1.0")),
      "protocol is meaningful only for process or wasm hosting"
    )
    refused(
      web.copy(runtime = Some("0.9.0")),
      "runtime is meaningful only for a service built on ankka; a web-hosted service declares none"
    )
  }

  test("a web-hosted service sets neither of the variables the platform tells its program") {
    for name <- Vector("PORT", "ANKKA_SERVICES_URL") do
      refused(
        web.copy(env = Vector(EnvVar(name, Some("1")))),
        s"env var '$name' is set by the platform and cannot be declared"
      )
  }

  test("PORT is refused only for web hosting: an embedded service may have set it for itself") {
    assertEquals(
      problems(ServiceSpec("cart:1", env = Vector(EnvVar("PORT", Some("1"))))),
      Vector.empty
    )
  }

  test("a web-hosted service has no database, so it cannot supply one") {
    refused(
      web.copy(env = Vector(EnvVar("ANKKA_DB_HOST", Some("db")))),
      "env var 'ANKKA_DB_HOST' supplies a database, and a web-hosted service has none"
    )
  }

  test("the process's port is in range, not the service's own, and not one the platform uses") {
    for bad <- Vector(0, 65536) do
      refused(web.copy(processPort = Some(bad)), s"processPort $bad is outside the range 1-65535")
    refused(
      web.copy(processPort = Some(9000)),
      "processPort 9000 is the service's own port; the process and the proxy cannot both listen on it"
    )
    for reserved <- Vector(7626, 7627, 7628, 7630, 17355) do
      refused(
        web.copy(processPort = Some(reserved)),
        s"processPort $reserved is used by the platform"
      )
  }

  test("a service port of 8080 with no process port is refused: both would be the default") {
    refused(
      web.copy(port = 8080),
      "processPort 8080 is the service's own port; the process and the proxy cannot both listen on it"
    )
  }

  test("a web-hosted service's own port is not one its proxy listens on for something else") {
    for reserved <- Vector(7627, 7630) do
      refused(web.copy(port = reserved), s"service port $reserved is used by the platform's proxy")
  }

  test("a mount is refused when it is malformed") {
    def mounted(mounts: Mount*) = web.copy(mounts = mounts.toVector)
    refused(mounted(Mount("api/cart", "cart")), "mount 'api/cart': a path starts with \"/\"")
    refused(
      mounted(Mount("/", "cart")),
      "mount '/': a mount cannot be every path; the process serves what no mount does"
    )
    for bad <- Vector("/api/", "/api//cart", "/api/cart?x=1", "/api/cart#x", "/api cart") do
      refused(
        mounted(Mount(bad, "cart")),
        s"mount '$bad': a path is whole segments of letters, digits, \"-\", \".\", \"_\" and " +
          "\"~\", with no trailing \"/\""
      )
    refused(
      mounted(Mount("/api", "cart"), Mount("/api", "orders")),
      "mount '/api' is declared more than once"
    )
    refused(
      mounted(Mount("/api", "cart"), Mount("/api/orders", "orders")),
      "mount '/api/orders' is inside mount '/api'"
    )
    refused(
      mounted(Mount("/api/cart", "Cart Service")),
      "mount '/api/cart': 'Cart Service' is not a service name"
    )
  }

  test("a mount matches whole segments: /apix is not inside /api") {
    assertEquals(
      problems(web.copy(mounts = Vector(Mount("/api", "cart"), Mount("/apix", "orders")))),
      Vector.empty
    )
  }

  test("a web-hosted service cannot mount itself") {
    val found = problems(web.copy(mounts = Vector(Mount("/api/web", "web"))), name = "web")
    assert(found.contains("mount '/api/web': a web-hosted service cannot mount itself"), found)
  }

  test("an admitted service is a name, a project and a name, or every service of the project") {
    assertEquals(
      problems(web.copy(callers = Vector("orders", "billing/invoices", "*"))),
      Vector.empty
    )
    for bad <- Vector("Order Service", "My Shop/orders", "a/b/c", "", "*/orders") do
      refused(
        web.copy(callers = Vector(bad)),
        s"caller '$bad' is not \"<service>\", \"<project>/<service>\" or \"*\""
      )
    refused(
      web.copy(callers = Vector("orders", "orders")),
      "caller 'orders' is declared more than once"
    )
  }

  test("only a web-hosted service has mounts, admitted services or a process port") {
    val embedded = ServiceSpec("cart:1")
    refused(
      embedded.copy(mounts = Vector(Mount("/api", "orders"))),
      "mounts is meaningful only for web hosting"
    )
    refused(embedded.copy(callers = Vector("orders")), "callers is meaningful only for web hosting")
    refused(
      embedded.copy(processPort = Some(3000)),
      "processPort is meaningful only for web hosting"
    )
  }

  test("no descriptor of any hosting takes a variable from a secret the platform issues") {
    val issued = Vector(
      "web-service-tls",
      "web-mount-tls",
      "orders-cluster-tls",
      "orders-database-tls",
      "ankka-db",
      "ankka-db-ca",
      "ankka-db-client-ca",
      "ankka-db-replication"
    )
    for
      hosting <- Vector(ServiceSpec.Embedded, ServiceSpec.Web)
      name    <- issued
    do
      refused(
        ServiceSpec("i:1", hosting = hosting, env = Vector(secret("K", name))),
        s"env var 'K': secret '$name' is issued by the platform and cannot be read by a service"
      )
  }

  test("a secret that only looks like one the platform issues is a service's own") {
    for name <- Vector("orders-db", "my-ankka-db", "ankka-dbx", "web-secrets") do
      assertEquals(
        problems(ServiceSpec("i:1", env = Vector(secret("K", name)))),
        Vector.empty,
        name
      )
  }

  test("every problem is reported at once") {
    val spec = web.copy(
      protocol = Some("1.0"),
      runtime = Some("0.9.0"),
      processPort = Some(7627),
      mounts = Vector(Mount("/", "cart")),
      callers = Vector("Bad Name")
    )
    assertEquals(problems(spec).size, 5, problems(spec))
  }

  test("a null mounts or callers is no mounts or callers, and a null process port is none") {
    val spec =
      readFromString[ServiceSpec](
        """{"image":"i:1","hosting":"web","mounts":null,"callers":null,"processPort":null}"""
      )
    assertEquals(spec.mounts, Vector.empty)
    assertEquals(spec.callers, Vector.empty)
    assertEquals(spec.processPort, None)
  }

  test("a descriptor that uses none of the new fields is written without them") {
    val written = writeToString(ServiceDescriptor("cart", ServiceSpec("cart:1")))
    for field <- Vector("mounts", "callers", "processPort") do
      assert(!written.contains(field), written)
  }
