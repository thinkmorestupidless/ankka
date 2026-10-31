package {{package}}.{{service_pkg}}

import {{package}}.{{service_pkg}}.api.{{Service}}Endpoint
import {{package}}.{{service_pkg}}.application.{ItemEntity, ItemRows, {{First}}Reader}
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{EndpointClients, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime, RuntimeExtension}

/**
 * Everything {{service}} hosts, said once: `start` runs it, and the project's tests module starts the
 * same descriptors and endpoint under a test kit, beside the project's other services.
 *
 * Registration is explicit, so this is the complete inventory of what the service hosts. `{{First}}Reader` reads the declared
 * topic `{{first}}-events`, a crossing of the project's context map.
 */
object {{Service}}Service:

  val descriptors: Seq[ComponentDescriptor] =
    Seq(ItemEntity.descriptor, ItemRows.descriptor, {{First}}Reader.descriptor)

  def endpoint(clients: EndpointClients): {{Service}}Endpoint = {{Service}}Endpoint(clients)

  /**
   * What the service runs beside its components: `projections` runs its views and consumers and
   * reaches the broker, `http` serves its endpoint. A test hands it a broker in memory and a server
   * on a port of its own; `start` hands it the environment's.
   */
  def extensions(projections: ProjectionRuntime, http: HttpServer): Seq[RuntimeExtension] =
    Seq(projections, http)

  /**
   * Runs the service until it is stopped. It starts under its own name, which is how the local console
   * lists it and how another service of the project finds it. `ProjectionRuntime.fromEnv()` reads the
   * broker from the environment: `scripts/run.sh {{service}}` names the project's Kafka, and a deployed
   * service is told of the installation's.
   */
  def start(): Unit =
    val service = extensions(ProjectionRuntime.fromEnv(), HttpServer.of(endpoint))
      .foldLeft(Ankka.service.registerAll(descriptors))(_.withExtension(_))
      .start(name = "{{service}}")
    sys.addShutdownHook(service.terminate())
    scala.concurrent.Await
      .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
