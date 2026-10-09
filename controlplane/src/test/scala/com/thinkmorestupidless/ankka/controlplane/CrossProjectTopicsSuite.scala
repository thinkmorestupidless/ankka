package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{
  CrossProjectTopic,
  DeclaredEdge,
  GrantState,
  GrantTarget,
  Grantee,
  InstanceTopologyDocument,
  ServiceEndpoint,
  TopologyService,
  TopologyWindow
}
import com.thinkmorestupidless.ankka.controlplane.domain.Grant

/**
 * `services get` names each other project's topic a service uses, and whether that project grants
 * it (feature 040): from the topology its instances report, joined with the granting project's
 * grants to this service.
 */
class CrossProjectTopicsSuite extends munit.FunSuite:

  private def document(edges: DeclaredEdge*) =
    InstanceTopologyDocument(
      TopologyService("attribution", "0.0.0", "attribution-1", "2026-10-09T10:00:00Z"),
      TopologyWindow(60, "2026-10-09T10:00:00Z", 0),
      Vector.empty,
      edges.toVector,
      Vector.empty
    )

  private val me = Grantee.Service("affiliates-hub", "attribution")

  private def grant(state: GrantState, topic: String, right: String, to: Grantee = me) =
    Grant(s"g-$topic-$right-$state", to, GrantTarget.topic(topic, right), state)

  private val docs = Vector(
    document(
      DeclaredEdge("topic:spinvibe/casino.players", "view:players", "topic-subscription"),
      DeclaredEdge("consumer:notifier", "topic:spinvibe/payments.deposits", "topic-publication"),
      DeclaredEdge("topic:payments/refunds", "consumer:refunds", "topic-subscription"),
      DeclaredEdge("topic:spinvibe/closed", "view:closed", "topic-subscription"),
      // Neither is another project's: the project's own topic, and a declared broker's.
      DeclaredEdge("topic:clicks", "view:clicks", "topic-subscription"),
      DeclaredEdge(
        "topic:legacy/orders",
        "view:orders",
        "topic-subscription",
        broker = Some("legacy")
      )
    ),
    // A second instance reporting the same edge adds nothing.
    document(DeclaredEdge("topic:spinvibe/casino.players", "view:players", "topic-subscription"))
  )

  private val grants: Map[String, Vector[Grant]] = Map(
    "spinvibe" -> Vector(
      grant(GrantState.Accepted, "casino.players", GrantTarget.Consume),
      grant(GrantState.Pending, "payments.deposits", GrantTarget.Produce),
      grant(GrantState.Revoked, "closed", GrantTarget.Consume),
      // Someone else's grant, and the right this service does not use.
      grant(GrantState.Accepted, "closed", GrantTarget.Consume, Grantee.Service("x", "y")),
      grant(GrantState.Accepted, "payments.deposits", GrantTarget.Consume)
    )
  )

  test("each other project's topic is listed with the right it needs and whether it is granted") {
    assertEquals(
      ServiceEndpoint.crossProjectTopicsOf(
        docs,
        "affiliates-hub",
        "attribution",
        p => grants.getOrElse(p, Vector.empty)
      ),
      Vector(
        CrossProjectTopic("payments", "refunds", "consume", "not granted (no grant)"),
        CrossProjectTopic("spinvibe", "casino.players", "consume", "granted"),
        CrossProjectTopic("spinvibe", "closed", "consume", "not granted (ended)"),
        CrossProjectTopic("spinvibe", "payments.deposits", "produce", "not granted (pending)")
      )
    )
  }

  test("a project whose grants cannot be read grants nothing") {
    assertEquals(
      ServiceEndpoint
        .crossProjectTopicsOf(docs.take(1), "affiliates-hub", "attribution", _ => sys.error("gone"))
        .map(_.status)
        .distinct,
      Vector("not granted (no grant)")
    )
  }
