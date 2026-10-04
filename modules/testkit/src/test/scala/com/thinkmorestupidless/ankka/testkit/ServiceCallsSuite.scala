package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.{Observability, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.sdk.*
import com.typesafe.config.ConfigFactory

import java.net.{InetAddress, ServerSocket}
import scala.compiletime.testing.typeCheckErrors
import scala.concurrent.duration.*

/**
 * Every component that may call another service does, in a running service: every scenario of
 * `features/service-calls/components.feature`, one case each, named for it.
 *
 * The other service is a `ScriptedService` on loopback, found by the name `psp-gateway`. On a
 * developer's machine every caller is the local machine, so what is asserted here is that the
 * request arrived and the answer came back; who the callee reads the caller as is a cluster's
 * question, asked by the k3s suites.
 */
class ServiceCallsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private val scripted = ScriptedService.start()
  private val model    = TestModelProvider()
  private val timers   = TimerRuntime(pollInterval = 200.millis)

  private var kit: AnkkaTestKit = null

  private val components =
    Seq(
      PayoutWorkflow.descriptor,
      LedgerEntity.descriptor,
      LedgerForwarder.descriptor,
      SettlementPoller.descriptor,
      PayoutAgent.descriptor,
      PayoutReporter.descriptor
    ) ++ AgentRuntime.descriptors

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      components,
      Seq(ProjectionRuntime(), timers, AgentRuntime.withDefaultModel(model)),
      localServices = Map("psp-gateway" -> scripted.address)
    )

  override def afterAll(): Unit =
    try if kit != null then kit.stop()
    finally scripted.stop()

  override def beforeEach(context: BeforeEach): Unit =
    scripted.clear()
    scripted.delay(Duration.Zero)
    scripted.answer(request => ScriptedServices.text(s"answered ${request.path}"))
    model.reset()

  private def client = kit.componentClient

  private def workflowState(id: String)(done: PayoutState => Boolean): PayoutState =
    kit.eventually(s"the workflow $id finished") {
      Some(client.forWorkflow(EntityId(id)).call(PayoutWorkflow.status).invoke()).filter(done)
    }

  private def requestsTo(prefix: String): Vector[ScriptedService.Request] =
    scripted.requests.filter(_.path.startsWith(prefix))

  test("a workflow's step calls another service and goes on with the answer") {
    client.forWorkflow(EntityId("pay-1")).call(PayoutWorkflow.start).invoke(25): Unit
    val state = workflowState("pay-1")(_.answer.nonEmpty)
    assertEquals(state.answer, "answered /payouts?amount=25")
    assertEquals(requestsTo("/payouts").map(_.method), Vector("GET"))
  }

  test("an agent's tool calls another service and answers the model with what it was given") {
    model
      .expectToolCall("payout_status", Json.obj("payout" -> Json.str("p-7")))
      .expectText("The payout is settled.")
    val reply = client.forAgent(SessionId("s-1")).call(PayoutAgent.ask).invoke("Is p-7 settled?")
    assertEquals(reply, "The payout is settled.")
    assertEquals(requestsTo("/payouts/").map(_.path), Vector("/payouts/p-7"))
    val results = model.requests(1).messages.collect { case ChatMessage.ToolResults(rs) => rs }
    assert(
      results.flatten.exists(_.content.contains("answered /payouts/p-7")),
      results.toString
    )
  }

  test("an autonomous agent's tool calls another service") {
    model
      .expectToolCall("payout_status", Json.obj("payout" -> Json.str("p-9")))
      .expectCompleteTaskText("p-9 is settled")
    val id = client.forAutonomousAgent(PayoutReporter).runSingleTask(PayoutReporter.report, "p-9?")
    val done = kit.awaitTask(id, PayoutReporter.report)
    assertEquals(done.status, TaskStatus.Completed)
    assertEquals(requestsTo("/payouts/").map(_.path), Vector("/payouts/p-9"))
  }

  test("a consumer calls another service before the change it handles is done with") {
    scripted.failNext()
    client.forEventSourcedEntity(EntityId("ledger-r")).call(LedgerEntity.add).invoke(3): Unit
    // The first call failed, so the change is delivered again and called for a second time.
    val delivered = kit.eventually("the change was delivered again") {
      Some(requestsTo("/ledgers/ledger-r/3")).filter(_.size >= 2)
    }
    assertEquals(delivered.map(_.path).distinct, Vector("/ledgers/ledger-r/3"))
  }

  test("a timed action calls another service when its timer fires") {
    timers.timerScheduler.createSingleTimer(
      "settle-b1",
      100.millis,
      SettlementPoller.poll.deferred("b1")
    )
    val polled = kit.eventually("the timer fired and called") {
      Some(requestsTo("/settlements/b1")).filter(_.nonEmpty)
    }
    assertEquals(polled.head.method, "GET")
  }

  test("a workflow calls another service in a step and not in a command") {
    val error = intercept[CommandError](
      client.forWorkflow(EntityId("pay-cmd")).call(PayoutWorkflow.callFromCommand).invoke(1)
    )
    assertEquals(error.code, ErrorCode.BadRequest, error.message)
    assert(error.message.contains("in a step"), error.message)
    assertEquals(requestsTo("/payouts"), Vector.empty)
  }

  test("a call made from a step is nested under the step in the trace") {
    client.forWorkflow(EntityId("pay-trace")).call(PayoutWorkflow.start).invoke(9): Unit
    val _              = workflowState("pay-trace")(_.answer.nonEmpty)
    val observability  = Observability(kit.service.system)
    def name(ref: Int) = observability.names.nameOf(ref).getOrElse("?")
    val spans          = observability.recorder.snapshot()
    val call = spans
      .find(s => name(s.componentRef) == "service:local/psp-gateway")
      .getOrElse(fail("no span for the call to the PSP gateway"))
    val step = spans
      .find(s => s.spanId == call.parentSpanId)
      .getOrElse(fail("the call's parent span is not recorded"))
    assertEquals((name(step.componentRef), name(step.handlerRef)), ("payout", "initiate-payout"))
    assertEquals(call.traceId, step.traceId)
  }

  test("a step of a service that knows of no other services is told so") {
    val alone = AnkkaTestKit.start(Seq(PayoutWorkflow.descriptor))
    try
      alone.componentClient
        .forWorkflow(EntityId("pay-alone"))
        .call(PayoutWorkflow.startTrying)
        .invoke(1): Unit
      val state = alone.eventually("the step recorded how the call failed") {
        Some(
          alone.componentClient
            .forWorkflow(EntityId("pay-alone"))
            .call(PayoutWorkflow.status)
            .invoke()
        ).filter(_.failure.nonEmpty)
      }
      assert(state.failure.startsWith("ServiceUnresolvable"), state.failure)
      assert(state.failure.contains("ankka.local-services"), state.failure)
    finally alone.stop()
  }

  test(
    "a service on a developer's machine calls a service that has stopped and the call is unanswered"
  ) {
    val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    val port   = closed.getLocalPort
    closed.close()
    val stopped = AnkkaTestKit.start(
      Seq(PayoutWorkflow.descriptor),
      localServices = Map("psp-gateway" -> s"http://127.0.0.1:$port")
    )
    try
      stopped.componentClient
        .forWorkflow(EntityId("pay-stopped"))
        .call(PayoutWorkflow.startTrying)
        .invoke(1): Unit
      val state = stopped.eventually("the step recorded how the call failed") {
        Some(
          stopped.componentClient
            .forWorkflow(EntityId("pay-stopped"))
            .call(PayoutWorkflow.status)
            .invoke()
        ).filter(_.failure.nonEmpty)
      }
      assert(state.failure.startsWith("ServiceUnanswered"), state.failure)
    finally stopped.stop()
  }

  test("a call that is not answered within the time its service is set to wait is unanswered") {
    // Given as a system property, the way a setting reaches a service the kit starts: the kit's
    // configuration falls back to the JVM's, and the service reads it once, when it starts.
    sys.props.put("ankka.service-client.timeout", "500ms")
    ConfigFactory.invalidateCaches()
    val waiting =
      try
        AnkkaTestKit.start(
          Seq(PayoutWorkflow.descriptor),
          localServices = Map("psp-gateway" -> scripted.address)
        )
      finally
        sys.props.remove("ankka.service-client.timeout")
        ConfigFactory.invalidateCaches()
    try
      scripted.delay(2.seconds)
      waiting.componentClient
        .forWorkflow(EntityId("pay-late"))
        .call(PayoutWorkflow.startTrying)
        .invoke(1): Unit
      val state = waiting.eventually("the step recorded how the call failed") {
        Some(
          waiting.componentClient
            .forWorkflow(EntityId("pay-late"))
            .call(PayoutWorkflow.status)
            .invoke()
        ).filter(_.failure.nonEmpty)
      }
      assert(state.failure.startsWith("ServiceUnanswered"), state.failure)
      assert(state.failure.contains("500"), state.failure)
      assertEquals(requestsTo("/payouts").size, 1, "sent once")
    finally waiting.stop()
  }

  test("a graph consumer calls another service, through the same context a consumer has") {
    val services = ScriptedServices().answer("psp-gateway")(_ => ScriptedServices.text("ok"))
    val graph    = ConsumerTestKit.graph(LedgerCheckGraph, services = services)
    val deltas   = graph.onMessage(LedgerEvent.Added(7), subject = "l-1")
    assertEquals(services.requests.map(_.path), Vector("/checks/7"))
    assertEquals(deltas.head.properties, Map("checked" -> "ok"))
  }

  test("an entity and a view are given no client for other services, by type") {
    assert(typeCheckErrors("(??? : EventSourcedEntityContext).services").nonEmpty)
    assert(typeCheckErrors("(??? : KeyValueEntityContext).services").nonEmpty)
    assert(typeCheckErrors("(??? : ViewComponentContext).services").nonEmpty)
    // The same expression against a context that has one compiles, so the check is not a typo.
    assertEquals(typeCheckErrors("(??? : ConsumerContext).services"), Nil)
  }
