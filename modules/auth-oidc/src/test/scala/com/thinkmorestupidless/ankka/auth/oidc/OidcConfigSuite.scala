package com.thinkmorestupidless.ankka.auth.oidc

import java.nio.file.Files
import scala.concurrent.duration.DurationInt

/** The named set: what it reads, what it ignores, and every way it refuses (data-model §1). */
class OidcConfigSuite extends munit.FunSuite:

  private val customers = Map(
    "ANKKA_AUTH_CUSTOMERS_ISSUER"   -> "https://auth.shop.test/realms/customers/",
    "ANKKA_AUTH_CUSTOMERS_JWKS_URL" -> "https://auth.shop.test/realms/customers/certs",
    "ANKKA_AUTH_CUSTOMERS_AUDIENCE" -> "shop"
  )

  private def problems(env: Map[String, String]): Vector[String] =
    OidcConfig.fromEnv(env) match
      case Left(found) => found
      case Right(c)    => fail(s"expected problems, got $c")

  private def config(env: Map[String, String]): OidcConfig =
    OidcConfig.fromEnv(env) match
      case Right(c)    => c
      case Left(found) => fail(s"expected a configuration, got $found")

  test("no ANKKA_AUTH_ISSUERS is no issuers, and nothing else is read") {
    assertEquals(config(Map.empty), OidcConfig.empty)
    assertEquals(config(customers).issuers, Vector.empty)
  }

  test("one issuer is read with its defaults, and the issuer's trailing slash is dropped") {
    val c = config(customers + ("ANKKA_AUTH_ISSUERS" -> "customers"))
    assertEquals(
      c.issuers,
      Vector(
        Issuer(
          "customers",
          "https://auth.shop.test/realms/customers",
          "https://auth.shop.test/realms/customers/certs",
          "shop"
        )
      )
    )
    assertEquals(c.realm, "ankka")
    assertEquals(c.issuers.head.typ, None, "the typ check is off unless asked for")
    assertEquals(c.issuers.head.clockSkew, 60.seconds)
  }

  test("a name with a hyphen reads the variables with an underscore") {
    val env = Map(
      "ANKKA_AUTH_ISSUERS"               -> "customers-eu",
      "ANKKA_AUTH_CUSTOMERS_EU_ISSUER"   -> "https://eu",
      "ANKKA_AUTH_CUSTOMERS_EU_JWKS_URL" -> "https://eu/certs",
      "ANKKA_AUTH_CUSTOMERS_EU_AUDIENCE" -> "shop"
    )
    assertEquals(config(env).issuers.map(_.name), Vector("customers-eu"))
  }

  test("two issuers, each with its own optional settings, and the realm") {
    val ca = Files.writeString(Files.createTempFile("ca", ".pem"), "not read here")
    val env = customers ++ Map(
      "ANKKA_AUTH_ISSUERS"          -> "customers, staff",
      "ANKKA_AUTH_STAFF_ISSUER"     -> "https://staff",
      "ANKKA_AUTH_STAFF_JWKS_URL"   -> "https://staff/certs",
      "ANKKA_AUTH_STAFF_AUDIENCE"   -> "backoffice",
      "ANKKA_AUTH_STAFF_TYP"        -> "Bearer",
      "ANKKA_AUTH_STAFF_CA"         -> ca.toString,
      "ANKKA_AUTH_STAFF_CLOCK_SKEW" -> "5s",
      "ANKKA_AUTH_REALM"            -> "shop"
    )
    val c     = config(env)
    val staff = c.issuers.find(_.name == "staff").get
    assertEquals(c.issuers.map(_.name), Vector("customers", "staff"))
    assertEquals(staff.typ, Some("Bearer"))
    assertEquals(staff.ca, Some(ca))
    assertEquals(staff.clockSkew, 5.seconds)
    assertEquals(c.issuers.find(_.name == "customers").get.typ, None)
    assertEquals(c.realm, "shop")
  }

  test("the control plane's own variables beside a named set are ignored") {
    val env = customers ++ Map(
      "ANKKA_AUTH_ISSUERS"  -> "customers",
      "ANKKA_AUTH_ISSUER"   -> "http://localhost:8081/realms/ankka",
      "ANKKA_AUTH_JWKS_URL" -> "http://localhost:8081/realms/ankka/certs",
      "ANKKA_AUTH_JWKS_CA"  -> "/nowhere"
    )
    assertEquals(config(env).issuers.map(_.name), Vector("customers"))
  }

  test("a name listed twice is refused, naming it") {
    val found = problems(customers + ("ANKKA_AUTH_ISSUERS" -> "customers,customers"))
    assertEquals(found, Vector("ANKKA_AUTH_ISSUERS: 'customers' is listed twice"))
  }

  test("a name that is not a word is refused, naming it") {
    val found = problems(Map("ANKKA_AUTH_ISSUERS" -> "1st"))
    assertEquals(found, Vector("ANKKA_AUTH_ISSUERS: '1st' is not a valid issuer name"))
  }

  test("a required variable missing is refused, naming the variable") {
    val found = problems(
      customers - "ANKKA_AUTH_CUSTOMERS_AUDIENCE" + ("ANKKA_AUTH_ISSUERS" -> "customers")
    )
    assertEquals(found, Vector("ANKKA_AUTH_CUSTOMERS_AUDIENCE is not set"))
  }

  test("a CA that is not a file, and a skew that is not a duration, are refused") {
    val found = problems(
      customers ++ Map(
        "ANKKA_AUTH_ISSUERS"              -> "customers",
        "ANKKA_AUTH_CUSTOMERS_CA"         -> "/no/such/bundle.pem",
        "ANKKA_AUTH_CUSTOMERS_CLOCK_SKEW" -> "soon"
      )
    )
    assertEquals(
      found.toSet,
      Set(
        "ANKKA_AUTH_CUSTOMERS_CA: no file at /no/such/bundle.pem",
        "ANKKA_AUTH_CUSTOMERS_CLOCK_SKEW: 'soon' is not a duration"
      )
    )
  }

  test("every problem is reported at once, not the first") {
    val found = problems(Map("ANKKA_AUTH_ISSUERS" -> "staff,staff,9x"))
    assertEquals(
      found.toSet,
      Set(
        "ANKKA_AUTH_ISSUERS: 'staff' is listed twice",
        "ANKKA_AUTH_ISSUERS: '9x' is not a valid issuer name",
        "ANKKA_AUTH_STAFF_ISSUER is not set",
        "ANKKA_AUTH_STAFF_JWKS_URL is not set",
        "ANKKA_AUTH_STAFF_AUDIENCE is not set"
      )
    )
  }

  test("a configuration built in code is checked the same way") {
    val one = Issuer("staff", "https://s", "https://s/certs", "backoffice")
    assertEquals(
      OidcConfig.problems(OidcConfig(Vector(one, one.copy(audience = "")))),
      Vector("issuer 'staff' is listed twice", "issuer 'staff' has no audience")
    )
  }
