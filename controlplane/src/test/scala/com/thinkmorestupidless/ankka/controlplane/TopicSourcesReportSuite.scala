package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{
  InstanceTopologyDocument,
  PartitionGapReport,
  RetentionGapReport,
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

  // features/topics/gap.feature: as the instances of "ledger" reported it
  test("the retention gap is merged by partition, never summed: gone if any instance says so") {
    val at    = java.time.Instant.parse("2026-09-01T10:00:00Z")
    val later = at.plusSeconds(60)
    def gap(
        gone: Boolean,
        compacted: Boolean,
        read: java.time.Instant,
        partitions: PartitionGapReport*
    ) =
      Some(RetentionGapReport(partitions.toVector, compacted, gone, Some(read)))
    val docs = Vector(
      document(
        "ledger-1",
        relay.copy(gap =
          gap(gone = true, compacted = false, at, PartitionGapReport(0, 1240, Some(at)))
        )
      ),
      document(
        "ledger-2",
        relay.copy(gap =
          gap(
            gone = false,
            compacted = false,
            later,
            PartitionGapReport(0, 1240, Some(at)),
            PartitionGapReport(1, 0, None)
          )
        )
      )
    )
    val merged = ServiceEndpoint.topicSourcesOf(docs).map(_.gap)
    assertEquals(
      merged,
      Vector(
        Some(
          RetentionGapReport(
            Vector(PartitionGapReport(0, 1240, Some(at)), PartitionGapReport(1, 0, None)),
            compacted = false,
            gone = true,
            readAt = Some(later)
          )
        )
      )
    )
    // Compacted only when every instance says so.
    val mixed = Vector(
      document("ledger-1", relay.copy(gap = gap(gone = false, compacted = true, at))),
      document("ledger-2", relay.copy(gap = gap(gone = false, compacted = false, at)))
    )
    assertEquals(
      ServiceEndpoint.topicSourcesOf(mixed).flatMap(_.gap).map(_.compacted),
      Vector(false)
    )
    // No instance has asked yet: no gap.
    assertEquals(
      ServiceEndpoint.topicSourcesOf(Vector(document("ledger-1", relay))).map(_.gap),
      Vector(None)
    )
  }

  test("an instance that reports no lag yet leaves the lag unknown") {
    assertEquals(
      ServiceEndpoint.topicSourcesOf(Vector(document("intake-1", relay))).map(_.lag),
      Vector(None)
    )
  }
