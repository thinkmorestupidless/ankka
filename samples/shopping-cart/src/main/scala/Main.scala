import com.thinkmorestupidless.ankka.agent.{AgentRuntime, AnthropicProvider}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime}
import shoppingcart.api.{CallersEndpoint, ShoppingCartEndpoint}
import shoppingcart.application.*

/**
 * The whole service definition.
 *
 * Registration is explicit, so this is also the complete inventory of what the service hosts —
 * there is nothing discovered by scanning at startup.
 *
 * Needs Postgres: `docker compose up -d`. A model key is optional, and only the assistant needs it.
 */
@main def runShoppingCart(): Unit =
  // docs:start registration
  val base = Ankka.service
    .register(ShoppingCartEntity.descriptor)
    .register(CheckoutLog.descriptor)
    .register(CheckoutWorkflow.descriptor)
    .register(CartRows.descriptor)
    // Without this the view's rows are never written, while every command still succeeds.
    // Kafka when ANKKA_KAFKA_BOOTSTRAP_SERVERS names a broker; entity sources only otherwise.
    .withExtension(ProjectionRuntime.fromEnv())

  /**
   * The assistant is registered only when the environment can serve it.
   *
   * An agent needs a model and the model needs a key, and `AnthropicProvider.fromEnv()` fails if
   * there is none. Making that a startup requirement would mean no one could run the cart, and no
   * cluster test could deploy it, without an API key — so the cart runs without an assistant rather
   * than not running at all.
   */
  /**
   * The checkout notices are published only when there is a broker to publish them to.
   *
   * A consumer that produces is refused at startup without one, so registering it unconditionally
   * would make Kafka a requirement for running the cart at all. Set `ANKKA_KAFKA_BOOTSTRAP_SERVERS`
   * in the descriptor's `env` and every checkout is published to `cart-checkouts`, where something
   * outside the service — an ankka-flow pipeline — can read it.
   */
  val withNotices = sys.env
    .get(ProjectionRuntime.KafkaEnvVar)
    .filter(_.trim.nonEmpty)
    .fold(base)(_ => base.register(CheckoutNotifier.descriptor))

  val service = sys.env
    .get("ANTHROPIC_API_KEY")
    .fold(withNotices) { key =>
      withNotices
        .register(CartAssistant.descriptor)
        .registerAll(AgentRuntime.descriptors)
        .withExtension(AgentRuntime.withDefaultModel(AnthropicProvider.withApiKey(key)))
    }
    .withExtension(
      HttpServer.of(
        clients => ShoppingCartEndpoint(clients.componentClient),
        clients => CallersEndpoint(clients.services)
      )
    )
    .start()
  // docs:end registration

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await
    .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
