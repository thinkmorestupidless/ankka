package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{
  DeclaredEdge,
  InstanceTopologyDocument,
  TopicCheck,
  TopicChecks,
  TopologyService
}
import com.thinkmorestupidless.ankka.controlplane.domain.DeclaredTopic
import com.thinkmorestupidless.ankka.core.Contract

import java.time.Instant

/** features/topics/contracts.feature: a contract is shown with the topic — the sides it has. */
class TopicChecksSuite extends munit.FunSuite:

  private val v1         = Contract("order.v1", "sha256:ab")
  private val v2         = Contract("order.v2", "sha256:cd")
  private val declaredAt = Instant.parse("2026-10-07T10:00:00Z")
  private val declared = Map(
    "orders" -> DeclaredTopic(3, Some(declaredAt), contract = Some(v1)),
    "plain"  -> DeclaredTopic(1, Some(declaredAt))
  )

  private def document(startedAt: String, edges: DeclaredEdge*) =
    InstanceTopologyDocument(
      TopologyService("wallet", "0.0.0", "wallet-1", startedAt),
      com.thinkmorestupidless.ankka.controlplane.api.TopologyWindow(60, startedAt, 0),
      Vector.empty,
      edges.toVector,
      Vector.empty
    )

  test("a side stating the declared contract is checked; another is a mismatch") {
    val after = "2026-10-07T11:00:00Z"
    val docs = Vector(
      "wallet" -> document(
        after,
        DeclaredEdge("consumer:relay", "topic:orders", "topic-publication", Some(v1))
      ),
      "shop" -> document(
        after,
        DeclaredEdge("topic:orders", "view:by-day", "topic-subscription", Some(v2))
      ),
      "intake" -> document(
        after,
        DeclaredEdge("topic:orders", "consumer:count", "topic-subscription", None)
      )
    )
    assertEquals(
      TopicChecks.of(declared, docs)("orders"),
      Vector(
        TopicCheck("orders", "intake", "consumer:count", "reads", None, "mismatch"),
        TopicCheck("orders", "shop", "view:by-day", "reads", Some("order.v2"), "mismatch"),
        TopicCheck("orders", "wallet", "consumer:relay", "publishes", Some("order.v1"), "checked")
      )
    )
  }

  test("an instance started before the declaration is unchecked, not a mismatch") {
    val before = "2026-10-07T09:00:00Z"
    val docs = Vector(
      "shop" -> document(
        before,
        DeclaredEdge("topic:orders", "view:by-day", "topic-subscription", None)
      )
    )
    assertEquals(TopicChecks.of(declared, docs)("orders").map(_.state), Vector("unchecked"))
  }

  test(
    "a topic without a contract, a declared broker's topic, and a topic nobody declared have no checks"
  ) {
    val docs = Vector(
      "a" -> document(
        "2026-10-07T11:00:00Z",
        DeclaredEdge("topic:plain", "view:x", "topic-subscription", None)
      ),
      "b" -> document(
        "2026-10-07T11:00:00Z",
        DeclaredEdge("topic:legacy/orders", "view:y", "topic-subscription", None, Some("legacy"))
      ),
      "c" -> document(
        "2026-10-07T11:00:00Z",
        DeclaredEdge("topic:unknown", "view:z", "topic-subscription", None)
      )
    )
    assertEquals(TopicChecks.of(declared, docs), Map.empty[String, Vector[TopicCheck]])
  }
