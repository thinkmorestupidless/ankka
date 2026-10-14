package com.thinkmorestupidless.ankka.testkit.replication

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.replication.{
  LoopbackAnswer,
  LoopbackCall,
  LoopbackReport
}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import org.apache.pekko.serialization.SerializationExtension

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.*

/**
 * The two routes a replicating service's local console endpoint serves to a sibling running beside
 * it on this machine (T051): a forwarded call reaches the entity through sharding and its reply
 * comes back in the runtime's serialized form; a body that is not one is refused; a report is
 * accepted; and a service that does not replicate serves neither route.
 */
class ReplicationRoutesSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private val vehicles = Fleet.vehicles
  private val http     = HttpClient.newHttpClient()

  private def post(kit: AnkkaTestKit, path: String, body: Array[Byte]): HttpResponse[Array[Byte]] =
    val address = kit.service.observabilityAddress.getOrElse(fail("no local console endpoint"))
    http.send(
      HttpRequest
        .newBuilder(URI(address + path))
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build(),
      HttpResponse.BodyHandlers.ofByteArray()
    )

  private def serialized(kit: AnkkaTestKit, value: AnyRef): Array[Byte] =
    SerializationExtension(kit.service.system.classicSystem).serialize(value).get

  test("a forwarded call is handled by the entity and its reply comes back serialized") {
    val fleet = AnkkaTestKit.installations("rr-a", "rr-b")
    try
      val a = fleet.start("rr-a", Seq(vehicles.descriptor))
      a.componentClient
        .forEventSourcedEntity(EntityId("v1"))
        .call(vehicles.createVehicle)
        .invoke(CreateVehicle())
      val call     = LoopbackCall("add-step", "forwarded".getBytes("UTF-8"), Vector.empty)
      val response = post(a, "/replication/forward/vehicle/v1", serialized(a, call))
      assertEquals(response.statusCode, 200)
      val answer = SerializationExtension(a.service.system.classicSystem)
        .deserialize(response.body, classOf[LoopbackAnswer])
        .get
      assert(answer.succeeded, answer.message)
      assert(String(answer.payload, "UTF-8").contains("\"add-step\""), String(answer.payload))
      // The call reached the entity: the step is in its state.
      val vehicle =
        a.componentClient.forEventSourcedEntity(EntityId("v1")).call(vehicles.get).invoke()
      assertEquals(vehicle.steps, Vector("forwarded"))

      // A refusal comes back as one, with its code.
      val unknown = LoopbackCall("no-such-handler", Array.emptyByteArray, Vector.empty)
      val refused = SerializationExtension(a.service.system.classicSystem)
        .deserialize(
          post(a, "/replication/forward/vehicle/v1", serialized(a, unknown)).body,
          classOf[LoopbackAnswer]
        )
        .get
      assert(!refused.succeeded, refused.toString)

      // A body that is not a forwarded call, and a path that names no entity.
      assertEquals(
        post(a, "/replication/forward/vehicle/v1", "nonsense".getBytes("UTF-8")).statusCode,
        400
      )
      assertEquals(post(a, "/replication/forward/vehicle", serialized(a, call)).statusCode, 404)

      // A handover's target reporting its position is accepted; a body that is not one is not.
      val report = serialized(a, LoopbackReport("rr-b", 2L))
      assertEquals(post(a, "/replication/report/vehicle/v1", report).statusCode, 204)
      assertEquals(
        post(a, "/replication/report/vehicle/v1", "nonsense".getBytes("UTF-8")).statusCode,
        400
      )
    finally fleet.stopAll()
  }

  test("a service that does not replicate serves neither route") {
    val kit = AnkkaTestKit.start(Seq(vehicles.descriptor))
    try
      val call = serialized(kit, LoopbackCall("get", Array.emptyByteArray, Vector.empty))
      assertEquals(post(kit, "/replication/forward/vehicle/v1", call).statusCode, 404)
      assertEquals(
        post(
          kit,
          "/replication/report/vehicle/v1",
          serialized(kit, LoopbackReport("x", 1L))
        ).statusCode,
        404
      )
    finally kit.stop()
  }
