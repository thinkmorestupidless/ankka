package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.AnkkaServiceSpec

import java.time.Instant

/**
 * A service's credential on the installation's broker, every rule with no cluster:
 * `BrokerProvisioning.decide` takes what the operator observed as a plain value. Cases named for a
 * scenario hold that scenario.
 */
class BrokerProvisioningSuite extends munit.FunSuite:

  private val broker = Some(BrokerStack.settings)
  private val wallet =
    AnkkaServiceSpec(projectId = "money", serviceName = "wallet", generation = 1L)

  private val created = Instant.parse("2026-10-04T12:00:00Z")
  private val earlier = created.minusSeconds(3600)
  private val later   = created.plusSeconds(60)

  private def ready(at: Instant) =
    StrimziObjectState(exists = true, ready = Some(true), createdAt = Some(at))

  private def seen(user: StrimziObjectState) =
    BrokerObservation(user = user, resourceCreatedAt = Some(created))

  private def decide(spec: AnkkaServiceSpec, observed: BrokerObservation) =
    BrokerProvisioning.decide(spec, broker, observed)

  // features/broker/topics.feature
  test("a web-hosted service is given nothing of the installation's broker") {
    val web = wallet.copy(hosting = Rendering.WebHosting)
    assertEquals(decide(web, seen(ready(later))), BrokerPlan.NotNeeded)
    assertEquals(BrokerPlan.NotNeeded.reportedPhase, None)
    assert(!BrokerProvisioning.known(web, broker))
  }

  // features/broker/supplied.feature
  test("a service whose descriptor names a broker is given nothing on the installation's") {
    val supplied = wallet.copy(provisionBroker = false)
    val plan     = decide(supplied, seen(ready(later)))
    assertEquals(plan, BrokerPlan.Supplied)
    assertEquals(plan.reportedPhase, Some("Supplied"))
    assert(!BrokerProvisioning.known(supplied, broker))
  }

  // features/broker/installation.feature
  test("a service of an installation with no broker is deployed as it was before") {
    val plan = BrokerProvisioning.decide(wallet, None, BrokerObservation.empty)
    assertEquals(plan, BrokerPlan.NotNeeded)
    assertEquals(plan.reportedPhase, None)
    assert(!BrokerProvisioning.known(wallet, None))
  }

  // features/broker/topics.feature: the outline, row by row
  test("the status says how far the platform has got with a service's credential") {
    assertEquals(decide(wallet, seen(StrimziObjectState.absent)).reportedPhase, Some("Waiting"))
    assertEquals(decide(wallet, seen(ready(later))), BrokerPlan.Ready(recovered = false))
    assertEquals(decide(wallet, seen(ready(later))).reportedPhase, Some("Provisioned"))
  }

  test("a credential the broker has not yet reported on waits") {
    val unreported = StrimziObjectState(exists = true, ready = None, createdAt = Some(later))
    assertEquals(
      decide(wallet, seen(unreported)),
      BrokerPlan.Waiting(Some("waiting for the broker to make the user"))
    )
  }

  test("any other reason for not being ready is waiting, as CNPG's transient states are") {
    val transient =
      StrimziObjectState(exists = true, ready = Some(false), reason = Some("KafkaError"))
    assert(decide(wallet, seen(transient)).isInstanceOf[BrokerPlan.Waiting])
  }

  test("a credential refused for a reason that will not clear is a failure, in Strimzi's words") {
    val refused = StrimziObjectState(
      exists = true,
      ready = Some(false),
      reason = Some("InvalidRequest"),
      message = Some("Invalid ACL")
    )
    assertEquals(decide(wallet, seen(refused)), BrokerPlan.Failed(Vector("user: Invalid ACL")))
  }

  // features/broker/kept.feature
  test("a service deployed again finds its credential and its topic") {
    val plan = decide(wallet, seen(ready(earlier)))
    assertEquals(plan, BrokerPlan.Ready(recovered = true))
    assertEquals(plan.reportedPhase, Some("Recovered"))
  }

  test("the decision is the same when asked twice") {
    assertEquals(decide(wallet, seen(ready(later))), decide(wallet, seen(ready(later))))
  }
