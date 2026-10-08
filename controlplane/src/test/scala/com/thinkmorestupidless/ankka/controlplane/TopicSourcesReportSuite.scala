package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{
  InstanceTopologyDocument,
  ServiceEndpoint,
  TopicSourceReport,
  TopologyService,
  TopologyWindow
}

/**
 * features/topics/status.feature: a service's status lists each topic source with how far behind it
 * is.
 */
class TopicSourcesReportSuite extends munit.FunSuite:

  private def document(instance: String, sources: TopicSourceReport*) =
    InstanceTopologyDocument(
      TopologyService("intake", "0.0.0", instance, "2026-10-07T10:00:00Z"),
      TopologyWindow(60, "2026-10-07T10:00:00Z", 0),
      Vector.empty,
      Vector.empty,
      Vector.empty,
      sources.toVector
    )

  private val relay = TopicSourceReport(
    "consumer",
    "relay",
    "events",
    "ankka.shop.intake.consumer.relay",
    "earliest",
    1
  )

  test(
    "one report per source over the instances: lags summed, the first failing kept, behind if any is"
  ) {
    val docs = Vector(
      document("intake-1", relay.copy(lag = Some(40), failing = None)),
      document(
        "intake-2",
        relay.copy(lag = Some(20), failing = Some("cannot decode offset 4711"), behind = true)
      ),
      document("intake-3", relay.copy(lag = None))
    )
    assertEquals(
      ServiceEndpoint.topicSourcesOf(docs),
      Vector(relay.copy(lag = Some(60), failing = Some("cannot decode offset 4711"), behind = true))
    )
  }

  test("an instance that reports no lag yet leaves the lag unknown") {
    assertEquals(
      ServiceEndpoint.topicSourcesOf(Vector(document("intake-1", relay))).map(_.lag),
      Vector(None)
    )
  }
