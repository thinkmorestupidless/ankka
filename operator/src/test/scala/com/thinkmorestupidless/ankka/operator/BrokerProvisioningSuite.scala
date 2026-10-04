package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaServiceSpec, TopicEntry}

import java.time.Instant

/**
 * Every rule of research R8, in its order, with no cluster: `BrokerProvisioning.decide` takes what
 * the operator observed as a plain value. Cases named for a scenario hold that scenario.
 */
class BrokerProvisioningSuite extends munit.FunSuite:

  private val broker = Some(BrokerStack.settings)
  private val wallet =
    AnkkaServiceSpec(projectId = "money", serviceName = "wallet", generation = 1L)
  private val declaring =
    wallet.copy(topics = List(TopicEntry("transactions", 12), TopicEntry("wallet-events", 3)))

  private val created = Instant.parse("2026-10-04T12:00:00Z")
  private val earlier = created.minusSeconds(3600)
  private val later   = created.plusSeconds(60)

  private def ready(at: Instant = later) =
    StrimziObjectState(exists = true, ready = Some(true), createdAt = Some(at))
  private def topic(partitions: Int, state: StrimziObjectState = ready()) =
    TopicState(state, Some(partitions))

  private def allReady(at: Instant = later) = BrokerObservation(
    user = ready(at),
    topics = Map(
      "money.transactions"  -> topic(12, ready(at)),
      "money.wallet-events" -> topic(3, ready(at))
    ),
    resourceCreatedAt = Some(created)
  )

  private def decide(spec: AnkkaServiceSpec, observed: BrokerObservation = allReady()) =
    BrokerProvisioning.decide(spec, broker, observed)

  test("a web-hosted service is nothing to the broker, and reports nothing") {
    val plan = decide(declaring.copy(hosting = Rendering.WebHosting))
    assertEquals(plan, BrokerPlan.NotNeeded)
    assertEquals(plan.reportedPhase, None)
    assertEquals(
      BrokerProvisioning.topicsToRender(declaring.copy(hosting = "web"), broker, allReady()),
      None
    )
  }

  // features/broker/supplied.feature
  test("a service whose descriptor names a broker is given nothing on the installation's") {
    val plan = decide(wallet.copy(provisionBroker = false), allReady())
    assertEquals(plan, BrokerPlan.Supplied)
    assertEquals(plan.reportedPhase, Some("Supplied"))
    assertEquals(
      BrokerProvisioning.topicsToRender(wallet.copy(provisionBroker = false), broker, allReady()),
      None
    )
  }

  // features/broker/installation.feature: an installation with no broker
  test("a service of an installation with no broker is deployed as it was before") {
    assertEquals(
      BrokerProvisioning.decide(wallet, None, BrokerObservation.empty),
      BrokerPlan.NotNeeded
    )
    assertEquals(BrokerProvisioning.topicsToRender(wallet, None, BrokerObservation.empty), None)
    val failed = BrokerProvisioning.decide(declaring, None, BrokerObservation.empty)
    assertEquals(failed, BrokerPlan.Failed(Vector("the installation has no broker")))
    assertEquals(failed.reportedPhase, Some("Failed"))
  }

  // features/broker/topics.feature: the status outline, row by row
  test("the status says how far the platform has got with a service's topics") {
    val nothingYet = BrokerObservation(resourceCreatedAt = Some(created))
    assertEquals(decide(declaring, nothingYet).reportedPhase, Some("Waiting"))

    val fewerSoFar = allReady().copy(topics =
      allReady().topics.updated(
        "money.transactions",
        topic(6, StrimziObjectState(exists = true, ready = None, createdAt = Some(later)))
      )
    )
    assertEquals(decide(declaring, fewerSoFar).reportedPhase, Some("Waiting"))

    assertEquals(decide(declaring, allReady()), BrokerPlan.Ready(recovered = false))
    assertEquals(decide(declaring, allReady()).reportedPhase, Some("Provisioned"))

    val wontClear = allReady().copy(topics =
      allReady().topics.updated(
        "money.transactions",
        topic(
          12,
          StrimziObjectState(
            exists = true,
            ready = Some(false),
            reason = Some("InvalidRequest"),
            message = Some("Invalid config value for resource ConfigResource"),
            createdAt = Some(later)
          )
        )
      )
    )
    assertEquals(decide(declaring, wontClear).reportedPhase, Some("Failed"))
  }

  test("any other reason for not being ready is waiting, as CNPG's transient states are") {
    val transient = allReady().copy(user =
      StrimziObjectState(exists = true, ready = Some(false), reason = Some("KafkaError"))
    )
    val plan = decide(declaring, transient)
    assert(plan.isInstanceOf[BrokerPlan.Waiting], plan.toString)
    assert(plan.toString.contains("the user"), plan.toString)
  }

  test("a service that declares no topic waits for its user alone, then is provisioned") {
    assertEquals(
      decide(wallet, BrokerObservation(resourceCreatedAt = Some(created))).reportedPhase,
      Some("Waiting")
    )
    assertEquals(
      decide(wallet, BrokerObservation(user = ready(), resourceCreatedAt = Some(created))),
      BrokerPlan.Ready(recovered = false)
    )
  }

  // features/broker/descriptor.feature: never fewer
  test("a topic asked for fewer partitions than it has fails, and is not rendered smaller") {
    val fewer =
      declaring.copy(topics = List(TopicEntry("transactions", 6), TopicEntry("wallet-events", 3)))
    assertEquals(
      decide(fewer, allReady()),
      BrokerPlan.Failed(
        Vector("topic 'money.transactions' has 12 partitions and cannot have fewer; 6 was asked")
      )
    )
    // The rest is still rendered, so a running service keeps the broker.
    assertEquals(
      BrokerProvisioning.topicsToRender(fewer, broker, allReady()),
      Some(Vector(TopicEntry("wallet-events", 3)))
    )
  }

  test("a topic asked for more partitions is rendered, and waits while the broker grows it") {
    val more =
      declaring.copy(topics = List(TopicEntry("transactions", 24), TopicEntry("wallet-events", 3)))
    assertEquals(
      BrokerProvisioning.topicsToRender(more, broker, allReady()),
      Some(more.topics.toVector)
    )
  }

  test("Strimzi's refusal of fewer partitions is a failure, with its own words") {
    val refused = allReady().copy(topics =
      allReady().topics.updated(
        "money.transactions",
        topic(
          12,
          StrimziObjectState(
            exists = true,
            ready = Some(false),
            reason = Some("NotSupported"),
            message = Some("Decreasing partitions not supported")
          )
        )
      )
    )
    assertEquals(
      decide(declaring, refused),
      BrokerPlan.Failed(Vector("topic 'money.transactions': Decreasing partitions not supported"))
    )
  }

  // features/broker/kept.feature
  test("a user and topics made before the resource are recovered") {
    val plan = decide(declaring, allReady(earlier))
    assertEquals(plan, BrokerPlan.Ready(recovered = true))
    assertEquals(plan.reportedPhase, Some("Recovered"))
  }

  test("a service that declares no topic is never reported recovered") {
    assertEquals(
      decide(wallet, BrokerObservation(user = ready(earlier), resourceCreatedAt = Some(created))),
      BrokerPlan.Ready(recovered = false)
    )
  }

  test("one topic made since the resource means the service was not here before") {
    val mixed = allReady(earlier).copy(topics =
      allReady(earlier).topics.updated("money.wallet-events", topic(3, ready(later)))
    )
    assertEquals(decide(declaring, mixed), BrokerPlan.Ready(recovered = false))
  }

  test("the decision is the same when asked twice") {
    assertEquals(decide(declaring, allReady()), decide(declaring, allReady()))
  }
