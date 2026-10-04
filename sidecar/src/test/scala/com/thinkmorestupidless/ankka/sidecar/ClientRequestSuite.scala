package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.client.*
import ankka.protocol.v1.endpoint.HttpRequest.Pair as Header
import ankka.protocol.v1.payload as pb
import com.google.protobuf.ByteString
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.runtime.{CallOrigin, Observability, Trace}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing, ScriptedService}
import org.apache.pekko.actor.typed.ActorSystem

import java.net.{InetAddress, ServerSocket}
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

/** An event sourced entity, a key value entity and a workflow: the kinds the rule tells apart. */
final class ClientRequestLedger extends EventSourcedEntity[Long, String]:
  def emptyState: Long                = 0L
  def applyEvent(event: String): Long = currentState + event.toLong
  def add(amount: Int): Effect[Done]  = effects.persist(amount.toString).thenReply(_ => Done)

object ClientRequestLedger
    extends EventSourcedEntity.Companion[ClientRequestLedger, Long, String](
      componentId = ComponentId("request-ledger"),
      stateSerializer = Serializers.long,
      eventSerializer = Serializers.string
    ):
  def create(context: EventSourcedEntityContext) = new ClientRequestLedger
  val add                                        = command("add")(_.add)

final class ClientRequestWallet extends KeyValueEntity[Int]:
  def emptyState: Int                = 0
  def put(amount: Int): Effect[Done] = effects.updateState(amount).thenReply(_ => Done)

object ClientRequestWallet
    extends KeyValueEntity.Companion[ClientRequestWallet, Int](
      componentId = ComponentId("request-wallet"),
      stateSerializer = Serializers.int
    ):
  def create(context: KeyValueEntityContext) = new ClientRequestWallet
  val put                                    = command("put")(_.put)

final class ClientRequestFlow extends Workflow[Int]:
  def emptyState: Int = 0
  def go(amount: Int): Effect[Done] =
    effects
      .updateState(amount)
      .transitionTo(ClientRequestFlow.pay.withInput(amount))
      .thenReply(Done)
  def payStep(amount: Int): StepEffect = stepEffects.updateState(amount).thenEnd

object ClientRequestFlow
    extends Workflow.Companion[ClientRequestFlow, Int](
      componentId = ComponentId("request-flow"),
      stateSerializer = Serializers.int
    ):
  def create(context: WorkflowContext) = new ClientRequestFlow
  val pay                              = step("pay")(_.payStep)
  val go                               = command("go")(_.go)

/**
 * `Request` as a process reaches it: `ClientLogic`, which the gRPC service and (later) a module's
 * import delegate to, against a real service whose other service is a stand-in on loopback.
 */
class ClientRequestSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val scripted = ScriptedService.start()
  private val slow     = ScriptedService.start()
  private val closed: Int =
    val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    try socket.getLocalPort
    finally socket.close()

  private var kit: AnkkaTestKit = null

  private val settings =
    Settings("127.0.0.1:0", 0, "127.0.0.1", 5.seconds, 1.second, 5.seconds, 5.seconds)

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Seq(
        ClientRequestLedger.descriptor,
        ClientRequestWallet.descriptor,
        ClientRequestFlow.descriptor
      ),
      configure = _.withServices { real =>
        new ServiceClients:
          def apply(name: String): ServiceClient = apply("local", name)
          def apply(project: String, name: String): ServiceClient =
            if name != "impostor" then real(project, name)
            else
              new ServiceClient:
                val target = s"$project/$name"
                def request(
                    method: String,
                    path: String,
                    body: Option[Array[Byte]],
                    contentType: Option[String],
                    headers: Seq[(String, String)]
                ): ServiceResponse =
                  throw ServiceIdentityMismatch(target, "the certificate names another service")
      },
      localServices = Map(
        "scripted" -> scripted.address,
        "slow"     -> slow.address,
        "gone"     -> s"http://127.0.0.1:$closed"
      )
    )

  override def afterAll(): Unit =
    try if kit != null then kit.stop()
    finally
      scripted.stop()
      slow.stop()

  override def beforeEach(context: BeforeEach): Unit =
    scripted.clear()
    scripted.answer(request =>
      ServiceResponse(
        201,
        "application/json",
        s"""{"path":"${request.path}"}""".getBytes,
        Vector("X-Answer" -> "yes")
      )
    )

  private def logic(declared: Option[String] = None): ClientLogic =
    given ActorSystem[?] = kit.service.system
    ClientLogic(kit.service, settings, () => None, declared)

  private def await[A](f: Future[A]): A = Await.result(f, 30.seconds)

  private def caller(component: String, handler: String): pb.Metadata =
    pb.Metadata(Seq(pb.Metadata.Entry(CallOrigin.MetadataKey, s"$component#$handler")))

  private def get(service: String, metadata: pb.Metadata = pb.Metadata()): ServiceRequest =
    ServiceRequest(service = service, method = "GET", path = "/x?y=1", metadata = Some(metadata))

  private def refused(reply: ServiceReply, code: pb.ErrorCode, says: String): Unit =
    reply.result match
      case ServiceReply.Result.Error(e) =>
        assertEquals(e.code, code, e.message)
        assert(e.message.contains(says), e.message)
      case other => fail(s"expected a refusal, got $other")

  private def counted(callerComponent: String, callerHandler: String): Long =
    val observability  = Observability(kit.service.system)
    def name(ref: Int) = observability.names.nameOf(ref).getOrElse("?")
    observability.calls
      .snapshot(System.currentTimeMillis())
      .pairs
      .filter(p =>
        name(p.callerComponent) == callerComponent && name(p.callerHandler) == callerHandler &&
          name(p.calleeComponent) == "service:local/scripted"
      )
      .map(_.handled)
      .sum

  test("a request reaches the service whole, and its answer reaches the process whole") {
    val reply = await(
      logic().request(
        ServiceRequest(
          service = "scripted",
          method = "POST",
          path = "/payouts?amount=5",
          headers = Seq(Header("X-Request-Id", "r1")),
          contentType = Some("application/json"),
          body = Some(ByteString.copyFromUtf8("""{"amount":5}"""))
        )
      )
    )
    val received = scripted.requests.head
    assertEquals((received.method, received.path), ("POST", "/payouts?amount=5"))
    assertEquals(received.contentType, Some("application/json"))
    assertEquals(received.header("x-request-id"), Some("r1"))
    assertEquals(received.text, """{"amount":5}""")
    val response = reply.result.response.getOrElse(fail(reply.toString))
    assertEquals(response.status, 201)
    assertEquals(response.contentType, "application/json")
    assertEquals(response.body.toStringUtf8, """{"path":"/payouts?amount=5"}""")
    assert(response.headers.exists(h => h.name.equalsIgnoreCase("x-answer") && h.value == "yes"))
  }

  test("a call a workflow's step asks for is made as that step, and counted from it") {
    val before = counted("request-flow", "pay")
    val reply  = await(logic().request(get("scripted", caller("request-flow", "pay"))))
    assert(reply.result.isResponse, reply.toString)
    assertEquals(counted("request-flow", "pay"), before + 1)
  }

  test("a call a process makes outside any handler is made and counted from the unknown caller") {
    val before = counted("(unknown)", "(unknown)")
    val reply  = await(logic().request(get("scripted")))
    assert(reply.result.isResponse, reply.toString)
    assertEquals(scripted.requests.size, 1)
    assertEquals(counted("(unknown)", "(unknown)"), before + 1)
  }

  test("a caller the service did not declare is not believed, and the call is made as nobody's") {
    val before = counted("(unknown)", "(unknown)")
    val reply  = await(logic().request(get("scripted", caller("nobody-declared", "this"))))
    assert(reply.result.isResponse, reply.toString)
    assertEquals(counted("(unknown)", "(unknown)"), before + 1)
  }

  test("an entity's handler in a process may not call another service") {
    for (component, handler, kind) <- Seq(
        ("request-ledger", "add", "event sourced entity"),
        ("request-wallet", "put", "key value entity")
      )
    do
      val reply = await(logic().request(get("scripted", caller(component, handler))))
      refused(reply, pb.ErrorCode.BAD_REQUEST, s"the $kind '$component' may not call")
    assertEquals(scripted.requests, Vector.empty)
  }

  test("a workflow calls another service in a step and not in a command") {
    refused(
      await(logic().request(get("scripted", caller("request-flow", "go")))),
      pb.ErrorCode.BAD_REQUEST,
      "in a step"
    )
    assertEquals(scripted.requests, Vector.empty)
  }

  test("a process made for a protocol version before calls to other services is not served one") {
    val reply = await(logic(declared = Some("1.6")).request(get("scripted")))
    refused(reply, pb.ErrorCode.BAD_REQUEST, "1.6")
    refused(reply, pb.ErrorCode.BAD_REQUEST, "1.7")
    assertEquals(scripted.requests, Vector.empty)
    assert(await(logic(declared = Some("1.7")).request(get("scripted"))).result.isResponse)
  }

  test("two calls a process makes at once are both answered without waiting for each other") {
    slow.delay(3.seconds)
    try
      val client  = logic()
      val started = System.nanoTime()
      val late    = client.request(get("slow"))
      val fast    = await(client.request(get("scripted")))
      assert(fast.result.isResponse, fast.toString)
      assert((System.nanoTime() - started).nanos < 2.seconds, "the fast call waited")
      assert(await(late).result.isResponse)
    finally slow.delay(Duration.Zero)
  }

  test("a body over the limit is refused before anything is sent, either way") {
    val large = ByteString.copyFrom(new Array[Byte](ClientLogic.MaxBodyBytes + 1))
    refused(
      await(logic().request(get("scripted").copy(method = "POST", body = Some(large)))),
      pb.ErrorCode.BAD_REQUEST,
      ClientLogic.MaxBodyBytes.toString
    )
    assertEquals(scripted.requests, Vector.empty)
    scripted.answer(_ =>
      ServiceResponse(
        200,
        "text/plain",
        new Array[Byte](ClientLogic.MaxBodyBytes + 1),
        Vector.empty
      )
    )
    refused(
      await(logic().request(get("scripted"))),
      pb.ErrorCode.INTERNAL,
      ClientLogic.MaxBodyBytes.toString
    )
  }

  test("each way a call can get no answer reaches the process as its own failure") {
    def reason(service: String): Option[ServiceFailure.Reason] =
      await(logic().request(get(service))).result.failure.map(_.reason)
    assertEquals(reason("not-there"), Some(ServiceFailure.Reason.UNRESOLVABLE))
    assertEquals(reason("gone"), Some(ServiceFailure.Reason.UNANSWERED))
    assertEquals(reason("impostor"), Some(ServiceFailure.Reason.IDENTITY_MISMATCH))
  }

  test("a request that does not name a service, a method or a path is refused, naming what") {
    refused(await(logic().request(get(""))), pb.ErrorCode.BAD_REQUEST, "names the service")
    refused(
      await(logic().request(get("scripted").copy(method = ""))),
      pb.ErrorCode.BAD_REQUEST,
      "method"
    )
    refused(
      await(logic().request(get("scripted").copy(path = "x"))),
      pb.ErrorCode.BAD_REQUEST,
      "starts with '/'"
    )
  }

  test("a call made for a handler in a trace is a span under the handler's span") {
    val traceId = Trace.mint()
    val spanId  = 4242L
    val metadata = caller("request-flow", "pay").addEntries(
      pb.Metadata.Entry(Trace.TraceIdKey, java.lang.Long.toHexString(traceId)),
      pb.Metadata.Entry(Trace.SpanIdKey, java.lang.Long.toHexString(spanId))
    )
    assert(await(logic().request(get("scripted", metadata))).result.isResponse)
    val spans = Observability(kit.service.system).recorder.spansOf(traceId)
    assertEquals(spans.map(_.parentSpanId), Vector(spanId), spans.toString)
  }

  test("nothing the process says in a call makes it come from another service") {
    val reply = await(
      logic().request(
        get("scripted").copy(headers =
          Seq(
            Header("X-Ankka-Caller", "ankka://payments/orders"),
            Header("X-Ankka-Local-Caller", "anything service:payments/orders")
          )
        )
      )
    )
    assert(reply.result.isResponse, reply.toString)
    val received = scripted.requests.head
    assertEquals(received.header("x-ankka-caller"), None)
    assertEquals(received.header("x-ankka-local-caller"), None)
  }
