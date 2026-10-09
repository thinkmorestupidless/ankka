package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{GrantState, GrantTarget, Grantee}
import com.thinkmorestupidless.ankka.controlplane.deploy.ProjectProjection
import com.thinkmorestupidless.ankka.controlplane.domain.{Grant, GrantMark}
import com.thinkmorestupidless.ankka.crd.ProjectGrantEntry

/**
 * What of a project's grants reaches the cluster (feature 040): the accepted ones, flat, in a fixed
 * order; a pending grant is not there to open anything, nor an ended one.
 */
class ProjectProjectionSuite extends munit.FunSuite:

  private val at       = java.time.Instant.parse("2026-10-08T10:00:00Z")
  private val merchant = Grantee.Service("payments", "merchant")
  private val route =
    GrantTarget.route("wallet", "POST", "/v1/wallets/{player}/{currency}/deposits")

  private def grant(id: String, state: GrantState, target: GrantTarget = route) =
    Grant(id, merchant, target, state, GrantMark(None, Some(at)))

  test(
    "only accepted grants are projected: pending, declined and ended ones are not in the cluster"
  ) {
    val grants = GrantState.values.toVector.zipWithIndex.map((s, i) => grant(s"g$i", s))
    val spec   = ProjectProjection.spec("spinvibe", Map.empty, Map.empty, grants)
    assertEquals(spec.grants.map(_.id), List("g1"))
    assertEquals(GrantState.values(1), GrantState.Accepted)
  }

  test("each kind of target is written flat, with only its own fields") {
    val grants = Vector(
      grant("a", GrantState.Accepted),
      grant("b", GrantState.Accepted, GrantTarget.grpcMethod("wallet", "WalletService/Deposit")),
      grant(
        "c",
        GrantState.Accepted,
        GrantTarget.topic("casino.players", "consume", decrypt = true)
      ),
      grant("d", GrantState.Accepted, GrantTarget.erasure)
    )
    val entries = ProjectProjection.spec("spinvibe", Map.empty, Map.empty, grants).grants
    assertEquals(
      entries,
      List(
        ProjectGrantEntry(
          "a",
          "service:payments/merchant",
          "route",
          service = Some("wallet"),
          httpMethod = Some("POST"),
          path = Some("/v1/wallets/{player}/{currency}/deposits"),
          grantedAt = at.toString
        ),
        ProjectGrantEntry(
          "b",
          "service:payments/merchant",
          "method",
          service = Some("wallet"),
          method = Some("WalletService/Deposit"),
          grantedAt = at.toString
        ),
        ProjectGrantEntry(
          "c",
          "service:payments/merchant",
          "topic",
          topic = Some("casino.players"),
          right = Some("consume"),
          decrypt = true,
          grantedAt = at.toString
        ),
        ProjectGrantEntry("d", "service:payments/merchant", "erasure", grantedAt = at.toString)
      )
    )
  }

  test(
    "the same grants in any order project to the same spec, so an unchanged project writes nothing"
  ) {
    val grants = Vector(grant("z", GrantState.Accepted), grant("a", GrantState.Accepted))
    assertEquals(
      ProjectProjection.spec("p", Map.empty, Map.empty, grants),
      ProjectProjection.spec("p", Map.empty, Map.empty, grants.reverse)
    )
  }
