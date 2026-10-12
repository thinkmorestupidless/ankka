package com.thinkmorestupidless.ankka.testkit.replication

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{Codecs, EntityId}
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

// docs:start fleet-endpoint
/**
 * The fleet's routes: a vehicle created with the installations it is replicated to, a step, and a
 * read. A command sent to an installation that is not the vehicle's home is forwarded to the home
 * by the component client, so the routes are the same at every installation.
 */
final class FleetEndpoint(clients: EndpointClients) extends HttpEndpoint("/vehicles"):
  import FleetEndpoint.given
  val acl: Acl = Acl.AllowAll
  private def vehicle(id: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(id))
  postBody("/{id}") { (id: String, input: CreateVehicle) =>
    vehicle(id).call(Fleet.vehicles.createVehicle).invoke(input)
  }
  postBody("/{id}/steps") { (id: String, step: String) =>
    vehicle(id).call(Fleet.vehicles.addStep).invoke(step)
  }
  get("/{id}")((id: String) => vehicle(id).call(Fleet.vehicles.get).invoke())
// docs:end fleet-endpoint

object FleetEndpoint:
  given JsonValueCodec[CreateVehicle] = Codecs.make[CreateVehicle]
  given JsonValueCodec[Receipt]       = Codecs.make[Receipt]
  given JsonValueCodec[Vehicle]       = Codecs.make[Vehicle]

/**
 * The fleet as a service of its own, for two installations on one machine (the quickstart): which
 * installation this is from `ANKKA_INSTALLATION`, the others' journals from
 * `ANKKA_INSTALLATION_<NAME>_DB_*`, its own database from `ANKKA_DB_*`, and its port from
 * `ANKKA_HTTP_PORT`, as every ankka service reads them.
 */
@main def FleetMain(): Unit =
  val service = Ankka.service
    .register(Fleet.vehicles.descriptor)
    .register(FloorKit(Fleet.vehicles).descriptor)
    .withExtension(ProjectionRuntime.fromEnv())
    .withExtension(HttpServer.of(clients => FleetEndpoint(clients)))
    .start()
  sys.addShutdownHook(service.terminate())
  Await.result(service.whenTerminated, Duration.Inf): Unit
