package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.*
import com.thinkmorestupidless.ankka.core.{EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import io.grpc.stub.ClientCalls
import io.grpc.{CallOptions, ManagedChannel, MethodDescriptor, Status, StatusRuntimeException}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.util.Try

/**
 * Every feature under `features/grpc/`, run as written: the behaviour of one service's gRPC
 * endpoints, which one process can show. What needs a cluster is under `features/grpc-deployed/`
 * and runs in the control plane's k3s suite.
 *
 * A feature not yet implemented is tagged `@ignore`, which reports its scenarios ignored rather
 * than passed; the task that implements a file removes its tag.
 *
 * One service on one throwaway Postgres serves the whole suite, with a gRPC server registered as
 * the test kit registers any extension — which is the testing feature's own scenario. Every other
 * scenario starts servers of its own over that service, so what one scenario declares cannot leak
 * into the next.
 */
class GrpcFeatures extends GherkinSuite("../../features/grpc") with LogCapturing:

  override val munitTimeout = 3.minutes

  // ── the world each scenario builds ────────────────────────────────────────────

  private enum Shape:
    case Complete, Twice, Unregistered
    case Missing(method: String)

  @volatile private var shape: Shape                         = Shape.Complete
  @volatile private var withHttp                             = false
  @volatile private var refusal: Option[(ErrorCode, String)] = None
  @volatile private var failure: Option[String]              = None
  @volatile private var useRegistered                        = false
  private val handled                                        = AtomicInteger()

  private var started: Vector[AutoCloseable]       = Vector.empty
  private var grpcPort: Option[Int]                = None
  private var httpPort: Option[Int]                = None
  private var startFailure: Option[Throwable]      = None
  private var outcome: Option[Either[Status, Any]] = None

  override def beforeEach(context: BeforeEach): Unit =
    shape = Shape.Complete
    withHttp = false
    refusal = None
    failure = None
    useRegistered = false
    handled.set(0)
    grpcPort = None
    httpPort = None
    startFailure = None
    outcome = None

  override def afterEach(context: AfterEach): Unit =
    started.reverse.foreach(c => Try(c.close()))
    started = Vector.empty

  // ── the service the suite shares ──────────────────────────────────────────────

  /** A cart endpoint over `FixtureCart`, behaving as the running scenario says. */
  private final class CartEndpoint(clients: EndpointClients, omit: Option[String] = None)
      extends GrpcEndpoint(CartServiceGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll

    private def cart(id: String) = clients.componentClient.forKeyValueEntity(EntityId(id))

    if !omit.contains("GetCart") then
      unary(CartServiceGrpc.METHOD_GET_CART) { request =>
        handled.incrementAndGet()
        val state = cart(request.cartId).call(FixtureCart.get).invoke()
        Cart(request.cartId, state.items.map(Item(_, 1)))
      }
    if !omit.contains("AddItem") then
      unary(CartServiceGrpc.METHOD_ADD_ITEM) { request =>
        handled.incrementAndGet()
        failure.foreach(message => throw RuntimeException(message))
        val state = refusal match
          case Some((code, message)) =>
            cart(request.cartId).call(FixtureCart.refuse).invoke(s"$code|$message")
          case None => cart(request.cartId).call(FixtureCart.add).invoke(request.product)
        Cart(request.cartId, state.items.map(Item(_, 1)))
      }

  private final class Ping extends HttpEndpoint("/ping"):
    val acl: Acl = Acl.AllowAll
    get("/")(() => "pong")

  private val registered = GrpcServer.at("127.0.0.1", 0)(clients => CartEndpoint(clients))
  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    super.beforeAll()
    testKit = AnkkaTestKit.start(Seq(FixtureCart.descriptor), Seq(registered))

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()
    super.afterAll()

  // ── what a step does ──────────────────────────────────────────────────────────

  private def start(): Unit =
    if useRegistered then grpcPort = registered.boundPort
    else if grpcPort.isEmpty && startFailure.isEmpty then
      val factories: Seq[EndpointClients => GrpcEndpoint] = shape match
        case Shape.Complete        => Seq(CartEndpoint(_))
        case Shape.Missing(method) => Seq(CartEndpoint(_, Some(method)))
        case Shape.Twice           => Seq(CartEndpoint(_), CartEndpoint(_))
        case Shape.Unregistered    => Seq.empty
      val server = GrpcServer.at("127.0.0.1", 0)(factories*)
      try
        server.start(testKit.service)
        started :+= (() => server.stop())
        grpcPort = server.boundPort
      catch case e: Throwable => startFailure = Some(e)
      if withHttp then
        val http = HttpServer.at("127.0.0.1", 0)(_ => Ping())
        http.start(testKit.service)
        started :+= (() => http.stop())
        httpPort = http.boundPort

  private def channel(): ManagedChannel =
    start()
    val port =
      grpcPort.getOrElse(fail(s"the service did not start: ${startFailure.map(_.getMessage)}"))
    val c = GrpcChannels.plaintext(port)
    started :+= (() => c.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit)
    c

  private def methodOf(definition: String, method: String): MethodDescriptor[Any, Any] =
    val descriptors: Map[String, io.grpc.ServiceDescriptor] = Map(
      "CartService"  -> CartServiceGrpc.SERVICE,
      "OrderService" -> OrderServiceGrpc.SERVICE,
      "CartStreams"  -> CartStreamsGrpc.SERVICE
    )
    val service =
      descriptors.getOrElse(definition, fail(s"no fixture service definition '$definition'"))
    service.getMethods.toArray.toVector
      .collect { case m: MethodDescriptor[?, ?] => m.asInstanceOf[MethodDescriptor[Any, Any]] }
      .find(_.getBareMethodName == method)
      .getOrElse(fail(s"'$definition' has no method '$method'"))

  private def requestFor(method: String): Any = method match
    case "GetCart"  => GetCartRequest(s"cart-$scenarioId")
    case "AddItem"  => AddItemRequest(s"cart-$scenarioId", "Widget", 1)
    case "GetOrder" => GetOrderRequest("o1")
    case other      => fail(s"no request fixture for '$other'")

  private def call(definition: String, method: String): Unit =
    val descriptor = methodOf(definition, method)
    outcome = Some(
      try
        Right(
          ClientCalls.blockingUnaryCall(
            channel(),
            descriptor,
            CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS),
            requestFor(method)
          )
        )
      catch case e: StatusRuntimeException => Left(e.getStatus)
    )

  private def statusOf(name: String): Status.Code =
    Status.Code.valueOf(name.toUpperCase.replace(' ', '_'))

  private def endedWith: Status = outcome match
    case Some(Right(_))     => Status.OK
    case Some(Left(status)) => status
    case None               => fail("no call was made")

  private val refusals: Map[String, ErrorCode] = Map(
    "bad request"  -> ErrorCode.BadRequest,
    "unauthorized" -> ErrorCode.Unauthorized,
    "forbidden"    -> ErrorCode.Forbidden,
    "not found"    -> ErrorCode.NotFound,
    "conflict"     -> ErrorCode.Conflict,
    "timeout"      -> ErrorCode.Timeout,
    "unavailable"  -> ErrorCode.Unavailable,
    "internal"     -> ErrorCode.Internal
  )

  // ── serving.feature ───────────────────────────────────────────────────────────

  Given("a service {string} with a gRPC endpoint for the service definition {string}") {
    (_: String, _: String) => shape = Shape.Complete
  }
  Given("the gRPC endpoint declares a handler for the method {string}")((_: String) => ())
  Given("the gRPC endpoint declares no handler for the method {string}") { (method: String) =>
    shape = Shape.Missing(method)
  }
  Given(
    "a service {string} with an HTTP endpoint and a gRPC endpoint for the service definition {string}"
  ) { (_: String, _: String) =>
    withHttp = true
  }
  Given(
    "a gRPC endpoint for the service definition {string} that is not registered with the service {string}"
  ) { (_: String, _: String) =>
    shape = Shape.Unregistered
  }
  Given("a service {string} with two gRPC endpoints for the service definition {string}") {
    (_: String, _: String) => shape = Shape.Twice
  }
  When("a developer calls the method {string} of {string}") { (method: String, definition: String) =>
    call(definition, method)
  }
  When("a developer starts the service {string}")((_: String) => start())
  Then("the handler for the method {string} runs")((_: String) => assertEquals(handled.get, 1))
  Then("the call ends with the status {string} and the handler's answer") { (status: String) =>
    assertEquals(endedWith.getCode, statusOf(status))
    assertEquals(
      outcome.flatMap(_.toOption).collect { case c: Cart => c.cartId },
      Some(s"cart-$scenarioId")
    )
  }
  Then("the service {string} serves the methods of {string}") { (_: String, definition: String) =>
    call(definition, "GetCart")
    assertEquals(endedWith.getCode, Status.Code.OK)
  }
  Then("the service {string} serves the routes of the HTTP endpoint") { (_: String) =>
    val port = httpPort.getOrElse(fail("no HTTP server was started"))
    val response = HttpClient
      .newHttpClient()
      .send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/ping")).build(),
        HttpResponse.BodyHandlers.ofString()
      )
    assertEquals(response.statusCode, 200)
  }
  Then("the call ends with the status {string}") { (status: String) =>
    assertEquals(endedWith.getCode, statusOf(status), endedWith.toString)
  }
  Then("the service {string} does not start") { (_: String) =>
    assert(startFailure.isDefined, "the service started")
  }
  Then("the service {string} says that the method {string} has no handler") {
    (_: String, method: String) =>
      val message = startFailure.map(_.getMessage).getOrElse(fail("the service started"))
      assert(message.contains(s"/$method' with no handler"), message)
  }
  Then("the service {string} says that {string} has two gRPC endpoints") {
    (_: String, definition: String) =>
      val message = startFailure.map(_.getMessage).getOrElse(fail("the service started"))
      assert(
        message.contains(
          s"2 endpoints implement the service definition 'ankka.fixtures.v1.$definition'"
        ),
        message
      )
  }

  // ── statuses.feature ──────────────────────────────────────────────────────────

  Given("a gRPC endpoint whose handler for the method {string} calls a component") { (_: String) =>
    ()
  }
  Given("the component answers with the refusal {string} and the message {string}") {
    (name: String, message: String) =>
      refusal = Some(refusals.getOrElse(name, fail(s"no refusal '$name'")) -> message)
  }
  When("a developer calls the method {string}")((method: String) => call("CartService", method))
  Then("the call ends with the status {string} and the message {string}") {
    (status: String, message: String) =>
      assertEquals(endedWith.getCode, statusOf(status), endedWith.toString)
      assertEquals(endedWith.getDescription, message)
  }
  Given("a gRPC endpoint whose handler for the method {string} fails with the message {string}") {
    (_: String, message: String) => failure = Some(message)
  }
  Then("the call does not end with the message {string}") { (message: String) =>
    assert(!Option(endedWith.getDescription).exists(_.contains(message)), endedWith.toString)
  }
  Given("a gRPC endpoint that declares a handler for the method {string}")((_: String) => ())
  When("a developer calls the method {string} with a request the gRPC endpoint cannot read") {
    (method: String) =>
      // A length-delimited first field whose length runs past the end of the message.
      val unreadable =
        methodOf("CartService", method).toBuilder(Binding.bytes, Binding.bytes).build()
      outcome = Some(
        try
          Right(
            ClientCalls.blockingUnaryCall(
              channel(),
              unreadable,
              CallOptions.DEFAULT,
              Array[Byte](0x0a, 0x7f)
            )
          )
        catch case e: StatusRuntimeException => Left(e.getStatus)
      )
  }
  Then("no handler runs")(() => assertEquals(handled.get, 0))

  // ── testing.feature ───────────────────────────────────────────────────────────

  Given("a test that starts the service {string} with the test kit") { (_: String) =>
    useRegistered = true
  }
  Given(
    "the service {string} has a gRPC endpoint whose handler for the method {string} calls a component"
  ) { (_: String, _: String) =>
    ()
  }
  When("the test calls the method {string}") { (method: String) =>
    // Written through the component, so the answer can only have come from it.
    val _ = testKit.componentClient
      .forKeyValueEntity(EntityId(s"cart-$scenarioId"))
      .call(FixtureCart.add)
      .invoke("written-by-the-test")
    call("CartService", method)
  }
  Then("the call is answered by the component of the service {string}") { (_: String) =>
    val items = outcome.flatMap(_.toOption).collect { case c: Cart => c.items.map(_.product) }
    assertEquals(items, Some(Seq("written-by-the-test")))
  }
