package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaProjectSpec, ProjectTopicEntry, ProjectTopicStatus}
import com.thinkmorestupidless.ankka.operator.strimzi.{KafkaTopicStatus, StrimziCondition}

import java.time.Instant

/**
 * A project's declared topics, every rule of research R22 with no cluster:
 * `TopicProvisioning.decide` takes what the operator observed as a plain value. Cases named for a
 * scenario hold that scenario.
 */
class TopicProvisioningSuite extends munit.FunSuite:

  private val broker   = Some(BrokerStack.settings)
  private val declared = Instant.parse("2026-10-05T10:00:00Z")
  private val earlier  = declared.minusSeconds(3600)
  private val later    = declared.plusSeconds(60)

  private val transactions = ProjectTopicEntry("transactions", 12, declared.toString)
  private val entries      = ProjectTopicEntry("entries", 3, declared.toString)
  private val money        = AnkkaProjectSpec("money", List(transactions, entries))

  private def ready(at: Instant = later) =
    StrimziObjectState(exists = true, ready = Some(true), createdAt = Some(at))

  private def made(partitions: Int, state: StrimziObjectState = ready()) =
    Some(TopicState(state, Some(partitions)))

  private def decide(entry: ProjectTopicEntry, observed: Option[TopicState]) =
    TopicProvisioning.decide("money", entry, broker, observed)

  // features/broker/declaring.feature: the outline, row by row
  test("a project's topics say how far the platform has got with them") {
    // Not yet made.
    assertEquals(decide(transactions, None).phase, "Waiting")
    // Made, and not yet reported on.
    assertEquals(
      decide(
        transactions,
        made(12, StrimziObjectState(exists = true, createdAt = Some(later)))
      ).phase,
      "Waiting"
    )
    // Made with fewer partitions so far: the resource asks for 12 and the broker reports an
    // earlier generation, which is not yet reported for this one.
    val stale = StrimziObjectState.found(
      Some(2L),
      Some(KafkaTopicStatus(Vector(StrimziCondition("Ready", "True")), Some(1L))),
      Some(later)
    )
    assertEquals(decide(transactions, made(12, stale)).phase, "Waiting")
    // Made.
    assertEquals(decide(transactions, made(12)), TopicPlan.Ready(recovered = false))
    assertEquals(decide(transactions, made(12)).phase, "Provisioned")
    // A problem that will not clear.
    val refused = StrimziObjectState(
      exists = true,
      ready = Some(false),
      reason = Some("NotSupported"),
      message = Some("Decreasing partitions not supported")
    )
    assertEquals(
      decide(transactions, made(12, refused)),
      TopicPlan.Failed(Vector("topic 'money.transactions': Decreasing partitions not supported"))
    )
  }

  test(
    "a topic whose resource already asks for more partitions fails, and is not rendered smaller"
  ) {
    val grown = Map(
      "money.transactions" -> TopicState(ready(), Some(13)),
      "money.entries"      -> TopicState(ready(), Some(3))
    )
    assertEquals(
      decide(transactions, grown.get("money.transactions")),
      TopicPlan.Failed(
        Vector("topic 'money.transactions' has 13 partitions and cannot have fewer; 12 was asked")
      )
    )
    assertEquals(TopicProvisioning.topicsToRender(money, broker, grown), Vector(entries))
  }

  test("a topic asked for more partitions is rendered, and waits while the broker grows it") {
    val raised = transactions.copy(partitions = 24)
    val seen   = Map("money.transactions" -> TopicState(ready(), Some(12)))
    assertEquals(
      TopicProvisioning.topicsToRender(AnkkaProjectSpec("money", List(raised)), broker, seen),
      Vector(raised)
    )
    assertEquals(decide(raised, seen.get("money.transactions")).phase, "Waiting")
  }

  // features/broker/kept.feature
  test("a topic declared again finds what was published to it") {
    assertEquals(decide(transactions, made(12, ready(earlier))), TopicPlan.Ready(recovered = true))
    assertEquals(decide(transactions, made(12, ready(earlier))).phase, "Recovered")
  }

  // features/broker/installation.feature
  test("a topic declared on an installation with no broker says why it is not made") {
    val plan = TopicProvisioning.decide("money", transactions, None, None)
    assertEquals(plan, TopicPlan.Failed(Vector("the installation has no broker")))
    assertEquals(TopicProvisioning.topicsToRender(money, None, Map.empty), Vector.empty)
    assertEquals(
      TopicProvisioning.status(money, None, Map.empty).topics,
      List(
        ProjectTopicStatus("transactions", "Failed", None, Some("the installation has no broker")),
        ProjectTopicStatus("entries", "Failed", None, Some("the installation has no broker"))
      )
    )
  }

  test(
    "the status lists every declared topic, in order, with the partitions its resource asks for"
  ) {
    val seen = Map("money.transactions" -> TopicState(ready(), Some(12)))
    assertEquals(
      TopicProvisioning
        .status(money, broker, seen)
        .topics
        .map(t => (t.name, t.phase, t.partitions)),
      List(("transactions", "Provisioned", Some(12)), ("entries", "Waiting", None))
    )
  }

  test("a declaration with a time that cannot be read is never taken as recovered") {
    val unreadable = transactions.copy(declaredAt = "")
    assertEquals(decide(unreadable, made(12, ready(earlier))), TopicPlan.Ready(recovered = false))
  }
