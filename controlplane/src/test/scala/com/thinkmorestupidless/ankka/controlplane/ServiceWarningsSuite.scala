package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.domain.DeclaredTopic

/**
 * `features/topics/gap.feature`'s warnings (feature 043): a view reading a topic that keeps less
 * than the installation's warning threshold is warned, worked out from the declarations as they are
 * when the status is read, so a topic lowered after the view was deployed warns it and one raised
 * clears the warning.
 */
class ServiceWarningsSuite extends munit.FunSuite:

  private val threshold = RetentionTime.Bounded(30L * 86400000L)

  private val entries =
    TopicSourceReport(
      "view",
      "entries",
      "transactions",
      "ankka.money.ledger.view.entries",
      "earliest",
      1
    )

  private def declared(retention: String, cleanup: String = "delete") =
    val settings = TopicSettingsRules
      .fill(
        TopicSettingsRules
          .parse(
            "transactions",
            TopicDeclarationRequest(Some(3), retention = Some(retention), cleanup = Some(cleanup))
          )
          .fold(p => fail(p.mkString), identity),
        TopicDefaults.Shipped
      )
      .settings
    Map("transactions" -> DeclaredTopic(3, settings = Some(settings)))

  private def warnings(topics: Map[String, DeclaredTopic], sources: TopicSourceReport*) =
    ServiceEndpoint.retentionWarnings(sources.toVector, topics, threshold)

  test("a view over a topic that keeps less than the warning threshold is warned in its status") {
    assertEquals(
      warnings(declared("7d"), entries),
      Vector(
        ServiceWarning(
          "retention",
          "entries",
          "transactions",
          "view 'entries' reads topic 'transactions', which keeps 7d; the installation warns below 30d"
        )
      )
    )
  }

  test("a topic that keeps everything or is compacted draws no retention warning") {
    assertEquals(warnings(declared("everything"), entries), Vector.empty)
    assertEquals(warnings(declared("7d", cleanup = "compact"), entries), Vector.empty)
    assertEquals(warnings(declared("7d", cleanup = "compact,delete"), entries), Vector.empty)
  }

  test(
    "a topic lowered below the warning threshold warns the views reading it, and raised, clears it"
  ) {
    assertEquals(warnings(declared("90d"), entries), Vector.empty)
    assertEquals(warnings(declared("7d"), entries).map(_.topic), Vector("transactions"))
    assertEquals(warnings(declared("90d"), entries), Vector.empty)
  }

  test(
    "a consumer, a topic the project does not declare, or one with no settings yet, draws none"
  ) {
    assertEquals(warnings(declared("7d"), entries.copy(kind = "consumer")), Vector.empty)
    assertEquals(warnings(Map.empty, entries), Vector.empty)
    assertEquals(warnings(Map("transactions" -> DeclaredTopic(3)), entries), Vector.empty)
  }
