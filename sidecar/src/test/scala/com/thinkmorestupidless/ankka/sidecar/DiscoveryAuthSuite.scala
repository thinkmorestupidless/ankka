package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.discovery.{Endpoint as EndpointSpec, Route as RouteSpec, Spec}

/**
 * A process that declares an authenticated route to a sidecar with no issuer is refused before
 * anything starts, in the same report as every other problem (features/service-identity/
 * languages.feature). Before feature 022 such a route started and answered 503 forever.
 */
class DiscoveryAuthSuite extends munit.FunSuite:

  private def route(id: String, acl: Option[EndpointSpec.Acl] = None) =
    RouteSpec(id = id, method = "GET", template = s"/$id", acl = acl)

  private val spec = Spec(
    protocolVersion = Discovery.ProtocolVersion,
    endpoints = Seq(
      EndpointSpec(
        id = "Account",
        prefix = "/account",
        acl = EndpointSpec.Acl.AUTHENTICATED,
        routes = Seq(route("me"))
      ),
      EndpointSpec(
        id = "Open",
        prefix = "/open",
        acl = EndpointSpec.Acl.ALLOW_ALL,
        routes = Seq(route("public"), route("orders", Some(EndpointSpec.Acl.AUTHENTICATED)))
      ),
      // An unrelated problem, so the report is shown to carry everything at once.
      EndpointSpec(id = "Twin", prefix = "/open", acl = EndpointSpec.Acl.ALLOW_ALL)
    )
  )

  test("a service with an authenticated route and no issuer listed does not start") {
    val problems = Discovery
      .validate(spec, Discovery.ProtocolVersion, authConfigured = false)
      .left
      .getOrElse(fail("a sidecar with no issuer accepted an authenticated route"))
    assert(
      problems.contains(
        "endpoint 'Account' is AUTHENTICATED but no issuer is configured; set ANKKA_AUTH_ISSUERS"
      ),
      problems.mkString("\n")
    )
    assert(
      problems.contains(
        "endpoint 'Open': route 'GET /orders' is AUTHENTICATED but no issuer is configured; " +
          "set ANKKA_AUTH_ISSUERS"
      ),
      problems.mkString("\n")
    )
  }

  test("the missing issuer is reported together with every other problem found in the service") {
    val problems =
      Discovery.validate(spec, Discovery.ProtocolVersion, authConfigured = false).left.toOption.get
    assert(problems.exists(_.contains("share the prefix '/open'")), problems.mkString("\n"))
    assertEquals(problems.count(_.contains("ANKKA_AUTH_ISSUERS")), 2)
  }

  test("with an issuer configured, only the unrelated problem remains") {
    val problems =
      Discovery.validate(spec, Discovery.ProtocolVersion, authConfigured = true).left.toOption.get
    assert(!problems.exists(_.contains("ANKKA_AUTH_ISSUERS")), problems.mkString("\n"))
    assert(problems.exists(_.contains("share the prefix '/open'")), problems.mkString("\n"))
  }

  test("a malformed set of issuers is every problem, read before anything is dialled") {
    val problems = Main
      .readAuth(
        Map("ANKKA_AUTH_ISSUERS" -> "staff,staff", "ANKKA_AUTH_STAFF_ISSUER" -> "https://s")
      )
      .left
      .getOrElse(fail("a malformed set was accepted"))
    assert(problems.contains("ANKKA_AUTH_ISSUERS: 'staff' is listed twice"), problems.toString)
    assert(problems.contains("ANKKA_AUTH_STAFF_JWKS_URL is not set"), problems.toString)
    // `run` stops there: it starts no actor system and dials no process. With nothing listening at
    // the default process address, a run that went on to dial would wait out the discovery timeout
    // rather than return at once.
    val started = System.nanoTime()
    assertEquals(Main.run(Map("ANKKA_AUTH_ISSUERS" -> "staff")), 1)
    assert((System.nanoTime() - started) / 1_000_000 < 5_000, "the run dialled before refusing")
  }
