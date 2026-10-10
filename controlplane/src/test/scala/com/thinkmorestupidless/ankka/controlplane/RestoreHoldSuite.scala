package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.ServiceDifference
import com.thinkmorestupidless.ankka.controlplane.deploy.{HeldClient, RestoreHold}
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.crd.{AnkkaProjectSpec, AnkkaServiceSpec, ProjectTopicEntry}

import java.time.Instant

/**
 * A control plane held after its own database was restored (feature 041, research R19): it writes
 * nothing to the cluster, lists what differs, and once released writes as before.
 */
class RestoreHoldSuite extends munit.FunSuite:

  private val restored = Instant.parse("2026-10-08T09:00:00Z")

  private def held() =
    val hold = new RestoreHold(_ => throw IllegalStateException("no database in this suite"))
    hold.marked(RestoreHold.Marker(restored, restored, None, None))
    hold

  private def cart(generation: Long, image: String) =
    AnkkaServiceSpec(
      projectId = "checkout",
      serviceName = "cart",
      generation = generation,
      image = image
    )

  test("held, a service's write is compared with the cluster, recorded, and not made") {
    val cluster = new FakeAnkkaServiceClient
    cluster.put("ankka-checkout", "cart", cart(2, "cart:2"))
    val hold   = held()
    val client = HeldClient(cluster, hold)
    val before = cluster.writeCount
    client.put("ankka-checkout", "cart", cart(1, "cart:1"))
    assertEquals(cluster.writeCount, before, "nothing written")
    assertEquals(cluster.list().head.spec.image, "cart:2", "the cluster keeps what it runs")
    assertEquals(
      hold.status.services,
      Vector(
        ServiceDifference("checkout", "cart", Some(1L), Some(2L), Some("cart:1"), Some("cart:2"))
      )
    )
    // A write that matches the cluster is no difference.
    client.put("ankka-checkout", "cart", cart(2, "cart:2"))
    assertEquals(hold.status.services, Vector.empty)
  }

  test("held, a delete removes nothing, and the service is listed as one the cluster still runs") {
    val cluster = new FakeAnkkaServiceClient
    cluster.put("ankka-checkout", "cart", cart(2, "cart:2"))
    val hold = held()
    HeldClient(cluster, hold).delete("ankka-checkout", "cart")
    assertEquals(cluster.deleteCount, 0)
    assertEquals(hold.status.services.map(_.recordedGeneration), Vector(None))
  }

  test("held, a project's write is not made, and differing topics are listed") {
    val cluster = new FakeAnkkaServiceClient
    val hold    = held()
    val spec    = AnkkaProjectSpec("checkout", List(ProjectTopicEntry("orders", 3, "")))
    HeldClient(cluster, hold).putProject("ankka-checkout", "checkout", spec)
    assertEquals(cluster.projectWriteCount, 0)
    assertEquals(hold.status.topics, Vector("checkout"), "the cluster holds none of its topics")
  }

  test("held, a credential or a secret is refused as unavailable rather than recorded") {
    val client = HeldClient(new FakeAnkkaServiceClient, held())
    val refusal =
      intercept[CommandError](client.setSecretEntries("ankka-checkout", "api", Map("k" -> "v")))
    assertEquals(refusal.code, ErrorCode.Unavailable)
    assert(refusal.getMessage.contains("releases"), refusal.getMessage)
  }

  test("released, every write is made, and the release says who and when") {
    val cluster = new FakeAnkkaServiceClient
    val hold    = held()
    val client  = HeldClient(cluster, hold)
    val status  = hold.release("root")
    assert(!status.held)
    assertEquals(status.releasedBy, Some("root"))
    client.put("ankka-checkout", "cart", cart(1, "cart:1"))
    assertEquals(cluster.list().map(_.spec.image), Vector("cart:1"))
    assertEquals(hold.status.services, Vector.empty)
  }

  test("without a marker nothing is held") {
    assert(!RestoreHold.none.held)
  }
