import nakka.agent.{AgentRuntime, AnthropicProvider}
import nakka.http.HttpServer
import nakka.runtime.Nakka
import planner.api.PlannerEndpoint
import planner.application.*

/**
 * The whole multi-agent service.
 *
 * Registration is explicit, so this is also the complete inventory: four agents, one
 * entity, one workflow, session memory, and the extensions that host them.
 *
 * Needs Postgres (`docker compose up -d`) and `ANTHROPIC_API_KEY`.
 */
@main def runPlanner(): Unit =
  val model = AnthropicProvider.fromEnv()

  val service = Nakka.service
    .register(PreferencesEntity.descriptor)
    .register(PlannerWorkflow.descriptor)
    .register(SelectorAgent.descriptor)
    .register(WeatherAgent.descriptor)
    .register(ActivityAgent.descriptor)
    .register(BudgetAgent.descriptor)
    .registerAll(AgentRuntime.descriptors)
    .register(SummaryAgent.descriptor)
    .withExtension(AgentRuntime.withDefaultModel(model))
    .withExtension(HttpServer.of(PlannerEndpoint(_)))
    .start()

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
