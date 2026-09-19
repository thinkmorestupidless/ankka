package $package$

import $package$.api.ItemEndpoint
import $package$.application.{ItemEntity, ItemRows}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime}

/**
 * The whole service definition.
 *
 * Registration is explicit — there is no classpath scanning — so this is also the complete
 * inventory of what the service hosts, and a component you forget fails here at startup rather
 * than at its first request. `ProjectionRuntime` is what runs the view: without it every listing
 * stays empty while every write succeeds.
 *
 * Locally this needs Postgres (`sbt schema`, then `docker compose up -d`). In an ankka deployment
 * the platform provides the database and every `ANKKA_*` variable.
 */
@main def run(): Unit =
  val service = Ankka.service
    .register(ItemEntity.descriptor)
    .register(ItemRows.descriptor)
    .withExtension(ProjectionRuntime())
    .withExtension(HttpServer.of(clients => ItemEndpoint(clients.componentClient, clients.viewClient)))
    .start()

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await
    .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
