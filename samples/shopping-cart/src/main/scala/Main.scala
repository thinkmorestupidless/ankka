import com.thinkmorestupidless.ankka.agent.{AgentRuntime, AnthropicProvider}
import com.thinkmorestupidless.ankka.grpc.{GrpcClients, GrpcServer}
import com.thinkmorestupidless.ankka.http.{Acl, Callers, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime}
import shoppingcart.api.{
  CallersEndpoint,
  CartGrpcEndpoint,
  CartStreamsEndpoint,
  CheckoutsSeenEndpoint,
  GrpcCallersEndpoint,
  QuestionsEndpoint,
  ShoppingCartEndpoint
}
import shoppingcart.application.*

/**
 * The whole service definition.
 *
 * Registration is explicit, so this is also the complete inventory of what the service hosts —
 * there is nothing discovered by scanning at startup.
 *
 * Needs Postgres: `docker compose up -d`. A model key is optional, and only the assistant and the
 * answerer need it; without one, `/questions` answers not found.
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
   *
   * The same goes for the carts as a graph: with a broker, every change to a cart is published to
   * `cart-graph` as graph deltas, which an ankka-flow pipeline of the built-in merge sink alone
   * writes into a graph database (`samples/shopping-cart/graph`).
   */
  val withNotices = sys.env
    .get(ProjectionRuntime.KafkaEnvVar)
    .filter(_.trim.nonEmpty)
    .fold(base) { _ =>
      base
        .register(CheckoutNotifier.descriptor)
        .register(CartGraph.descriptor)
        .register(CartContentsGraph.descriptor)
        // Reads back the topic the notices go to: this service's own, or, under
        // CART_CHECKOUTS_TOPIC, a topic another service of the project publishes to.
        .register(CheckoutsSeen.descriptor)
    }
  val brokered = sys.env.get(ProjectionRuntime.KafkaEnvVar).exists(_.trim.nonEmpty)

  /**
   * The gRPC API, on its own port beside HTTP (9090 unless `ANKKA_GRPC_PORT` says otherwise).
   *
   * `CART_GRPC=off` leaves it out. That is not something a real service wants; it is how the
   * platform's own tests deploy a service whose descriptor declares gRPC and that serves none, to
   * see the platform report it.
   */
  val withGrpc =
    if sys.env.get("CART_GRPC").contains("off") then withNotices
    else
      // docs:start grpc-registration
      val server = GrpcServer.of(
        clients => CartGrpcEndpoint(clients),
        clients => CartStreamsEndpoint(clients)
      )
      // docs:end grpc-registration
      // docs:start reflection
      // A tool such as grpcurl may ask what the cart serves: from this machine, and through the
      // gateway when the cart is exposed. CART_REFLECTION=off leaves it out.
      val reflecting =
        if sys.env.get("CART_REFLECTION").contains("off") then server
        else server.withReflection(Acl.allowCallers(Callers.internet))
      // docs:end reflection
      // A deployment that sets CART_REFLECTION_CALLER admits that one service to reflection, and
      // nobody else: how the platform's own tests see reflection answered at the gRPC address, judged
      // by the calling service's certificate.
      val admittingCaller = sys.env.get("CART_REFLECTION_CALLER").filter(_.nonEmpty) match
        case Some(only) if !sys.env.get("CART_REFLECTION").contains("off") =>
          server.withReflection(Acl.allowCallers(Callers.service(only)))
        case _ => reflecting
      withNotices.withExtension(admittingCaller)

  // Channels to other services' gRPC endpoints, created once and handed to what calls them.
  // docs:start grpc-clients
  val grpcClients = GrpcClients()
  // docs:end grpc-clients

  val service = sys.env
    .get("ANTHROPIC_API_KEY")
    .fold(withGrpc) { key =>
      withGrpc
        .register(CartAssistant.descriptor)
        .register(CartAnswerer.descriptor)
        .registerAll(AgentRuntime.descriptors)
        .withExtension(AgentRuntime.withDefaultModel(AnthropicProvider.withApiKey(key)))
    }
    .withExtension(
      HttpServer.of(
        Seq[EndpointClients => HttpEndpoint](
          clients => ShoppingCartEndpoint(clients.componentClient),
          clients => CallersEndpoint(clients.services),
          clients => QuestionsEndpoint(clients.componentClient),
          _ => GrpcCallersEndpoint(grpcClients)
        ) ++ Option.when(brokered)(clients => CheckoutsSeenEndpoint(clients.viewClient))*
      )
    )
    .withExtension(grpcClients)
    .start()
  // docs:end registration

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await
    .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
