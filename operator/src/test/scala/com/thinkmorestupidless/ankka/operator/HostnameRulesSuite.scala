package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaServiceSpec, HostnameStatus}

/** Where a custom hostname stands, from what the operator read (feature 045, research R6). */
class HostnameRulesSuite extends munit.FunSuite:

  private val h = "app.example.com"

  private def listener(conditions: (String, (Boolean, String))*) = Some(
    ListenerView(conditions.toMap)
  )

  private val programmed = listener(
    "Accepted"     -> (true, "Accepted"),
    "ResolvedRefs" -> (true, "ResolvedRefs"),
    "Programmed"   -> (true, "Programmed"),
    "Conflicted"   -> (false, "NoConflicts")
  )

  private def status(v: HostnameViews) = HostnameRules.status(v)

  test("a listener in conflict is rejected with the gateway's reason, whatever else is true") {
    val v = HostnameViews(
      h,
      Some(CertificateView(true)),
      None,
      listener("Conflicted" -> (true, "HostnameConflict"))
    )
    assertEquals(status(v), HostnameStatus(h, "rejected", Some("HostnameConflict")))
  }

  test("nothing read yet: the certificate is being issued") {
    assertEquals(
      status(HostnameViews(h)),
      HostnameStatus(h, "pending", Some(HostnameRules.Issuing))
    )
  }

  test("a challenge's reason is the authority's word, carried verbatim") {
    val reason =
      "Waiting for HTTP-01 challenge propagation: failed to perform self check GET request 'http://app.example.com/.well-known/acme-challenge/x': dial tcp: lookup app.example.com: no such host"
    val v = HostnameViews(
      h,
      Some(CertificateView(false)),
      Some(ChallengeView(Some("pending"), Some(reason)))
    )
    assertEquals(
      status(v),
      HostnameStatus(h, "pending", Some(s"waiting for the certificate: $reason"))
    )
  }

  test("a listener without its certificate yet is pending, not rejected") {
    val v = HostnameViews(h, None, None, listener("Accepted" -> (false, "InvalidCertificateRef")))
    assertEquals(status(v).state, "pending")
  }

  test("a listener the gateway does not accept, once the certificate is issued, is rejected") {
    val v = HostnameViews(
      h,
      Some(CertificateView(true)),
      None,
      listener("Accepted" -> (false, "TooManyListeners"))
    )
    assertEquals(status(v), HostnameStatus(h, "rejected", Some("TooManyListeners")))
  }

  test("a route the set does not accept is rejected with the route's reason") {
    val v = HostnameViews(
      h,
      Some(CertificateView(true)),
      None,
      programmed,
      Some((false, "NotAllowedByListeners"))
    )
    assertEquals(status(v), HostnameStatus(h, "rejected", Some("NotAllowedByListeners")))
  }

  test("an issued certificate on a listener not yet programmed is the gateway attaching it") {
    val v = HostnameViews(
      h,
      Some(CertificateView(true)),
      None,
      listener("Accepted" -> (true, "Accepted"))
    )
    assertEquals(status(v), HostnameStatus(h, "pending", Some(HostnameRules.Attaching)))
    val unresolved = HostnameViews(
      h,
      Some(CertificateView(true)),
      None,
      listener("Programmed" -> (true, ""), "ResolvedRefs" -> (false, "InvalidCertificateRef"))
    )
    assertEquals(status(unresolved).state, "pending")
  }

  test("issued, programmed and accepted is serving, with nothing to say") {
    val v =
      HostnameViews(h, Some(CertificateView(true)), None, programmed, Some((true, "Accepted")))
    assertEquals(status(v), HostnameStatus(h, "serving", None))
  }

  test("a refused renewal is shown beside a hostname that goes on serving") {
    val v = HostnameViews(
      h,
      Some(CertificateView(true, Some("429 rate limited"))),
      None,
      programmed,
      Some((true, "Accepted"))
    )
    assertEquals(status(v), HostnameStatus(h, "serving", Some("renewal refused: 429 rate limited")))
  }

  private val spec = AnkkaServiceSpec(
    projectId = "checkout",
    serviceName = "cart",
    image = "img:1",
    port = Some(9000),
    exposed = true,
    customHostnames = List("b.example.com", "a.example.com")
  )
  private val settings =
    Settings.default.copy(baseDomain = Some("example.test"), hostnameIssuer = Some("pebble"))

  test("one status per hostname in the spec's order, and none while not exposed") {
    val all = HostnameRules.statuses(spec, settings, Map.empty)
    assertEquals(all.map(_.hostname), List("b.example.com", "a.example.com"))
    assertEquals(HostnameRules.statuses(spec.copy(exposed = false), settings, Map.empty), Nil)
  }

  test("an operator with no issuer rejects each, naming the setting") {
    val all = HostnameRules.statuses(spec, settings.copy(hostnameIssuer = None), Map.empty)
    assertEquals(all.map(_.state).distinct, List("rejected"))
    assert(all.head.reason.exists(_.contains("ANKKA_HOSTNAME_ISSUER")))
  }

  test("a name under the base domain is rejected by the operator itself") {
    val all = HostnameRules.statuses(
      spec.copy(customHostnames = List("api.example.test")),
      settings,
      Map.empty
    )
    assertEquals(all.map(_.state), List("rejected"))
    assert(all.head.reason.exists(_.contains("under the base domain")), all.toString)
  }
