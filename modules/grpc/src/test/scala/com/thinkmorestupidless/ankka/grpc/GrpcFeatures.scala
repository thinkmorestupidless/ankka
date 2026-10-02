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
import io.grpc.ClientCall
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
  @volatile private var failure: Option[String]              = None
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
          val state = cart(request.cartId).call(FixtureCart.get).invoke()
          Cart(request.cartId, state.items.map(Item(_, 1)))
        }
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

  /** The streaming methods, behaving as the running scenario says. */
  private final class StreamsEndpoint extends GrpcEndpoint(CartStreamsGrpc.SERVICE):
    val acl: Acl = endpointAcl

    serverStream(CartStreamsGrpc.METHOD_WATCH_CART) { request =>
      val pad  = padding(watchParts.getOrElse(0))
      val base = watchParts.fold(Source.repeat(0).zipWithIndex.map(_._2.toInt))(n => Source(1 to n))
      val paced = watchEvery.fold(base)(every => base.throttle(1, every))
      val carts = paced.map { i =>
        produced.incrementAndGet()
        Cart(cartId = s"${request.cartId}-$i$pad")
      }
      watchEndsIn
        .fold(carts)(code => carts.concat(Source.failed(CommandError("the cart went away", code))))
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
      val plain  = GrpcServer.at("127.0.0.1", 0)(factories*)
      val server = if deployed then plain.withTls(serverIdentity) else plain
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
    RotatingTls(
      authority
        .issue(uris = Seq(s"ankka://shop/$service"))
        .writeTo(java.nio.file.Files.createTempDirectory(s"grpc-features-$service")),
      1.minute
    )

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
      awaitClose: Boolean
  ): ClientCall[Array[Byte], Any] =
    val rebound = method.toBuilder(Binding.bytes, method.getResponseMarshaller).build()
    val call    = channel().newCall(rebound, CallOptions.DEFAULT)
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
  ) { (_: String, admitted: String) =>
    deployed = true
    endpointAcl = Acl.allowCallers(Callers.service(admitted))
  }
  When("the service {string} calls the method {string} of the service {string}") {
    (calling: String, method: String, _: String) =>
      deployedCaller = calling
      call("CartService", method)
  }
  Given("a gRPC endpoint for the service definition {string} whose ACL denies all") { (_: String) =>
    endpointAcl = Acl.DenyAll
  }
  Given("a service {string} running on a developer's machine") { (_: String) => deployed = false }
  Given("the service {string} has a gRPC endpoint whose ACL admits only the service {string}") {
    (_: String, admitted: String) => endpointAcl = Acl.allowCallers(Callers.service(admitted))
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
