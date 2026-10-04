package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.*
import com.thinkmorestupidless.ankka.core.{EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.{
  Acl,
  AuthDecision,
  Caller,
  Callers,
  EndpointClients,
  HttpEndpoint,
  HttpServer,
  Principal
}
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testpki.TestPki
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import io.grpc.stub.ClientCalls
import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.runtime.{Observability, RecordedSpan, SpanOutcome}
import com.thinkmorestupidless.ankka.sdk.{
  ServiceIdentityMismatch,
  ServiceServesNoGrpc,
  ServiceUnresolvable
}
import com.typesafe.config.ConfigFactory
import io.grpc.ClientCall
import io.grpc.reflection.v1.{
  ServerReflectionGrpc,
  ServerReflectionRequest,
  ServerReflectionResponse
}
import io.grpc.stub.StreamObserver
import java.net.InetSocketAddress
import scala.jdk.CollectionConverters.*
import io.grpc.stub.MetadataUtils
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, Promise}
import io.grpc.{
  CallOptions,
  Channel,
  ClientInterceptors,
  Metadata,
  MethodDescriptor,
  Status,
  StatusRuntimeException
}

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
  @volatile private var failure: Option[(String, String)]    = None
  @volatile private var useRegistered                        = false
  private val handled                                        = AtomicInteger()

  // Who may call, as the scenario states it.
  @volatile private var endpointAcl: Acl                  = Acl.AllowAll
  @volatile private var methodAcls: Map[String, Acl]      = Map.empty
  @volatile private var answer: String                    = "allow"
  @volatile private var establishes: String               = "someone"
  @volatile private var sending: Vector[(String, String)] = Vector.empty
  // Deployed: the server serves mutual TLS and a caller is who its certificate names.
  @volatile private var deployed               = false
  @volatile private var deployedCaller: String = "checkout"

  // Streams, as the scenario describes them.
  private enum Importing:
    case Once
    case ReadThenWait(parts: Int)
    case RefuseAfter(code: ErrorCode, parts: Int)

  @volatile private var watchParts: Option[Int]            = Some(3)
  @volatile private var watchEvery: Option[FiniteDuration] = None
  @volatile private var watchEndsIn: Option[ErrorCode]     = None
  @volatile private var importing: Importing               = Importing.Once
  private val produced                                     = AtomicInteger()
  private val streamEnded                                  = AtomicBoolean(false)
  private val read                                         = AtomicInteger()
  private val toldUnfinished                               = AtomicBoolean(false)
  @volatile private var release                            = CountDownLatch(1)
  @volatile private var handlerDone                        = CountDownLatch(1)
  @volatile private var arrivals: Vector[Long]             = Vector.empty
  @volatile private var received: Vector[Any]              = Vector.empty
  @volatile private var sent                               = 0

  /** Parts sized so a count measures backpressure: the HTTP/2 window is counted in bytes. */
  private def padding(parts: Int): String = if parts >= 1000 then "x" * 16384 else ""

  // GetCart refusing through the component, for the calling and traces features.
  @volatile private var getCartRefusal: Option[ErrorCode] = None
  // Reflection, when the scenario opts in.
  @volatile private var reflectionAcl: Option[Acl]  = None
  @volatile private var reflected: Vector[String]   = Vector.empty
  @volatile private var methodsTold: Vector[String] = Vector.empty
  // One service calling another, through GrpcClients.
  @volatile private var served: Set[String]             = Set.empty
  @volatile private var withoutGrpc: Set[String]        = Set.empty
  @volatile private var projectOf: Map[String, String]  = Map.empty
  @volatile private var impostor: Option[(String, Int)] = None
  private val impostorHandled                           = AtomicInteger()
  @volatile private var callFailure: Option[Throwable]  = None
  // The spans recorded before the scenario, so its own can be told apart.
  @volatile private var spansBefore: Set[Long] = Set.empty

  // What the handler for GetCart saw.
  @volatile private var sawPrincipal: Option[String]     = None
  @volatile private var sawMetadata: Map[String, String] = Map.empty
  @volatile private var sawCaller: Option[Caller]        = None

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
    endpointAcl = Acl.AllowAll
    methodAcls = Map.empty
    answer = "allow"
    establishes = "someone"
    sending = Vector.empty
    deployed = false
    deployedCaller = "checkout"
    sawPrincipal = None
    sawMetadata = Map.empty
    sawCaller = None
    getCartRefusal = None
    reflectionAcl = None
    reflected = Vector.empty
    methodsTold = Vector.empty
    served = Set.empty
    withoutGrpc = Set.empty
    projectOf = Map.empty
    impostor = None
    impostorHandled.set(0)
    callFailure = None
    spansBefore = observability.recorder.snapshot().map(_.spanId).toSet
    watchParts = Some(3)
    watchEvery = None
    watchEndsIn = None
    importing = Importing.Once
    produced.set(0)
    streamEnded.set(false)
    read.set(0)
    toldUnfinished.set(false)
    release.countDown()
    release = CountDownLatch(1)
    handlerDone = CountDownLatch(1)
    arrivals = Vector.empty
    received = Vector.empty
    sent = 0
    grpcPort = None
    httpPort = None
    startFailure = None
    outcome = None

  override def afterEach(context: AfterEach): Unit =
    release.countDown()
    started.reverse.foreach(c => Try(c.close()))
    started = Vector.empty

  // ── the service the suite shares ──────────────────────────────────────────────

  /** A cart endpoint over `FixtureCart`, behaving as the running scenario says. */
  private final class CartEndpoint(clients: EndpointClients, omit: Option[String] = None)
      extends GrpcEndpoint(CartServiceGrpc.SERVICE):
    val acl: Acl = endpointAcl

    private def cart(id: String) = clients.componentClient.forKeyValueEntity(EntityId(id))

    /** Declares under the method's own ACL when the scenario gave it one. */
    private def declaring(method: String)(declare: => Unit): Unit =
      methodAcls.get(method).fold(declare)(acl => withAcl(acl)(declare))

    if !omit.contains("GetCart") then
      declaring("GetCart") {
        unary(CartServiceGrpc.METHOD_GET_CART) { request =>
          handled.incrementAndGet()
          sawCaller = Some(caller)
          sawPrincipal = Try(principal.subject).toOption
          sawMetadata = metadata.toSeq.toMap
          failure.collect { case ("GetCart", message) => throw RuntimeException(message) }
          val state = getCartRefusal match
            case Some(code) =>
              cart(request.cartId).call(FixtureCart.refuse).invoke(s"$code|no such cart")
            case None => cart(request.cartId).call(FixtureCart.get).invoke()
          Cart(request.cartId, state.items.map(Item(_, 1)))
        }
      }
    if !omit.contains("AddItem") then
      unary(CartServiceGrpc.METHOD_ADD_ITEM) { request =>
        handled.incrementAndGet()
        failure.collect { case ("AddItem", message) => throw RuntimeException(message) }
        val state = refusal match
          case Some((code, message)) =>
            cart(request.cartId).call(FixtureCart.refuse).invoke(s"$code|$message")
          case None => cart(request.cartId).call(FixtureCart.add).invoke(request.product)
        Cart(request.cartId, state.items.map(Item(_, 1)))
      }

  /** The streaming methods, behaving as the running scenario says. */
  private final class StreamsEndpoint extends GrpcEndpoint(CartStreamsGrpc.SERVICE):
    val acl: Acl = endpointAcl

    serverStream(CartStreamsGrpc.METHOD_WATCH_CART) { request =>
      val pad = padding(watchParts.getOrElse(0))
      // A stream that ends in a refusal refuses when its next part is asked for, as one built over a
      // component refuses mid-stream. (A `Source.failed` joined on with `concat` fails the stream at
      // once, before the parts ahead of it exist: Pekko's semantics, not the platform's.)
      val base = (watchParts, watchEndsIn) match
        case (None, _)          => Source.repeat(0).zipWithIndex.map(_._2.toInt)
        case (Some(n), Some(_)) => Source(1 to n + 1)
        case (Some(n), None)    => Source(1 to n)
      val paced = watchEvery.fold(base)(every => base.throttle(1, every))
      paced
        .map { i =>
          watchEndsIn
            .filter(_ => watchParts.exists(i > _))
            .foreach(code => throw CommandError("the cart went away", code))
          produced.incrementAndGet()
          Cart(cartId = s"${request.cartId}-$i$pad")
        }
        .watchTermination() { (_, done) =>
          done.onComplete(_ => streamEnded.set(true))(using ExecutionContext.global)
          NotUsed
        }
    }

    clientStream(CartStreamsGrpc.METHOD_IMPORT_ITEMS) { requests =>
      try
        importing match
          case Importing.Once =>
            requests.foreach(_ => read.incrementAndGet(): Unit)
            ImportSummary(read.get)
          case Importing.ReadThenWait(parts) =>
            requests.take(parts).foreach(_ => read.incrementAndGet(): Unit)
            release.await(60, TimeUnit.SECONDS)
            ImportSummary(read.get)
          case Importing.RefuseAfter(code, parts) =>
            requests.take(parts).foreach(_ => read.incrementAndGet(): Unit)
            throw CommandError("no more items", code)
      catch
        case cancelled: CallCancelled =>
          toldUnfinished.set(true)
          throw cancelled
      finally handlerDone.countDown()
    }

    bidiStream(CartStreamsGrpc.METHOD_CONVERSE) { requests =>
      requests.asSource.map(line => Line(s"heard ${line.text}", line.sequence))
    }

  private final class Ping extends HttpEndpoint("/ping"):
    val acl: Acl = Acl.AllowAll
    get("/")(() => "pong")

  private val registered = GrpcServer.at("127.0.0.1", 0)(clients => CartEndpoint(clients))
  private var testKit: AnkkaTestKit = null

  private def observability = Observability(testKit.service.system)

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
        case Shape.Complete        => Seq(CartEndpoint(_), _ => StreamsEndpoint())
        case Shape.Missing(method) => Seq(CartEndpoint(_, Some(method)), _ => StreamsEndpoint())
        case Shape.Twice           => Seq(CartEndpoint(_), CartEndpoint(_))
        case Shape.Unregistered    => Seq.empty
      val plain   = GrpcServer.at("127.0.0.1", 0)(factories*)
      val secured = if deployed then plain.withTls(serverIdentity) else plain
      val server  = reflectionAcl.fold(secured)(secured.withReflection)
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

  private def channel(): Channel =
    start()
    val port =
      grpcPort.getOrElse(fail(s"the service did not start: ${startFailure.map(_.getMessage)}"))
    val base =
      if deployed then
        GrpcChannels.tls(port, "localhost", identityOf(deployedCaller), "ankka://shop/cart")
      else GrpcChannels.plaintext(port)
    started :+= (() => base.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit)
    if sending.isEmpty then base
    else
      val headers = Metadata()
      sending.foreach((k, v) =>
        headers.put(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER), v)
      )
      ClientInterceptors.intercept(base, MetadataUtils.newAttachHeadersInterceptor(headers))

  // ── certificates, for a scenario that says "deployed" ─────────────────────────

  private val authority = TestPki.root("grpc-features")

  private lazy val serverIdentity: java.nio.file.Path =
    authority
      .issue(uris = Seq("ankka://shop/cart"), dnsNames = Seq("localhost"))
      .writeTo(java.nio.file.Files.createTempDirectory("grpc-features-cart"))

  private def identityOf(service: String): RotatingTls =
    RotatingTls(identityDirectory(projectOf.getOrElse(service, "shop"), service), 1.minute)

  private def identityDirectory(project: String, service: String): java.nio.file.Path =
    authority
      .issue(uris = Seq(s"ankka://$project/$service"))
      .writeTo(java.nio.file.Files.createTempDirectory(s"grpc-features-$service"))

  // ── one service calling another, through GrpcClients ──────────────────────────

  private def definitionOf(method: String): String = method match
    case "GetOrder"                               => "OrderService"
    case "WatchCart" | "ImportItems" | "Converse" => "CartStreams"
    case _                                        => "CartService"

  /** Where each name is: the scenario's server, an impostor, a service without gRPC, or nothing. */
  private val locate: GrpcClients.Locate = (project, name) =>
    def at(port: Int) = Right(
      GrpcClients.Located.Addresses(Vector(InetSocketAddress("127.0.0.1", port)), "localhost")
    )
    impostor.filter(_._1 == name) match
      case Some((_, port)) => at(port)
      case None =>
        if served.contains(name) then
          start()
          at(grpcPort.getOrElse(fail(s"the service did not start: $startFailure")))
        else if withoutGrpc.contains(name) then Left(ServiceServesNoGrpc(s"$project/$name"))
        else Left(ServiceUnresolvable(s"$project/$name", s"no service at $name"))

  /** `caller`'s GrpcClients: its own certificate when deployed, none on a developer's machine. */
  private def clientsOf(caller: String): GrpcClients =
    val config =
      if !deployed then ConfigFactory.load()
      else
        val directory = identityDirectory(projectOf.getOrElse(caller, "shop"), caller)
        ConfigFactory
          .parseString(s"""ankka.tls.service-directory = "$directory"""")
          .withFallback(ConfigFactory.load())
    val clients = GrpcClients.locatedBy(locate).configure(config)
    started :+= (() => clients.stop())
    clients

  /** `caller` calls `method` of `target`, as itself, and the outcome is kept. */
  private def callAs(
      caller: String,
      target: String,
      method: String,
      project: Option[String] = None
  ): Unit =
    val clients = clientsOf(caller)
    val through = project.fold(clients(target))(clients(_, target))
    val request = if method == "GetOrder" then GetOrderRequest("o1") else requestFor(method)
    try
      outcome = Some(
        Right(
          ClientCalls.blockingUnaryCall(
            through,
            methodOf(definitionOf(method), method),
            CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS),
            request
          )
        )
      )
    catch
      case e: StatusRuntimeException =>
        callFailure = Some(e)
        outcome = Some(Left(e.getStatus))
      case scala.util.control.NonFatal(e) => callFailure = Some(e)

  // ── the spans a scenario recorded ─────────────────────────────────────────────

  private def rootsOfThisScenario: Vector[RecordedSpan] =
    def roots = observability.recorder
      .snapshot()
      .filterNot(span => spansBefore.contains(span.spanId))
      .filter(span =>
        span.parentSpanId == 0L && observability.names.nameOf(span.componentRef).contains("grpc")
      )
    // A span completes as its call ends, which the caller may hear first.
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while roots.isEmpty && System.nanoTime() < deadline do Thread.sleep(20)
    roots

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
      .getOrElse(
        // A method the definition does not have: sent as bytes, under the name a caller chose.
        MethodDescriptor
          .newBuilder(Binding.bytes, Binding.bytes)
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName(MethodDescriptor.generateFullMethodName(service.getName, method))
          .build()
          .asInstanceOf[MethodDescriptor[Any, Any]]
      )

  private def requestFor(method: String): Any = method match
    case "GetCart"  => GetCartRequest(s"cart-$scenarioId")
    case "AddItem"  => AddItemRequest(s"cart-$scenarioId", "Widget", 1)
    case "GetOrder" => GetOrderRequest("o1")
    case _ if !Set("GetCart", "AddItem").contains(method) => Array.emptyByteArray

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

  /** A server stream, read whole, with when each part arrived. */
  private def watch(reading: Option[Int] = None): Unit =
    val parts = ClientCalls.blockingServerStreamingCall(
      channel(),
      CartStreamsGrpc.METHOD_WATCH_CART,
      CallOptions.DEFAULT,
      GetCartRequest(s"cart-$scenarioId")
    )
    outcome = Some(
      try
        while reading.forall(received.size < _) && parts.hasNext do
          received :+= parts.next()
          arrivals :+= System.nanoTime()
        Right(received)
      catch case e: StatusRuntimeException => Left(e.getStatus)
    )

  /**
   * A stream of requests, sent as the server can take them: a part is written only while the call
   * is ready, and sending stops once it has not been ready for three seconds — which, against a
   * handler that has stopped reading, is the point.
   */
  private def send(
      method: MethodDescriptor[Any, Any],
      parts: Iterator[Array[Byte]],
      end: Boolean,
      awaitClose: Boolean,
      through: => Channel = channel()
  ): ClientCall[Array[Byte], Any] =
    val rebound = method.toBuilder(Binding.bytes, method.getResponseMarshaller).build()
    val call    = through.newCall(rebound, CallOptions.DEFAULT)
    val closed  = Promise[Status]()
    val ready   = Object()
    call.start(
      new ClientCall.Listener[Any]:
        override def onMessage(message: Any): Unit =
          received :+= message
          call.request(1)
        override def onClose(status: Status, trailers: Metadata): Unit =
          closed.trySuccess(status): Unit
          ready.synchronized(ready.notifyAll())
        override def onReady(): Unit = ready.synchronized(ready.notifyAll())
      ,
      Metadata()
    )
    call.request(1)
    var stalled = false
    while !stalled && !closed.isCompleted && parts.hasNext do
      val deadline = System.nanoTime() + 3.seconds.toNanos
      while !call.isReady && !closed.isCompleted && System.nanoTime() < deadline do
        ready.synchronized(ready.wait(50))
      if call.isReady && !closed.isCompleted then
        call.sendMessage(parts.next())
        sent += 1
      else stalled = true
    if end && !closed.isCompleted then call.halfClose()
    if awaitClose then
      val status = Await.result(closed.future, 30.seconds)
      outcome = Some(if status.isOk then Right(received.headOption.orNull) else Left(status))
    call

  private def items(n: Int): Iterator[Array[Byte]] =
    val pad = padding(n)
    Iterator.tabulate(n)(i => AddItemRequest(s"cart-$scenarioId", s"product-$i$pad", 1).toByteArray)

  private def eventually(condition: => Boolean, what: String): Unit =
    val deadline = System.nanoTime() + 10.seconds.toNanos
    while !condition && System.nanoTime() < deadline do Thread.sleep(20)
    assert(condition, what)

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
    assertEquals(endedWith.getCode, statusOf(status), endedWith.toString)
    outcome.flatMap(_.toOption) match
      case Some(c: Cart)          => assertEquals(c.cartId, s"cart-$scenarioId")
      case Some(s: ImportSummary) => assertEquals(s.count, sent)
      case other                  => fail(s"no answer from the handler: $other")
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
  When("a developer calls the method {string}") { (method: String) =>
    if method == "WatchCart" then watch() else call("CartService", method)
  }
  Then("the call ends with the status {string} and the message {string}") {
    (status: String, message: String) =>
      assertEquals(endedWith.getCode, statusOf(status), endedWith.toString)
      assertEquals(endedWith.getDescription, message)
  }
  Given("a gRPC endpoint whose handler for the method {string} fails with the message {string}") {
    (method: String, message: String) => failure = Some(method -> message)
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

  // ── access.feature ────────────────────────────────────────────────────────────

  private val authenticator: Acl = Acl.Authenticate { _ =>
    answer match
      case "allow"           => AuthDecision.Allow(Principal(establishes))
      case "unauthenticated" => AuthDecision.Unauthenticated("realm=\"cart\"")
      case "forbidden"       => AuthDecision.Forbidden("not your cart")
      case "unavailable"     => AuthDecision.Unavailable("the keys could not be fetched")
      case other             => fail(s"no authenticator answer '$other'")
  }

  Given("a service {string} with a gRPC endpoint that states no ACL") { (_: String) =>
    endpointAcl = null
  }
  Given("a gRPC endpoint whose ACL denies all") { () => endpointAcl = Acl.DenyAll }
  Given("the method {string} states an ACL that allows all") { (method: String) =>
    methodAcls += method -> Acl.AllowAll
  }
  Given("a gRPC endpoint whose ACL is an authenticator") { () => endpointAcl = authenticator }
  Given("the authenticator answers {string}") { (a: String) => answer = a }
  Then("the handler for the method {string} has run {string} times") { (_: String, runs: String) =>
    assertEquals(handled.get, runs.toInt)
  }
  Given("the authenticator establishes the principal {string}") { (subject: String) =>
    answer = "allow"
    establishes = subject
  }
  Then("the handler for the method {string} reads the principal {string}") {
    (_: String, subject: String) => assertEquals(sawPrincipal, Some(subject))
  }
  When("a developer calls the method {string} with the metadata {string} set to {string}") {
    (method: String, key: String, value: String) =>
      sending :+= key -> value
      call("CartService", method)
  }
  Then("the handler for the method {string} reads the metadata {string} as {string}") {
    (_: String, key: String, value: String) => assertEquals(sawMetadata.get(key), Some(value))
  }
  Given(
    "a deployed service {string} with a gRPC endpoint whose ACL admits only the service {string}"
  ) { (name: String, admitted: String) =>
    deployed = true
    served += name
    endpointAcl = Acl.allowCallers(Callers.service(admitted))
  }
  When("the service {string} calls the method {string} of the service {string}") {
    (calling: String, method: String, target: String) =>
      deployedCaller = calling
      callAs(calling, target, method)
  }
  Given("a gRPC endpoint for the service definition {string} whose ACL denies all") { (_: String) =>
    endpointAcl = Acl.DenyAll
  }
  Given("a service {string} running on a developer's machine") { (_: String) => deployed = false }
  Given("the service {string} has a gRPC endpoint whose ACL admits only the service {string}") {
    (name: String, admitted: String) =>
      served += name
      endpointAcl = Acl.allowCallers(Callers.service(admitted))
  }
  Then("the handler for the method {string} reads the calling workload as the local caller") {
    (_: String) => assertEquals(sawCaller, Some(Caller.Local))
  }

  // ── streaming.feature ─────────────────────────────────────────────────────────

  Given("a gRPC endpoint whose handler for the method {string} answers with a stream") {
    (_: String) => ()
  }
  Given("the stream produces {string} parts, one each second") { (n: String) =>
    watchParts = Some(n.toInt)
    watchEvery = Some(1.second)
  }
  Then("the developer is given each part as it is produced") { () =>
    assertEquals(received.size, watchParts.getOrElse(0))
    // Three parts a second apart arrive spread over two seconds, not together at the end.
    assert(arrivals.last - arrivals.head >= 1500.millis.toNanos, arrivals.toString)
  }
  Then("the call ends with the status {string} after the last part") { (status: String) =>
    assertEquals(endedWith.getCode, statusOf(status))
  }
  Given("the stream produces {string} parts") { (n: String) => watchParts = Some(n.toInt) }
  When("a developer calls the method {string} and reads {string} parts") { (_: String, n: String) =>
    watch(reading = Some(n.toInt))
    Thread.sleep(1000)
  }
  Then("the stream has produced fewer than {string} parts") { (n: String) =>
    assert(produced.get < n.toInt, s"${produced.get} parts were produced")
  }
  Given("the stream produces parts and does not end") { () => watchParts = None }
  When("a developer calls the method {string} and goes away after {string} parts") {
    (_: String, n: String) =>
      val own = GrpcChannels.plaintext(grpcPort.getOrElse { start(); grpcPort.get })
      val parts = ClientCalls.blockingServerStreamingCall(
        own,
        CartStreamsGrpc.METHOD_WATCH_CART,
        CallOptions.DEFAULT,
        GetCartRequest(s"cart-$scenarioId")
      )
      (1 to n.toInt).foreach(_ => received :+= parts.next())
      own.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit
  }
  Then("the stream stops being produced") { () =>
    eventually(streamEnded.get, "the stream was still being produced")
  }
  Given("the stream produces {string} parts and then ends in the refusal {string}") {
    (n: String, refusal: String) =>
      watchParts = Some(n.toInt)
      watchEndsIn = Some(refusals.getOrElse(refusal, fail(s"no refusal '$refusal'")))
  }
  Then("the developer is given {string} parts")((n: String) => assertEquals(received.size, n.toInt))
  Given("the gRPC endpoint's ACL denies all") { () => endpointAcl = Acl.DenyAll }
  Then("no part is sent")(() => assertEquals(received.size, 0))

  // ── request-streams.feature ───────────────────────────────────────────────────

  Given("a gRPC endpoint whose handler for the method {string} takes a stream and answers once") {
    (_: String) => importing = Importing.Once
  }
  When("a developer calls the method {string} and sends {string} parts") { (_: String, n: String) =>
    val _ = send(
      CartStreamsGrpc.METHOD_IMPORT_ITEMS.asInstanceOf[MethodDescriptor[Any, Any]],
      items(n.toInt),
      end = true,
      awaitClose = n.toInt < 1000
    )
  }
  Then("the handler for the method {string} reads {string} parts") { (_: String, n: String) =>
    handlerDone.await(10, TimeUnit.SECONDS)
    assertEquals(read.get, n.toInt)
  }
  Given(
    "a gRPC endpoint whose handler for the method {string} takes a stream and answers with a stream"
  ) { (_: String) =>
    ()
  }
  Given("the handler for the method {string} answers each part it reads with one part") {
    (_: String) => ()
  }
  When("a developer calls the method {string} and sends {string} part without ending the stream") {
    (_: String, n: String) =>
      val lines = Iterator.tabulate(n.toInt)(i => Line(s"line $i", i).toByteArray)
      val call = send(
        CartStreamsGrpc.METHOD_CONVERSE.asInstanceOf[MethodDescriptor[Any, Any]],
        lines,
        end = false,
        awaitClose = false
      )
      eventually(received.nonEmpty, "no part came back while the stream was still open")
      call.cancel("done", null)
  }
  Then("the developer is given {string} part")((n: String) => assertEquals(received.size, n.toInt))
  Given(
    "a gRPC endpoint whose handler for the method {string} takes a stream, reads {string} parts and then waits"
  ) { (_: String, n: String) =>
    importing = Importing.ReadThenWait(n.toInt)
  }
  Then("the service holds fewer than {string} parts that the handler has not read") { (n: String) =>
    assert(sent - read.get < n.toInt, s"$sent parts were accepted and ${read.get} read")
  }
  When("a developer calls the method {string}, sends {string} parts and goes away") {
    (_: String, n: String) =>
      val call = send(
        CartStreamsGrpc.METHOD_IMPORT_ITEMS.asInstanceOf[MethodDescriptor[Any, Any]],
        items(n.toInt),
        end = false,
        awaitClose = false
      )
      // Gone once what was sent has been read: a part still in flight when a call is cancelled is
      // discarded by the transport, and the scenario is about what the handler is told.
      eventually(read.get == n.toInt, s"the handler read ${read.get} parts")
      call.cancel("going away", null)
  }
  Then("the handler for the method {string} is told that the stream ended unfinished") {
    (_: String) =>
      handlerDone.await(10, TimeUnit.SECONDS)
      assert(toldUnfinished.get, "the handler was not told")
  }
  Given(
    "a gRPC endpoint whose handler for the method {string} takes a stream and answers with the refusal {string} after {string} part"
  ) { (_: String, refusal: String, n: String) =>
    importing =
      Importing.RefuseAfter(refusals.getOrElse(refusal, fail(s"no refusal '$refusal'")), n.toInt)
  }
  When("a developer calls the method {string} and sends {string} parts without ending the stream") {
    (_: String, n: String) =>
      val _ = send(
        CartStreamsGrpc.METHOD_IMPORT_ITEMS.asInstanceOf[MethodDescriptor[Any, Any]],
        items(n.toInt),
        end = false,
        awaitClose = true
      )
  }
  When(
    "a developer calls the method {string} and sends {string} parts and then a part the gRPC endpoint cannot read"
  ) { (_: String, n: String) =>
    val parts = items(n.toInt) ++ Iterator(Array[Byte](0x0a, 0x7f))
    val _ = send(
      CartStreamsGrpc.METHOD_IMPORT_ITEMS.asInstanceOf[MethodDescriptor[Any, Any]],
      parts,
      end = true,
      awaitClose = true
    )
  }

  // ── calling.feature ───────────────────────────────────────────────────────────

  Given("a deployed service {string} in the project {string} that serves gRPC") {
    (name: String, project: String) =>
      deployed = true
      served += name
      projectOf += name -> project
  }
  Given("a deployed service {string} in the project {string}") { (name: String, project: String) =>
    deployed = true
    projectOf += name -> project
  }
  When(
    "the service {string} calls the method {string} of the service {string} of the project {string}"
  ) { (calling: String, method: String, target: String, project: String) =>
    callAs(calling, target, method, Some(project))
  }
  Then(
    "the handler for the method {string} reads the calling workload as the service {string} of the project {string}"
  ) { (_: String, name: String, project: String) =>
    assertEquals(sawCaller, Some(Caller.Service(project, name)))
  }
  Given("a deployed service {string}") { (_: String) => deployed = true }
  Given("a workload at the gRPC address of {string} whose certificate names the service {string}") {
    (name: String, impersonated: String) =>
      deployed = true
      val directory = authority
        .issue(uris = Seq(s"ankka://shop/$impersonated"), dnsNames = Seq("localhost"))
        .writeTo(java.nio.file.Files.createTempDirectory("grpc-features-impostor"))
      val server = GrpcServer.at("127.0.0.1", 0)(_ => Impostor()).withTls(directory)
      server.start(testKit.service)
      started :+= (() => server.stop())
      impostor = Some(name -> server.boundPort.get)
  }
  Then(
    "the call fails, and the service {string} is shown that the workload is not the service {string}"
  ) { (_: String, name: String) =>
    val cause = callFailure.flatMap(f => Option(f.getCause))
    assert(
      cause.exists(_.isInstanceOf[ServiceIdentityMismatch]),
      s"failed with $callFailure, caused by $cause"
    )
    assert(cause.exists(_.getMessage.contains(name)), cause.toString)
  }
  Then("no request is sent to the workload")(() => assertEquals(impostorHandled.get, 0))
  Given("no service {string}")((_: String) => ())
  Then(
    "the call fails, and the service {string} is shown that the service {string} cannot be found"
  ) { (_: String, name: String) =>
    assert(callFailure.exists(_.isInstanceOf[ServiceUnresolvable]), callFailure.toString)
    assert(callFailure.exists(_.getMessage.contains(name)), callFailure.toString)
  }
  Given("a deployed service {string} whose descriptor does not declare gRPC") { (name: String) =>
    deployed = true
    withoutGrpc += name
  }
  Then(
    "the call fails, and the service {string} is shown that the service {string} does not serve gRPC"
  ) { (_: String, name: String) =>
    assert(callFailure.exists(_.isInstanceOf[ServiceServesNoGrpc]), callFailure.toString)
    assert(callFailure.exists(_.getMessage.contains(name)), callFailure.toString)
  }
  Given(
    "a deployed service {string} whose handler for the method {string} answers with the refusal {string}"
  ) { (name: String, _: String, refusal: String) =>
    deployed = true
    served += name
    getCartRefusal = Some(refusals.getOrElse(refusal, fail(s"no refusal '$refusal'")))
  }
  When("a handler of the service {string} calls the method {string} of the service {string}") {
    (calling: String, method: String, target: String) => callAs(calling, target, method)
  }
  Then("the handler of the service {string} is given the refusal {string}") {
    (_: String, refusal: String) =>
      // What the calling handler holds: a failed call whose cause is the refusal, which a handler
      // that lets it pass answers its own caller with.
      val handed = callFailure.flatMap(CommandError.from)
      assertEquals(handed.map(_.code), refusals.get(refusal), callFailure.toString)
      assertEquals(handed.map(_.message), Some("no such cart"))
  }
  Given(
    "a deployed service {string} whose handler for the method {string} takes a stream and answers each part it reads with one part"
  ) { (name: String, _: String) =>
    deployed = true
    served += name
  }
  When(
    "the service {string} calls the method {string} of the service {string} and sends {string} part without ending the stream"
  ) { (calling: String, method: String, target: String, n: String) =>
    val lines   = Iterator.tabulate(n.toInt)(i => Line(s"line $i", i).toByteArray)
    val through = clientsOf(calling)(target)
    val call = send(
      methodOf(definitionOf(method), method),
      lines,
      end = false,
      awaitClose = false,
      through = through
    )
    eventually(received.nonEmpty, "a part to come back while the stream is still open")
    call.cancel("done", null)
  }
  Then("the service {string} is given {string} part") { (_: String, n: String) =>
    assertEquals(received.size, n.toInt)
  }
  Given("a service {string} running on a developer's machine that serves gRPC") { (name: String) =>
    deployed = false
    served += name
  }

  /** Answers at an address it should not: a workload holding another service's certificate. */
  private final class Impostor extends GrpcEndpoint(CartServiceGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll
    unary(CartServiceGrpc.METHOD_GET_CART) { request =>
      impostorHandled.incrementAndGet()
      Cart(request.cartId)
    }
    unary(CartServiceGrpc.METHOD_ADD_ITEM)(request => Cart(request.cartId))

  // ── traces.feature ────────────────────────────────────────────────────────────

  Given(
    "a gRPC endpoint for the service definition {string} whose handler for the method {string} calls a component"
  )((_: String, _: String) => ())
  Then("the service records a trace whose root is the call to the method {string} of {string}") {
    (method: String, definition: String) =>
      assertEquals(
        rootsOfThisScenario.map(r => observability.names.nameOf(r.handlerRef)),
        Vector(Some(s"ankka.fixtures.v1.$definition/$method"))
      )
  }
  Then("the trace shows the call to the component under the root") { () =>
    val root = rootsOfThisScenario.headOption.getOrElse(fail("no root was recorded"))
    val beneath = observability.recorder
      .spansOf(root.traceId)
      .filter(_.parentSpanId == root.spanId)
      .map(s => observability.names.nameOf(s.componentRef))
    assert(beneath.contains(Some("fixture-cart")), beneath.toString)
  }
  Given("a gRPC endpoint whose handler for the method {string} answers with the refusal {string}") {
    (_: String, refusal: String) =>
      getCartRefusal = Some(refusals.getOrElse(refusal, fail(s"no refusal '$refusal'")))
  }
  Then("the service records a trace whose root is marked refused") { () =>
    assertEquals(rootsOfThisScenario.map(_.outcome), Vector(SpanOutcome.Refused))
  }
  Then("the service records a trace whose root is marked failed") { () =>
    assertEquals(rootsOfThisScenario.map(_.outcome), Vector(SpanOutcome.Failed))
  }
  Given(
    "a gRPC endpoint whose handler for the method {string} answers with a stream that produces {string} parts"
  ) { (_: String, n: String) => watchParts = Some(n.toInt) }
  Then("the service records a trace with {string} root") { (n: String) =>
    assertEquals(rootsOfThisScenario.size, n.toInt)
  }

  // ── reflection.feature ────────────────────────────────────────────────────────

  /** One reflection request, as a tool sends it, and its one answer. */
  private def askReflection(request: ServerReflectionRequest): ServerReflectionResponse =
    val answer = Promise[ServerReflectionResponse]()
    val requests = ServerReflectionGrpc
      .newStub(channel())
      .serverReflectionInfo(new StreamObserver[ServerReflectionResponse]:
        def onNext(value: ServerReflectionResponse): Unit = answer.trySuccess(value): Unit
        def onError(t: Throwable): Unit                   = answer.tryFailure(t): Unit
        def onCompleted(): Unit                           = ())
    requests.onNext(request)
    requests.onCompleted()
    Await.result(answer.future, 30.seconds)

  /** Asks what the service serves, as a tool does, then asks it to describe each definition. */
  private def reflect(): Unit =
    val listing = ServerReflectionRequest.newBuilder().setListServices("").build()
    Try(askReflection(listing)) match
      case scala.util.Success(response) =>
        outcome = Some(Right(response))
        reflected = response.getListServicesResponse.getServiceList.asScala.map(_.getName).toVector
        methodsTold = reflected.filterNot(_.startsWith("grpc.reflection")).flatMap { service =>
          askReflection(
            ServerReflectionRequest.newBuilder().setFileContainingSymbol(service).build()
          ).getFileDescriptorResponse.getFileDescriptorProtoList.asScala.toVector
            .map(com.google.protobuf.DescriptorProtos.FileDescriptorProto.parseFrom)
            .flatMap(file =>
              file.getServiceList.asScala
                .filter(d => s"${file.getPackage}.${d.getName}" == service)
                .flatMap(_.getMethodList.asScala.map(m => s"$service/${m.getName}"))
            )
        }
      case scala.util.Failure(e: StatusRuntimeException) => outcome = Some(Left(e.getStatus))
      case scala.util.Failure(other)                     => fail(s"reflection failed: $other")

  Given("the service {string} has not opted into reflection") { (_: String) =>
    reflectionAcl = None
  }
  When("a developer asks the service {string} for reflection")((_: String) => reflect())
  Given("the service {string} opts into reflection with an ACL that allows all") { (_: String) =>
    reflectionAcl = Some(Acl.AllowAll)
  }
  Then("the developer is told the service definition {string}") { (definition: String) =>
    assert(reflected.contains(s"ankka.fixtures.v1.$definition"), reflected.toString)
  }
  Then("the developer is told the methods of {string}") { (definition: String) =>
    Seq("GetCart", "AddItem").foreach(method =>
      assert(methodsTold.contains(s"ankka.fixtures.v1.$definition/$method"), methodsTold.toString)
    )
  }
  Given("a service {string} that opts into reflection and states no ACL for reflection") {
    (_: String) => reflectionAcl = Some(null)
  }
  Given(
    "a deployed service {string} that opts into reflection with an ACL that admits only the service {string}"
  ) { (_: String, admitted: String) =>
    deployed = true
    reflectionAcl = Some(Acl.allowCallers(Callers.service(admitted)))
  }
  When("the service {string} asks the service {string} for reflection") {
    (calling: String, _: String) =>
      deployedCaller = calling
      reflect()
  }
  Then("the service {string} is told nothing of what the service {string} serves") {
    (_: String, _: String) => assertEquals((reflected, methodsTold), (Vector.empty, Vector.empty))
  }
  Given(
    "a service {string} with a gRPC endpoint for the service definition {string} whose ACL denies all"
  ) { (_: String, _: String) => endpointAcl = Acl.DenyAll }
  When(
    "a developer asks the service {string} for reflection and then calls the method {string} of {string}"
  ) { (_: String, method: String, definition: String) =>
    reflect()
    call(definition, method)
  }
