import com.thinkmorestupidless.ankka.agent.{AgentRuntime, AnthropicProvider}
import com.thinkmorestupidless.ankka.agent.providers.ModelProviders
import com.thinkmorestupidless.ankka.grpc.{GrpcClients, GrpcServer}
import com.thinkmorestupidless.ankka.http.{Acl, Callers, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime}
import com.thinkmorestupidless.ankka.mcp.McpRuntime
import shoppingcart.api.{
  CallersEndpoint,
  CartGrpcEndpoint,
  CartKeysEndpoint,
  CartMcpEndpoint,
  CartStreamsEndpoint,
  CheckoutsSeenEndpoint,
  GrpcCallersEndpoint,
  OpenCartsEndpoint,
  QuestionsEndpoint,
  SecretsEndpoint,
  ServiceCallerEndpoint,
  ShoppingCartEndpoint,
  StockHelperEndpoint,
  StorageEndpoint
}
import shoppingcart.application.*

import scala.concurrent.duration.DurationInt

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
    // The client keys the cart's tools admit (feature 054).
    .registerAll(McpRuntime.descriptors)
    // Without this the view's rows are never written, while every command still succeeds.
    // Kafka when ANKKA_KAFKA_BOOTSTRAP_SERVERS names a broker; entity sources only otherwise.
    .withExtension(ProjectionRuntime.fromEnv())

  /**
   * The assistant is registered only when the environment can serve it.
   *
   * An agent needs a model and the model needs a key. A provider built without one starts and fails
   * every call, but an assistant that can only refuse is not one worth offering, and the cluster
   * suites read the cart's components and routes without a key — so without a key the cart runs
   * with no assistant at all.
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
  // `CART_WALLET=on` (feature 040, below) serves the wallet's gRPC service beside the cart's, on
  // the one gRPC port a service has.
  val wallet = sys.env.get("CART_WALLET").contains("on")

  val withGrpc =
    if sys.env.get("CART_GRPC").contains("off") then withNotices
    else if wallet then
      withNotices.withExtension(
        GrpcServer
          .of(
            clients => CartGrpcEndpoint(clients),
            clients => CartStreamsEndpoint(clients),
            clients => shoppingcart.api.WalletGrpcEndpoint(clients)
          )
          .withReflection(Acl.allowCallers(Callers.internet))
      )
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

  /**
   * `CART_CALLING_AGENT=on`, with no model key, registers an agent whose tool calls another service
   * of the project, answered by a model that needs no key (`RelayModel`). It is how the platform's
   * cluster suite shows a tool's call admitted by the called service's ACL, by this service's name.
   */
  val callingAgent =
    sys.env.get("CART_CALLING_AGENT").contains("on") && !sys.env.contains("ANTHROPIC_API_KEY")

  /**
   * A descriptor naming a model provider `local` with the preset `installation` registers
   * [[StockHelper]] on that installation model, with no model key, behind `/helper/ask`.
   * `CART_MODEL_TIMEOUT` (seconds) bounds each model call. It is how the platform's cluster suite
   * shows an agent answered by the installation's model server.
   */
  val installationModel =
    Option
      .when(sys.env.get("ANKKA_MODEL_LOCAL_PRESET").contains("installation"))(
        sys.env.get("ANKKA_MODEL_LOCAL_MODEL")
      )
      .flatten
      .filter(_ => !sys.env.contains("ANTHROPIC_API_KEY"))
  def withHelper(builder: com.thinkmorestupidless.ankka.runtime.ServiceBuilder) =
    installationModel.fold(builder) { model =>
      val timeout = sys.env.get("CART_MODEL_TIMEOUT").map(_.toInt.seconds).getOrElse(10.minutes)
      builder
        .register(StockHelper.descriptor)
        .registerAll(AgentRuntime.descriptors)
        .withExtension(
          AgentRuntime.withDefaultModel(ModelProviders.installation(model, "local"), timeout)
        )
    }

  /**
   * `CART_WALLET=on` adds a player's wallet and an affiliates feed, under granted callers (feature
   * 040): what the platform's cross-project suites deploy as another project's service. The report
   * route authenticates when the descriptor lists issuers (`ANKKA_AUTH_ISSUERS`).
   */
  val report = Option.when(sys.env.get("ANKKA_AUTH_ISSUERS").exists(_.trim.nonEmpty))(
    com.thinkmorestupidless.ankka.auth.oidc.Oidc.authenticate()
  )
  val withWallet = if wallet then withGrpc.register(WalletEntity.descriptor) else withGrpc

  val service = sys.env
    .get("ANTHROPIC_API_KEY")
    .fold(
      if !callingAgent then withHelper(withWallet)
      else
        withWallet
          .register(ServiceCaller.descriptor)
          .registerAll(AgentRuntime.descriptors)
          .withExtension(AgentRuntime.withDefaultModel(RelayModel))
    ) { key =>
      withWallet
        .register(CartAssistant.descriptor)
        .register(CartAnswerer.descriptor)
        .registerAll(AgentRuntime.descriptors)
        .withExtension(AgentRuntime.withDefaultModel(AnthropicProvider.withApiKey(key)))
    }
    .withExtension(
      HttpServer.of(
        Seq[EndpointClients => HttpEndpoint](
          clients => ShoppingCartEndpoint(clients.componentClient),
          clients => OpenCartsEndpoint(clients.viewClient),
          clients => CallersEndpoint(clients.services),
          clients => QuestionsEndpoint(clients.componentClient),
          clients => SecretsEndpoint(clients.secrets),
          clients => StorageEndpoint(clients.storage),
          _ => GrpcCallersEndpoint(grpcClients),
          // The cart's tools, for a customer's assistant, and its keys (feature 054).
          clients => CartMcpEndpoint(clients.componentClient),
          clients => CartKeysEndpoint(clients.clientKeys)
        ) ++ Option.when(brokered)(clients => CheckoutsSeenEndpoint(clients.viewClient)) ++
          Option.when(callingAgent)(clients => ServiceCallerEndpoint(clients.componentClient)) ++
          installationModel.map(_ => clients => StockHelperEndpoint(clients.componentClient)) ++
          Option.when(wallet)(clients =>
            shoppingcart.api.WalletEndpoint(clients.componentClient)
          ) ++
          Option.when(wallet)(_ => shoppingcart.api.AffiliatesEndpoint(report))*
      )
    )
    .withExtension(grpcClients)
    .withExtension(McpRuntime())
    .start()
  // docs:end registration

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await
    .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
