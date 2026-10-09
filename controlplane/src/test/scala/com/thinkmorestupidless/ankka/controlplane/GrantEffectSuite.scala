package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.domain.Grant

/**
 * Whether a grant opens what it names, or why not (feature 040): each word a listing shows, in the
 * order the service's status and topology are consulted.
 */
class GrantEffectSuite extends munit.FunSuite:

  private val merchant = Grantee.Service("payments", "merchant")
  private val network  = Grantee.Machine("affiliates", "network")
  private val deposits =
    GrantTarget.route("wallet", "POST", "/v1/wallets/{player}/{currency}/deposits")

  private def grant(
      target: GrantTarget = deposits,
      state: GrantState = GrantState.Accepted,
      to: Grantee = merchant
  ) =
    Grant("g1", to, target, state)

  private def status(
      hosting: String = "embedded",
      ready: Int = 1,
      grants: Option[String] = Some("mounted")
  ) =
    ServiceStatus(
      name = "wallet",
      projectId = "spinvibe",
      lifecycle = ServiceLifecycle.Ready,
      generation = 1L,
      image = "wallet:1",
      readyInstances = ready,
      desiredInstances = 1,
      hosting = hosting,
      grants = grants
    )

  private def document(handlers: TopologyHandler*) =
    InstanceTopologyDocument(
      TopologyService("wallet", "0.0.0", "wallet-1", "2026-10-09T10:00:00Z"),
      TopologyWindow(60, "2026-10-09T10:00:00Z", 0),
      Vector(
        TopologyNode("endpoint:/v1/wallets", "Endpoint", 0, platform = false, handlers.toVector)
      ),
      Vector.empty,
      Vector.empty
    )

  private val grantable =
    document(
      TopologyHandler("POST /v1/wallets/{p}/{c}/deposits", "route", grantable = Some(true)),
      TopologyHandler("GET /v1/wallets/{player}", "route"),
      TopologyHandler(
        "POST /shoppingcart.v1.WalletService/Deposit",
        "route",
        grantable = Some(true)
      )
    )

  private def effect(
      g: Grant,
      s: Option[ServiceStatus] = Some(status()),
      d: Vector[InstanceTopologyDocument] = Vector(grantable),
      exposed: Boolean = false
  ) =
    GrantEffect.of(g, s, d, exposed)

  test("an accepted grant on a route the running service has, and may be granted, is in effect") {
    assertEquals(effect(grant()), "in effect")
    assertEquals(
      effect(grant(GrantTarget.grpcMethod("wallet", "WalletService/Deposit"))),
      "in effect"
    )
  }

  test("a grant that is not accepted says its state, whatever the service says") {
    for state <- GrantState.values.filterNot(_ == GrantState.Accepted) do
      assertEquals(effect(grant(state = state), None), state.word)
  }

  test("a web-hosted service's routes are not grantable, before anything else is asked") {
    assertEquals(
      effect(grant(), Some(status(hosting = "web", grants = None))),
      "route not grantable"
    )
  }

  test("a service with no ready instance, or none at all, shows no route") {
    assertEquals(effect(grant(), Some(status(ready = 0))), "route not seen")
    assertEquals(effect(grant(), None), "route not seen")
  }

  test("a service whose instances read no grants waits for a rollout") {
    assertEquals(effect(grant(), Some(status(grants = None))), "rollout needed")
  }

  test(
    "a route the service does not have is not seen; one whose ACL does not name granted callers is not grantable"
  ) {
    val history = GrantTarget.route("wallet", "GET", "/v1/wallets/{player}/history")
    assertEquals(effect(grant(history)), "route not seen")
    assertEquals(
      effect(grant(GrantTarget.route("wallet", "GET", "/v1/wallets/{player}"))),
      "route not grantable"
    )
    assertEquals(effect(grant(), d = Vector.empty), "route not seen")
    assertEquals(
      effect(grant(GrantTarget.grpcMethod("wallet", "WalletService/GetBalance"))),
      "route not seen"
    )
  }

  test(
    "a machine's topic grant waits for an installation that exposes its broker; a service's does not"
  ) {
    val topic = GrantTarget.topic("affiliates.attribution", GrantTarget.Consume)
    assertEquals(effect(grant(topic, to = network)), "broker not exposed")
    assertEquals(effect(grant(topic, to = network), exposed = true), "in effect")
    assertEquals(effect(grant(topic)), "in effect")
    assertEquals(effect(grant(GrantTarget.erasure)), "in effect")
  }
