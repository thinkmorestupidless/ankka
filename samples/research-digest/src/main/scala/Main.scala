import com.thinkmorestupidless.ankka.agent.judgment.JevProvider
import com.thinkmorestupidless.ankka.agent.{AgentRuntime, AnthropicProvider}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime, TimerRuntime}
import digest.api.DigestEndpoint
import digest.application.{Blueprints, EntriesView, PapersEntity, Source}

import java.time.Clock

/**
 * The whole research digest: one entity, one view, the platform's blueprint components, and two
 * blueprints carried as JSON. The watch runs daily and the digest weekly, on the service's timers;
 * the HTTP endpoint is for reading what they wrote and starting a run by hand.
 *
 * Needs Postgres (`docker compose up -d`), `ANTHROPIC_API_KEY`, and the key `JevProvider.fromEnv`
 * reads for the critique step's judgment.
 */
@main def runResearchDigest(): Unit =
  val agents = AgentRuntime
    .withDefaultModel(AnthropicProvider.fromEnv())
    .withJudgments(JevProvider.fromEnv())
    .withBlueprints(context => Blueprints.registry(context, Source.all, Clock.systemUTC()))

  val service = Ankka.service
    .register(PapersEntity.descriptor)
    .register(EntriesView.descriptor)
    .registerAll(AgentRuntime.descriptors)
    .withExtension(agents)
    .withExtension(TimerRuntime())
    .withExtension(ProjectionRuntime())
    .withExtension(HttpServer.of(clients => DigestEndpoint(clients.componentClient, agents)))
    .start()

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await
    .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
